package xyz.funkybit.integrationtests.bitcoin

import kotlinx.serialization.decodeFromByteArray
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.core.blockchain.ContractType
import xyz.funkybit.core.blockchain.bitcoin.ArchNetworkClient
import xyz.funkybit.core.blockchain.bitcoin.bitcoinConfig
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.bitcoin.ArchAccountState
import xyz.funkybit.core.model.bitcoin.BitcoinNetworkType
import xyz.funkybit.core.model.db.ArchAccountEntity
import xyz.funkybit.core.model.db.ArchAccountStatus
import xyz.funkybit.core.model.db.BlockEntity
import xyz.funkybit.core.model.db.BlockTable
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.utils.bitcoin.ExchangeProgramProtocolFormat
import xyz.funkybit.core.utils.toHex
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.testutils.isBitcoinDisabled
import xyz.funkybit.integrationtests.testutils.triggerRepeaterTaskAndWaitForCompletion
import xyz.funkybit.integrationtests.testutils.waitFor
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalUnsignedTypes::class)
@ExtendWith(AppUnderTestRunner::class)
class ArchOnboardingTest {

    companion object {

        fun waitForDeployedContract() {
            if (validContracts().isEmpty()) {
                waitFor(180000) {
                    validContracts().isNotEmpty()
                }
            }
        }

        fun waitForProgramAccount(): ArchAccountEntity {
            if (programAccount()?.status != ArchAccountStatus.Complete) {
                waitFor(180000) {
                    programAccount()?.status == ArchAccountStatus.Complete
                }
            }
            return programAccount()!!
        }

        fun waitForProgramStateAccount(): ArchAccountEntity {
            if (programStateAccount()?.status != ArchAccountStatus.Complete) {
                waitFor(60000) {
                    programStateAccount()?.status == ArchAccountStatus.Complete
                }
            }
            return programStateAccount()!!
        }

        fun waitForTokenStateAccount(): ArchAccountEntity {
            if (tokenStateAccount()?.status != ArchAccountStatus.Complete) {
                waitFor(60000) {
                    triggerRepeaterTaskAndWaitForCompletion("arch_token_state_setup")
                    tokenStateAccount()?.status == ArchAccountStatus.Complete
                }
                Thread.sleep(2000)
            }
            return tokenStateAccount()!!
        }

        private fun programAccount() = transaction { ArchAccountEntity.findProgramAccount() }

        private fun programStateAccount() = transaction { ArchAccountEntity.findProgramStateAccount() }

        private fun tokenStateAccount() = transaction {
            ArchAccountEntity.findTokenAccountsForSymbol(
                SymbolEntity.forChain(bitcoinConfig.chainId).first(),
            ).firstOrNull { it.status == ArchAccountStatus.Complete }
        }

        private fun validContracts() = transaction { DeployedSmartContractEntity.validContracts(bitcoinConfig.chainId) }

        private fun getLastProcessedBlock(): BlockEntity? = transaction {
            BlockTable
                .selectAll()
                .where { BlockTable.chainId.eq(bitcoinConfig.chainId) }
                .orderBy(Pair(BlockTable.number, SortOrder.DESC))
                .limit(1)
                .map { BlockEntity.wrapRow(it) }
                .firstOrNull()
        }
    }

    @Test
    fun testOnboarding() {
        Assumptions.assumeFalse(isBitcoinDisabled())

        waitForDeployedContract()

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
            DeployedSmartContractEntity.validContracts(bitcoinConfig.chainId).first()
        }
        val programStateInfo = ArchNetworkClient.readAccountInfo(programStateEntity.rpcPubkey())
        assertFalse(programStateInfo.isExecutable)
        assertEquals(programStateInfo.owner.bytes.toHex(false), programEntity.publicKey)
        val programState = ExchangeProgramProtocolFormat.decodeFromByteArray<ArchAccountState.Program>(programStateInfo.data.toByteArray())
        assertEquals(0, programState.version)
        assertEquals(bitcoinConfig.feeCollectionAddress, programState.feeAccount)
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
        val tokenState = ExchangeProgramProtocolFormat.decodeFromByteArray<ArchAccountState.Token>(tokenStateInfo.data.toByteArray())
        assertEquals(0, tokenState.version)
        assertEquals("BTC:0", tokenState.tokenId)
        assertEquals(programStateEntity.rpcPubkey().toString(), tokenState.programStateAccount.toString())
        assertTrue(tokenState.balances.isNotEmpty())
        assertEquals(bitcoinConfig.feeCollectionAddress, tokenState.balances.first().walletAddress)
    }
}
