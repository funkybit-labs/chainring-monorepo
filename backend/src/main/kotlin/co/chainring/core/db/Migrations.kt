package co.chainring.core.db

import co.chainring.core.model.db.migrations.V10_WithdrawalTable
import co.chainring.core.model.db.migrations.V11_NonNullableDeployedContractProxyAddress
import co.chainring.core.model.db.migrations.V12_BigDecimalPrice
import co.chainring.core.model.db.migrations.V13_AddSignatureToOrderTable
import co.chainring.core.model.db.migrations.V14_MarketTickSize
import co.chainring.core.model.db.migrations.V15_WalletTable
import co.chainring.core.model.db.migrations.V16_BalanceAndLogTable
import co.chainring.core.model.db.migrations.V1_DeployedSmartContract
import co.chainring.core.model.db.migrations.V2_ERC20Token
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
)
