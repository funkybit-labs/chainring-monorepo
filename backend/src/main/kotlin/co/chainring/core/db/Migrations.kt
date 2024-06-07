package co.chainring.core.db

import co.chainring.core.model.db.migrations.V10_WithdrawalTable
import co.chainring.core.model.db.migrations.V11_NonNullableDeployedContractProxyAddress
import co.chainring.core.model.db.migrations.V12_BigDecimalPrice
import co.chainring.core.model.db.migrations.V13_AddSignatureToOrderTable
import co.chainring.core.model.db.migrations.V14_MarketTickSize
import co.chainring.core.model.db.migrations.V15_WalletTable
import co.chainring.core.model.db.migrations.V16_BalanceAndLogTable
import co.chainring.core.model.db.migrations.V17_DepositTable
import co.chainring.core.model.db.migrations.V18_BlockchainNonce
import co.chainring.core.model.db.migrations.V19_BlockchainTransaction
import co.chainring.core.model.db.migrations.V1_DeployedSmartContract
import co.chainring.core.model.db.migrations.V20_ExchangeTransaction
import co.chainring.core.model.db.migrations.V21_UpdateDepositStatus
import co.chainring.core.model.db.migrations.V22_BroadcasterJob
import co.chainring.core.model.db.migrations.V23_OHLC
import co.chainring.core.model.db.migrations.V24_KeyValueStore
import co.chainring.core.model.db.migrations.V25_ChecksumWalletAddresses
import co.chainring.core.model.db.migrations.V26_ChecksumContractAddresses
import co.chainring.core.model.db.migrations.V27_TelegramBotTables
import co.chainring.core.model.db.migrations.V28_RemoveCrossesMarket
import co.chainring.core.model.db.migrations.V29_ExchangeTransactionBatch
import co.chainring.core.model.db.migrations.V2_ERC20Token
import co.chainring.core.model.db.migrations.V30_AddErrorToTrade
import co.chainring.core.model.db.migrations.V31_AddWithdrawalStatuses
import co.chainring.core.model.db.migrations.V32_Indexes
import co.chainring.core.model.db.migrations.V33_SettlementBatch
import co.chainring.core.model.db.migrations.V34_AddSessionStateToTelegramBotUser
import co.chainring.core.model.db.migrations.V35_RemoveExchangeContract
import co.chainring.core.model.db.migrations.V36_AddPendingDepositsToTelegramBotUser
import co.chainring.core.model.db.migrations.V37_AddBatchHash
import co.chainring.core.model.db.migrations.V38_AddLastPriceToMarket
import co.chainring.core.model.db.migrations.V39_AddSequencerTimeToOrder
import co.chainring.core.model.db.migrations.V3_UpdateDeployedSmartContract
import co.chainring.core.model.db.migrations.V4_AddDecimalsToERC20Token
import co.chainring.core.model.db.migrations.V5_ChainTable
import co.chainring.core.model.db.migrations.V6_MarketTable
import co.chainring.core.model.db.migrations.V7_OrderTable
import co.chainring.core.model.db.migrations.V8_ExecutionsAndTrades
import co.chainring.core.model.db.migrations.V9_SymbolTable

val migrations = listOf(
    V1_DeployedSmartContract(),
    V2_ERC20Token(),
    V3_UpdateDeployedSmartContract(),
    V4_AddDecimalsToERC20Token(),
    V5_ChainTable(),
    V6_MarketTable(),
    V7_OrderTable(),
    V8_ExecutionsAndTrades(),
    V9_SymbolTable(),
    V10_WithdrawalTable(),
    V11_NonNullableDeployedContractProxyAddress(),
    V12_BigDecimalPrice(),
    V13_AddSignatureToOrderTable(),
    V14_MarketTickSize(),
    V15_WalletTable(),
    V16_BalanceAndLogTable(),
    V17_DepositTable(),
    V18_BlockchainNonce(),
    V19_BlockchainTransaction(),
    V20_ExchangeTransaction(),
    V21_UpdateDepositStatus(),
    V22_BroadcasterJob(),
    V23_OHLC(),
    V24_KeyValueStore(),
    V25_ChecksumWalletAddresses(),
    V26_ChecksumContractAddresses(),
    V27_TelegramBotTables(),
    V28_RemoveCrossesMarket(),
    V29_ExchangeTransactionBatch(),
    V30_AddErrorToTrade(),
    V31_AddWithdrawalStatuses(),
    V32_Indexes(),
    V33_SettlementBatch(),
    V34_AddSessionStateToTelegramBotUser(),
    V35_RemoveExchangeContract(),
    V36_AddPendingDepositsToTelegramBotUser(),
    V37_AddBatchHash(),
    V38_AddLastPriceToMarket(),
    V39_AddSequencerTimeToOrder(),
)
