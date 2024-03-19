package co.chainring.core.model

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class Symbol(val value: String)

@Serializable
@JvmInline
value class Market(val value: String)
