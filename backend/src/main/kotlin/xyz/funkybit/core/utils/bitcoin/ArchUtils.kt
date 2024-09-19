package xyz.funkybit.core.utils.bitcoin

import com.funkatronics.kborsh.Borsh
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionInput
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.script.ScriptBuilder
import org.jetbrains.exposed.dao.id.EntityID
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.Settlement
import xyz.funkybit.core.model.WalletAndSymbol
import xyz.funkybit.core.model.bitcoin.ProgramInstruction
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexEntity
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexStatus
import xyz.funkybit.core.model.db.ArchAccountBalanceInfo
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountType
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.model.db.ConfirmedBitcoinDeposit
import xyz.funkybit.core.model.db.CreateArchAccountBalanceIndexAssignment
import xyz.funkybit.core.model.db.SequencedArchWithdrawal
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.model.db.WalletTable
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.services.UtxoSelectionService
import xyz.funkybit.core.utils.schnorr.Schnorr
import xyz.funkybit.core.utils.sha256
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import java.math.BigDecimal
import java.math.BigInteger

object ArchUtils {

    val logger = KotlinLogging.logger {}

    private val submitterPubkey = BitcoinClient.bitcoinConfig.submitterPubkey

    fun fundArchAccountCreation(
        ecKey: ECKey,
        accountType: ArchAccountType,
        symbolEntity: SymbolEntity? = null,
    ): ArchAccountEntity? {
        val config = BitcoinClient.bitcoinConfig

        val accountAddress = ArchNetworkClient.getAccountAddress(ArchNetworkRpc.Pubkey.fromECKey(ecKey))

        val rentAmount = BigInteger("1500")

        return try {
            val selectedUtxos = UtxoSelectionService.selectUtxos(
                config.feePayerAddress,
                rentAmount,
                BitcoinClient.calculateFee(BitcoinClient.estimateVSize(1, 2)),
            )

            val onboardingTx = BitcoinClient.buildAndSignDepositTx(
                accountAddress,
                rentAmount,
                selectedUtxos,
                config.feePayerEcKey,
            )

            val txId = BitcoinClient.sendRawTransaction(onboardingTx.toHexString())
            UtxoSelectionService.reserveUtxos(selectedUtxos, txId.value)
            ArchAccountEntity.create(BitcoinUtxoId.fromTxHashAndVout(txId, 0), ecKey, accountType, symbolEntity)
        } catch (e: Exception) {
            logger.warn { "Unable to onboard state UTXO: ${e.message}" }
            null
        }
    }

    fun sendCreateAccountTx(account: ArchAccountEntity, owningProgram: ArchNetworkRpc.Pubkey?) {
        val ecKey = account.ecKey()
        val creationTxId = signAndSendInstructions(
            listOf(
                ArchNetworkRpc.createNewAccountInstruction(
                    ArchNetworkRpc.Pubkey.fromECKey(ecKey),
                    ArchNetworkRpc.UtxoMeta(account.utxoId.txId(), account.utxoId.vout().toInt()),
                ),
            ) + (
                owningProgram?.let {
                    listOf(
                        ArchNetworkRpc.changeOwnershipInstruction(
                            ArchNetworkRpc.Pubkey.fromECKey(ecKey),
                            it,
                        ),
                    )
                } ?: listOf()
                ),
            ecKey.privKeyBytes,
        )
        account.markAsCreating(creationTxId)
    }

    fun handleCreateAccountTxStatus(account: ArchAccountEntity) {
        account.creationTxId?.let { txId ->
            handleTxStatusUpdate(
                txId = txId,
                onError = {
                    logger.error { "Account creation failed for ${account.publicKey}" }
                    account.markAsFailed()
                },
                onProcessed = {
                    account.markAsCreated()
                    logger.debug { "Completed initialization for account ${account.publicKey}" }
                },
            )
        }
    }

    fun handleTxStatusUpdate(txId: TxHash, onError: () -> Unit, onProcessed: () -> Unit): Boolean {
        return ArchNetworkClient.getProcessedTransaction(txId)?.let {
            when (it.status) {
                ArchNetworkRpc.Status.Processed -> {
                    logger.debug { "$txId Processed" }
                    onProcessed()
                }
                ArchNetworkRpc.Status.Failed -> {
                    logger.debug { "$txId Failed Processing" }
                    onError()
                }
                ArchNetworkRpc.Status.Processing -> {
                    logger.debug { "$txId Processing" }
                }
            }
            true
        } ?: false
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun buildDepositBatchInstruction(programPubkey: ArchNetworkRpc.Pubkey, deposits: List<ConfirmedBitcoinDeposit>): ArchNetworkRpc.Instruction {
        val accountMetas = mutableListOf(
            ArchNetworkRpc.AccountMeta(
                pubkey = submitterPubkey,
                isSigner = true,
                isWritable = false,
            ),
        )
        val tokenDepositsList = deposits.groupBy { it.archAccountEntity.rpcPubkey() }.map { (pubKey, tokenDeposits) ->
            ProgramInstruction.TokenDeposits(
                accountIndex = accountMetas.size.toUByte(),
                deposits = tokenDeposits.map { ProgramInstruction.Adjustment(it.balanceIndex!!.toUInt(), it.depositEntity.amount.toLong().toULong()) },
            ).also {
                accountMetas.add(
                    ArchNetworkRpc.AccountMeta(
                        pubkey = pubKey,
                        isWritable = true,
                        isSigner = false,
                    ),
                )
            }
        }
        return ArchNetworkRpc.Instruction(
            programPubkey,
            accountMetas,
            ProgramInstruction.DepositBatchParams(tokenDepositsList).serialize(),
        )
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun buildWithdrawBatchInstruction(programBitcoinAddress: BitcoinAddress, programPubkey: ArchNetworkRpc.Pubkey, withdrawals: List<SequencedArchWithdrawal>): Pair<ArchNetworkRpc.Instruction, List<BitcoinUtxoEntity>> {
        val accountMetas = mutableListOf(
            ArchNetworkRpc.AccountMeta(
                pubkey = submitterPubkey,
                isSigner = true,
                isWritable = true,
            ),
        )
        val totalAmount = withdrawals.sumOf { it.withdrawalEntity.chainAmount() }
        val selectedUtxos = UtxoSelectionService.selectUtxosForProgram(totalAmount, BitcoinClient.calculateFee(BitcoinClient.estimateVSize(withdrawals.size, withdrawals.size + 1)))
        val txHex = buildWithdrawalTx(withdrawals.map { it.withdrawalEntity }, programBitcoinAddress, totalAmount, selectedUtxos)
        val tokenWithdrawalsList = withdrawals.groupBy { it.archAccountEntity.rpcPubkey() }.map { (pubKey, tokenWithdrawals) ->
            ProgramInstruction.TokenWithdrawals(
                accountIndex = accountMetas.size.toUByte(),
                feeAddressIndex = 0u,
                withdrawals = tokenWithdrawals.map {
                    ProgramInstruction.Withdrawal(
                        it.balanceIndex.toUInt(),
                        it.withdrawalEntity.resolvedAmount().toLong().toULong(),
                        it.withdrawalEntity.fee.toLong().toULong(),
                    )
                },
            ).also {
                accountMetas.add(
                    ArchNetworkRpc.AccountMeta(
                        pubkey = pubKey,
                        isWritable = true,
                        isSigner = false,
                    ),
                )
            }
        }
        return Pair(
            ArchNetworkRpc.Instruction(
                programPubkey,
                accountMetas,
                ProgramInstruction.WithdrawBatchParams(
                    tokenWithdrawalsList,
                    txHex.toHexBytes(),
                ).serialize(),
            ),
            selectedUtxos,
        )
    }

    private fun buildSettlementBatch(
        batchSettlement: Settlement.Batch,
        indexMap: Map<WalletAndSymbol, Int>,
    ): Pair<List<ArchNetworkRpc.AccountMeta>, List<ProgramInstruction.SettlementAdjustments>> {
        val tokenAccountBySymbolId = ArchAccountEntity.findTokenAccountsForSymbols(
            batchSettlement.tokenAdjustmentLists.map { it.symbolId },
        ).associateBy { it.symbolGuid!!.value }
        val accountMetas = mutableListOf(
            ArchNetworkRpc.AccountMeta(
                pubkey = submitterPubkey,
                isSigner = true,
                isWritable = true,
            ),
        )
        val settlements = batchSettlement.tokenAdjustmentLists.mapIndexed { index, tokenAdjustmentList ->
            ProgramInstruction.SettlementAdjustments(
                accountIndex = (index + 1).toUByte(),
                increments = tokenAdjustmentList.increments.map {
                    ProgramInstruction.Adjustment(
                        addressIndex = indexMap.getValue(WalletAndSymbol(it.walletId, tokenAdjustmentList.symbolId)).toUInt(),
                        amount = it.amount.toLong().toULong(),
                    )
                },
                decrements = tokenAdjustmentList.decrements.map {
                    ProgramInstruction.Adjustment(
                        addressIndex = indexMap.getValue(WalletAndSymbol(it.walletId, tokenAdjustmentList.symbolId)).toUInt(),
                        amount = it.amount.toLong().toULong(),
                    )
                },
                feeAmount = tokenAdjustmentList.feeAmount.toLong().toULong(),
            ).also {
                accountMetas.add(
                    ArchNetworkRpc.AccountMeta(
                        pubkey = tokenAccountBySymbolId.getValue(tokenAdjustmentList.symbolId).rpcPubkey(),
                        isWritable = true,
                        isSigner = false,
                    ),
                )
            }
        }
        return Pair(accountMetas, settlements)
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun buildPrepareSettlementBatchInstruction(
        programPubkey: ArchNetworkRpc.Pubkey,
        batchSettlement: Settlement.Batch,
        indexMap: Map<WalletAndSymbol, Int>,
    ): Pair<ArchNetworkRpc.Instruction, String> {
        val (accountMetas, settlements) = buildSettlementBatch(batchSettlement, indexMap)
        return Pair(
            ArchNetworkRpc.Instruction(
                programPubkey,
                accountMetas,
                ProgramInstruction.PrepareSettlementBatchParams(settlements).serialize(),
            ),
            sha256(Borsh.encodeToByteArray(ProgramInstruction.PrepareSettlementBatchParams(settlements))).toHex(false),
        )
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun buildSubmitSettlementBatchInstruction(
        programPubkey: ArchNetworkRpc.Pubkey,
        batchSettlement: Settlement.Batch,
        indexMap: Map<WalletAndSymbol, Int>,
    ): Pair<ArchNetworkRpc.Instruction, String> {
        val (accountMetas, settlements) = buildSettlementBatch(batchSettlement, indexMap)
        return Pair(
            ArchNetworkRpc.Instruction(
                programPubkey,
                accountMetas,
                ProgramInstruction.SubmitSettlementBatchParams(settlements).serialize(),
            ),
            sha256(Borsh.encodeToByteArray(ProgramInstruction.PrepareSettlementBatchParams(settlements))).toHex(false),
        )
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun buildRollbackSettlementBatchInstruction(
        programPubkey: ArchNetworkRpc.Pubkey,
    ): ArchNetworkRpc.Instruction {
        return ArchNetworkRpc.Instruction(
            programPubkey,
            listOf(
                ArchNetworkRpc.AccountMeta(
                    pubkey = submitterPubkey,
                    isSigner = true,
                    isWritable = true,
                ),
            ),
            ProgramInstruction.RollbackSettlement.serialize(),
        )
    }

    private fun buildWithdrawalTx(
        withdrawals: List<WithdrawalEntity>,
        changeAddress: BitcoinAddress,
        totalAmount: BigInteger,
        utxos: List<BitcoinUtxoEntity>,
    ): String {
        val params = BitcoinClient.getParams()
        val feeAmount = estimateWithdrawalTxFee(
            withdrawals.map { (it.wallet.address as BitcoinAddress) },
            changeAddress,
            utxos,
        )
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        withdrawals.forEach {
            rawTx.addOutput(
                TransactionOutput(
                    params,
                    rawTx,
                    Coin.valueOf(it.chainAmount().toLong()),
                    (it.wallet.address as BitcoinAddress).toBitcoinCoreAddress(params),
                ),
            )
        }
        val changeAmount = BigInteger.ZERO.max(utxos.sumOf { it.amount } - totalAmount - feeAmount)
        if (changeAmount > BitcoinClient.bitcoinConfig.changeDustThreshold) {
            logger.debug { "Adding change output of  $changeAmount" }
            rawTx.addOutput(
                TransactionOutput(
                    params,
                    rawTx,
                    Coin.valueOf(changeAmount.toLong()),
                    changeAddress.toBitcoinCoreAddress(params),
                ),
            )
        }
        utxos.forEach {
            logger.debug { "Adding input with value of ${it.amount}" }
            rawTx.addInput(
                TransactionInput(
                    params,
                    rawTx,
                    ByteArray(0),
                    TransactionOutPoint(
                        params,
                        it.vout(),
                        Sha256Hash.wrap(it.txId().value),
                    ),
                    Coin.valueOf(it.amount.toLong()),
                ),
            )
        }
        return rawTx.toHexString()
    }

    private fun estimateWithdrawalTxFee(
        destinationAddresses: List<BitcoinAddress>,
        changeAddress: BitcoinAddress,
        utxos: List<BitcoinUtxoEntity>,
    ): BigInteger {
        val ecKey = ECKey()
        val params = BitcoinClient.getParams()
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        destinationAddresses.forEach {
            rawTx.addOutput(
                TransactionOutput(
                    params,
                    rawTx,
                    BitcoinClient.zeroCoinValue,
                    it.toBitcoinCoreAddress(params),
                ),
            )
        }
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                BitcoinClient.zeroCoinValue,
                changeAddress.toBitcoinCoreAddress(params),
            ),
        )
        utxos.forEach {
            rawTx.addSignedInput(
                TransactionOutPoint(
                    params,
                    it.vout(),
                    Sha256Hash.wrap(it.txId().value),
                ),
                ScriptBuilder.createP2WPKHOutputScript(ecKey),
                Coin.valueOf(it.amount.toLong()),
                ecKey,
                Transaction.SigHash.NONE,
                true,
            )
        }
        logger.debug { "estimated virtual size for withdraw tx = ${rawTx.vsize}" }
        return BitcoinClient.calculateFee(rawTx.vsize)
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun buildInitTokenBalanceIndexBatchInstruction(programPubkey: ArchNetworkRpc.Pubkey, infos: List<ArchAccountBalanceInfo>): ArchNetworkRpc.Instruction {
        val accountMetas = mutableListOf(
            ArchNetworkRpc.AccountMeta(
                pubkey = submitterPubkey,
                isSigner = true,
                isWritable = false,
            ),
        )
        val setups = infos.groupBy { it.archAccountAddress }.map { (pubKey, infos) ->
            ProgramInstruction.TokenBalanceSetup(
                accountIndex = accountMetas.size.toUByte(),
                walletAddresses = infos.map { it.walletAddress.value },
            ).also {
                accountMetas.add(
                    ArchNetworkRpc.AccountMeta(
                        pubkey = pubKey,
                        isWritable = true,
                        isSigner = false,
                    ),
                )
            }
        }
        return ArchNetworkRpc.Instruction(
            programPubkey,
            accountMetas,
            ProgramInstruction.InitTokenBalancesParams(setups).serialize(),
        )
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    inline fun <reified T> getAccountState(pubkey: ArchNetworkRpc.Pubkey): T =
        Borsh.decodeFromByteArray(ArchNetworkClient.readAccountInfo(pubkey).data.toByteArray())

    @OptIn(ExperimentalUnsignedTypes::class)
    fun signAndSendProgramInstruction(programPubkey: ArchNetworkRpc.Pubkey, accountMetas: List<ArchNetworkRpc.AccountMeta>, programInstruction: ProgramInstruction): TxHash {
        return signAndSendInstruction(
            ArchNetworkRpc.Instruction(
                programPubkey,
                accountMetas,
                Borsh.encodeToByteArray(
                    programInstruction,
                ).toUByteArray(),
            ),
            BitcoinClient.bitcoinConfig.submitterPrivateKey,
        )
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun signAndSendInstructions(instructions: List<ArchNetworkRpc.Instruction>, privateKey: ByteArray): TxHash {
        val message = ArchNetworkRpc.Message(
            signers = listOf(
                ArchNetworkRpc.Pubkey.fromECKey(ECKey.fromPrivate(privateKey)),
            ),
            instructions = instructions,
        )

        val signature = Schnorr.sign(message.hash(), privateKey)

        val runtimeTransaction = ArchNetworkRpc.RuntimeTransaction(
            version = 0,
            signatures = listOf(
                ArchNetworkRpc.Signature(signature.toUByteArray()),
            ),
            message = message,
        )

        return ArchNetworkClient.sendTransaction(runtimeTransaction)
    }

    fun signAndSendInstruction(instruction: ArchNetworkRpc.Instruction, privateKey: ByteArray? = null): TxHash {
        return signAndSendInstructions(listOf(instruction), privateKey ?: BitcoinClient.bitcoinConfig.submitterPrivateKey)
    }

    fun retrieveOrCreateBalanceIndexes(batchSettlement: Settlement.Batch): Map<WalletAndSymbol, Int>? {
        val walletSymbolSet = batchSettlement.tokenAdjustmentLists.flatMap {
            it.increments.map { inc -> WalletAndSymbol(inc.walletId, it.symbolId) } +
                it.decrements.map { dec -> WalletAndSymbol(dec.walletId, it.symbolId) }
        }.toSet()
        val balanceIndexByWalletAndSymbol = ArchAccountBalanceIndexEntity.findForWalletsAndSymbols(
            walletSymbolSet.map { it.walletId }.toSet().toList(),
            batchSettlement.tokenAdjustmentLists.map { it.symbolId },
        )
        val missing = walletSymbolSet.subtract(balanceIndexByWalletAndSymbol.keys)
        return if (missing.isNotEmpty()) {
            val tokenAccount = ArchAccountEntity.findTokenAccountsForSymbols(missing.map { it.symbolId }.toSet().toList())
            ArchAccountBalanceIndexEntity.batchCreate(
                missing.map { walletAndSymbol ->
                    CreateArchAccountBalanceIndexAssignment(
                        EntityID(walletAndSymbol.walletId, WalletTable),
                        tokenAccount.find { it.symbolGuid?.value == walletAndSymbol.symbolId }!!.guid,
                    )
                },
            )
            null
        } else if (walletSymbolSet.any { balanceIndexByWalletAndSymbol[it]?.status != ArchAccountBalanceIndexStatus.Assigned }) {
            null
        } else {
            walletSymbolSet.associateWith { balanceIndexByWalletAndSymbol[it]!!.addressIndex }
        }
    }
}

fun BigInteger.fromSatoshi(): BigDecimal {
    return BigDecimal(this).setScale(8) / BigDecimal("1e8")
}

fun BigInteger.inSatsAsDecimalString(): String {
    return this.fromSatoshi().toPlainString()
}
