package co.chainring.integrationtests.utils

import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.web3j.protocol.core.RemoteCall
import java.time.Duration

private val faucetPossible = (System.getenv("FAUCET_POSSIBLE") ?: "0") == "1"

fun <T> RemoteCall<T>.sendAndWaitForConfirmation(): T {
    val async = this.sendAsync()

    await
        .withAlias("Waiting for block confirmation")
        .pollInSameThread()
        .pollDelay(Duration.ofMillis(100))
        .pollInterval(Duration.ofMillis(100))
        .atMost(Duration.ofMillis(10000L))
        .until {
            async.isDone.also { done ->
                if (!done && faucetPossible) Faucet.mine()
            }
        }

    return async.get()
}
