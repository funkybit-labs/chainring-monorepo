package co.chainring.integrationtests.testutils

import co.chainring.core.model.db.BlockchainTransactionStatus
import co.chainring.core.model.db.TxHash
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

fun waitForFinalizedWithdrawalWithForking(id: WithdrawalId) {
    waitFor {
        transaction {
            WithdrawalEntity[id].blockchainTransaction?.status == BlockchainTransactionStatus.Submitted
        }
    }

    transaction {
        WithdrawalEntity[id].blockchainTransaction!!.txHash = TxHash("0x6d37aaf942f1679e7c34d241859017d5caf42f57f7c1b4f1f0c149c2649bb822")
    }

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

fun waitFor(condition: () -> Boolean) {
    await
        .pollInSameThread()
        .pollDelay(Duration.ofMillis(100))
        .pollInterval(Duration.ofMillis(100))
        .atMost(Duration.ofMillis(30000L))
        .until {
            condition()
        }
}

fun waitForFinalizedWithdrawal(id: WithdrawalId) {
    waitFor {
        Faucet.mine()
        transaction {
            WithdrawalEntity[id].status.isFinal()
        }
    }
}
