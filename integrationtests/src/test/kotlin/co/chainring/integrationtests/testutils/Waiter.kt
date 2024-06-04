package co.chainring.integrationtests.testutils

import co.chainring.core.model.db.DepositEntity
import co.chainring.core.model.db.DepositStatus
import co.chainring.core.model.db.DepositTable
import co.chainring.core.model.db.SettlementBatchEntity
import co.chainring.core.model.db.SettlementBatchStatus
import co.chainring.core.model.db.SettlementBatchTable
import co.chainring.core.model.db.WithdrawalEntity
import co.chainring.core.model.db.WithdrawalStatus
import co.chainring.core.model.db.WithdrawalTable
import co.chainring.integrationtests.utils.Faucet
import io.github.oshai.kotlinlogging.KotlinLogging
import org.awaitility.core.ConditionTimeoutException
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.transactions.transaction
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
                }"
            }
        }
    }
}
