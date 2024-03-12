package co.chainring.apps.api

import co.chainring.apps.api.model.ConfigurationApiResponse
import co.chainring.core.model.db.KeyValueStore
import io.github.oshai.kotlinlogging.KotlinLogging
import org.http4k.contract.ContractRoute
import org.http4k.contract.meta
import org.http4k.core.Body
import org.http4k.core.Method
import org.http4k.core.Response
import org.http4k.core.Status
import org.http4k.core.with
import org.http4k.format.KotlinxSerialization.auto
import org.jetbrains.exposed.sql.transactions.transaction

object ConfigRoutes {
    private val logger = KotlinLogging.logger {}
    const val CONTRACT_ADDRESS_KEY = "contract_address"

    fun getConfiguration(): ContractRoute {
        val responseBody = Body.auto<ConfigurationApiResponse>().toLens()

        return "config" meta {
            operationId = "config"
            summary = "Retrive configuration"
            returning(
                Status.OK,
                responseBody to ConfigurationApiResponse(
                    contractAddress = "",
                ),
            )
        } bindContract Method.GET to { _ ->
            transaction {
                when (val contractAddress = KeyValueStore.getValue(CONTRACT_ADDRESS_KEY)) {
                    null -> Response(Status.NOT_FOUND)
                    else -> Response(Status.OK).with(
                        responseBody of ConfigurationApiResponse(
                            contractAddress = contractAddress,
                        ),
                    )
                }
            }
        }
    }
}
