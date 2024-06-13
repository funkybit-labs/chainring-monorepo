package co.chainring.core.model.telegrambot

import kotlinx.serialization.Serializable

@Serializable
@JvmInline
value class TelegramMessageId(val value: Int)
