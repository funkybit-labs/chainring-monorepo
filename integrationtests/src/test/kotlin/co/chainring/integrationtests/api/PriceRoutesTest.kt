package co.chainring.integrationtests.api

import co.chainring.apps.api.model.ApiError
import co.chainring.apps.api.model.ReasonCode
import co.chainring.core.model.db.MarketEntity
import co.chainring.core.model.db.MarketId
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.TestApiClient
import co.chainring.integrationtests.utils.assertError
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.extension.ExtendWith
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
