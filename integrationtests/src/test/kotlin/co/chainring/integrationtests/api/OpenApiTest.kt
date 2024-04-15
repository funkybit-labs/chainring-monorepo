package co.chainring.integrationtests.api

import co.chainring.integrationtests.testutils.ApiClient
import co.chainring.integrationtests.testutils.AppUnderTestRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(AppUnderTestRunner::class)
class OpenApiTest {

    @Test
    fun `test that documentation can be generated`() {
        assertDoesNotThrow {
            ApiClient.getOpenApiDocumentation()
        }
    }
}
