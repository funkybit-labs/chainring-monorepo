package co.chainring.integrationtests.testutils

import co.chainring.contracts.generated.ERC20Mock
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.model.Address
import co.chainring.core.model.Symbol
import co.chainring.core.model.db.Chain
import co.chainring.core.model.db.ERC20TokenEntity
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.Transfer
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger

class TestBlockchainClient(private val config: BlockchainClientConfig = BlockchainClientConfig()) : BlockchainClient(config) {

    fun deployERC20Mock(symbol: String, name: String): ERC20Mock {
        val contract = ERC20Mock.deploy(web3j, transactionManager, gasProvider).send()
        ERC20TokenEntity.create(Symbol("USDC"), name, Chain.Ethereum, Address(contract.contractAddress), 18.toUByte())
        return contract
    }

    fun loadERC20Mock(address: String) = ERC20Mock.load(address, web3j, transactionManager, gasProvider)

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

fun BigDecimal.toFundamentalUnits(decimals: Int): BigInteger {
    return (this * BigDecimal("1e$decimals")).toBigInteger()
}
