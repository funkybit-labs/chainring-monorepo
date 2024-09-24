package xyz.funkybit.integrationtests.bitcoin

import com.funkatronics.kborsh.Borsh
import kotlinx.serialization.decodeFromByteArray
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.BitcoinClient
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.bitcoin.ArchAccountState
import xyz.funkybit.core.model.bitcoin.BitcoinNetworkType
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountStatus
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.isTestEnvRun
import xyz.funkybit.integrationtests.testutils.triggerRepeaterTaskAndWaitForCompletion
import xyz.funkybit.integrationtests.testutils.waitFor
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalUnsignedTypes::class)
@ExtendWith(AppUnderTestRunner::class)
class ArchOnboardingTest {

    companion object {
        fun waitForProgramAccount(): ArchAccountEntity {
            if (programAccount()?.status != ArchAccountStatus.Complete) {
                waitFor(180000) {
                    programAccount()?.status == ArchAccountStatus.Complete
                }
                Thread.sleep(2000)
            }
            return programAccount()!!
        }

        fun waitForProgramStateAccount(): ArchAccountEntity {
            if (programStateAccount()?.status != ArchAccountStatus.Complete) {
                waitFor(6000) {
                    programStateAccount()?.status == ArchAccountStatus.Complete
                }
                Thread.sleep(2000)
            }
            return programStateAccount()!!
        }

        fun waitForTokenStateAccount(): ArchAccountEntity {
            if (tokenStateAccount()?.status != ArchAccountStatus.Complete) {
                waitFor(60000) {
                    triggerRepeaterTaskAndWaitForCompletion("arch_token_state_setup")
                    transaction {
                        tokenStateAccount()?.status == ArchAccountStatus.Complete
                    }
                }
                Thread.sleep(2000)
            }
            return tokenStateAccount()!!
        }

        private fun programAccount() = transaction { ArchAccountEntity.findProgramAccount() }

        private fun programStateAccount() = transaction { ArchAccountEntity.findProgramStateAccount() }

        private fun tokenStateAccount() = transaction {
            ArchAccountEntity.findTokenAccountForSymbol(
                SymbolEntity.forChain(BitcoinClient.chainId).first(),
            )
        }
    }

    @Test
    fun testOnboarding() {
        Assumptions.assumeFalse(true)
        Assumptions.assumeFalse(isTestEnvRun())

        // wait for the contract ot be deployed and verify program account
        val programEntity = waitForProgramAccount()
        // read the account and verify the account is executable
        val programInfo = ArchNetworkClient.readAccountInfo(programEntity.rpcPubkey())
        assertTrue(programInfo.isExecutable)
        assertTrue(javaClass.getResource("/exchangeprogram.so")!!.readBytes().contentEquals(programInfo.data.toByteArray()))
        assertEquals(programEntity.status, ArchAccountStatus.Complete)

        // wait for the program state account to be setup and verify owned by program and initial state
        val programStateEntity = waitForProgramStateAccount()
        val bitcoinContract = transaction {
            DeployedSmartContractEntity.validContracts(BitcoinClient.chainId).first()
        }
        val programStateInfo = ArchNetworkClient.readAccountInfo(programStateEntity.rpcPubkey())
        assertFalse(programStateInfo.isExecutable)
        assertEquals(programStateInfo.owner.bytes.toHex(false), programEntity.publicKey)
        val programState = Borsh.decodeFromByteArray<ArchAccountState.Program>(programStateInfo.data.toByteArray())
        assertEquals(0, programState.version)
        assertEquals(BitcoinClient.bitcoinConfig.feeCollectionAddress, programState.feeAccount)
        assertEquals(bitcoinContract.proxyAddress, programState.programChangeAddress)
        assertEquals(BitcoinNetworkType.Regtest, programState.networkType)
        assertEquals(ContractType.Exchange.name, bitcoinContract.name)
        assertTrue(bitcoinContract.proxyAddress is BitcoinAddress.Taproot)
        assertTrue((bitcoinContract.proxyAddress as BitcoinAddress.Taproot).testnet)

        // trigger repeater app task which will create token state accounts as needed
        triggerRepeaterTaskAndWaitForCompletion("arch_token_state_setup")
        val tokenStateEntity = waitForTokenStateAccount()
        val tokenStateInfo = ArchNetworkClient.readAccountInfo(tokenStateEntity.rpcPubkey())
        assertFalse(tokenStateInfo.isExecutable)
        assertEquals(tokenStateInfo.owner.bytes.toHex(false), programEntity.publicKey)
        val tokenState = Borsh.decodeFromByteArray<ArchAccountState.Token>(tokenStateInfo.data.toByteArray())
        assertEquals(0, tokenState.version)
        assertEquals("BTC:0", tokenState.tokenId)
        assertEquals(programStateEntity.rpcPubkey().toString(), tokenState.programStateAccount.toString())
        assertEquals(1, tokenState.balances.size)
        assertEquals(ArchAccountState.Balance(BitcoinClient.bitcoinConfig.feeCollectionAddress.value, 0u), tokenState.balances.first())
    }
}
