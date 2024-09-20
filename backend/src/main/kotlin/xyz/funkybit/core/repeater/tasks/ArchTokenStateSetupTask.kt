package xyz.funkybit.core.repeater.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import org.bitcoinj.core.ECKey
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.apps.ring.ArchContractsPublisher
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.bitcoin.ProgramInstruction
import xyz.funkybit.core.model.db.AccountSetupState
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountStatus
import xyz.funkybit.core.model.db.ArchAccountType
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.utils.bitcoin.ArchUtils
import kotlin.time.Duration.Companion.seconds

class ArchTokenStateSetupTask : RepeaterBaseTask(
    invokePeriod = 30.seconds,
) {
    val logger = KotlinLogging.logger {}

    override val name: String = "arch_token_state_setup"

    override fun runWithLock() {
        transaction {
            val programStateAccount = getProgramStateAccount()
            if (programStateAccount?.status != ArchAccountStatus.Complete) {
                logger.debug { "Leaving since program state account is not fully setup" }
                return@transaction
            }

            // onboard token accounts
            findSymbolsWithNoAccount().forEach {
                logger.debug { "Need to setup account for ${it.name}" }
                ArchUtils.fundArchAccountCreation(ECKey(), ArchAccountType.TokenState, it)
            }
            ArchAccountEntity.findAllInitializingTokenAccounts().forEach {
                if (it.status == ArchAccountStatus.Funded) {
                    ArchUtils.sendCreateAccountTx(it, getProgramPubkey())
                }
                if (it.status == ArchAccountStatus.Creating) {
                    ArchUtils.handleCreateAccountTxStatus(it)
                }
                if (it.status == ArchAccountStatus.Created) {
                    Thread.sleep(2000)
                    initializeTokenStateAccount(it, programStateAccount)
                }
                if (it.status == ArchAccountStatus.Initializing) {
                    val setupState = it.setupState as AccountSetupState.TokenState
                    ArchUtils.handleTxStatusUpdate(
                        txId = setupState.initializeTxId,
                        onError = {
                            logger.error { "Failed to init token account ${it.publicKey}" }
                            it.markAsFailed()
                        },
                        onProcessed = {
                            ArchContractsPublisher.logger.debug { "Inited token account ${it.publicKey}" }
                            it.markAsComplete()
                        },
                    )
                }
            }
        }
    }

    private fun getProgramStateAccount() = ArchAccountEntity.findProgramStateAccount()

    private fun getProgramPubkey() = ArchAccountEntity.findProgramAccount()!!.rpcPubkey()

    private fun findSymbolsWithNoAccount(): List<SymbolEntity> {
        val allSymbols = SymbolEntity.forChain(BitcoinClient.chainId)
        val symbolsWithTokenState = ArchAccountEntity.findAllTokenAccounts().filterNot { it.status == ArchAccountStatus.Full }.map { it.symbolGuid }.toSet()
        return allSymbols.filterNot { symbolsWithTokenState.contains(it.guid) }
    }

    private fun initializeTokenStateAccount(
        tokenAccount: ArchAccountEntity,
        programStateAccount: ArchAccountEntity,
    ) {
        ArchUtils.signAndSendProgramInstruction(
            getProgramPubkey(),
            listOf(
                ArchNetworkRpc.AccountMeta(
                    pubkey = programStateAccount.rpcPubkey(),
                    isWritable = false,
                    isSigner = true,
                ),
                ArchNetworkRpc.AccountMeta(
                    pubkey = tokenAccount.rpcPubkey(),
                    isWritable = true,
                    isSigner = true,
                ),
            ),
            ProgramInstruction.InitTokenStateParams(
                tokenId = tokenAccount.symbol!!.name,
            ),
        ).also {
            transaction {
                tokenAccount.markAsInitializing(AccountSetupState.TokenState(it))
            }
        }
    }
}
