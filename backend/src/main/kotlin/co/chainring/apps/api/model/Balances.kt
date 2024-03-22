package co.chainring.apps.api.model

import co.chainring.core.model.Symbol
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Balance(
    val symbol: Symbol,
    val total: BigIntegerJson,
    val available: BigIntegerJson,
    val lastUpdated: Instant,
)

@Serializable
data class BalancesApiResponse(
    val balances: List<Balance>,
)
