package co.chainring.apps.api

import co.chainring.apps.api.model.ConfigurationApiResponse
import co.chainring.apps.api.model.DeployedContract
import co.chainring.core.model.Address
import co.chainring.core.model.db.Chain
import co.chainring.core.model.db.DeployedSmartContractEntity
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

    fun getConfiguration(): ContractRoute {
        val responseBody = Body.auto<ConfigurationApiResponse>().toLens()

        return "config" meta {
            operationId = "config"
            summary = "Retrive configuration"
            returning(
                Status.OK,
                responseBody to
                    ConfigurationApiResponse(
                        addresses =
                            listOf(
                                DeployedContract(
                                    chain = Chain.Ethereum,
                                    name = "Exchange",
                                    address = Address("0x0000000000000000000000000000000000000000"),
                                ),
                            ),
                    ),
            )
        } bindContract Method.GET to { _ ->
            val addresses =
                transaction {
                    DeployedSmartContractEntity.validContracts().map {
                        DeployedContract(
                            chain = it.chain,
                            name = it.name,
                            address = it.address,
                        )
                    }
                }

            Response(Status.OK).with(
                responseBody of
                    ConfigurationApiResponse(
                        addresses = addresses,
                    ),
            )
        }
    }
}
