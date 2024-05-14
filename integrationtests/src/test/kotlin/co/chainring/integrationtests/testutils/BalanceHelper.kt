package co.chainring.integrationtests.testutils

import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalId
import co.chainring.integrationtests.utils.ExpectedBalance
import co.chainring.integrationtests.utils.Faucet
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.assertBalances
import co.chainring.integrationtests.utils.assertBalancesMessageReceived
import org.awaitility.kotlin.await
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.transactions.transaction
import java.time.Duration

fun waitForBalance(apiClient: TestApiClient, wsClient: WsClient, expectedBalances: List<ExpectedBalance>) {
    wsClient.assertBalancesMessageReceived(expectedBalances)
    assertBalances(expectedBalances, apiClient.getBalances().balances)
}

fun waitForFinalizedWithdrawal(id: WithdrawalId) {
    await
        .pollInSameThread()
        .pollDelay(Duration.ofMillis(100))
        .pollInterval(Duration.ofMillis(100))
        .atMost(Duration.ofMillis(30000L))
        .until {
            Faucet.mine()
            transaction {
                WithdrawalEntity[id].status.isFinal()
            }
        }
}
