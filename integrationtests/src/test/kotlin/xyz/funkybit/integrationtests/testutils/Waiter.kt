package xyz.funkybit.integrationtests.testutils

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.awaitility.core.ConditionTimeoutException
import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.SqlExpressionBuilder.neq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.notInList
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.evm.EvmChainManager
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorEntity
import xyz.funkybit.core.model.db.BitcoinUtxoAddressMonitorId
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.BlockchainTransactionData
import xyz.funkybit.core.model.db.BlockchainTransactionEntity
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.DepositStatus
import xyz.funkybit.core.model.db.DepositTable
import xyz.funkybit.core.model.db.NetworkType
import xyz.funkybit.core.model.db.SettlementBatchEntity
import xyz.funkybit.core.model.db.SettlementBatchStatus
import xyz.funkybit.core.model.db.SettlementBatchTable
import xyz.funkybit.core.model.db.TradeEntity
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.db.WithdrawalStatus
import xyz.funkybit.core.model.db.WithdrawalTable
import xyz.funkybit.core.services.UtxoManager
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.core.utils.triggerRepeaterTask
import xyz.funkybit.integrationtests.utils.Faucet
import java.math.BigInteger
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
        val preparingBatch = transaction {
            logger.debug {
                "Timed out waiting between tests - settlements=${
                    SettlementBatchEntity.all().map { it.status }
                }, withdrawals=${WithdrawalEntity.all().map { it.status }}, deposits=${
                    DepositEntity.all().map { it.status }
                }, pending trades = ${TradeEntity.findPendingForNewSettlementBatch().map { it.id.value }}"
            }
            SettlementBatchEntity.all().firstOrNull { it.status == SettlementBatchStatus.Preparing }
        }
        // if we are preparing on chain - try to rollback or subsequent withdrawal or settlement tests will fail
        preparingBatch?.let {
            try {
                transaction {
                    SettlementBatchEntity.all().firstOrNull { it.status == SettlementBatchStatus.Preparing }
                        ?.let { batch ->
                            batch.markAsRollingBack()
                            batch.chainBatches.filter { it.status == SettlementBatchStatus.Prepared }.forEach {
                                val chainId = it.chainId.value
                                val transactionData = when (chainId.networkType()) {
                                    NetworkType.Evm -> {
                                        val evmClient = EvmChainManager.getEvmClient(chainId)
                                        BlockchainTransactionData(
                                            evmClient.encodeRollbackBatchFunctionCall(),
                                            evmClient.exchangeContractAddress,
                                            BigInteger.ZERO,
                                        )
                                    }

                                    NetworkType.Bitcoin -> {
                                        BlockchainTransactionData(
                                            Json.encodeToString(
                                                ArchUtils.buildRollbackSettlementBatchInstruction(
                                                    ArchAccountEntity.findProgramAccount()!!.rpcPubkey(),
                                                ),
                                            ),
                                            EvmAddress.zero,
                                            BigInteger.ZERO,
                                        )
                                    }
                                }
                                it.markAsRollingBack(
                                    BlockchainTransactionEntity.create(
                                        chainId = chainId,
                                        transactionData = transactionData,
                                        null,
                                    ),
                                )
                            }
                        }
                }
                waitFor(5000) {
                    transaction { preparingBatch.allChainSettlementsRolledBackOrCompleted() }
                }
            } catch (e: Exception) {
                logger.error { "failed to rollback on chain ${e.message}" }
            }
        }
    }
}

fun waitForTx(address: BitcoinAddress, txId: TxHash, sender: BitcoinAddress? = null) {
    val receiverInfo = transaction { BitcoinUtxoAddressMonitorEntity.findById(BitcoinUtxoAddressMonitorId(address.value)) }
    waitFor {
        transaction {
            BitcoinUtxoEntity.findUnspentByAddress(address).map { it.guid.value.txId() }.toSet().contains(txId).also { result ->
                if (!result) {
                    if (receiverInfo?.isDepositAddress == true) {
                        triggerRepeaterTask("program_utxo_refresher")
                    } else {
                        UtxoManager.refreshUtxos(address)
                    }
                }
            }
        }
    }
    sender?.let {
        val senderInfo = transaction { BitcoinUtxoAddressMonitorEntity.findById(BitcoinUtxoAddressMonitorId(it.value)) }
        waitFor {
            transaction {
                BitcoinUtxoEntity.findSpentForAddressAndTxId(sender, txId).isNotEmpty().also { result ->
                    if (!result) {
                        if (senderInfo?.isDepositAddress == true) {
                            triggerRepeaterTask("program_utxo_refresher")
                        } else {
                            UtxoManager.refreshUtxos(sender)
                        }
                    }
                }
            }
        }
    }
}
