package co.chainring.integrationtests.utils

import co.chainring.contracts.generated.Exchange
import co.chainring.contracts.generated.MockERC20
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.BlockchainClientConfig
import co.chainring.core.blockchain.ContractType
import co.chainring.core.model.Address
import co.chainring.core.model.TxHash
import org.awaitility.kotlin.await
import org.awaitility.kotlin.withAlias
import org.web3j.crypto.Credentials
import org.web3j.protocol.core.Request
import org.web3j.protocol.core.Response
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.protocol.core.methods.response.VoidResponse
import org.web3j.tx.RawTransactionManager
import org.web3j.utils.Numeric
import java.math.BigInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

class TestBlockchainClient(blockchainConfig: BlockchainClientConfig) : BlockchainClient(blockchainConfig) {
    fun loadERC20Mock(address: String) = MockERC20.load(address, web3j, transactionManager, gasProvider)
    fun loadExchange(address: String) = Exchange.load(address, web3j, transactionManager, gasProvider)

    fun waitForTransactionReceipt(txHash: TxHash): TransactionReceipt {
        var receipt: TransactionReceipt? = null

        await
            .withAlias("Waiting for tx $txHash receipt")
            .pollInSameThread()
            .pollDelay(100.milliseconds.toJavaDuration())
            .pollInterval(100.milliseconds.toJavaDuration())
            .atMost(30.seconds.toJavaDuration())
            .until {
                receipt = getTransactionReceipt(txHash)
                receipt != null
            }

        return receipt!!
    }

    fun mine(numberOfBlocks: Int = 1) {
        Request(
            "anvil_mine",
            listOf(numberOfBlocks),
            web3jService,
            VoidResponse::class.java,
        ).send()
    }

    class SnapshotResponse : Response<String>() {
        val id: BigInteger
            get() = Numeric.decodeQuantity(result)
    }

    fun snapshot(): SnapshotResponse =
        Request(
            "evm_snapshot",
            emptyList<String>(),
            web3jService,
            SnapshotResponse::class.java,
        ).send()

    fun revert(snapshotId: BigInteger) {
        Request(
            "evm_revert",
            listOf(snapshotId),
            web3jService,
            VoidResponse::class.java,
        ).send()
    }

    fun dropTransaction(txHash: TxHash) =
        Request(
            "anvil_dropTransaction",
            listOf(txHash.value),
            web3jService,
            VoidResponse::class.java,
        ).send()

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

    fun initiateSovereignWithdrawal(senderCredentials: Credentials, tokenContractAddress: Address, amount: BigInteger): TxHash {
        val exchangeContractAddress = getContractAddress(ContractType.Exchange).value
        val txManager = RawTransactionManager(
            web3j,
            senderCredentials, // custom sender credentials
            chainId.value.toLong(),
            receiptProcessor,
        )

        val sovereignWithdrawalFunction = loadExchange(exchangeContractAddress)
            .initiateSovereignWithdrawal(tokenContractAddress.value, amount)
            .encodeFunctionCall()

        return txManager.sendTransaction(
            web3j.ethGasPrice().send().gasPrice,
            gasProvider.gasLimit,
            exchangeContractAddress,
            sovereignWithdrawalFunction,
            BigInteger.ZERO,
        )
            .transactionHash
            .let { TxHash(it) }
            .also { logger.debug { "Sovereign withdrawal requested, txHash: $it" } }
    }
}
