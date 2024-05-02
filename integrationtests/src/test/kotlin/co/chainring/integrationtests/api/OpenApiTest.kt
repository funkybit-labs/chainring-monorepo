package co.chainring.integrationtests.api

import co.chainring.integrationtests.testutils.AppUnderTestRunner
import co.chainring.integrationtests.utils.TestApiClient
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(AppUnderTestRunner::class)
class OpenApiTest {

    @Test
    fun `test that documentation can be generated`() {
        assertDoesNotThrow {
            TestApiClient.getOpenApiDocumentation()
        }
    }
}
