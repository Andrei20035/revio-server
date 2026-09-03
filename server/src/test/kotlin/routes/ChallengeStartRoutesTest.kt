package routes

import com.revio.server.features.challenge.ChallengeStatus
import com.revio.server.features.notification.ChallengeStartHealthDTO
import com.revio.server.features.notification.ChallengeStartRunResultDTO
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.ChallengeTestSeed
import testutils.TestDatabaseFactory
import testutils.stopKoinSafely
import testutils.testChallengeStartModule
import java.time.Instant

/**
 * Covers the `X-Cron-Secret` gate on `POST /api/internal/notifications/challenge-start`
 * (push-notifications plan, "challenge is live" work) — the job's own detection/fan-out logic is
 * covered separately by ChallengeStartJobTest, same split as DiscoveryRoutesTest/DiscoveryJobTest.
 * Also covers `GET /api/internal/notifications/challenge-start-health` (mirrors
 * ChallengeAdminRoutes' `/finalization-health`), which needs a real `IChallengeDAO` — hence the
 * database lifecycle below, unlike the other cron routes' pure-mock test modules.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ChallengeStartRoutesTest {

    private val validCronSecret = "test-cron-secret"

    @BeforeAll
    fun setup() = TestDatabaseFactory.start()

    @AfterAll
    fun tearDownDatabase() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() = TestDatabaseFactory.cleanDatabase()

    @AfterEach
    fun tearDown() {
        stopKoinSafely()
    }

    private fun challengeStartTest(
        cronSecret: String? = validCronSecret,
        block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit,
    ) = testApplication {
        application { testChallengeStartModule(cronSecret) }
        val client = createClient {
            install(ContentNegotiation) {
                json(Json { ignoreUnknownKeys = true; isLenient = true })
            }
        }
        block(client)
    }

    @Test
    fun `POST challenge-start without secret returns 401`() = challengeStartTest { client ->
        val resp = client.post("/api/internal/notifications/challenge-start")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST challenge-start with wrong secret returns 401`() = challengeStartTest { client ->
        val resp = client.post("/api/internal/notifications/challenge-start") {
            header("X-Cron-Secret", "wrong-secret")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST challenge-start fails closed with 401 when CRON_SECRET is blank`() = challengeStartTest(cronSecret = "") { client ->
        val resp = client.post("/api/internal/notifications/challenge-start") {
            header("X-Cron-Secret", "")
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `POST challenge-start with correct secret returns 200 with a report`() = challengeStartTest { client ->
        val resp = client.post("/api/internal/notifications/challenge-start") {
            header("X-Cron-Secret", validCronSecret)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body: ChallengeStartRunResultDTO = resp.body()
        assertEquals(0, body.challengesProcessed)
        assertEquals(0, body.notified)
        assertEquals(0, body.skipped)
    }

    // ---------- GET challenge-start-health ----------

    @Test
    fun `GET challenge-start-health without secret returns 401`() = challengeStartTest { client ->
        val resp = client.get("/api/internal/notifications/challenge-start-health")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `GET challenge-start-health with nothing due returns 200 with an empty list`() = challengeStartTest { client ->
        val resp = client.get("/api/internal/notifications/challenge-start-health") {
            header("X-Cron-Secret", validCronSecret)
        }

        assertEquals(HttpStatusCode.OK, resp.status)
        val body: ChallengeStartHealthDTO = resp.body()
        assertTrue(body.stuckChallenges.isEmpty())
    }

    @Test
    fun `GET challenge-start-health flags a challenge whose window opened over 30 minutes ago and still isn't notified`() =
        challengeStartTest { client ->
            val now = Instant.now()
            val familyId = ChallengeTestSeed.seedFamily()
            val stuckChallengeId = ChallengeTestSeed.seedChallenge(
                familyId = familyId,
                startsAt = now.minusSeconds(3600),
                endsAt = now.plusSeconds(3600),
                status = ChallengeStatus.SCHEDULED,
            )

            val resp = client.get("/api/internal/notifications/challenge-start-health") {
                header("X-Cron-Secret", validCronSecret)
            }

            assertEquals(HttpStatusCode.ServiceUnavailable, resp.status)
            val body: ChallengeStartHealthDTO = resp.body()
            assertEquals(listOf(stuckChallengeId), body.stuckChallenges.map { it.id })
        }

    @Test
    fun `GET challenge-start-health does not flag a challenge whose window opened less than 30 minutes ago`() =
        challengeStartTest { client ->
            val now = Instant.now()
            val familyId = ChallengeTestSeed.seedFamily()
            ChallengeTestSeed.seedChallenge(
                familyId = familyId,
                startsAt = now.minusSeconds(60),
                endsAt = now.plusSeconds(3600),
                status = ChallengeStatus.SCHEDULED,
            )

            val resp = client.get("/api/internal/notifications/challenge-start-health") {
                header("X-Cron-Secret", validCronSecret)
            }

            assertEquals(HttpStatusCode.OK, resp.status)
        }
}
