package routes

import com.revio.server.features.notification.InactivityRunResultDTO
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
import testutils.testInactivityModule

/**
 * Covers the `X-Cron-Secret` gate on `POST /api/internal/notifications/inactivity` (plan §18
 * Pasul 7) — the job's own eligibility logic (ban state, prefs, milestone, dedupe, etc.) is
 * covered separately by InactivityJobTest.
 */
class InactivityRoutesTest {

    private val validCronSecret = "test-cron-secret"

    @AfterEach
    fun tearDown() {
        stopKoinSafely()
    }

    private fun inactivityTest(
        cronSecret: String? = validCronSecret,
        block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        application { testInactivityModule(cronSecret) }
        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        block(client)
    }

    @Test
    fun `POST inactivity without secret returns 401`() = inactivityTest { client ->
        val resp = client.post("/api/internal/notifications/inactivity")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST inactivity with wrong secret returns 401`() = inactivityTest { client ->
        val resp = client.post("/api/internal/notifications/inactivity") {
            header("X-Cron-Secret", "wrong-secret")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST inactivity fails closed with 401 when CRON_SECRET is blank`() = inactivityTest(cronSecret = "") { client ->
        val resp = client.post("/api/internal/notifications/inactivity") {
            header("X-Cron-Secret", "")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST inactivity fails closed with 401 when CRON_SECRET is unset`() = inactivityTest(cronSecret = null) { client ->
        val resp = client.post("/api/internal/notifications/inactivity") {
            header("X-Cron-Secret", validCronSecret)
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST inactivity with correct secret returns 200 with a report`() = inactivityTest { client ->
        val resp = client.post("/api/internal/notifications/inactivity") {
            header("X-Cron-Secret", validCronSecret)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body: InactivityRunResultDTO = resp.body()
        assertEquals(0, body.evaluated)
        assertEquals(0, body.sent)
        assertEquals(0, body.skipped)
    }
}
