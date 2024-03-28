package co.chainring.testutils

import co.chainring.apps.GatewayApp
import co.chainring.apps.GatewayConfig
import co.chainring.apps.SequencerApp
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import org.junit.jupiter.api.extension.ExtensionContext.Store.CloseableResource
import java.lang.System.getenv

// This extension allows us to start the app under test only once
class AppUnderTestRunner : BeforeAllCallback {
    override fun beforeAll(context: ExtensionContext) {
        context
            .root
            .getStore(ExtensionContext.Namespace.GLOBAL)
            .getOrComputeIfAbsent("app under test") {
                object : CloseableResource {
                    private val gatewayApp = GatewayApp(GatewayConfig(port = 5338))
                    private val sequencerApp = SequencerApp()

                    private val isIntegrationRun = (getenv("INTEGRATION_RUN") ?: "0") == "1"

                    init {
                        if (!isIntegrationRun) {
                            sequencerApp.start()
                            gatewayApp.start()
                        }
                    }

                    @Throws(Throwable::class)
                    override fun close() {
                        if (!isIntegrationRun) {
                            sequencerApp.stop()
                            gatewayApp.stop()
                        }
                    }
                }
            }
    }
}
