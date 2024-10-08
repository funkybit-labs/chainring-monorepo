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
import xyz.funkybit.core.blockchain.bitcoin.MempoolSpaceClient
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.ConfirmedBitcoinDeposit
import xyz.funkybit.core.model.PubkeyAndIndex
import xyz.funkybit.core.model.Settlement
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.WalletAndSymbol
import xyz.funkybit.core.model.bitcoin.ProgramInstruction
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexEntity
import xyz.funkybit.core.model.db.ArchAccountBalanceIndexStatus
import xyz.funkybit.core.model.db.ArchAccountBalanceInfo
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountType
import xyz.funkybit.core.model.db.BitcoinUtxoEntity
import xyz.funkybit.core.model.db.BitcoinUtxoId
import xyz.funkybit.core.model.db.CreateArchAccountBalanceIndexAssignment
import xyz.funkybit.core.model.db.DepositEntity
import xyz.funkybit.core.model.db.SequencedArchWithdrawal
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WalletTable
import xyz.funkybit.core.model.db.WithdrawalEntity
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.services.UtxoManager
import xyz.funkybit.core.utils.schnorr.Schnorr
import xyz.funkybit.core.utils.sha256
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.core.utils.toHexBytes
import java.math.BigDecimal
import java.math.BigInteger

object ArchUtils {

    val logger = KotlinLogging.logger {}

    private val submitterPubkey = bitcoinConfig.submitterPubkey
    private val zeroCoinValue = Coin.valueOf(0)

    var walletsPerTokenAccountThreshold: Int = System.getenv("ARCH_WALLETS_PER_TOKEN_ACCOUNT_THRESHOLD")?.toIntOrNull() ?: 100_000

    fun fundArchAccountCreation(
        ecKey: ECKey,
        accountType: ArchAccountType,
        symbolEntity: SymbolEntity? = null,
    ): ArchAccountEntity? {
        val accountAddress = ArchNetworkClient.getAccountAddress(ArchNetworkRpc.Pubkey.fromECKey(ecKey))

        val rentAmount = BigInteger("1500")

        return try {
            val selectedUtxos = UtxoManager.selectUtxos(
                bitcoinConfig.feePayerAddress,
                rentAmount,
                MempoolSpaceClient.calculateFee(MempoolSpaceClient.estimateVSize(1, 2)),
            )

            val onboardingTx = buildAndSignDepositTx(
                accountAddress,
                rentAmount,
                selectedUtxos,
                bitcoinConfig.feePayerEcKey,
            )

            val txId = MempoolSpaceClient.sendTransaction(onboardingTx.toHexString())
            UtxoManager.reserveUtxos(selectedUtxos, txId.value)
            ArchAccountEntity.create(BitcoinUtxoId.fromTxHashAndVout(txId, 0), ecKey, accountType, symbolEntity)
        } catch (e: Exception) {
            UtxoManager.refreshUtxos(bitcoinConfig.feePayerAddress)
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
        val tokenDepositsList = deposits.groupBy { it.pubkeyAndIndex.pubkey }.map { (pubKey, tokenDeposits) ->
            ProgramInstruction.TokenDeposits(
                accountIndex = accountMetas.size.toUByte(),
                deposits = tokenDeposits.map { ProgramInstruction.Adjustment(it.pubkeyAndIndex.addressIndex.toUInt(), it.depositEntity.amount.toLong().toULong()) },
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
    fun buildWithdrawBatchInstruction(
        programBitcoinAddress: BitcoinAddress,
        programPubkey: ArchNetworkRpc.Pubkey,
        withdrawals: List<SequencedArchWithdrawal>,
    ): Pair<ArchNetworkRpc.Instruction, List<BitcoinUtxoEntity>> {
        val accountMetas = mutableListOf(
            ArchNetworkRpc.AccountMeta(
                pubkey = submitterPubkey,
                isSigner = true,
                isWritable = true,
            ),
        )
        val totalAmount = withdrawals.sumOf { it.withdrawalEntity.chainAmount() }
        val selectedUtxos = UtxoManager.selectUtxos(programBitcoinAddress, totalAmount, MempoolSpaceClient.calculateFee(MempoolSpaceClient.estimateVSize(withdrawals.size, withdrawals.size + 1)))
        val (txInputsHex, changeAmount) = buildWithdrawalInputs(withdrawals.map { it.withdrawalEntity }, programBitcoinAddress, totalAmount, selectedUtxos)
        val tokenWithdrawalsList = withdrawals.groupBy { it.archAccountEntity.rpcPubkey() }.map { (pubKey, tokenWithdrawals) ->
            ProgramInstruction.TokenWithdrawals(
                accountIndex = accountMetas.size.toUByte(),
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
                    changeAmount.toLong().toULong(),
                    txInputsHex.toHexBytes(),
                ).serialize(),
            ),
            selectedUtxos,
        )
    }

    private fun buildSettlementBatch(
        batchSettlement: Settlement.Batch,
        indexMap: Map<WalletAndSymbol, PubkeyAndIndex>,
    ): Pair<List<ArchNetworkRpc.AccountMeta>, List<ProgramInstruction.SettlementAdjustments>> {
        val accountMetas = mutableListOf(
            ArchNetworkRpc.AccountMeta(
                pubkey = submitterPubkey,
                isSigner = true,
                isWritable = true,
            ),
        )
        var accountIndex = 1
        val settlements = batchSettlement.tokenAdjustmentLists.flatMap { tokenAdjustmentList ->
            // need to partition based on account pubkey since we might have multiple token accounts for same symbol
            val incrementsMap = tokenAdjustmentList.increments.groupBy { indexMap.getValue(WalletAndSymbol(it.walletId, tokenAdjustmentList.symbolId)).pubkey.toHexString() }
            val decrementsMap = tokenAdjustmentList.decrements.groupBy { indexMap.getValue(WalletAndSymbol(it.walletId, tokenAdjustmentList.symbolId)).pubkey.toHexString() }
            incrementsMap.keys.plus(decrementsMap.keys).mapIndexed { index, pubKey ->
                ProgramInstruction.SettlementAdjustments(
                    accountIndex = accountIndex.toUByte(),
                    increments = (incrementsMap[pubKey] ?: emptyList()).map {
                        ProgramInstruction.Adjustment(
                            addressIndex = indexMap.getValue(WalletAndSymbol(it.walletId, tokenAdjustmentList.symbolId)).addressIndex.toUInt(),
                            amount = it.amount.toLong().toULong(),
                        )
                    },
                    decrements = (decrementsMap[pubKey] ?: emptyList()).map {
                        ProgramInstruction.Adjustment(
                            addressIndex = indexMap.getValue(WalletAndSymbol(it.walletId, tokenAdjustmentList.symbolId)).addressIndex.toUInt(),
                            amount = it.amount.toLong().toULong(),
                        )
                    },
                    feeAmount = if (index == 0) tokenAdjustmentList.feeAmount.toLong().toULong() else 0u,
                ).also {
                    accountMetas.add(
                        ArchNetworkRpc.AccountMeta(
                            pubkey = ArchNetworkRpc.Pubkey.fromHexString(pubKey),
                            isWritable = true,
                            isSigner = false,
                        ),
                    )
                    accountIndex++
                }
            }
        }
        return Pair(accountMetas, settlements)
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    fun buildPrepareSettlementBatchInstruction(
        programPubkey: ArchNetworkRpc.Pubkey,
        batchSettlement: Settlement.Batch,
        indexMap: Map<WalletAndSymbol, PubkeyAndIndex>,
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
        indexMap: Map<WalletAndSymbol, PubkeyAndIndex>,
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

    private fun buildWithdrawalInputs(
        withdrawals: List<WithdrawalEntity>,
        changeAddress: BitcoinAddress,
        totalAmount: BigInteger,
        utxos: List<BitcoinUtxoEntity>,
    ): Pair<String, BigInteger> {
        val params = bitcoinConfig.params
        val feeAmount = estimateWithdrawalTxFee(
            withdrawals.map { (it.wallet.address as BitcoinAddress) },
            changeAddress,
            utxos,
        )
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        val changeAmount = BigInteger.ZERO.max(utxos.sumOf { it.amount } - totalAmount - feeAmount)
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
        return Pair(
            rawTx.toHexString(),
            if (changeAmount > bitcoinConfig.changeDustThreshold) {
                changeAmount
            } else {
                BigInteger.ZERO
            },
        )
    }

    private fun estimateWithdrawalTxFee(
        destinationAddresses: List<BitcoinAddress>,
        changeAddress: BitcoinAddress,
        utxos: List<BitcoinUtxoEntity>,
    ): BigInteger {
        val ecKey = ECKey()
        val params = bitcoinConfig.params
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        destinationAddresses.forEach {
            rawTx.addOutput(
                TransactionOutput(
                    params,
                    rawTx,
                    zeroCoinValue,
                    it.toBitcoinCoreAddress(params),
                ),
            )
        }
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                zeroCoinValue,
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
        return MempoolSpaceClient.calculateFee(rawTx.vsize)
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
    fun getAccountData(pubkey: ArchNetworkRpc.Pubkey) = ArchNetworkClient.readAccountInfo(pubkey).data.toByteArray()

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
            bitcoinConfig.submitterPrivateKey,
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
        return signAndSendInstructions(listOf(instruction), privateKey ?: bitcoinConfig.submitterPrivateKey)
    }

    fun getConfirmedBitcoinDeposits(limit: Int): List<ConfirmedBitcoinDeposit> {
        val confirmedDeposits = DepositEntity.getConfirmedForUpdate(bitcoinConfig.chainId, limit = limit)
        val walletAndSymbols = confirmedDeposits.map { WalletAndSymbol(it.walletGuid.value, it.symbolGuid.value) }.toSet()
        val walletsAndSymbolsWithAssignedIndex = retrieveOrCreateBalanceIndexes(walletAndSymbols, returnAssignedIndexes = true)!!
        return confirmedDeposits.mapNotNull { deposit ->
            val walletAndSymbol = WalletAndSymbol(deposit.walletGuid.value, deposit.symbolGuid.value)
            walletsAndSymbolsWithAssignedIndex[walletAndSymbol]?.let {
                ConfirmedBitcoinDeposit(deposit, it)
            }
        }
    }

    fun retrieveOrCreateBalanceIndexes(batchSettlement: Settlement.Batch): Map<WalletAndSymbol, PubkeyAndIndex>? {
        return retrieveOrCreateBalanceIndexes(
            batchSettlement.tokenAdjustmentLists.flatMap {
                it.increments.map { inc -> WalletAndSymbol(inc.walletId, it.symbolId) } +
                    it.decrements.map { dec -> WalletAndSymbol(dec.walletId, it.symbolId) }
            }.toSet(),
        )
    }

    fun retrieveOrCreateBalanceIndexes(walletAndSymbols: Set<WalletAndSymbol>, returnAssignedIndexes: Boolean = false): Map<WalletAndSymbol, PubkeyAndIndex>? {
        val balanceIndexByWalletAndSymbol = ArchAccountBalanceIndexEntity.findForWalletsAndSymbols(
            walletAndSymbols.map { it.walletId }.toSet().toList(),
            walletAndSymbols.map { it.symbolId }.toSet().toList(),
        )
        val missing = walletAndSymbols.subtract(balanceIndexByWalletAndSymbol.keys)
        val result = if (missing.isNotEmpty()) {
            val tokenAccount = missing.map { it.symbolId }.toSet().mapNotNull {
                ArchAccountEntity.findTokenAccountForSymbolForNewIndex(it)
            }
            ArchAccountBalanceIndexEntity.batchCreate(
                missing.mapNotNull { walletAndSymbol ->
                    tokenAccount.firstOrNull { it.symbolGuid?.value == walletAndSymbol.symbolId }?.guid?.let { tokenAccountGuid ->
                        CreateArchAccountBalanceIndexAssignment(
                            EntityID(walletAndSymbol.walletId, WalletTable),
                            tokenAccountGuid,
                        )
                    }
                },
            )
            null
        } else if (walletAndSymbols.any { balanceIndexByWalletAndSymbol[it]?.status != ArchAccountBalanceIndexStatus.Assigned }) {
            null
        } else {
            walletAndSymbols.associateWith { balanceIndexByWalletAndSymbol[it]!!.let { balanceIndex -> PubkeyAndIndex(balanceIndex.archAccount.rpcPubkey(), balanceIndex.addressIndex) } }
        }

        return if (result == null && returnAssignedIndexes) {
            walletAndSymbols.mapNotNull {
                val balanceIndex = balanceIndexByWalletAndSymbol[it]
                if (balanceIndex?.status == ArchAccountBalanceIndexStatus.Assigned) {
                    it to PubkeyAndIndex(balanceIndex.archAccount.rpcPubkey(), balanceIndex.addressIndex)
                } else {
                    null
                }
            }.toMap()
        } else {
            result
        }
    }

    fun buildAndSignDepositTx(
        accountAddress: BitcoinAddress,
        amount: BigInteger,
        utxos: List<BitcoinUtxoEntity>,
        ecKey: ECKey,
    ): Transaction {
        val params = bitcoinConfig.params
        val feeAmount = estimateDepositTxFee(ecKey, accountAddress, utxos)
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                Coin.valueOf(amount.toLong()),
                accountAddress.toBitcoinCoreAddress(params),
            ),
        )
        val changeAmount = BigInteger.ZERO.max(utxos.sumOf { it.amount } - amount - feeAmount)
        if (changeAmount > bitcoinConfig.changeDustThreshold) {
            rawTx.addOutput(
                TransactionOutput(
                    params,
                    rawTx,
                    Coin.valueOf(changeAmount.toLong()),
                    BitcoinAddress.fromKey(params, ecKey).toBitcoinCoreAddress(params),
                ),
            )
        }
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
        return rawTx
    }

    private fun estimateDepositTxFee(
        ecKey: ECKey,
        accountAddress: BitcoinAddress,
        utxos: List<BitcoinUtxoEntity>,
    ): BigInteger {
        val params = bitcoinConfig.params
        val rawTx = Transaction(params)
        rawTx.setVersion(2)
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                zeroCoinValue,
                accountAddress.toBitcoinCoreAddress(params),
            ),
        )
        rawTx.addOutput(
            TransactionOutput(
                params,
                rawTx,
                zeroCoinValue,
                BitcoinAddress.fromKey(params, ecKey).toBitcoinCoreAddress(params),
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
        return MempoolSpaceClient.calculateFee(rawTx.vsize)
    }
}

fun BigInteger.fromSatoshi(): BigDecimal {
    return BigDecimal(this).setScale(8) / BigDecimal("1e8")
}

fun BigInteger.inSatsAsDecimalString(): String {
    return this.fromSatoshi().toPlainString()
}
