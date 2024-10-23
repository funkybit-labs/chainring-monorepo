package xyz.funkybit.core.utils

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.http4k.base64Encode
import java.util.UUID

object OAuth2 {
    @JvmInline
    value class CodeVerifier(val value: String) {
        companion object {
            fun generate(): CodeVerifier =
                CodeVerifier(UUID.randomUUID().toString().replace("-", ""))
        }

        fun toChallenge(): CodeChallenge =
            CodeChallenge(
                value = sha256(value.toByteArray()).base64Encode().replace("+", "-").replace("/", "_").replace("=", ""),
                method = CodeChallengeMethod.S256,
            )
    }

    @Serializable
    @JvmInline
    value class AuthorizationCode(val value: String)

    @Serializable
    enum class CodeChallengeMethod {
        S256,

        @SerialName("plain")
        Plain,
    }

    @Serializable
    class CodeChallenge(
        val value: String,
        val method: CodeChallengeMethod,
    )

    @Serializable
    data class Tokens(
        @SerialName("access_token")
        val access: String,
        @SerialName("refresh_token")
        val refresh: String,
    )
}
