package xyz.funkybit.core.db

import xyz.funkybit.core.model.db.migrations.V100_AddDiscordUserIdToUser
import xyz.funkybit.core.model.db.migrations.V101_OHLCTimestampWithTimezone
import xyz.funkybit.core.model.db.migrations.V102_UserLinkedAccount
import xyz.funkybit.core.model.db.migrations.V103_TMAUser
import xyz.funkybit.core.model.db.migrations.V104_AddDiscordAndOauthRelayTokenToTMAUser
import xyz.funkybit.core.model.db.migrations.V10_WithdrawalTable
import xyz.funkybit.core.model.db.migrations.V11_NonNullableDeployedContractProxyAddress
import xyz.funkybit.core.model.db.migrations.V12_BigDecimalPrice
import xyz.funkybit.core.model.db.migrations.V13_AddSignatureToOrderTable
import xyz.funkybit.core.model.db.migrations.V14_MarketTickSize
import xyz.funkybit.core.model.db.migrations.V15_WalletTable
import xyz.funkybit.core.model.db.migrations.V16_BalanceAndLogTable
import xyz.funkybit.core.model.db.migrations.V17_DepositTable
import xyz.funkybit.core.model.db.migrations.V18_BlockchainNonce
import xyz.funkybit.core.model.db.migrations.V19_BlockchainTransaction
import xyz.funkybit.core.model.db.migrations.V1_DeployedSmartContract
import xyz.funkybit.core.model.db.migrations.V20_ExchangeTransaction
import xyz.funkybit.core.model.db.migrations.V21_UpdateDepositStatus
import xyz.funkybit.core.model.db.migrations.V22_BroadcasterJob
import xyz.funkybit.core.model.db.migrations.V23_OHLC
import xyz.funkybit.core.model.db.migrations.V24_KeyValueStore
import xyz.funkybit.core.model.db.migrations.V25_ChecksumWalletAddresses
import xyz.funkybit.core.model.db.migrations.V26_ChecksumContractAddresses
import xyz.funkybit.core.model.db.migrations.V27_TelegramBotTables
import xyz.funkybit.core.model.db.migrations.V28_RemoveCrossesMarket
import xyz.funkybit.core.model.db.migrations.V29_ExchangeTransactionBatch
import xyz.funkybit.core.model.db.migrations.V2_ERC20Token
import xyz.funkybit.core.model.db.migrations.V30_AddErrorToTrade
import xyz.funkybit.core.model.db.migrations.V31_AddWithdrawalStatuses
import xyz.funkybit.core.model.db.migrations.V32_Indexes
import xyz.funkybit.core.model.db.migrations.V33_SettlementBatch
import xyz.funkybit.core.model.db.migrations.V34_AddSessionStateToTelegramBotUser
import xyz.funkybit.core.model.db.migrations.V35_RemoveExchangeContract
import xyz.funkybit.core.model.db.migrations.V36_AddPendingDepositsToTelegramBotUser
import xyz.funkybit.core.model.db.migrations.V37_AddBatchHash
import xyz.funkybit.core.model.db.migrations.V38_AddLastPriceToMarket
import xyz.funkybit.core.model.db.migrations.V39_AddSequencerTimeToOrder
import xyz.funkybit.core.model.db.migrations.V3_UpdateDeployedSmartContract
import xyz.funkybit.core.model.db.migrations.V40_OrderIndexes
import xyz.funkybit.core.model.db.migrations.V41_AddActualAmountToWithdrawalTable
import xyz.funkybit.core.model.db.migrations.V42_ChainConfiguration
import xyz.funkybit.core.model.db.migrations.V43_AddFsmSessionStateToTelegramBotUser
import xyz.funkybit.core.model.db.migrations.V44_AddSentToSequencerStatusToDeposit
import xyz.funkybit.core.model.db.migrations.V45_ChainSettlementBatchIndexes
import xyz.funkybit.core.model.db.migrations.V46_MarketMinMaxPrice
import xyz.funkybit.core.model.db.migrations.V47_TelegramMiniAppUser
import xyz.funkybit.core.model.db.migrations.V48_AddingSymbolsToWallets
import xyz.funkybit.core.model.db.migrations.V49_AddWithdrawalFee
import xyz.funkybit.core.model.db.migrations.V4_AddDecimalsToERC20Token
import xyz.funkybit.core.model.db.migrations.V50_TelegramMiniAppReactionTime
import xyz.funkybit.core.model.db.migrations.V51_FaucetDrip
import xyz.funkybit.core.model.db.migrations.V52_TelegramMiniAppCheckInStreak
import xyz.funkybit.core.model.db.migrations.V53_AddPendingRollbackStatusToTrade
import xyz.funkybit.core.model.db.migrations.V54_TelegramMiniAppGameReactionTime
import xyz.funkybit.core.model.db.migrations.V55_TelegramMiniAppMilestones
import xyz.funkybit.core.model.db.migrations.V56_TelegramMiniAppInvites
import xyz.funkybit.core.model.db.migrations.V57_AddMinFeeToMarket
import xyz.funkybit.core.model.db.migrations.V58_TelegramMiniAppBotDetection
import xyz.funkybit.core.model.db.migrations.V59_NullableDepositBlockNumber
import xyz.funkybit.core.model.db.migrations.V5_ChainTable
import xyz.funkybit.core.model.db.migrations.V60_AddResponseSequenceToTradeAndWithdrawal
import xyz.funkybit.core.model.db.migrations.V61_CreateBlockTable
import xyz.funkybit.core.model.db.migrations.V62_BackToBackOrders
import xyz.funkybit.core.model.db.migrations.V63_SymbolIconUrls
import xyz.funkybit.core.model.db.migrations.V64_AddIsAdminToWallet
import xyz.funkybit.core.model.db.migrations.V65_WalletLinkedSigner
import xyz.funkybit.core.model.db.migrations.V66_AddCounterOrderIdToOrderExecution
import xyz.funkybit.core.model.db.migrations.V67_AddLimitTable
import xyz.funkybit.core.model.db.migrations.V68_RemoveMinMaxOfferPriceFromMarket
import xyz.funkybit.core.model.db.migrations.V69_ClientOrderId
import xyz.funkybit.core.model.db.migrations.V6_MarketTable
import xyz.funkybit.core.model.db.migrations.V70_AddOrderBookSnapshotTable
import xyz.funkybit.core.model.db.migrations.V71_BalanceLogTableChanges
import xyz.funkybit.core.model.db.migrations.V72_AddNetworkType
import xyz.funkybit.core.model.db.migrations.V73_AddBitcoinWalletStateTable
import xyz.funkybit.core.model.db.migrations.V74_ArchStateUtxoTables
import xyz.funkybit.core.model.db.migrations.V75_AddAutoReducedToOrder
import xyz.funkybit.core.model.db.migrations.V76_AddBlockHeightToBitcoinWalletState
import xyz.funkybit.core.model.db.migrations.V77_User
import xyz.funkybit.core.model.db.migrations.V78_ArchAccountTable
import xyz.funkybit.core.model.db.migrations.V79_UserTestnetChallenge
import xyz.funkybit.core.model.db.migrations.V7_OrderTable
import xyz.funkybit.core.model.db.migrations.V80_CrossChainSequencerOrders
import xyz.funkybit.core.model.db.migrations.V81_UserNicknameIndex
import xyz.funkybit.core.model.db.migrations.V82_AddSettlingStatusToDeposit
import xyz.funkybit.core.model.db.migrations.V83_ArchAccountBalanceIndexTable
import xyz.funkybit.core.model.db.migrations.V84_MarketPriceFeed
import xyz.funkybit.core.model.db.migrations.V85_TestnetChallengePNL
import xyz.funkybit.core.model.db.migrations.V86_AddArchTransactionToWithdrawal
import xyz.funkybit.core.model.db.migrations.V87_TestnetChallengeRewardCategory
import xyz.funkybit.core.model.db.migrations.V88_TypeId
import xyz.funkybit.core.model.db.migrations.V89_RewardTimestampWithTimezone
import xyz.funkybit.core.model.db.migrations.V8_ExecutionsAndTrades
import xyz.funkybit.core.model.db.migrations.V90_OrderExecutionSide
import xyz.funkybit.core.model.db.migrations.V91_TestnetChallengeRewardTypes
import xyz.funkybit.core.model.db.migrations.V92_DepositWithdrawalTimestamps
import xyz.funkybit.core.model.db.migrations.V93_AddBitcoinUtxoTable
import xyz.funkybit.core.model.db.migrations.V94_UpdateArchAccountTable
import xyz.funkybit.core.model.db.migrations.V95_AddIndexToBroadcasterJobTable
import xyz.funkybit.core.model.db.migrations.V96_UpdateBitcoinUtxoTable
import xyz.funkybit.core.model.db.migrations.V97_AddSentToArchStatusToDeposit
import xyz.funkybit.core.model.db.migrations.V98_AddUserGuidToOrderAndExecution
import xyz.funkybit.core.model.db.migrations.V99_AddArchRollbackTransactionToWithdrawal
import xyz.funkybit.core.model.db.migrations.V9_SymbolTable

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
    V40_OrderIndexes(),
    V41_AddActualAmountToWithdrawalTable(),
    V42_ChainConfiguration(),
    V43_AddFsmSessionStateToTelegramBotUser(),
    V44_AddSentToSequencerStatusToDeposit(),
    V45_ChainSettlementBatchIndexes(),
    V46_MarketMinMaxPrice(),
    V47_TelegramMiniAppUser(),
    V48_AddingSymbolsToWallets(),
    V49_AddWithdrawalFee(),
    V50_TelegramMiniAppReactionTime(),
    V51_FaucetDrip(),
    V52_TelegramMiniAppCheckInStreak(),
    V53_AddPendingRollbackStatusToTrade(),
    V54_TelegramMiniAppGameReactionTime(),
    V55_TelegramMiniAppMilestones(),
    V56_TelegramMiniAppInvites(),
    V57_AddMinFeeToMarket(),
    V58_TelegramMiniAppBotDetection(),
    V59_NullableDepositBlockNumber(),
    V60_AddResponseSequenceToTradeAndWithdrawal(),
    V61_CreateBlockTable(),
    V62_BackToBackOrders(),
    V63_SymbolIconUrls(),
    V64_AddIsAdminToWallet(),
    V65_WalletLinkedSigner(),
    V66_AddCounterOrderIdToOrderExecution(),
    V67_AddLimitTable(),
    V68_RemoveMinMaxOfferPriceFromMarket(),
    V69_ClientOrderId(),
    V70_AddOrderBookSnapshotTable(),
    V71_BalanceLogTableChanges(),
    V72_AddNetworkType(),
    V73_AddBitcoinWalletStateTable(),
    V74_ArchStateUtxoTables(),
    V75_AddAutoReducedToOrder(),
    V76_AddBlockHeightToBitcoinWalletState(),
    V77_User(),
    V78_ArchAccountTable(),
    V79_UserTestnetChallenge(),
    V80_CrossChainSequencerOrders(),
    V81_UserNicknameIndex(),
    V82_AddSettlingStatusToDeposit(),
    V83_ArchAccountBalanceIndexTable(),
    V84_MarketPriceFeed(),
    V85_TestnetChallengePNL(),
    V86_AddArchTransactionToWithdrawal(),
    V87_TestnetChallengeRewardCategory(),
    V88_TypeId(),
    V89_RewardTimestampWithTimezone(),
    V90_OrderExecutionSide(),
    V91_TestnetChallengeRewardTypes(),
    V92_DepositWithdrawalTimestamps(),
    V93_AddBitcoinUtxoTable(),
    V94_UpdateArchAccountTable(),
    V95_AddIndexToBroadcasterJobTable(),
    V96_UpdateBitcoinUtxoTable(),
    V97_AddSentToArchStatusToDeposit(),
    V98_AddUserGuidToOrderAndExecution(),
    V99_AddArchRollbackTransactionToWithdrawal(),
    V100_AddDiscordUserIdToUser(),
    V101_OHLCTimestampWithTimezone(),
    V102_UserLinkedAccount(),
    V103_TMAUser(),
    V104_AddDiscordAndOauthRelayTokenToTMAUser(),
)
