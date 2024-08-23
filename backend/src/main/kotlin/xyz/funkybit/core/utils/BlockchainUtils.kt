package xyz.funkybit.core.utils

import org.web3j.tx.exceptions.ContractCallException
import xyz.funkybit.core.blockchain.DefaultBlockParam

object BlockchainUtils {
    fun <A> getAsOfBlockOrLater(blockNumber: DefaultBlockParam.BlockNumber, query: (blockParam: DefaultBlockParam) -> A): A {
        try {
            return query(blockNumber)
        } catch (e: ContractCallException) {
            e.message?.let {
                Regex(
                    ".*Contract Call has been reverted by the EVM with the reason: 'BlockOutOfRangeError: block height is (\\d+) but requested was (\\d+)'.*",
                ).matchEntire(it)?.let { matchResult ->
                    if (matchResult.groups.size == 3) {
                        val blockHeight = matchResult.groups[1]!!.value.toBigInteger()
                        val requestedHeight = matchResult.groups[2]!!.value.toBigInteger()
                        if (blockHeight > requestedHeight) {
                            return getAsOfBlockOrLater(DefaultBlockParam.BlockNumber(blockHeight), query)
                        }
                    }
                }
            }
            throw(e)
        }
    }
}
