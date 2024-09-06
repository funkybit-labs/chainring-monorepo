package xyz.funkybit.core.repeater.tasks

import io.github.oshai.kotlinlogging.KotlinLogging
import org.jetbrains.exposed.sql.transactions.transaction
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.model.db.TestnetChallengeStatus
import xyz.funkybit.core.model.db.UserEntity
import xyz.funkybit.core.utils.TestnetChallengeUtils
import kotlin.time.Duration.Companion.seconds

class TestnetChallengeMonitorTask : RepeaterBaseTask(
    invokePeriod = 5.seconds,
) {
    override val name: String = "testnet_challenge_monitor"

    val logger = KotlinLogging.logger {}
    val blockchainClient by lazy {
        transaction { ChainManager.getBlockchainClient(TestnetChallengeUtils.depositSymbol().chainId.value) }
    }

    override fun runWithLock() {
        transaction {
            UserEntity.findWithTestnetChallengeStatus(TestnetChallengeStatus.PendingAirdrop).forEach { user ->
                user.testnetAirdropTxHash?.let {
                    blockchainClient.getTransactionReceipt(it)?.let { receipt ->
                        when (receipt.status) {
                            "0x1" -> {
                                user.testnetChallengeStatus = TestnetChallengeStatus.PendingDeposit
                            }

                            else -> {
                                val error = receipt.revertReason ?: "Unknown Error"
                                logger.error { "Airdrop for user ${user.guid} failed with revert reason $error" }
                                user.testnetChallengeStatus = TestnetChallengeStatus.Unenrolled
                            }
                        }
                    }
                }
            }
        }
    }
}
