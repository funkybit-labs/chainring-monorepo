package co.chainring.core.utils

import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.plugins.Plugin
import org.apache.logging.log4j.core.config.plugins.PluginAttribute
import org.apache.logging.log4j.core.config.plugins.PluginFactory
import java.util.concurrent.ConcurrentHashMap

@Plugin(name = "ErrorReporter", category = "Core", elementType = Appender.ELEMENT_TYPE)
class ErrorReporter(name: String) : AbstractAppender(name, null, null, true, emptyArray()) {
    companion object {
        @PluginFactory
        @JvmStatic
        fun factory(@PluginAttribute("name") name: String): ErrorReporter {
            return ErrorReporter(name)
        }
    }

    private val appName = System.getenv("APP_NAME")
    private val envName = System.getenv("ENV_NAME")
    private val slackChannelId = System.getenv("SLACK_ERROR_REPORTING_CHANNEL_ID")
    private val slackToken = System.getenv("SLACK_ERROR_REPORTING_APP_TOKEN")

    private val httpClient = OkHttpClient.Builder().build()
    private val applicationJsonMediaType = "application/json".toMediaType()

    private val reportedErrorsHashCodes = ConcurrentHashMap.newKeySet<Int>()

    override fun append(event: LogEvent) {
        if (slackChannelId.isNullOrEmpty() || slackToken.isNullOrEmpty() || event.level != Level.ERROR) return

        val errorInfo = ErrorInfo(
            loggerName = event.loggerName,
            logMessage = event.message.formattedMessage,
            threadName = event.threadName,
            throwable = event.thrown?.let { throwable ->
                ErrorInfo.ThrowableInfo(
                    className = throwable.javaClass.name,
                    message = throwable.message,
                    stackTrace = throwable.stackTraceToString(),
                )
            },
        )

        if (reportedErrorsHashCodes.add(errorInfo.hashCode())) {
            val text = StringBuilder()
                .append(
                    """
                        *Error in the logs from `$appName` in `$envName` env:*
                        
                        ```
                        ${errorInfo.logMessage}
                        ```
                        
                        Logger: `${errorInfo.loggerName}`
                        Thread name: `${errorInfo.threadName}`
                        Timestamp: `${Instant.fromEpochMilliseconds(event.timeMillis)}`
                    """.trimIndent(),
                )
                .append(
                    errorInfo.throwable?.let { t -> "\n\nException:\n\n```\n${t.stackTrace}```" } ?: "",
                )
                .toString()

            val slackMessage = SlackMessage(
                channel = slackChannelId,
                blocks = listOf(
                    SlackMessage.Block.Section(
                        text = SlackMessage.Text(
                            type = "mrkdwn",
                            text = text,
                        ),
                    ),
                ),
            )

            httpClient.newCall(
                Request
                    .Builder()
                    .url("https://slack.com/api/chat.postMessage")
                    .header("Authorization", "Bearer $slackToken")
                    .post(
                        Json.encodeToString(slackMessage).toRequestBody(applicationJsonMediaType),
                    )
                    .build(),
            ).execute()
        }
    }

    data class ErrorInfo(
        val loggerName: String,
        val logMessage: String,
        val threadName: String,
        val throwable: ThrowableInfo?,
    ) {
        data class ThrowableInfo(
            val className: String,
            val message: String?,
            val stackTrace: String,
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    data class SlackMessage(
        val channel: String,
        val blocks: List<Block>,
    ) {
        @Serializable
        @JsonClassDiscriminator("type")
        sealed class Block {
            @Serializable
            @SerialName("section")
            data class Section(
                val text: Text,
            ) : Block()
        }

        @Serializable
        data class Text(
            val type: String,
            val text: String,
        )
    }
}
