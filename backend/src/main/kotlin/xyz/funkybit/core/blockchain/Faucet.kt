package xyz.funkybit.core.blockchain

import io.github.oshai.kotlinlogging.KotlinLogging
import xyz.funkybit.core.blockchain.evm.EvmChainManager
import xyz.funkybit.core.model.Address
import xyz.funkybit.core.model.BitcoinAddress
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.SymbolEntity
import java.math.BigDecimal
import java.math.BigInteger

object Faucet {
    val logger = KotlinLogging.logger {}

    fun transfer(symbol: SymbolEntity, address: Address): Pair<BigInteger, TxHash> {
        val amount = BigDecimal("1")
        logger.debug { "Sending $amount ${symbol.name} to $address" }

        val amountInFundamentalUnits = amount.movePointRight(symbol.decimals.toInt()).toBigInteger()
        val txHash = when (val tokenContractAddress = symbol.contractAddress) {
            null -> {
                EvmChainManager
                    .getFaucetEvmClient(symbol.chainId.value)!!
                    .sendNativeDepositTx(address, amountInFundamentalUnits)
            }

            is EvmAddress -> {
                EvmChainManager
                    .getFaucetEvmClient(symbol.chainId.value)!!
                    .sendMintERC20Tx(
                        tokenContractAddress,
                        address as EvmAddress,
                        amountInFundamentalUnits,
                    )
            }

            is BitcoinAddress -> TODO()
        }

        return amountInFundamentalUnits to txHash
    }
}
