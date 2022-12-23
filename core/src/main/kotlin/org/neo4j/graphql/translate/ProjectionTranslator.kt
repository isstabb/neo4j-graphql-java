package org.neo4j.graphql.translate

import graphql.schema.DataFetchingEnvironment
import org.neo4j.cypherdsl.core.*
import org.neo4j.cypherdsl.core.StatementBuilder.OngoingReading
import org.neo4j.graphql.*
import org.neo4j.graphql.domain.Node
import org.neo4j.graphql.domain.directives.AuthDirective
import org.neo4j.graphql.domain.fields.*
import org.neo4j.graphql.domain.inputs.Dict
import org.neo4j.graphql.domain.inputs.WhereInput
import org.neo4j.graphql.domain.inputs.options.OptionsInput
import org.neo4j.graphql.handler.utils.ChainString
import org.neo4j.graphql.translate.where.createWhere
import org.neo4j.graphql.utils.ResolveTree
import org.neo4j.graphql.utils.ResolveTree.Companion.generateMissingOrAliasedFields
import org.neo4j.graphql.utils.ResolveTree.Companion.resolve

class ProjectionTranslator {

    fun createProjectionAndParams(
        node: Node,
        varName: org.neo4j.cypherdsl.core.Node,
        env: DataFetchingEnvironment,
        chainStr: ChainString? = null,
        schemaConfig: SchemaConfig,
        variables: Map<String, Any>,
        queryContext: QueryContext,
    ): Projection {
        return createProjectionAndParams(node, varName, resolve(env), chainStr, schemaConfig, variables, queryContext)
    }

    fun createProjectionAndParams(
        node: Node,
        varName: org.neo4j.cypherdsl.core.Node,
        resolveTree: ResolveTree,
        chainStr: ChainString? = null,
        schemaConfig: SchemaConfig,
        variables: Map<String, Any>,
        queryContext: QueryContext,
        withVars: List<SymbolicName> = emptyList(),
        resolveType: Boolean = false,
    ): Projection {

        val selectedFields = resolveTree.fieldsByTypeName[node.name] ?: return Projection()

        val mergedFields = selectedFields + generateMissingOrAliasedRequiredFields(node, selectedFields)

        var authValidate: Condition? = null
        val projections = mutableListOf<Any>()
        val subQueries = mutableListOf<Statement>()

        if (resolveType) {
            projections += "__resolveType"
            projections += node.name.asCypherLiteral()
        }
        mergedFields.values.forEach { field ->
            val alias = field.aliasOrName
            val param = chainStr?.extend(alias) ?: ChainString(schemaConfig, varName, alias)

            val nodeField = node.getField(field.name) ?: return@forEach
            var optionsInput = OptionsInput.create(field.args[Constants.OPTIONS])

            if (nodeField is AuthableField) {
                nodeField.auth?.let { auth ->
                    AuthTranslator(
                        schemaConfig,
                        queryContext,
                        allow = AuthTranslator.AuthOptions(varName, node, param)
                    )
                        .createAuth(auth, AuthDirective.AuthOperation.READ)
                        ?.let { authValidate = authValidate and it }
                }
            }

            if (nodeField is CypherField) {
                TODO("implement CypherField")
                return@forEach
            }

            if (nodeField is RelationField) {
                val referenceNode = nodeField.node
                val isArray = nodeField.typeMeta.type.isList()
                val whereInputAny = field.args[Constants.WHERE]
                if (referenceNode?.queryOptions != null) {
                    optionsInput = optionsInput.merge(referenceNode.queryOptions)
                }

                if (nodeField.interfaze != null) {
                    val whereInput =
                        whereInputAny?.let { WhereInput.create(nodeField.interfaze, it) }

                    projections += alias

                    val fieldName = Cypher.name(field.name)

                    val fullWithVars = withVars + varName.requiredSymbolicName
                    val referenceNodes =
                        nodeField.interfaze.implementations.values.filter { whereInput?.hasFilterForNode(node) == true }

                    val interfaceQueries = mutableListOf<Statement>()
                    referenceNodes.map { refNode ->
                        val endNode = refNode.asCypherNode(queryContext, ChainString(schemaConfig, varName, refNode))

                        var subQuery: OngoingReading = Cypher.with(fullWithVars)
                            .match(nodeField.createDslRelation(Cypher.anyNode(varName.requiredSymbolicName), endNode))

                        AuthTranslator(schemaConfig, queryContext, allow = AuthTranslator.AuthOptions(endNode, refNode))
                            .createAuth(refNode.auth, AuthDirective.AuthOperation.READ)
                            ?.let { subQuery = subQuery.apocValidate(it, Constants.AUTH_FORBIDDEN_ERROR) }

                        var (condition, whereSubQueries) = createWhere(
                            refNode,
                            whereInput?.withPreferredOn(node),
                            endNode,
                            (chainStr ?: ChainString(schemaConfig, varName)).extend(alias),
                            schemaConfig,
                            queryContext
                        )
                        subQueries.addAll(whereSubQueries)

                        AuthTranslator(schemaConfig, queryContext, where = AuthTranslator.AuthOptions(endNode, refNode))
                            .createAuth(refNode.auth, AuthDirective.AuthOperation.READ)
                            ?.let { condition = condition and it }

                        val recurse = createProjectionAndParams(
                            refNode,
                            endNode,
                            field,
                            chainStr = null,
                            schemaConfig,
                            variables,
                            queryContext,
                            resolveType = true,
                        )
                        // TODO what about the nestedAuth strings

                        interfaceQueries += subQuery
                            .withSubQueries(recurse.subQueries)
                            .returning(endNode.project(recurse.projection).`as`(fieldName))
                            .build()

                    }

                    val ongoingReading: OngoingReading = if (interfaceQueries.size > 1) {
                        Cypher.with(fullWithVars).call(Cypher.union(interfaceQueries))
                    } else {
                        Cypher.with(fullWithVars).call(interfaceQueries.first())
                    }

                    subQueries += if (nodeField.typeMeta.type.isList()) {
                        ongoingReading.returning(Functions.collect(fieldName).`as`(fieldName))
                    } else {
                        ongoingReading.returning(fieldName)
                    }.build()
                    projections += fieldName

                    // TODO sort and limit

                } else if (nodeField.isUnion) {
                    val referenceNodes = requireNotNull(nodeField.union).nodes.values
                    projections += alias
                    val endNode = Cypher.anyNode(param.resolveName())
                    val rel = nodeField.createDslRelation(varName, endNode)
                    var unionConditions: Condition? = null
                    val p = endNode.requiredSymbolicName
                    val unionProjection = mutableListOf<Expression>()
                    referenceNodes.forEach { refNode ->
                        var labelCondition: Condition? = null
                        refNode.allLabels(queryContext).forEach {
                            labelCondition = labelCondition and it.asCypherLiteral().`in`(Functions.labels(endNode))
                        }
                        labelCondition?.let { unionConditions = unionConditions or it }

                        // TODO __resolveType vs __typename ?
                        val projection = mutableListOf("__resolveType", refNode.name.asCypherLiteral())
                        val typeFields = field.fieldsByTypeName[refNode.name]

                        // TODO revert to the following after https://github.com/neo4j-contrib/cypher-dsl/issues/350 is fixed:
                        //  var unionCondition = labelCondition
                        var unionCondition = Conditions.noCondition().and(labelCondition)

                        if (!typeFields.isNullOrEmpty()) {
                            val whereInput =
                                whereInputAny?.let {
                                    WhereInput.UnionWhereInput(requireNotNull(nodeField.union), Dict(it))
                                }

                            val recurse = createProjectionAndParams(
                                refNode,
                                endNode,
                                field,
                                chainStr = null,
                                schemaConfig,
                                variables,
                                queryContext
                            )

                            createNodeWhereAndParams(
                                whereInput?.getDataForNode(refNode),
                                queryContext,
                                schemaConfig,
                                refNode,
                                endNode,
                                recurse.authValidate,
                                param.extend(refNode)
                            )
                                .let { (nodeWhere, nodeSubQueries) ->
                                    subQueries.addAll(nodeSubQueries)
                                    if (nodeWhere != null) {
                                        unionCondition = unionCondition and nodeWhere
                                    }
                                }
                            projection.addAll(recurse.projection)
                        }

                        unionProjection += Cypher.listWith(p).`in`(Cypher.listOf(p)).where(unionCondition)
                            .returning(p.project(projection))
                    }

                    var unionParts: Expression = Cypher.listWith(p)
                        .`in`(
                            Cypher.listBasedOn(rel)
                                .where(unionConditions)
                                .returning(Functions.head(unionProjection.reduce { acc, expression -> acc.add(expression) }))
                        )
                        .where(p.isNotNull)
                        .returning()

                    unionParts = optionsInput.wrapLimitAndOffset(unionParts)

                    projections += if (isArray) {
                        unionParts
                    } else {
                        Functions.head(unionParts)
                    }

                } else {
                    TODO("implement Node")
                }

                return@forEach
            }

            // TODO field aggregation

            if (nodeField is ConnectionField) {
                TODO("implement ConnectionField")
                return@forEach
            }

            if (nodeField is PointField) {
                TODO("implement PointField")
            } else if (nodeField is TemporalField && nodeField.typeMeta.type.name() == Constants.DATE_TIME) {
                TODO("implement TemporalField DATE_TIME")
            } else {
                projections += alias
                if (alias != nodeField.dbPropertyName) {
                    projections += varName.property(nodeField.dbPropertyName)
                }
            }

        }
        return Projection(subQueries, projections, authValidate)
    }

    private fun generateMissingOrAliasedRequiredFields(
        node: Node,
        selectedFields: Map<*, ResolveTree>
    ): Map<String, ResolveTree> {
        val requiredFields = node.computedFields
            .filter { cf -> selectedFields.values.find { it.name == cf.fieldName } != null }
            .mapNotNull { it.requiredFields }
            .flatMapTo(linkedSetOf()) { it }
        return generateMissingOrAliasedFields(requiredFields, selectedFields)
    }

    private fun createNodeWhereAndParams(
        whereInput: WhereInput?,
        context: QueryContext,
        schemaConfig: SchemaConfig,
        node: Node,
        varName: org.neo4j.cypherdsl.core.Node,
        authValidate: Condition?,
        chainStr: ChainString?
    ): WhereResult {
        var condition: Condition? = null
        val subQueries = mutableListOf<Statement>()
        if (whereInput != null) {
            val whereResult = createWhere(node, whereInput, varName, chainStr, schemaConfig, context)
            condition = whereResult.predicate
            subQueries.addAll(whereResult.preComputedSubQueries)
        }

        AuthTranslator(schemaConfig, context, where = AuthTranslator.AuthOptions(varName, node, chainStr))
            .createAuth(node.auth, AuthDirective.AuthOperation.READ)
            ?.let { condition = condition and it }

        AuthTranslator(schemaConfig, context, allow = AuthTranslator.AuthOptions(varName, node, chainStr))
            .createAuth(node.auth, AuthDirective.AuthOperation.READ)
            .apocValidatePredicate(Constants.AUTH_FORBIDDEN_ERROR)
            ?.let { condition = condition and it }

        authValidate.apocValidatePredicate(Constants.AUTH_FORBIDDEN_ERROR)
            ?.let { condition = condition and it }

        return WhereResult(condition, subQueries)
    }

    data class Projection(
        val subQueries: List<Statement> = emptyList(),
        val projection: List<Any> = emptyList(),
        val authValidate: Condition? = null,
    )

}
