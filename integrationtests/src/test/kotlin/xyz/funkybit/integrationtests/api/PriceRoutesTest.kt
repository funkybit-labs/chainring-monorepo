package xyz.funkybit.integrationtests.api

import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.apps.api.model.ApiError
import xyz.funkybit.apps.api.model.ReasonCode
import xyz.funkybit.core.model.db.MarketEntity
import xyz.funkybit.core.model.db.MarketId
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.TestApiClient
import xyz.funkybit.integrationtests.utils.assertError
import kotlin.test.Test

@ExtendWith(AppUnderTestRunner::class)
class PriceRoutesTest {
    @Test
    fun `get last price for market`() {
        val apiClient = TestApiClient()

        apiClient.getConfiguration().markets.forEach { market ->
            assertEquals(
                transaction { MarketEntity[market.id].lastPrice },
                apiClient.getLastPrice(market.id).lastPrice,
            )
        }

        // unknown market
        apiClient
            .tryGetLastPrice(MarketId("FOO/BAR"))
            .assertError(ApiError(ReasonCode.MarketNotFound, "Unknown market"))
    }
}
