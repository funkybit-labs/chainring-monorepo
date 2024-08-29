package xyz.funkybit.integrationtests.api

import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.LinkIdentityApiRequest
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.WalletKeyPair
import xyz.funkybit.integrationtests.utils.signBitcoinWalletLinkProof
import xyz.funkybit.integrationtests.utils.signEvmWalletLinkProof
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
        val bitcoinAddress = BitcoinAddress.fromKey(NetworkParameters.fromID(NetworkParameters.ID_REGTEST)!!, bitcoinKey)

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
}
