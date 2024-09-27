package xyz.funkybit.integrationtests.utils

import org.web3j.protocol.core.methods.response.TransactionReceipt
import xyz.funkybit.apps.ring.EvmDepositHandler
import xyz.funkybit.core.blockchain.evm.EvmChainManager
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.utils.toFundamentalUnits
import java.math.BigDecimal
import java.math.BigInteger

object Faucet {
    private val evmClients = EvmChainManager.evmClientConfigs.map {
        TestEvmClient(it)
    }

    private val evmClientsByChainId = evmClients.associateBy { it.chainId }

    fun fundAndMine(address: EvmAddress, amount: BigInteger? = null, chainId: ChainId? = null): TransactionReceipt {
        return evmClient(chainId).let { client ->
            val txHash = client.sendNativeDepositTx(address, amount ?: BigDecimal("0.05").toFundamentalUnits(18))
            repeat(EvmDepositHandler.DEFAULT_NUM_CONFIRMATIONS) {
                client.mine()
            }
            client.getTransactionReceipt(txHash)!!
        }
    }

    fun fundAndWaitForTxReceipt(address: EvmAddress, amount: BigInteger? = null, chainId: ChainId? = null): TransactionReceipt {
        return evmClient(chainId).let { client ->
            val txHash = client.sendNativeDepositTx(address, amount ?: BigDecimal("0.05").toFundamentalUnits(18))
            client.waitForTransactionReceipt(txHash)
        }
    }

    fun mine(numberOfBlocks: Int = 1, chainId: ChainId? = null) {
        if (chainId != null) {
            evmClient(chainId).mine(numberOfBlocks)
        } else {
            evmClients.forEach { it.mine(numberOfBlocks) }
        }
    }

    fun evmClient(chainId: ChainId?) =
        chainId?.let { evmClientsByChainId.getValue(chainId) } ?: evmClients.first()
}
