package com.revio.server.routes

import com.revio.server.features.announcement.dto.AnnouncementAckRequest
import com.revio.server.features.announcement.dto.AnnouncementDTO
import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.user.UserDao
import com.revio.server.features.user.dto.CreateUserRequest
import com.revio.server.features.user.dto.CreateUserResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.TestDatabaseFactory
import testutils.TestEnv
import testutils.UserTestSeed
import testutils.setTestEnv
import testutils.stopKoinSafely
import testutils.testAnnouncementModule
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnnouncementRoutesTest {
    private val jwt = JwtService(
        jwtSecret = TestEnv.JWT_SECRET,
        jwtIssuer = TestEnv.JWT_ISSUER,
        jwtAudience = TestEnv.JWT_AUDIENCE,
    )

    @BeforeAll
    fun setup() {
        setTestEnv()
        TestDatabaseFactory.start()
    }

    @AfterAll
    fun tearDown() {
        TestDatabaseFactory.stop()
    }

    @BeforeEach
    fun clean() {
        TestDatabaseFactory.cleanDatabase()
        stopKoinSafely()
    }

    private fun announcementTest(block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit) =
        testApplication {
            application { testAnnouncementModule() }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
            block(client)
        }

    private suspend fun onboardingToken(credentialId: UUID, email: String): String {
        val (session, _) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = credentialId,
            scope = SessionScope.ONBOARDING,
            userId = null,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        return jwt.generateAccessToken(session, credentialId, email, null)
    }

    private suspend fun profileToken(credentialId: UUID, userId: UUID, email: String): String {
        val (session, _) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = credentialId,
            scope = SessionScope.FULL,
            userId = userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        return jwt.generateAccessToken(session, credentialId, email, userId)
    }

    private suspend fun createUser(
        client: io.ktor.client.HttpClient,
        email: String,
        username: String,
    ): CreateUserResponse {
        val credential = UserTestSeed.seedAuthCredential(email)
        val token = onboardingToken(credential.authCredentialId, credential.email)
        val response = client.post("/api/users") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                CreateUserRequest(
                    fullName = "Test User",
                    birthDate = java.time.LocalDate.of(1995, 1, 1),
                    username = username,
                    country = "RO",
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }

    // ---- GET /api/users/me/announcements ----

    @Test
    fun `GET users me announcements returns 200 with the WELCOME and BONUS rows for a freshly created early spotter`() =
        announcementTest { client ->
            val created = createUser(client, "early@example.com", "earlyspotter")

            val response = client.get("/api/users/me/announcements") {
                bearerAuth(created.accessToken)
            }

            assertEquals(HttpStatusCode.OK, response.status)
            val body: List<AnnouncementDTO> = response.body()
            assertEquals(2, body.size)

            val welcome = body.first { it.key == "EARLY_SPOTTER_WELCOME" }
            assertEquals("PENDING", welcome.status)
            assertNotNull(welcome.payload)
            assertTrue(welcome.payload!!.contains("\"earlySpotterNumber\":1"))

            val bonus = body.first { it.key == "EARLY_SPOTTER_BONUS" }
            assertEquals("PENDING", bonus.status)
            assertNotNull(bonus.payload)
            assertTrue(bonus.payload!!.contains("\"points\":300"))
        }

    @Test
    fun `GET users me announcements returns 401 without JWT`() = announcementTest { client ->
        val response = client.get("/api/users/me/announcements")
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // ---- POST /api/users/me/announcements/ack ----

    @Test
    fun `POST users me announcements ack returns 200 both times and the key stops appearing in getPending after the first ack`() =
        announcementTest { client ->
            val created = createUser(client, "acker@example.com", "ackeruser")

            val firstAck = client.post("/api/users/me/announcements/ack") {
                bearerAuth(created.accessToken)
                contentType(ContentType.Application.Json)
                setBody(AnnouncementAckRequest(key = "EARLY_SPOTTER_WELCOME"))
            }
            assertEquals(HttpStatusCode.OK, firstAck.status)

            val secondAck = client.post("/api/users/me/announcements/ack") {
                bearerAuth(created.accessToken)
                contentType(ContentType.Application.Json)
                setBody(AnnouncementAckRequest(key = "EARLY_SPOTTER_WELCOME"))
            }
            assertEquals(HttpStatusCode.OK, secondAck.status)

            val pending: List<AnnouncementDTO> = client.get("/api/users/me/announcements") {
                bearerAuth(created.accessToken)
            }.body()
            assertTrue(pending.none { it.key == "EARLY_SPOTTER_WELCOME" })
            assertTrue(pending.any { it.key == "EARLY_SPOTTER_BONUS" })
        }

    // ---- POST /api/users: isEarlySpotter/pendingAnnouncements at counter == 0 and counter == 1000 ----

    @Test
    fun `POST users for the first user in a clean DB returns isEarlySpotter true with both pending announcement keys`() =
        announcementTest { client ->
            val created = createUser(client, "first@example.com", "firstuser")

            assertTrue(created.isEarlySpotter)
            assertEquals(1, created.earlySpotterNumber)
            assertEquals(300, created.earlySpotterBonusPoints)
            assertEquals(
                setOf("EARLY_SPOTTER_WELCOME", "EARLY_SPOTTER_BONUS"),
                created.pendingAnnouncements.toSet(),
            )
        }

    @Test
    fun `POST users after the 1000 early spotter slots are exhausted returns isEarlySpotter false with no pending announcements`() =
        announcementTest { client ->
            val dao = UserDao()
            repeat(1000) { i ->
                val cred = UserTestSeed.seedAuthCredential("slot$i@example.com")
                dao.createUser(UserTestSeed.buildUser(cred.authCredentialId, username = "slot$i"))
            }

            val created = createUser(client, "late@example.com", "lateuser")

            assertEquals(false, created.isEarlySpotter)
            assertNull(created.earlySpotterNumber)
            assertNull(created.earlySpotterBonusPoints)
            assertTrue(created.pendingAnnouncements.isEmpty())
        }
}
