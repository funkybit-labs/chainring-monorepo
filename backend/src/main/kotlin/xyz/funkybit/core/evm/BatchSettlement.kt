package xyz.funkybit.core.evm

import org.web3j.abi.Utils
import org.web3j.abi.datatypes.Address
import org.web3j.abi.datatypes.DynamicArray
import org.web3j.abi.datatypes.DynamicStruct
import org.web3j.abi.datatypes.StaticStruct
import org.web3j.abi.datatypes.generated.Bytes32
import org.web3j.abi.datatypes.generated.Uint16
import org.web3j.abi.datatypes.generated.Uint256
import java.math.BigInteger

class Adjustment : StaticStruct {
    var walletIndex: BigInteger
    var amount: BigInteger

    constructor(walletIndex: BigInteger, amount: BigInteger) : super(
        Uint16(walletIndex),
        Uint256(amount),
    ) {
        this.walletIndex = walletIndex
        this.amount = amount
    }

    override fun toString(): String {
        return "Adjustment(walletIndex=$walletIndex, amount=$amount)"
    }
}

class TokenAdjustmentList : DynamicStruct {
    var token: String
    var increments: List<Adjustment>
    var decrements: List<Adjustment>
    var feeAmount: BigInteger

    constructor(
        token: String,
        increments: List<Adjustment>,
        decrements: List<Adjustment>,
        feeAmount: BigInteger,
    ) : super(
        Address(160, token),
        DynamicArray<Adjustment>(Adjustment::class.java, increments),
        DynamicArray<Adjustment>(Adjustment::class.java, decrements),
        Uint256(feeAmount),
    ) {
        this.token = token
        this.increments = increments
        this.decrements = decrements
        this.feeAmount = feeAmount
    }

    override fun toString(): String {
        return "TokenAdjustmentList(token='$token', increments=$increments, decrements=$decrements, feeAmount=$feeAmount)"
    }
}

class WalletTradeList : DynamicStruct {
    var tradeHashes: List<ByteArray>

    constructor(tradeHashes: List<ByteArray>) : super(
        DynamicArray<Bytes32>(
            Bytes32::class.java,
            Utils.typeMap<ByteArray, Bytes32>(tradeHashes, Bytes32::class.java),
        ),
    ) {
        this.tradeHashes = tradeHashes
    }

    override fun toString(): String {
        return "WalletTradeList(tradeHashes=$tradeHashes)"
    }
}

class BatchSettlement : DynamicStruct {
    var walletAddresses: List<String>
    var walletTradeLists: List<WalletTradeList>
    var tokenAdjustmentLists: List<TokenAdjustmentList>

    constructor(
        walletAddresses: List<String>,
        walletTradeLists: List<WalletTradeList>,
        tokenAdjustmentLists: List<TokenAdjustmentList>,
    ) : super(
        DynamicArray<Address>(
            Address::class.java,
            Utils.typeMap<String, Address>(walletAddresses, Address::class.java),
        ),
        DynamicArray<WalletTradeList>(WalletTradeList::class.java, walletTradeLists),
        DynamicArray<TokenAdjustmentList>(TokenAdjustmentList::class.java, tokenAdjustmentLists),
    ) {
        this.walletAddresses = walletAddresses
        this.walletTradeLists = walletTradeLists
        this.tokenAdjustmentLists = tokenAdjustmentLists
    }

    override fun toString(): String {
        return "BatchSettlement(walletAddresses=$walletAddresses, walletTradeLists=$walletTradeLists, tokenAdjustmentLists=$tokenAdjustmentLists)"
    }
}
