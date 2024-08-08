package xyz.funkybit.apps.api.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.funkybit.core.model.Symbol

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
