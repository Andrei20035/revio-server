package routes

import com.revio.server.features.notification.DiscoveryRunResultDTO
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import testutils.stopKoinSafely
import testutils.testDiscoveryModule

/**
 * Covers the `X-Cron-Secret` gate on `POST /api/internal/notifications/discovery` (plan §18,
 * step 6.3) — the job's own eligibility logic (account age, content threshold, feed-open gate,
 * weekly cap, quiet hours, etc.) is covered separately by DiscoveryJobTest.
 */
class DiscoveryRoutesTest {

    private val validCronSecret = "test-cron-secret"

    @AfterEach
    fun tearDown() {
        stopKoinSafely()
    }

    private fun discoveryTest(
        cronSecret: String? = validCronSecret,
        block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        application { testDiscoveryModule(cronSecret) }
        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        block(client)
    }

    @Test
    fun `POST discovery without secret returns 401`() = discoveryTest { client ->
        val resp = client.post("/api/internal/notifications/discovery")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST discovery with wrong secret returns 401`() = discoveryTest { client ->
        val resp = client.post("/api/internal/notifications/discovery") {
            header("X-Cron-Secret", "wrong-secret")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST discovery fails closed with 401 when CRON_SECRET is blank`() = discoveryTest(cronSecret = "") { client ->
        val resp = client.post("/api/internal/notifications/discovery") {
            header("X-Cron-Secret", "")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST discovery fails closed with 401 when CRON_SECRET is unset`() = discoveryTest(cronSecret = null) { client ->
        val resp = client.post("/api/internal/notifications/discovery") {
            header("X-Cron-Secret", validCronSecret)
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST discovery with correct secret returns 200 with a report`() = discoveryTest { client ->
        val resp = client.post("/api/internal/notifications/discovery") {
            header("X-Cron-Secret", validCronSecret)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body: DiscoveryRunResultDTO = resp.body()
        assertEquals(0, body.evaluated)
        assertEquals(0, body.sent)
        assertEquals(0, body.skipped)
    }
}
