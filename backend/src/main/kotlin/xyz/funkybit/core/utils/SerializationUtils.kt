package xyz.funkybit.core.utils

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.serializer

@OptIn(InternalSerializationApi::class)
fun Any?.toJsonElement(): JsonElement =
    when (this) {
        null -> JsonNull
        is Map<*, *> -> JsonObject(this.map { Pair(it.key.toString(), it.value.toJsonElement()) }.toMap())
        is Collection<*> -> JsonArray(this.map { it.toJsonElement() })
        is Boolean -> JsonPrimitive(this)
        is Number -> JsonPrimitive(this)
        is String -> JsonPrimitive(this)
        is Enum<*> -> JsonPrimitive(this.toString())
        else -> this.javaClass.kotlin.serializer().let { Json.encodeToJsonElement(it, this) }
    }
