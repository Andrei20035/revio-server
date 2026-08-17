package routes

import com.revio.server.features.waitlist.WaitlistEventResponseDTO
import com.revio.server.features.waitlist.WaitlistSyncResponseDTO
import com.revio.server.features.waitlist.WaitlistTable
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.deleteAll
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.stopKoinSafely
import testutils.testWaitlistModule

/**
 * waitlist_signups has no FK to any other table (V31), so [TestDatabaseFactory.cleanDatabase]'s
 * TRUNCATE list doesn't reach it — cleared manually in [clean] instead.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WaitlistRoutesTest {

    private val validCronSecret = "test-cron-secret"
    private val validWebhookSecret = "test-webhook-secret"

    @BeforeAll
    fun setup() {
        TestDatabaseFactory.start()
    }

    @AfterAll
    fun tearDown() {
        TestDatabaseFactory.stop()
    }

    @BeforeEach
    fun clean() {
        TestDatabaseFactory.cleanDatabase()
        transaction { WaitlistTable.deleteAll() }
        stopKoinSafely()
    }

    private fun waitlistTest(block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application { testWaitlistModule(validCronSecret, validWebhookSecret) }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
            block(client)
        }

    private fun webhookPayload(id: String, email: String) = """
        {
            "type": "INSERT",
            "record": {
                "id": "$id",
                "email": "$email",
                "username": "webhookuser",
                "platform": "ios",
                "country": "RO",
                "created_at": "2026-01-01T00:00:00+00:00",
                "updated_at": "2026-01-01T00:00:00+00:00"
            }
        }
    """.trimIndent()

    // ---------- POST /internal/waitlist/sync ----------

    @Test
    fun `POST sync without secret returns 401`() = waitlistTest { client ->
        val resp = client.post("/api/internal/waitlist/sync")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST sync with wrong secret returns 401`() = waitlistTest { client ->
        val resp = client.post("/api/internal/waitlist/sync") {
            header("X-Cron-Secret", "wrong-secret")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST sync with correct secret returns 200 with a report`() = waitlistTest { client ->
        val resp = client.post("/api/internal/waitlist/sync") {
            header("X-Cron-Secret", validCronSecret)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body: WaitlistSyncResponseDTO = resp.body()
        assertTrue(body.success)
    }

    // ---------- POST /internal/waitlist/events ----------

    @Test
    fun `POST events without secret returns 401`() = waitlistTest { client ->
        val resp = client.post("/api/internal/waitlist/events") {
            contentType(ContentType.Application.Json)
            setBody(webhookPayload("11111111-1111-1111-1111-111111111111", "noauth@example.com"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST events with wrong secret returns 401`() = waitlistTest { client ->
        val resp = client.post("/api/internal/waitlist/events") {
            header("X-Waitlist-Secret", "wrong-secret")
            contentType(ContentType.Application.Json)
            setBody(webhookPayload("22222222-2222-2222-2222-222222222222", "wrongsecret@example.com"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `a duplicate webhook payload returns 200 both times and leaves a single row`() = waitlistTest { client ->
        val id = "33333333-3333-3333-3333-333333333333"
        val payload = webhookPayload(id, "dup@example.com")

        val first = client.post("/api/internal/waitlist/events") {
            header("X-Waitlist-Secret", validWebhookSecret)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }
        val second = client.post("/api/internal/waitlist/events") {
            header("X-Waitlist-Secret", validWebhookSecret)
            contentType(ContentType.Application.Json)
            setBody(payload)
        }

        assertEquals(HttpStatusCode.OK, first.status)
        assertEquals(HttpStatusCode.OK, second.status)
        val firstBody: WaitlistEventResponseDTO = first.body()
        val secondBody: WaitlistEventResponseDTO = second.body()
        assertTrue(firstBody.applied)
        assertFalse(secondBody.applied)

        val rowCount = transaction { WaitlistTable.selectAll().count() }
        assertEquals(1L, rowCount)
    }

    // ---------- GET /internal/waitlist/health ----------

    @Test
    fun `GET health without secret returns 401`() = waitlistTest { client ->
        val resp = client.get("/api/internal/waitlist/health")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `GET health with wrong secret returns 401`() = waitlistTest { client ->
        val resp = client.get("/api/internal/waitlist/health") {
            header("X-Cron-Secret", "wrong-secret")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `GET health returns 503 when no sync has ever succeeded`() = waitlistTest { client ->
        val resp = client.get("/api/internal/waitlist/health") {
            header("X-Cron-Secret", validCronSecret)
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
    }

    @Test
    fun `GET health returns 200 right after a successful sync`() = waitlistTest { client ->
        client.post("/api/internal/waitlist/sync") {
            header("X-Cron-Secret", validCronSecret)
        }

        val resp = client.get("/api/internal/waitlist/health") {
            header("X-Cron-Secret", validCronSecret)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }
}
