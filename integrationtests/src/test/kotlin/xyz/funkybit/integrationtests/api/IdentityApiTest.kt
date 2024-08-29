package xyz.funkybit.integrationtests.api

import kotlinx.datetime.Clock
import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.LinkIdentityApiRequest
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.WalletKeyPair
import xyz.funkybit.integrationtests.utils.assertError
import xyz.funkybit.integrationtests.utils.signBitcoinWalletLinkProof
import xyz.funkybit.integrationtests.utils.signEvmWalletLinkProof
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

@ExtendWith(AppUnderTestRunner::class)
class IdentityApiTest {

    @Test
    fun `evm wallet link bitcoin wallet`() {
        val evmWalletKeyPair = WalletKeyPair.EVM.generate()
        val evmKeyApiClient = TestApiClient(evmWalletKeyPair)
        val evmAddress = evmKeyApiClient.address as EvmAddress
        assertTrue { evmKeyApiClient.getAccountConfiguration().linkedAddresses.isEmpty() }

        val bitcoinKey = ECKey()
        val bitcoinKeyApiClient = TestApiClient(WalletKeyPair.Bitcoin(bitcoinKey), chainId = ChainId(0u))
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

        assertEquals(listOf(evmAddress), bitcoinKeyApiClient.getAccountConfiguration().linkedAddresses)
        assertEquals(listOf(bitcoinAddress), evmKeyApiClient.getAccountConfiguration().linkedAddresses)
    }

    @Test
    fun `bitcoin wallet link evm wallet`() {
        val bitcoinKey = ECKey()
        val bitcoinKeyApiClient = TestApiClient(WalletKeyPair.Bitcoin(bitcoinKey), chainId = ChainId(0u))
        val bitcoinAddress = BitcoinAddress.fromKey(NetworkParameters.fromID(NetworkParameters.ID_REGTEST)!!, bitcoinKey)
        assertTrue { bitcoinKeyApiClient.getAccountConfiguration().linkedAddresses.isEmpty() }

        val evmWalletKeyPair = WalletKeyPair.EVM.generate()
        val evmKeyApiClient = TestApiClient(evmWalletKeyPair)
        val evmAddress = evmKeyApiClient.address as EvmAddress

        bitcoinKeyApiClient.linkIdentity(
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

        assertEquals(listOf(evmAddress), bitcoinKeyApiClient.getAccountConfiguration().linkedAddresses)
        assertEquals(listOf(bitcoinAddress), evmKeyApiClient.getAccountConfiguration().linkedAddresses)
    }

    @Test
    fun `already linked address can't be re-linked`() {
        val evmWalletKeyPair = WalletKeyPair.EVM.generate()
        val evmKeyApiClient = TestApiClient(evmWalletKeyPair)
        val evmAddress = evmKeyApiClient.address as EvmAddress
        assertTrue { evmKeyApiClient.getAccountConfiguration().linkedAddresses.isEmpty() }

        val bitcoinKey = ECKey()
        val bitcoinKeyApiClient = TestApiClient(WalletKeyPair.Bitcoin(bitcoinKey), chainId = ChainId(0u))
        val bitcoinAddress = BitcoinAddress.fromKey(NetworkParameters.fromID(NetworkParameters.ID_REGTEST)!!, bitcoinKey)
        assertTrue { bitcoinKeyApiClient.getAccountConfiguration().linkedAddresses.isEmpty() }

        // note: each wallet has been linked to a new user by sending an api request
        bitcoinKeyApiClient.tryLinkIdentity(
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
        ).assertError(ApiError(ReasonCode.LinkIdentityError, "Link address is already in use"))
    }

    @Test
    fun `link proof validation error cases`() {
        val evmWalletKeyPair = WalletKeyPair.EVM.generate()
        val evmKeyApiClient = TestApiClient(evmWalletKeyPair)
        val evmAddress = evmKeyApiClient.address as EvmAddress
        assertTrue { evmKeyApiClient.getAccountConfiguration().linkedAddresses.isEmpty() }

        val bitcoinKey = ECKey()
        val bitcoinKeyApiClient = TestApiClient(WalletKeyPair.Bitcoin(bitcoinKey), chainId = ChainId(0u))
        val bitcoinAddress = BitcoinAddress.fromKey(NetworkParameters.fromID(NetworkParameters.ID_REGTEST)!!, bitcoinKey)

        // TODO uncomment once bitcoin recovered address check is fixed
//        // signature should be valid
//        evmKeyApiClient.tryLinkIdentity(
//            LinkIdentityApiRequest(
//                bitcoinLinkAddressProof = signBitcoinWalletLinkProof(
//                    ecKey = bitcoinKey,
//                    address = bitcoinAddress,
//                    linkAddress = evmAddress,
//                ).copy(signature = BitcoinSignature("H7P1/r+gULX05tXwaJGfglZSL4sRhykAsgwQtpm92xRIPaGUnxQAhm1CZsTuQ8wh3w51f1uUVpxU2RUfJ3hq81I=")),
//                evmLinkAddressProof = signEvmWalletLinkProof(
//                    ecKeyPair = evmWalletKeyPair.ecKeyPair,
//                    address = evmAddress,
//                    linkAddress = bitcoinAddress,
//                ),
//            ),
//        ).assertError(ApiError(ReasonCode.LinkIdentityError, "Signature can't be verified"))
        evmKeyApiClient.tryLinkIdentity(
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
                ).copy(signature = EvmSignature("0x1cd66f580ec8f6fd37b2101849955c5a50d787092fe8a97e5c55bea6a24c1d47409e17450adaa617cc07907f56a4eb1f468467fcefe7808cbd63fbba935ee0201b")),
            ),
        ).assertError(ApiError(ReasonCode.LinkIdentityError, "Signature can't be verified"))

        // link proof timestamp should be recent
        bitcoinKeyApiClient.tryLinkIdentity(
            LinkIdentityApiRequest(
                bitcoinLinkAddressProof = signBitcoinWalletLinkProof(
                    ecKey = bitcoinKey,
                    address = bitcoinAddress,
                    linkAddress = evmAddress,
                    timestamp = Clock.System.now() + 11.seconds,
                ),
                evmLinkAddressProof = signEvmWalletLinkProof(
                    ecKeyPair = evmWalletKeyPair.ecKeyPair,
                    address = evmAddress,
                    linkAddress = bitcoinAddress,
                ),
            ),
        ).assertError(ApiError(ReasonCode.LinkIdentityError, "Link proof has expired or not valid yet"))
        bitcoinKeyApiClient.tryLinkIdentity(
            LinkIdentityApiRequest(
                bitcoinLinkAddressProof = signBitcoinWalletLinkProof(
                    ecKey = bitcoinKey,
                    address = bitcoinAddress,
                    linkAddress = evmAddress,
                    timestamp = Clock.System.now() - 5.minutes - 1.seconds,
                ),
                evmLinkAddressProof = signEvmWalletLinkProof(
                    ecKeyPair = evmWalletKeyPair.ecKeyPair,
                    address = evmAddress,
                    linkAddress = bitcoinAddress,
                ),
            ),
        ).assertError(ApiError(ReasonCode.LinkIdentityError, "Link proof has expired or not valid yet"))
        bitcoinKeyApiClient.tryLinkIdentity(
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
                    timestamp = Clock.System.now() + 11.seconds,
                ),
            ),
        ).assertError(ApiError(ReasonCode.LinkIdentityError, "Link proof has expired or not valid yet"))
        bitcoinKeyApiClient.tryLinkIdentity(
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
                    timestamp = Clock.System.now() - 5.minutes - 1.seconds,
                ),
            ),
        ).assertError(ApiError(ReasonCode.LinkIdentityError, "Link proof has expired or not valid yet"))

        // wallets should link each other
        bitcoinKeyApiClient.tryLinkIdentity(
            LinkIdentityApiRequest(
                bitcoinLinkAddressProof = signBitcoinWalletLinkProof(
                    ecKey = bitcoinKey,
                    address = bitcoinAddress,
                    linkAddress = EvmAddress.generate(),
                    timestamp = Clock.System.now() + 11.seconds,
                ),
                evmLinkAddressProof = signEvmWalletLinkProof(
                    ecKeyPair = evmWalletKeyPair.ecKeyPair,
                    address = evmAddress,
                    linkAddress = bitcoinAddress,
                ),
            ),
        ).assertError(ApiError(ReasonCode.LinkIdentityError, "Invalid identity links"))
        bitcoinKeyApiClient.tryLinkIdentity(
            LinkIdentityApiRequest(
                bitcoinLinkAddressProof = signBitcoinWalletLinkProof(
                    ecKey = bitcoinKey,
                    address = bitcoinAddress,
                    linkAddress = evmAddress,
                    timestamp = Clock.System.now() + 11.seconds,
                ),
                evmLinkAddressProof = signEvmWalletLinkProof(
                    ecKeyPair = evmWalletKeyPair.ecKeyPair,
                    address = evmAddress,
                    linkAddress = BitcoinAddress.fromKey(NetworkParameters.fromID(NetworkParameters.ID_REGTEST)!!, ECKey()),
                ),
            ),
        ).assertError(ApiError(ReasonCode.LinkIdentityError, "Invalid identity links"))
    }
}
