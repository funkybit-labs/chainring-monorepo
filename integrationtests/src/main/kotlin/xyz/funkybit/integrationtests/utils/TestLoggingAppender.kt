package xyz.funkybit.integrationtests.utils

import org.apache.logging.log4j.core.Appender
import org.apache.logging.log4j.core.Core
import org.apache.logging.log4j.core.Filter
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.config.plugins.Plugin
import org.apache.logging.log4j.core.config.plugins.PluginAttribute
import org.apache.logging.log4j.core.config.plugins.PluginElement
import org.apache.logging.log4j.core.config.plugins.PluginFactory

data class TestLogMessage(val level: String, val message: String, val exception: Throwable? = null)
val logMessages = mutableListOf<TestLogMessage>()

fun clearLogMessages() {
    logMessages.clear()
}

fun assertLogMessageProduced(expectedMessage: TestLogMessage, fn: () -> Unit) {
    clearLogMessages()
    fn()
    assert(logMessages.contains(expectedMessage))
}

@Plugin(name = "TestLoggingAppender", category = Core.CATEGORY_NAME, elementType = Appender.ELEMENT_TYPE)
class TestLoggingAppender(name: String, filter: Filter?) : AbstractAppender(name, filter, null, true, Property.EMPTY_ARRAY) {

    override fun append(event: LogEvent) {
        logMessages.add(TestLogMessage(event.level.name(), event.message.toString(), event.thrown))
    }

    companion object {
        @PluginFactory
        @JvmStatic
        fun createAppender(
            @PluginAttribute("name") name: String,
            @PluginElement("Filter") filter: Filter?,
        ): TestLoggingAppender {
            return TestLoggingAppender(name, filter)
        }
    }
}
