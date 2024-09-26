package xyz.funkybit.integrationtests.testutils

import io.github.oshai.kotlinlogging.KotlinLogging
import org.awaitility.core.ConditionTimeoutException
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.DepositStatus
import xyz.funkybit.core.model.db.DepositTable
import xyz.funkybit.core.model.db.SettlementBatchEntity
import xyz.funkybit.core.model.db.SettlementBatchStatus
import xyz.funkybit.core.model.db.SettlementBatchTable
import xyz.funkybit.core.model.db.TradeEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.db.WithdrawalTable
import xyz.funkybit.integrationtests.utils.Faucet
import java.time.Duration

fun waitFor(atMost: Long? = null, condition: () -> Boolean) {
    await
        .pollInSameThread()
        .pollDelay(Duration.ofMillis(100))
        .pollInterval(Duration.ofMillis(100))
        .atMost(Duration.ofMillis(atMost ?: 30000L))
        .until {
            condition()
        }
}

private val logger = KotlinLogging.logger { }
fun waitForActivityToComplete() {
    try {
        waitFor(4000L) {
            transaction {
                runCatching { Faucet.mine() }
                TradeEntity.findPendingForNewSettlementBatch().isEmpty() &&
                    SettlementBatchEntity.count(SettlementBatchTable.status.neq(SettlementBatchStatus.Completed)) == 0L &&
                    WithdrawalEntity.count(
                        WithdrawalTable.status.notInList(
                            listOf(
                                WithdrawalStatus.Complete,
                                WithdrawalStatus.Failed,
                            ),
                        ),
                    ) == 0L &&
                    DepositEntity.count(
                        DepositTable.status.notInList(
                            listOf(
                                DepositStatus.Complete,
                                DepositStatus.Failed,
                            ),
                        ),
                    ) == 0L
            }
        }
    } catch (e: ConditionTimeoutException) {
        transaction {
            logger.debug {
                "Timed out waiting between tests - settlements=${
                    SettlementBatchEntity.all().map { it.status }
                }, withdrawals=${WithdrawalEntity.all().map { it.status }}, deposits=${
                    DepositEntity.all().map { it.status }
                }, pending trades = ${TradeEntity.findPendingForNewSettlementBatch().map {it.id.value}}"
            }
        }
    }
}

fun waitForTx(address: BitcoinAddress, txId: TxHash) {
    waitFor {
        transaction {
            BitcoinUtxoEntity.findUnspentByAddress(address).map { it.guid.value.txId() }.toSet().contains(txId)
        }
    }
}
