package co.chainring.core.evm

import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicBytes
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.StaticStruct
import org.web3j.abi.datatypes.generated.Uint256
import org.web3j.abi.datatypes.generated.Uint64
import java.math.BigInteger

sealed class ExchangeTransactions {

    enum class TransactionType {
        Withdraw,
        WithdrawAll,
    }

    class Withdraw(sequence: Long, sender: String, token: String, amount: BigInteger, nonce: BigInteger) : StaticStruct(
        Uint256(sequence),
        Address(160, sender),
        Address(160, token),
        Uint256(amount),
        Uint64(nonce),
    )

    class WithdrawWithSignature(tx: Withdraw, signature: ByteArray) : DynamicStruct(
        tx,
        DynamicBytes(signature),
    )
}
