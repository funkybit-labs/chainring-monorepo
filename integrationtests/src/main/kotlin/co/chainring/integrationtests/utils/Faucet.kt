package co.chainring.integrationtests.utils

import co.chainring.core.blockchain.ChainManager
import co.chainring.core.model.Address
import co.chainring.core.model.db.ChainId
import co.chainring.core.utils.toFundamentalUnits
import org.web3j.protocol.core.methods.response.TransactionReceipt
import java.math.BigDecimal
import java.math.BigInteger

object Faucet {
    private val blockchainClients = ChainManager.blockchainConfigs.map {
        TestBlockchainClient(it)
    }

    private val blockchainClientsByChainId = blockchainClients.associateBy { it.chainId }

    fun fundAndMine(address: Address, amount: BigInteger? = null, chainId: ChainId? = null): TransactionReceipt {
        return blockchainClient(chainId).let { client ->
            val txHash = client.sendNativeDepositTx(address, amount ?: BigDecimal("0.05").toFundamentalUnits(18))
            client.mine()
            client.getTransactionReceipt(txHash)!!
        }
    }

    fun fundAndWaitForTxReceipt(address: Address, amount: BigInteger? = null, chainId: ChainId? = null): TransactionReceipt {
        return blockchainClient(chainId).let { client ->
            val txHash = client.sendNativeDepositTx(address, amount ?: BigDecimal("0.05").toFundamentalUnits(18))
            client.waitForTransactionReceipt(txHash)
        }
    }

    fun mine(numberOfBlocks: Int = 1, chainId: ChainId? = null) {
        if (chainId != null) {
            blockchainClient(chainId).mine(numberOfBlocks)
        } else {
            blockchainClients.forEach { it.mine(numberOfBlocks) }
        }
    }

    fun blockchainClient(chainId: ChainId?) =
        chainId?.let { blockchainClientsByChainId.getValue(chainId) } ?: blockchainClients.first()
}
