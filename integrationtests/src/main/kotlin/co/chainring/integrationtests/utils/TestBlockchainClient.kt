package co.chainring.integrationtests.utils

import co.chainring.contracts.generated.MockERC20
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.utils.toHex
import org.web3j.crypto.Sign
import org.web3j.protocol.core.DefaultBlockParameter
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.core.methods.response.VoidResponse
import org.web3j.tx.Transfer
import org.web3j.utils.Convert
import java.math.BigInteger

class TestBlockchainClient(val config: BlockchainClientConfig = BlockchainClientConfig()) : BlockchainClient(config) {
    fun loadERC20Mock(address: String) = MockERC20.load(address, web3j, transactionManager, gasProvider)

    fun getNativeBalance(address: Address) = web3j.ethGetBalance(address.value, DefaultBlockParameter.valueOf("latest")).send().balance

    fun depositNative(address: Address, amount: BigInteger): TransactionReceipt {
        return Transfer(web3j, transactionManager).sendFunds(
            address.value,
            Convert.toWei(amount.toString(10), Convert.Unit.WEI),
            Convert.Unit.WEI,
            web3j.ethGasPrice().send().gasPrice,
            config.contractCreationLimit,
        ).sendAndWaitForConfirmation()
    }

    fun signData(hash: ByteArray): EvmSignature {
        val signature = Sign.signMessage(hash, credentials.ecKeyPair, false)
        return EvmSignature((signature.r + signature.s + signature.v).toHex())
    }

    fun mine(numberOfBlocks: Int = 1) {
        Request(
            "anvil_mine",
            listOf(numberOfBlocks),
            web3jService,
            VoidResponse::class.java,
        ).send()
    }

    fun setIntervalMining(interval: Int = 1): VoidResponse = Request(
        "evm_setIntervalMining",
        listOf(interval),
        web3jService,
        VoidResponse::class.java,
    ).send()

    fun setAutoMining(value: Boolean): VoidResponse = Request(
        "evm_setAutomine",
        listOf(value),
        web3jService,
        VoidResponse::class.java,
    ).send()
}
