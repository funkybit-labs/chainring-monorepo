package co.chainring.integrationtests.utils

import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.web3j.protocol.core.RemoteCall
import java.time.Duration

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
                if (!done) Faucet.mine()
            }
        }

    return async.get()
}
