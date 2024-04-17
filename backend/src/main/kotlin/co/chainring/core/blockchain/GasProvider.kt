package co.chainring.core.blockchain

import io.github.oshai.kotlinlogging.KotlinLogging
import org.web3j.protocol.Web3j
import org.web3j.protocol.core.DefaultBlockParameterName
import org.web3j.tx.Contract
import org.web3j.tx.gas.ContractEIP1559GasProvider
import java.math.BigInteger

open class GasProvider(
    private val contractCreationLimit: BigInteger,
    private val contractInvocationLimit: BigInteger,
    private val defaultMaxPriorityFeePerGas: BigInteger,
    private val chainId: Long,
    val web3j: Web3j,
) : ContractEIP1559GasProvider {

    val logger = KotlinLogging.logger {}
    override fun getGasLimit(contractFunc: String?): BigInteger {
        return when (contractFunc) {
            Contract.FUNC_DEPLOY -> contractCreationLimit
            else -> contractInvocationLimit
        }
    }
    override fun getGasPrice(contractFunc: String?): BigInteger {
        return BigInteger.ZERO
    }
    override fun isEIP1559Enabled() = true
    override fun getChainId() = chainId

    override fun getMaxFeePerGas(contractFunc: String?): BigInteger {
        return getMaxFeePerGas(getMaxPriorityFeePerGas(""))
    }

    private fun getMaxFeePerGas(maxPriorityFeePerGas: BigInteger): BigInteger {
        val baseFeePerGas = web3j.ethGetBlockByNumber(DefaultBlockParameterName.PENDING, false).send().block.baseFeePerGas
        return baseFeePerGas.multiply(BigInteger.TWO).add(maxPriorityFeePerGas)
    }

    // TODO : In the past we periodically retrieved this value from alchemy and persisted it
    override fun getMaxPriorityFeePerGas(contractFunc: String?): BigInteger = defaultMaxPriorityFeePerGas

    override fun getGasLimit(): BigInteger = contractCreationLimit
    override fun getGasPrice(): BigInteger = getGasPrice("")
}
