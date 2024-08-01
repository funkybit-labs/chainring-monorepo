package xyz.funkybit.core.services

import org.awaitility.kotlin.await
import org.jetbrains.exposed.sql.transactions.TransactionManager
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.web3j.crypto.Keys
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.testfixtures.DbTestHelpers.createChain
import xyz.funkybit.testutils.TestWithDb
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LinkedSignerServiceTest : TestWithDb() {
    private val chainId1 = ChainId(123UL)
    private val chainId2 = ChainId(456UL)

    @BeforeEach
    fun setup() {
        transaction {
            createChain(chainId1, "test-chain1")
            createChain(chainId2, "test-chain2")
        }
        LinkedSignerService.start(TransactionManager.defaultDatabase!!)
    }

    @AfterEach
    fun shutdown() {
        LinkedSignerService.stop()
    }

    @Test
    fun `handles adding and removing linked signers`() {
        assertNull(LinkedSignerService.getLinkedSigner(Address.zero, chainId1))

        // add a linked signer and verify
        val walletAddress = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(Keys.createEcKeyPair())))
        val linkedSignerAddress = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(Keys.createEcKeyPair())))

        // update the signer on one chain and verify
        sendAndWaitForLinkedSignerUpdate(walletAddress, chainId1, linkedSignerAddress)

        // update on second chain and verify
        sendAndWaitForLinkedSignerUpdate(walletAddress, chainId2, linkedSignerAddress)

        // remove on one chain and verify just removed from that chain
        sendAndWaitForLinkedSignerUpdate(walletAddress, chainId2, Address.zero)
        assertEquals(linkedSignerAddress, LinkedSignerService.getLinkedSigner(walletAddress, chainId1))
        assertNull(LinkedSignerService.getLinkedSigner(walletAddress, chainId2))

        // remove on other and verify
        sendAndWaitForLinkedSignerUpdate(walletAddress, chainId2, Address.zero)
        // idempotent
        sendAndWaitForLinkedSignerUpdate(walletAddress, chainId2, Address.zero)

        // add multiple
        val walletAddress2 = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(Keys.createEcKeyPair())))
        val linkedSignerAddress2 = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(Keys.createEcKeyPair())))
        sendAndWaitForLinkedSignerUpdate(walletAddress, chainId1, linkedSignerAddress)
        sendAndWaitForLinkedSignerUpdate(walletAddress2, chainId1, linkedSignerAddress2)
        sendAndWaitForLinkedSignerUpdate(walletAddress, chainId2, linkedSignerAddress)
        sendAndWaitForLinkedSignerUpdate(walletAddress2, chainId2, linkedSignerAddress2)

        // update one to a different signer
        sendAndWaitForLinkedSignerUpdate(walletAddress, chainId1, linkedSignerAddress2)
    }

    @Test
    fun `updates state from db on a restart`() {
        val walletAddress = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(Keys.createEcKeyPair())))
        val linkedSignerAddress = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(Keys.createEcKeyPair())))
        val walletAddress2 = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(Keys.createEcKeyPair())))
        val linkedSignerAddress2 = Address(Keys.toChecksumAddress("0x" + Keys.getAddress(Keys.createEcKeyPair())))

        sendAndWaitForLinkedSignerUpdate(walletAddress, chainId1, linkedSignerAddress)
        sendAndWaitForLinkedSignerUpdate(walletAddress2, chainId1, linkedSignerAddress2)
        sendAndWaitForLinkedSignerUpdate(walletAddress, chainId2, linkedSignerAddress)
        sendAndWaitForLinkedSignerUpdate(walletAddress2, chainId2, linkedSignerAddress2)

        // stopping clears cache - verify null returned
        LinkedSignerService.stop()
        assertNull(LinkedSignerService.getLinkedSigner(walletAddress, chainId1))
        assertNull(LinkedSignerService.getLinkedSigner(walletAddress, chainId2))
        assertNull(LinkedSignerService.getLinkedSigner(walletAddress2, chainId1))
        assertNull(LinkedSignerService.getLinkedSigner(walletAddress2, chainId2))

        // start and verify the persisted signers are returned
        LinkedSignerService.start(TransactionManager.defaultDatabase!!)
        assertEquals(linkedSignerAddress, LinkedSignerService.getLinkedSigner(walletAddress, chainId1))
        assertEquals(linkedSignerAddress, LinkedSignerService.getLinkedSigner(walletAddress, chainId2))
        assertEquals(linkedSignerAddress2, LinkedSignerService.getLinkedSigner(walletAddress2, chainId1))
        assertEquals(linkedSignerAddress2, LinkedSignerService.getLinkedSigner(walletAddress2, chainId2))
    }

    private fun sendAndWaitForLinkedSignerUpdate(walletAddress: Address, chainId: ChainId, linkedSignerAddress: Address) {
        transaction {
            LinkedSignerService.createOrUpdateWalletLinkedSigner(walletAddress, chainId, linkedSignerAddress)
        }

        await
            .pollInSameThread()
            .pollDelay(Duration.ofMillis(100))
            .pollInterval(Duration.ofMillis(100))
            .atMost(Duration.ofMillis(5000L))
            .until {
                LinkedSignerService.getLinkedSigner(walletAddress, chainId) ==
                    if (linkedSignerAddress == Address.zero) null else linkedSignerAddress
            }
    }
}
