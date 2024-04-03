package co.chainring.sequencer.core

@JvmInline
value class WalletAddress(val value: Long) {
    override fun toString(): String = value.toString()
}

fun Long.toWalletAddress() = WalletAddress(this)

@JvmInline
value class OrderGuid(val value: Long) {
    override fun toString(): String = value.toString()
}

fun Long.toOrderGuid() = OrderGuid(this)

@JvmInline
value class MarketId(val value: String) {
    init {
        require(
            value.contains('/'),
        ) {
            "Invalid market ID"
        }
    }
    fun baseAsset() = value.split('/', limit = 2)[0].toAsset()
    fun quoteAsset() = value.split('/', limit = 2)[1].toAsset()
    override fun toString(): String = value
}

fun String.toMarketId() = MarketId(this)

@JvmInline
value class Asset(val value: String) {
    override fun toString(): String = value
}

fun String.toAsset() = Asset(this)
