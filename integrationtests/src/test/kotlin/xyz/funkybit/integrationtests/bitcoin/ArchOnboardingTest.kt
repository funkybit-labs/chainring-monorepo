package xyz.funkybit.integrationtests.bitcoin

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.db.ArchStateUtxoEntity
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.integrationtests.bitcoin.UtxoSelectionTest.Companion.waitForTx
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.isTestEnvRun
import xyz.funkybit.integrationtests.testutils.triggerRepeaterTaskAndWaitForCompletion
import java.math.BigDecimal
import java.math.BigInteger
import kotlin.test.assertEquals

@ExtendWith(AppUnderTestRunner::class)
class ArchOnboardingTest {

    @Test
    fun testOnboarding() {
        Assumptions.assumeFalse(isTestEnvRun())

        triggerRepeaterTaskAndWaitForCompletion("arch_onboarding")

        airdropToSubmitter()

        // notify the repeater app task
        triggerRepeaterTaskAndWaitForCompletion("arch_onboarding")

        validateStateUtxo(
            transaction {
                ArchStateUtxoEntity.findExchangeStateUtxo()!!
            },
        )

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

    private fun airdropToSubmitter() {
        val txId = BitcoinClient.sendToAddressAndMine(
            BitcoinClient.bitcoinConfig.submitterAddress,
            BigInteger("6000"),
        )
        waitForTx(BitcoinClient.bitcoinConfig.submitterAddress, txId)
    }

    @OptIn(ExperimentalUnsignedTypes::class)
    private fun validateStateUtxo(stateUtxo: ArchStateUtxoEntity) {
        BitcoinClient.mine(1)
        val tx = BitcoinClient.getRawTransaction(stateUtxo.utxoId.txId())
        val stateUtxoVout = tx.txOuts.first { it.value.compareTo(BigDecimal("0.00001500")) == 0 }.index
        assertEquals(stateUtxoVout, stateUtxo.utxoId.vout().toInt())

        // verify no state data yet and the submitter is the authority
        val utxoInfo = ArchNetworkClient.readUtxo(stateUtxo.utxoId)
        assertEquals("", utxoInfo.data.toByteArray().toHex(false))
        assertEquals(BitcoinClient.bitcoinConfig.submitterXOnlyPublicKey.toHex(), utxoInfo.authority.toByteArray().toHex())
    }
}
