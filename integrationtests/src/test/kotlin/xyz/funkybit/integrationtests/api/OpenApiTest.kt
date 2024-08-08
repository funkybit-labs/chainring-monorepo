package xyz.funkybit.integrationtests.api

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.extension.ExtendWith
import xyz.funkybit.integrationtests.testutils.AppUnderTestRunner
import xyz.funkybit.integrationtests.utils.TestApiClient

@ExtendWith(AppUnderTestRunner::class)
class OpenApiTest {

    @Test
    fun `test that documentation can be generated`() {
        assertDoesNotThrow {
            TestApiClient.getOpenApiDocumentation()
        }
    }
}
