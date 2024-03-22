package co.chainring.integrationtests.testutils

import co.chainring.contracts.generated.MockERC20
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.ERC20TokenEntity
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.Transfer
import org.web3j.utils.Convert
import java.math.BigInteger

class TestBlockchainClient(val config: BlockchainClientConfig = BlockchainClientConfig()) : BlockchainClient(config) {

    fun deployERC20Mock(symbol: String, name: String): MockERC20 {
        val contract = MockERC20.deploy(web3j, transactionManager, gasProvider, "USDC", "USDC").send()
        ERC20TokenEntity.create(Symbol("USDC"), name, chainId, Address(contract.contractAddress), 18.toUByte())
        return contract
    }

    fun loadERC20Mock(address: String) = MockERC20.load(address, web3j, transactionManager, gasProvider)

    fun getNativeBalance(address: Address) = web3j.ethGetBalance(address.value, DefaultBlockParameter.valueOf("latest")).send().balance

    fun depositNative(address: Address, amount: BigInteger): TransactionReceipt {
        return Transfer(web3j, transactionManager).sendFunds(
            address.value,
            Convert.toWei(amount.toString(10), Convert.Unit.WEI),
            Convert.Unit.WEI,
            web3j.ethGasPrice().send().gasPrice,
            config.contractCreationLimit,
        ).send()
    }
}
