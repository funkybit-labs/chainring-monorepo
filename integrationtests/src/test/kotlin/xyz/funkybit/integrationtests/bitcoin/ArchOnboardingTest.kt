package xyz.funkybit.integrationtests.bitcoin

import com.funkatronics.kborsh.Borsh
import kotlinx.serialization.decodeFromByteArray
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.bitcoin.ProgramState
import xyz.funkybit.core.model.db.ArchStateUtxoEntity
import xyz.funkybit.core.model.db.StateUtxoStatus
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.integrationtests.bitcoin.UtxoSelectionTest.Companion.waitForTx
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.isTestEnvRun
import xyz.funkybit.integrationtests.testutils.triggerRepeaterTaskAndWaitForCompletion
import xyz.funkybit.integrationtests.testutils.waitFor
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals

@OptIn(ExperimentalUnsignedTypes::class)
@ExtendWith(AppUnderTestRunner::class)
class ArchOnboardingTest {

    @Test
    fun testOnboarding() {
        Assumptions.assumeFalse(isTestEnvRun())

        triggerRepeaterTaskAndWaitForCompletion("arch_onboarding")

        airdropToSubmitter(wait = false) // for onboarding
        airdropToSubmitter() // for initialization

        // notify the repeater app task
        triggerRepeaterTaskAndWaitForCompletion("arch_onboarding")

        val stateUtxo = transaction { ArchStateUtxoEntity.getProgramStateUtxo() }
        validateStateUtxo(stateUtxo)
        assertTrue(listOf(StateUtxoStatus.Onboarded, StateUtxoStatus.Initializing, StateUtxoStatus.Complete).contains(stateUtxo.status))

        waitFor {
            triggerRepeaterTaskAndWaitForCompletion("arch_onboarding")
            transaction {
                ArchStateUtxoEntity.getProgramStateUtxo().status == StateUtxoStatus.Complete
            }
        }

        // verify state
        transaction {
            val utxoId = ArchStateUtxoEntity.getProgramStateUtxo().utxoId
            val utxoInfo = ArchNetworkClient.readUtxo(utxoId)
            val programState = Borsh.decodeFromByteArray<ProgramState>(utxoInfo.data.toByteArray())
            assertEquals(BitcoinClient.bitcoinConfig.feeAccountAddress, programState.feeAccount)
            assertEquals("", programState.lastSettlementBatchHash)
            assertEquals("", programState.lastWithdrawalBatchHash)
            assertEquals(BitcoinClient.bitcoinConfig.submitterXOnlyPublicKey.toHex(), utxoInfo.authority.bytes.toHex())
        }

        // now verify token state utxo is onboarded
        airdropToSubmitter()

        // notify the repeater app task
        triggerRepeaterTaskAndWaitForCompletion("arch_onboarding")

        validateStateUtxo(
            transaction {
                ArchStateUtxoEntity.findTokenStateUtxo(SymbolEntity.forChain(BitcoinClient.chainId).first())!!
            },
        )
    }

    private fun airdropToSubmitter(wait: Boolean = true) {
        val txId = BitcoinClient.sendToAddressAndMine(
            BitcoinClient.bitcoinConfig.submitterAddress,
            BigInteger("6000"),
        )
        if (wait) {
            waitForTx(BitcoinClient.bitcoinConfig.submitterAddress, txId)
        }
    }

    private fun validateStateUtxo(stateUtxo: ArchStateUtxoEntity) {
        BitcoinClient.mine(1)
        val tx = BitcoinClient.getRawTransaction(stateUtxo.utxoId.txId())
        val stateUtxoVout = tx.txOuts.first { it.value.compareTo(BigDecimal("0.00001500")) == 0 }.index
        assertEquals(stateUtxoVout, stateUtxo.utxoId.vout().toInt())

        // verify no state data yet and the submitter is the authority
        val utxoInfo = ArchNetworkClient.readUtxo(stateUtxo.utxoId)
        assertEquals(BitcoinClient.bitcoinConfig.submitterXOnlyPublicKey.toHex(), utxoInfo.authority.bytes.toHex())
    }
}
