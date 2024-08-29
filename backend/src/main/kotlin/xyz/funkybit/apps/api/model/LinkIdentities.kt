package xyz.funkybit.apps.api.model

import kotlinx.serialization.Serializable
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.BitcoinSignature
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.db.ChainId

@Serializable
data class BitcoinLinkAddressProof(
    val message: String,
    val address: BitcoinAddress,
    val linkAddress: EvmAddress,
    val timestamp: String,
    val signature: BitcoinSignature,
)

@Serializable
data class EvmLinkAddressProof(
    val message: String,
    val chainId: ChainId,
    val address: EvmAddress,
    val linkAddress: BitcoinAddress,
    val timestamp: String,
    val signature: EvmSignature,
)

@Serializable
data class LinkIdentityApiRequest(
    val bitcoinLinkAddressProof: BitcoinLinkAddressProof,
    val evmLinkAddressProof: EvmLinkAddressProof,
)
