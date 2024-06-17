package co.chainring.core.model.db

import co.chainring.apps.api.model.OrderAmount
import co.chainring.core.blockchain.BlockchainClient
import co.chainring.core.blockchain.ChainManager
import co.chainring.core.evm.EIP712Helper
import co.chainring.core.evm.EIP712Transaction
import co.chainring.core.evm.TokenAddressAndChain
import co.chainring.core.model.Address
import co.chainring.core.model.EvmSignature
import co.chainring.core.model.TxHash
import co.chainring.core.utils.fromFundamentalUnits
import co.chainring.core.utils.toFundamentalUnits
import co.chainring.core.utils.toHexBytes
import co.chanring.core.model.EncryptedString
import de.fxlae.typeid.TypeId
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import java.math.BigDecimal
import java.math.BigInteger

@Serializable
@JvmInline
value class TelegramBotUserWalletId(override val value: String) : EntityId {
    companion object {
        fun generate(): TelegramBotUserWalletId = TelegramBotUserWalletId(TypeId.generate("tbuw").toString())
    }

    override fun toString(): String = value
}

object TelegramBotUserWalletTable : GUIDTable<TelegramBotUserWalletId>("telegram_bot_user_wallet", ::TelegramBotUserWalletId) {
    val walletGuid = reference("wallet_guid", WalletTable).index()
    val telegrambotUserGuid = reference("telegram_bot_user_guid", TelegramBotUserTable).index()
    val encryptedPrivateKey = varchar("encrypted_private_key", 10485760)
    val isCurrent = bool("is_current")
    val createdAt = timestamp("created_at")
    val createdBy = varchar("created_by", 10485760)
}

class TelegramBotUserWalletEntity(guid: EntityID<TelegramBotUserWalletId>) : GUIDEntity<TelegramBotUserWalletId>(guid) {
    companion object : EntityClass<TelegramBotUserWalletId, TelegramBotUserWalletEntity>(TelegramBotUserWalletTable) {
        fun create(
            wallet: WalletEntity,
            telegramBotUser: TelegramBotUserEntity,
            privateKey: EncryptedString,
            isCurrent: Boolean,
        ) = TelegramBotUserWalletEntity.new(TelegramBotUserWalletId.generate()) {
            val now = Clock.System.now()
            this.encryptedPrivateKey = privateKey
            this.createdAt = now
            this.createdBy = telegramBotUser.guid.value.value
            this.walletGuid = wallet.guid
            this.telegramBotUserGuid = telegramBotUser.guid
            this.isCurrent = isCurrent
        }
    }

    var walletGuid by TelegramBotUserWalletTable.walletGuid
    var wallet by WalletEntity referencedOn TelegramBotUserWalletTable.walletGuid
    var telegramBotUserGuid by TelegramBotUserWalletTable.telegrambotUserGuid
    var user by TelegramBotUserEntity referencedOn TelegramBotUserWalletTable.telegrambotUserGuid
    var createdAt by TelegramBotUserWalletTable.createdAt
    var createdBy by TelegramBotUserWalletTable.createdBy
    var encryptedPrivateKey by TelegramBotUserWalletTable.encryptedPrivateKey.transform(
        toReal = { EncryptedString(it) },
        toColumn = { it.encrypted },
    )
    var isCurrent by TelegramBotUserWalletTable.isCurrent

    val address: Address
        get() = wallet.address

    fun exchangeBalances(): List<BalanceEntity> =
        BalanceEntity.getBalancesForWallet(wallet)

    fun exchangeAvailableBalance(symbol: SymbolEntity): BigDecimal =
        (BalanceEntity.findForWalletAndSymbol(wallet, symbol, BalanceType.Available)?.balance ?: BigInteger.ZERO)
            .fromFundamentalUnits(symbol.decimals)

    fun onChainBalances(symbols: List<SymbolEntity>): List<Pair<SymbolEntity, BigDecimal>> =
        runBlocking {
            symbols.map { symbol ->
                Pair(symbol, ChainManager.getBlockchainClient(symbol.chainId.value).asyncGetBalance(wallet.address, symbol))
            }
        }

    fun onChainBalance(symbol: SymbolEntity): BigDecimal =
        ChainManager
            .getBlockchainClient(symbol.chainId.value)
            .getBalance(wallet.address, symbol)

    fun blockchainClient(chainId: ChainId): BlockchainClient =
        ChainManager
            .getBlockchainClient(chainId, privateKeyHex = encryptedPrivateKey.decrypt())

    fun deposit(amount: BigDecimal, symbol: SymbolEntity): TxHash? {
        val blockchainClient = blockchainClient(symbol.chainId.value)
        val exchangeContractAddress = DeployedSmartContractEntity.latestExchangeContractAddress(blockchainClient.chainId)!!
        val bigIntAmount = amount.toFundamentalUnits(symbol.decimals)
        val tokenAddress = symbol.contractAddress
        return if (tokenAddress != null) {
            runBlocking {
                val allowanceTxReceipt = blockchainClient
                    .loadERC20(tokenAddress)
                    .approve(exchangeContractAddress.value, bigIntAmount)
                    .sendAsync()
                    .await()
                if (allowanceTxReceipt.isStatusOK) {
                    blockchainClient.sendTransaction(
                        exchangeContractAddress,
                        blockchainClient
                            .loadExchangeContract(exchangeContractAddress)
                            .deposit(tokenAddress.value, bigIntAmount).encodeFunctionCall(),
                        BigInteger.ZERO,
                    )
                } else {
                    null
                }
            }
        } else {
            blockchainClient.asyncDepositNative(exchangeContractAddress, bigIntAmount)
        }
    }

    fun signWithdrawal(amount: BigDecimal, symbol: SymbolEntity, nonce: Long): EvmSignature {
        val blockchainClient = blockchainClient(symbol.chainId.value)
        val exchangeContractAddress = DeployedSmartContractEntity.latestExchangeContractAddress(blockchainClient.chainId)!!
        val bigIntAmount = amount.toFundamentalUnits(symbol.decimals)
        val tx = EIP712Transaction.WithdrawTx(
            wallet.address,
            TokenAddressAndChain(symbol.contractAddress ?: Address.zero, symbol.chainId.value),
            bigIntAmount,
            nonce,
            bigIntAmount == BigInteger.ZERO,
            EvmSignature.emptySignature(),
        )
        return blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress))
    }

    fun signOrder(market: MarketEntity, side: OrderSide, amount: OrderAmount, nonce: String): Pair<EvmSignature, ChainId> {
        val blockchainClient = blockchainClient(market.baseSymbol.chainId.value)
        val exchangeContractAddress = DeployedSmartContractEntity.latestExchangeContractAddress(blockchainClient.chainId)!!

        val tx = EIP712Transaction.Order(
            wallet.address,
            baseChainId = market.baseSymbol.chainId.value,
            baseToken = market.baseSymbol.contractAddress ?: Address.zero,
            quoteChainId = market.quoteSymbol.chainId.value,
            quoteToken = market.quoteSymbol.contractAddress ?: Address.zero,
            amount = if (side == OrderSide.Buy) amount else amount.negate(),
            price = BigInteger.ZERO,
            nonce = BigInteger(1, nonce.toHexBytes()),
            EvmSignature.emptySignature(),
        )

        blockchainClient.signData(
            EIP712Helper.computeHash(
                tx,
                blockchainClient.chainId,
                exchangeContractAddress,
            ),
        )

        return Pair(
            blockchainClient.signData(
                EIP712Helper.computeHash(
                    tx,
                    blockchainClient.chainId,
                    exchangeContractAddress,
                ),
            ),
            blockchainClient.chainId,
        )
    }
}
