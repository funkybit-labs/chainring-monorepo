package co.chainring.core.evm

import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.StaticStruct
import org.web3j.abi.datatypes.generated.Int256
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint64
import java.math.BigInteger

sealed class ExchangeTransactions {

    enum class TransactionType {
        Withdraw,
        WithdrawNative,
        SettleTrade,
    }

    class Withdraw(sequence: Long, sender: String, token: String, amount: BigInteger, nonce: BigInteger) : StaticStruct(
        Uint256(sequence),
        Address(160, sender),
        Address(160, token),
        Uint256(amount),
        Uint64(nonce),
    )

    class WithdrawNative(sequence: Long, sender: String, amount: BigInteger, nonce: BigInteger) : StaticStruct(
        Uint256(sequence),
        Address(160, sender),
        Uint256(amount),
        Uint64(nonce),
    )

    class WithdrawNativeWithSignature(tx: WithdrawNative, signature: ByteArray) : DynamicStruct(
        tx,
        DynamicBytes(signature),
    )

    class WithdrawWithSignature(tx: Withdraw, signature: ByteArray) : DynamicStruct(
        tx,
        DynamicBytes(signature),
    )

    class Order(sender: String, amount: BigInteger, price: BigInteger, nonce: BigInteger) : StaticStruct(
        Address(160, sender),
        Int256(amount),
        Uint256(price),
        Uint256(nonce),
    )

    class OrderWithSignature(tx: Order, signature: ByteArray) : DynamicStruct(
        tx,
        DynamicBytes(signature),
    )

    class SettleTrade(
        sequence: Long,
        baseToken: String,
        quoteToken: String,
        amount: BigInteger,
        price: BigInteger,
        takerFee: BigInteger,
        makerFee: BigInteger,
        takerOrder: OrderWithSignature,
        makerOrder: OrderWithSignature,
    ) : DynamicStruct(
        Uint256(sequence),
        Address(160, baseToken),
        Address(160, quoteToken),
        Int256(amount),
        Uint256(price),
        Uint256(takerFee),
        Uint256(makerFee),
        takerOrder,
        makerOrder,
    )
}
