package co.chainring.integrationtests.testutils

import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.model.Address
import co.chainring.core.utils.toFundamentalUnits
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigDecimal
import java.math.BigInteger

object Faucet {
    val blockchainClient = TestBlockchainClient(
        BlockchainClientConfig().copy(privateKeyHex = "0x47e179ec197488593b187f80a00eb0da91f1b9d0b13f8733639f19c30a34926a"),
    )

    fun fund(address: Address, amount: BigInteger? = null): TransactionReceipt {
        return blockchainClient.depositNative(address, amount ?: BigDecimal("0.05").toFundamentalUnits(18))
    }

    fun mine(numberOfBlocks: Int = 1) {
        blockchainClient.mine(numberOfBlocks)
    }
}
