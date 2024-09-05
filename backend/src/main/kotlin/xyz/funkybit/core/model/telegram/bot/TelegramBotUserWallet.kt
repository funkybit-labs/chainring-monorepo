package xyz.funkybit.core.model.telegram.bot

import de.fxlae.typeid.TypeId
import kotlinx.coroutines.future.await
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.dao.EntityClass
import org.jetbrains.exposed.dao.id.EntityID
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import xyz.funkybit.apps.api.model.OrderAmount
import xyz.funkybit.core.blockchain.BlockchainClient
import xyz.funkybit.core.blockchain.ChainManager
import xyz.funkybit.core.evm.EIP712Helper
import xyz.funkybit.core.evm.EIP712Transaction
import xyz.funkybit.core.evm.TokenAddressAndChain
import xyz.funkybit.core.model.EncryptedString
import xyz.funkybit.core.model.EvmAddress
import xyz.funkybit.core.model.EvmSignature
import xyz.funkybit.core.model.TxHash
import xyz.funkybit.core.model.db.BalanceEntity
import xyz.funkybit.core.model.db.BalanceType
import xyz.funkybit.core.model.db.ChainId
import xyz.funkybit.core.model.db.DeployedSmartContractEntity
import xyz.funkybit.core.model.db.EntityId
import xyz.funkybit.core.model.db.GUIDEntity
import xyz.funkybit.core.model.db.GUIDTable
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.OrderSide
import xyz.funkybit.core.model.db.SymbolEntity
import xyz.funkybit.core.model.db.WalletEntity
import xyz.funkybit.core.model.db.WalletTable
import xyz.funkybit.core.utils.fromFundamentalUnits
import xyz.funkybit.core.utils.toFundamentalUnits
import xyz.funkybit.core.utils.toHexBytes
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
    companion object : EntityClass<TelegramBotUserWalletId, TelegramBotUserWalletEntity>(
        TelegramBotUserWalletTable,
    ) {
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

    val evmAddress: EvmAddress
        get() = wallet.address as EvmAddress

    fun exchangeBalances(): List<BalanceEntity> =
        BalanceEntity.getBalancesForUserId(wallet.userGuid)

    fun exchangeAvailableBalance(symbol: SymbolEntity): BigDecimal =
        BalanceEntity
            .findForUserAndSymbol(wallet.userGuid, symbol, BalanceType.Available)
            ?.balance
            ?.fromFundamentalUnits(symbol.decimals)
            ?: BigInteger.ZERO.fromFundamentalUnits(symbol.decimals)

    fun onChainBalances(symbols: List<SymbolEntity>): List<Pair<SymbolEntity, BigDecimal>> =
        runBlocking {
            symbols.map { symbol ->
                Pair(symbol, ChainManager.getBlockchainClient(symbol.chainId.value).asyncGetBalance(evmAddress, symbol))
            }
        }

    fun onChainBalance(symbol: SymbolEntity): BigDecimal =
        ChainManager
            .getBlockchainClient(symbol.chainId.value)
            .getBalance(evmAddress, symbol)

    fun blockchainClient(chainId: ChainId): BlockchainClient =
        ChainManager
            .getBlockchainClient(chainId, privateKeyHex = encryptedPrivateKey.decrypt())

    fun deposit(amount: BigDecimal, symbol: SymbolEntity): TxHash? {
        val blockchainClient = blockchainClient(symbol.chainId.value)
        val exchangeContractAddress = DeployedSmartContractEntity.latestExchangeContractAddress(
            blockchainClient.chainId,
        ) as? EvmAddress
        val bigIntAmount = amount.toFundamentalUnits(symbol.decimals)
        val tokenAddress = symbol.contractAddress as? EvmAddress
        return exchangeContractAddress?.let {
            if (tokenAddress != null) {
                runBlocking {
                    val allowanceTxReceipt = blockchainClient
                        .loadERC20(tokenAddress)
                        .approve(exchangeContractAddress.toString(), bigIntAmount)
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
                blockchainClient.sendNativeDepositTx(exchangeContractAddress, bigIntAmount)
            }
        }
    }

    fun signWithdrawal(amount: BigDecimal, symbol: SymbolEntity, nonce: Long): EvmSignature {
        val blockchainClient = blockchainClient(symbol.chainId.value)
        val exchangeContractAddress = DeployedSmartContractEntity.latestExchangeContractAddress(
            blockchainClient.chainId,
        )!!
        val bigIntAmount = amount.toFundamentalUnits(symbol.decimals)
        val tx = EIP712Transaction.WithdrawTx(
            wallet.address,
            TokenAddressAndChain(symbol.contractAddress ?: EvmAddress.zero, symbol.chainId.value),
            bigIntAmount,
            nonce,
            bigIntAmount == BigInteger.ZERO,
            EvmSignature.emptySignature(),
        )
        return blockchainClient.signData(EIP712Helper.computeHash(tx, blockchainClient.chainId, exchangeContractAddress))
    }

    fun signOrder(market: MarketEntity, side: OrderSide, amount: OrderAmount, nonce: String): Pair<EvmSignature, ChainId> {
        val blockchainClient = blockchainClient(market.baseSymbol.chainId.value)
        val exchangeContractAddress = DeployedSmartContractEntity.latestExchangeContractAddress(
            blockchainClient.chainId,
        )!!

        val tx = EIP712Transaction.Order(
            wallet.address,
            baseChainId = market.baseSymbol.chainId.value,
            baseToken = market.baseSymbol.contractAddress ?: EvmAddress.zero,
            quoteChainId = market.quoteSymbol.chainId.value,
            quoteToken = market.quoteSymbol.contractAddress ?: EvmAddress.zero,
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
