package xyz.funkybit.core.blockchain.bitcoin

import org.bitcoinj.core.ECKey
import org.bitcoinj.core.NetworkParameters
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.rpc.ArchNetworkRpc
import xyz.funkybit.core.utils.toHexBytes
import java.math.BigInteger

data class BitcoinConfig(
    val enabled: Boolean,
    val net: String,
    val feeSettings: FeeEstimationSettings,
    val blockExplorerNetName: String,
    val blockExplorerUrl: String,
    val faucetAddress: BitcoinAddress?,
    val submitterPrivateKey: ByteArray,
    val feePayerPrivateKey: ByteArray,
    val changeDustThreshold: BigInteger,
    val feeCollectionAddress: BitcoinAddress,
) {
    enum class SmartFeeMode {
        ECONOMICAL,
        CONSERVATIVE,
    }

    data class FeeEstimationSettings(
        val blocks: Int,
        val mode: SmartFeeMode,
        val minValue: Int,
        val maxValue: Int,
    )

    val params: NetworkParameters = NetworkParameters.fromID(net)!!
    val submitterEcKey: ECKey = ECKey.fromPrivate(submitterPrivateKey)
    val submitterPubkey = ArchNetworkRpc.Pubkey.fromECKey(ECKey.fromPrivate(submitterPrivateKey))
    val feePayerEcKey: ECKey = ECKey.fromPrivate(feePayerPrivateKey)
    val feePayerAddress = BitcoinAddress.fromKey(params, feePayerEcKey)

    val chainId = ChainId(0u)
}

val bitcoinConfig = BitcoinConfig(
    enabled = (System.getenv("BITCOIN_NETWORK_ENABLED") ?: "true").toBoolean(),
    net = System.getenv("BITCOIN_NETWORK_NAME") ?: "org.bitcoin.regtest",
    feeSettings = BitcoinConfig.FeeEstimationSettings(
        blocks = 1,
        mode = BitcoinConfig.SmartFeeMode.CONSERVATIVE,
        minValue = 5,
        maxValue = 50,
    ),
    blockExplorerNetName = System.getenv("BLOCK_EXPLORER_NET_NAME_BITCOIN") ?: "Bitcoin Network",
    blockExplorerUrl = System.getenv("BLOCK_EXPLORER_URL_BITCOIN") ?: "http://localhost:1080",
    faucetAddress = BitcoinAddress.canonicalize("bcrt1q3nyukkpkg6yj0y5tj6nj80dh67m30p963mzxy7"),
    submitterPrivateKey = (System.getenv("BITCOIN_SUBMITTER_PRIVATE_KEY") ?: "0x7ebc626d01c2d916c61dffee4ed2501f579009ad362360d82fcc30e3d8746cec").toHexBytes(),
    feePayerPrivateKey = (System.getenv("BITCOIN_FEE_PAYER_PRIVATE_KEY") ?: "cec2c679ef0d11820c75b17174a8635d7aa1ce740b0bf32d80967c3bf7b3676d").toHexBytes(),
    changeDustThreshold = BigInteger(System.getenv("BITCOIN_CHANGE_DUST_THRESHOLD") ?: "300"),
    feeCollectionAddress = BitcoinAddress.canonicalize(System.getenv("BITCOIN_FEE_ACCOUNT_ADDRESS") ?: "bcrt1q5jdpyt2x5utsqgaazdf6jr4cc7yeaec0me0e4u"),
    /* privKey for bcrt1q5jdpyt2x5utsqgaazdf6jr4cc7yeaec0me0e4u = 0x63fe5172a2b186b8015fb60d9c314eb9017a8465ed169d5e5ff41ed8fa8d9a4a */
)
