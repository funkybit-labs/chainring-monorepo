package xyz.funkybit.core.utils.bitcoin

import com.funkatronics.kborsh.Borsh
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.encodeToByteArray
import org.bitcoinj.core.Coin
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.Sha256Hash
import org.bitcoinj.core.Transaction
import org.bitcoinj.core.TransactionOutPoint
import org.bitcoinj.core.TransactionOutput
import org.bitcoinj.script.ScriptBuilder
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.bitcoin.ProgramInstruction
import xyz.funkybit.core.model.bitcoin.UtxoId
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountType
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TxHash
import xyz.funkybit.core.model.db.UnspentUtxo
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.services.UtxoSelectionService
import xyz.funkybit.core.utils.schnorr.Schnorr
import java.math.BigDecimal
import java.math.BigInteger

object ArchUtils {

    val logger = KotlinLogging.logger {}

    // 10.5 (Overhead), 68 (Vin) * 2, 31(Vout), 27 (Witness) * 2
    const val P2WPKH_2IN_1OUT_VSIZE = 232
    const val P2WPKH_2IN_2OUT_VSIZE = 263

    // 57.5 (Vin), 43(Vout), 24 (Witness) (signature(64 bytes) + hash of data(32))
    const val P2TR_UTXO_IN_OUT_VSIZE = 125

    private val zeroCoinValue = Coin.valueOf(0)

    private fun buildFundAccountTx(
        accountAddress: BitcoinAddress,
        amount: BigInteger,
        feeAmount: BigInteger,
        utxos: List<UnspentUtxo>,
    ): Transaction {
        val params = BitcoinClient.getParams()
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
        if (changeAmount > BitcoinClient.bitcoinConfig.changeDustThreshold) {
            rawTx.addOutput(
                TransactionOutput(
                    params,
                    rawTx,
                    Coin.valueOf(changeAmount.toLong()),
                    BitcoinClient.bitcoinConfig.feePayerAddress.toBitcoinCoreAddress(params),
                ),
            )
        }
        utxos.forEach {
            rawTx.addSignedInput(
                TransactionOutPoint(
                    params,
                    it.utxoId.vout(),
                    Sha256Hash.wrap(it.utxoId.txId().value),
                ),
                ScriptBuilder.createP2WPKHOutputScript(BitcoinClient.bitcoinConfig.feePayerEcKey),
                Coin.valueOf(it.amount.toLong()),
                BitcoinClient.bitcoinConfig.feePayerEcKey,
                Transaction.SigHash.NONE,
                true,
            )
        }
        return rawTx
    }

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
                calculateFee(P2WPKH_2IN_2OUT_VSIZE),
            )
            val recalculatedFee = estimateFundAccountTxFee(
                config.feePayerEcKey,
                accountAddress,
                config.feePayerAddress,
                selectedUtxos,
            )

            val onboardingTx = buildFundAccountTx(
                accountAddress,
                rentAmount,
                recalculatedFee,
                selectedUtxos,
            )

            val txId = BitcoinClient.sendRawTransaction(onboardingTx.toHexString())
            UtxoSelectionService.reserveUtxos(config.feePayerAddress, selectedUtxos.map { it.utxoId }.toSet(), txId.value)
            ArchAccountEntity.create(UtxoId.fromTxHashAndVout(txId, 0), ecKey, accountType, symbolEntity)
        } catch (e: Exception) {
            logger.warn(e) { "Unable to onboard state UTXO: ${e.message}" }
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

    private fun estimateFundAccountTxFee(
        ecKey: ECKey,
        accountAddress: BitcoinAddress,
        changeAddress: BitcoinAddress,
        utxos: List<UnspentUtxo>,
    ): BigInteger {
        val params = BitcoinClient.getParams()
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
                changeAddress.toBitcoinCoreAddress(params),
            ),
        )
        utxos.forEach {
            rawTx.addSignedInput(
                TransactionOutPoint(
                    params,
                    it.utxoId.vout(),
                    Sha256Hash.wrap(it.utxoId.txId().value),
                ),
                ScriptBuilder.createP2WPKHOutputScript(ecKey),
                Coin.valueOf(it.amount.toLong()),
                ecKey,
                Transaction.SigHash.NONE,
                true,
            )
        }
        return calculateFee(rawTx.vsize)
    }

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

    fun signAndSendInstruction(instruction: ArchNetworkRpc.Instruction, privateKey: ByteArray): TxHash {
        return signAndSendInstructions(listOf(instruction), privateKey)
    }

    fun calculateFee(vsize: Int) =
        BitcoinClient.estimateSmartFeeInSatPerVByte().toBigInteger() * vsize.toBigInteger()
}

fun BigInteger.fromSatoshi(): BigDecimal {
    return BigDecimal(this).setScale(8) / BigDecimal("1e8")
}

fun BigInteger.inSatsAsDecimalString(): String {
    return this.fromSatoshi().toPlainString()
}
