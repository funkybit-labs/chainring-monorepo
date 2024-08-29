package xyz.funkybit.integrationtests.api

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.web3j.crypto.Credentials
import org.web3j.crypto.ECKeyPair
import xyz.funkybit.apps.api.model.BitcoinLinkAddressProof
import xyz.funkybit.apps.api.model.EvmLinkAddressProof
import xyz.funkybit.apps.api.model.LinkIdentityApiRequest
import xyz.funkybit.core.evm.ECHelper
import xyz.funkybit.core.evm.EIP712Helper
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.BitcoinSignature
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.WalletKeyPair
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@ExtendWith(AppUnderTestRunner::class)
class IdentityApiTest {

    @Test
    fun `evm wallet link bitcoin wallet`() {
        val evmWalletKeyPair = WalletKeyPair.EVM.generate()
        val evmKeyApiClient = TestApiClient(evmWalletKeyPair)
        val evmAddress = evmKeyApiClient.address as EvmAddress
        assertTrue { evmKeyApiClient.getAccountConfiguration().linkedAddresses.isEmpty() }

        val bitcoinKey = ECKey()
        val bitcoinAddress = BitcoinAddress.fromKey(NetworkParameters.fromID("org.bitcoinj.unittest")!!, bitcoinKey)

        evmKeyApiClient.linkIdentity(
            LinkIdentityApiRequest(
                bitcoinLinkAddressProof = signBitcoinWalletLinkProof(
                    ecKey = bitcoinKey,
                    address = bitcoinAddress,
                    linkAddress = evmAddress,
                ),
                evmLinkAddressProof = signEvmWalletLinkProof(
                    ecKeyPair = evmWalletKeyPair.ecKeyPair,
                    address = evmAddress,
                    linkAddress = bitcoinAddress,
                ),
            ),
        )

        assertEquals(listOf(bitcoinAddress), evmKeyApiClient.getAccountConfiguration().linkedAddresses)
    }

    @Test
    fun `error cases`() {
        // invalid signatures, timestamp, addresses
        // link address in already in use
    }

    private fun signBitcoinWalletLinkProof(
        ecKey: ECKey,
        address: BitcoinAddress,
        linkAddress: EvmAddress,
        timestamp: Instant = Clock.System.now(),
    ): BitcoinLinkAddressProof {
        val message = "[funkybit] Please sign this message to link your wallets. This action will not cost any gas fees."
        val bitcoinLinkAddressMessage = "$message\nAddress: ${address.value}, LinkAddress: ${linkAddress.value}, Timestamp: $timestamp"
        val signature = BitcoinSignature(ecKey.signMessage(bitcoinLinkAddressMessage))

        return BitcoinLinkAddressProof(
            message = message,
            address = address,
            linkAddress = linkAddress,
            timestamp = timestamp.toString(),
            signature = signature,
        )
    }

    private fun signEvmWalletLinkProof(
        ecKeyPair: ECKeyPair,
        address: EvmAddress,
        linkAddress: BitcoinAddress,
        chainId: ChainId = ChainId(1337U),
        timestamp: Instant = Clock.System.now(),
    ): EvmLinkAddressProof {
        val evmLinkAddressProof = EvmLinkAddressProof(
            message = "[funkybit] Please sign this message to link your wallets. This action will not cost any gas fees.",
            address = address,
            linkAddress = linkAddress,
            chainId = chainId,
            timestamp = timestamp.toString(),
            signature = EvmSignature.emptySignature(),
        )

        val signature: EvmSignature = ECHelper.signData(Credentials.create(ecKeyPair), EIP712Helper.computeHash(evmLinkAddressProof))

        return evmLinkAddressProof.copy(signature = signature)
    }
}
