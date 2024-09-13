package xyz.funkybit.apps.ring

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bitcoinj.core.ECKey
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.bitcoin.ProgramInstruction
import xyz.funkybit.core.model.db.AccountSetupState
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountStatus
import xyz.funkybit.core.model.db.ArchAccountType
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import xyz.funkybit.core.utils.schnorr.Schnorr
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ArchContractsPublisher {
    val logger = KotlinLogging.logger {}

    fun updateContracts() {
        transaction {
            ContractType.entries.forEach { contractType ->
                val deployedContract = DeployedSmartContractEntity
                    .findLastDeployedContractByNameAndChain(contractType.name, BitcoinClient.chainId)

                if (deployedContract == null) {
                    logger.info { "Deploying arch contract: $contractType" }
                    deployContract(contractType)
                } else if (deployedContract.deprecated) {
                    throw Exception("upgrades not currently supported on Arch.")
                }
            }
        }
    }

    private fun deployContract(contractType: ContractType) {
        var programAccount = getProgramAccount()
        while (programAccount?.status?.isFinal() != true) {
            try {
                transaction {
                    handleProgramDeployment()
                }
            } catch (e: Exception) {
                logger.error(e) { "Uncaught exception in deployment of arch contract" }
            }
            Thread.sleep(2000)
            programAccount = getProgramAccount()
        }

        var programStateAccount = getProgramStateAccount()
        while (programStateAccount?.status?.isFinal() != true) {
            try {
                transaction {
                    handleProgramStateSetup(programAccount, contractType)
                }
            } catch (e: Exception) {
                logger.error(e) { "Uncaught exception in deployment of arch contract" }
            }
            Thread.sleep(2000)
            programStateAccount = getProgramStateAccount()
        }
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun handleProgramDeployment() {
        val programAccount = ArchAccountEntity.findProgramAccount() ?: ArchUtils.fundArchAccountCreation(ECKey(), ArchAccountType.Program)
        when (programAccount?.status) {
            ArchAccountStatus.Funded -> {
                ArchUtils.sendCreateAccountTx(programAccount, null)
            }

            ArchAccountStatus.Creating -> {
                ArchUtils.handleCreateAccountTxStatus(programAccount)
            }

            ArchAccountStatus.Created -> {
                val chunkSize = ArchNetworkClient.MAX_TX_SIZE - 187 // 187 is overhead signature, account meta, system program id etc
                var chunkOffset = 0
                val ecKey = programAccount.ecKey()
                val pubkey = ArchNetworkRpc.Pubkey.fromECKey(ecKey)
                javaClass.getResource("/exchangeprogram.so")?.readBytes()?.let {
                    val txs = it.asSequence().chunked(chunkSize) { chunk ->
                        logger.debug { "building extendBytesTransactions offset = $chunkOffset, size = ${chunk.size}" }
                        val buffer = ByteBuffer.allocate(chunk.size + 8)
                        buffer.order(ByteOrder.LITTLE_ENDIAN)
                        buffer.putInt(chunkOffset)
                        buffer.putInt(chunk.size)
                        buffer.put(chunk.toByteArray())
                        chunkOffset += chunk.size
                        val message = ArchNetworkRpc.Message(
                            signers = listOf(
                                ArchNetworkRpc.Pubkey.fromECKey(ecKey),
                            ),
                            instructions = listOf(
                                ArchNetworkRpc.extendBytesInstruction(pubkey, buffer.array()),
                            ),
                        )

                        val signature = Schnorr.sign(message.hash(), ecKey.privKeyBytes)

                        ArchNetworkRpc.RuntimeTransaction(
                            version = 0,
                            signatures = listOf(
                                ArchNetworkRpc.Signature(signature.toUByteArray()),
                            ),
                            message = message,
                        )
                    }.toList()
                    val txIds = txs.asSequence().chunked(100) { rtxs ->
                        ArchNetworkClient.sendTransactions(rtxs).also {
                            Thread.sleep(4000)
                        }
                    }.flatten().toList()
                    programAccount.markAsInitializing(
                        AccountSetupState.Program(txIds, 0, null),
                    )
                }
            }

            ArchAccountStatus.Initializing -> {
                val setupState = (programAccount.setupState as AccountSetupState.Program).copy()
                when {
                    setupState.executableTxId != null -> {
                        ArchUtils.handleTxStatusUpdate(
                            txId = setupState.executableTxId!!,
                            onError = {
                                logger.error { "Failed to make account ${programAccount.publicKey} executable" }
                                programAccount.markAsFailed()
                            },
                            onProcessed = {
                                logger.debug { "Account ${programAccount.publicKey} is now executable" }
                                programAccount.markAsComplete()
                            },
                        )
                    }

                    setupState.numProcessed == setupState.deploymentTxIds.size -> {
                        Thread.sleep(2000)
                        sendMakeAccountExecutableTx(programAccount, setupState)
                    }

                    else -> {
                        var index = setupState.numProcessed
                        while (index < setupState.deploymentTxIds.size && ArchUtils.handleTxStatusUpdate(
                                setupState.deploymentTxIds[index],
                                onError = {
                                    programAccount.markAsFailed()
                                    logger.debug { "Failed to process $index deployment txId" }
                                },
                                onProcessed = {
                                    setupState.numProcessed++
                                    logger.debug { "Processed $index deployment txId" }
                                },
                            )
                        ) {
                            index++
                        }
                        if (setupState.numProcessed == setupState.deploymentTxIds.size) {
                            Thread.sleep(2000)
                            sendMakeAccountExecutableTx(programAccount, setupState)
                        } else {
                            programAccount.markAsInitializing(setupState)
                        }
                    }
                }
            }

            else -> {}
        }
    }

    private fun handleProgramStateSetup(programAccount: ArchAccountEntity, contractType: ContractType) {
        val programStateAccount = ArchAccountEntity.findProgramStateAccount() ?: ArchUtils.fundArchAccountCreation(BitcoinClient.bitcoinConfig.submitterEcKey, ArchAccountType.ProgramState)
        when (programStateAccount?.status) {
            ArchAccountStatus.Funded -> {
                ArchUtils.sendCreateAccountTx(programStateAccount, programAccount.rpcPubkey())
            }

            ArchAccountStatus.Creating -> {
                ArchUtils.handleCreateAccountTxStatus(programStateAccount)
            }

            ArchAccountStatus.Created -> {
                initializeProgramStateAccount(programAccount, programStateAccount)
            }

            ArchAccountStatus.Initializing -> {
                val setupState = programStateAccount.setupState as AccountSetupState.ProgramState
                ArchUtils.handleTxStatusUpdate(
                    txId = setupState.initializeTxId,
                    onError = {
                        logger.error { "Failed to initialize program state account ${programStateAccount.publicKey}" }
                        programStateAccount.markAsFailed()
                    },
                    onProcessed = {
                        logger.debug { "Initialized program state account ${programStateAccount.publicKey} " }
                        // get the address of the program to use for deposits
                        val address = ArchNetworkClient.getAccountAddress(programAccount.rpcPubkey())
                        DeployedSmartContractEntity.create(
                            name = contractType.name,
                            chainId = BitcoinClient.chainId,
                            implementationAddress = address,
                            proxyAddress = address,
                            version = 1,
                        )
                        programStateAccount.markAsComplete()
                    },
                )
            }

            else -> {}
        }
    }

    private fun getProgramAccount() = transaction {
        ArchAccountEntity.findProgramAccount()
    }

    private fun getProgramStateAccount() = transaction {
        ArchAccountEntity.findProgramStateAccount()
    }

    private fun sendMakeAccountExecutableTx(account: ArchAccountEntity, setupState: AccountSetupState.Program) {
        val executableTxId = ArchUtils.signAndSendInstruction(
            ArchNetworkRpc.makeAccountExecutableInstruction(
                ArchNetworkRpc.Pubkey.fromECKey(account.ecKey()),
            ),
            account.ecKey().privKeyBytes,
        )
        setupState.executableTxId = executableTxId
        logger.debug { "setting executable id ${setupState.executableTxId} ${setupState.numProcessed}" }
        account.markAsInitializing(setupState)
    }

    private fun initializeProgramStateAccount(programAccount: ArchAccountEntity, programStateAccount: ArchAccountEntity) {
        ArchUtils.signAndSendProgramInstruction(
            programAccount.rpcPubkey(),
            listOf(
                ArchNetworkRpc.AccountMeta(
                    pubkey = programStateAccount.rpcPubkey(),
                    isWritable = true,
                    isSigner = true,
                ),
            ),
            ProgramInstruction.InitProgramStateParams(
                feeAccount = BitcoinClient.bitcoinConfig.feeCollectionAddress,
            ),
        ).also {
            transaction {
                programStateAccount.markAsInitializing(AccountSetupState.ProgramState(it))
            }
        }
    }
}
