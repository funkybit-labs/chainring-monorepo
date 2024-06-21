package co.chainring.core.model.telegram.bot

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class TelegramMessageId(val value: Int)
