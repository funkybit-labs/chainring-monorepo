package co.chainring.core.model.tgbot

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class TelegramMessageId(val value: Int)
