package xyz.funkybit.integrationtests.testutils

import org.awaitility.kotlin.await
import org.http4k.websocket.WsClient
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.api.model.SymbolInfo
import xyz.funkybit.core.model.bitcoin.ArchAccountState
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.BlockchainTransactionStatus
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalId
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.integrationtests.utils.AssetAmount
import xyz.funkybit.integrationtests.utils.ExpectedBalance
import xyz.funkybit.integrationtests.utils.Faucet
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.assertBalances
import xyz.funkybit.integrationtests.utils.assertBalancesMessageReceived
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

fun waitForFinalizedWithdrawal(id: WithdrawalId, expectedStatus: WithdrawalStatus) {
    waitFor {
        Faucet.mine()
        transaction {
            WithdrawalEntity[id].status == expectedStatus
        }
    }
}

fun getFeeAccountBalanceOnArch(symbol: SymbolInfo): AssetAmount {
    return transaction {
        val symbolEntity = SymbolEntity.forName(symbol.name)
        val tokenAccountPubKey = ArchAccountEntity.findTokenAccountForSymbol(symbolEntity)!!.rpcPubkey()
        val tokenState = ArchUtils.getAccountState<ArchAccountState.Token>(tokenAccountPubKey)
        AssetAmount(symbol, tokenState.balances[0].balance.toLong().toBigInteger())
    }
}
