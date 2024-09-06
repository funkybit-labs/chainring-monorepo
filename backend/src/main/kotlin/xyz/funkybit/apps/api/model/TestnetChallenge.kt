package xyz.funkybit.apps.api.model

import kotlinx.serialization.Serializable

@Serializable
data class SetNickname(val name: String)

@Serializable
data class SetAvatarUrl(val url: String)
