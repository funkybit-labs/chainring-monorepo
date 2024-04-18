package co.chainring.integrationtests.testutils

import co.chainring.integrationtests.testutils.Faucet.blockchainClient
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.web3j.protocol.core.RemoteCall
import java.time.Duration

fun <T> confirmedBlock(logic: () -> RemoteCall<T>): T {
    val async = logic().sendAsync()

    await
        .withAlias("Waiting for block confirmation")
        .pollInSameThread()
        .pollDelay(Duration.ofMillis(100))
        .pollInterval(Duration.ofMillis(100))
        .atMost(Duration.ofMillis(10000L))
        .until {
            blockchainClient.mine()
            async.isDone
        }

    return async.get()
}
