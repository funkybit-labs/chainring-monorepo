package xyz.funkybit.core.utils.bitcoin

import com.funkatronics.kborsh.Borsh
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import org.bitcoinj.core.ECKey
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.bitcoin.ProgramInstruction
import xyz.funkybit.core.model.bitcoin.UtxoId
import xyz.funkybit.core.model.db.ArchAccountBalanceInfo
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountType
import xyz.funkybit.core.model.db.ConfirmedBitcoinDeposit
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.TxHash
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
                BitcoinClient.calculateFee(P2WPKH_2IN_2OUT_VSIZE),
            )

            val onboardingTx = BitcoinClient.buildAndSignDepositTx(
                accountAddress,
                rentAmount,
                selectedUtxos,
                config.feePayerEcKey,
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
}

fun BigInteger.fromSatoshi(): BigDecimal {
    return BigDecimal(this).setScale(8) / BigDecimal("1e8")
}

fun BigInteger.inSatsAsDecimalString(): String {
    return this.fromSatoshi().toPlainString()
}
