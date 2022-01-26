package org.neo4j.graphql

import graphql.*
import graphql.execution.NonNullableFieldWasNullError
import graphql.schema.GraphQLSchema

class Translator(val schema: GraphQLSchema) {

    class CypherHolder(var cypher: OldCypher?)

    private val gql: GraphQL = GraphQL.newGraphQL(schema).build()

    @JvmOverloads
    @Throws(OptimizedQueryException::class)
    fun translate(query: String, params: Map<String, Any?> = emptyMap(), ctx: QueryContext = QueryContext()): List<OldCypher> {
        val cypherHolder = CypherHolder(null)
        val executionInput = ExecutionInput.newExecutionInput()
            .query(query)
            .variables(params)
            .context(ctx)
            .localContext(cypherHolder)
            .build()
        val result = gql.execute(executionInput)
        result.errors?.forEach {
            when (it) {
                is ExceptionWhileDataFetching -> throw it.exception

                is TypeMismatchError, // expected since we return cypher here instead of the correct json
                is NonNullableFieldWasNullError, // expected since the returned cypher does not match the shape of the graphql type
                is SerializationError // expected since the returned cypher does not match the shape of the graphql type
                -> {
                    // ignore
                }
                // generic error handling
                is GraphQLError -> throw InvalidQueryException(it)
            }
        }

        return listOf(requireNotNull(cypherHolder.cypher))
    }
}
