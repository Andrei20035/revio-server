package com.revio.server.routes

import com.revio.server.core.storage.IStorageService
import com.revio.server.core.storage.StoredImage
import com.revio.server.features.account_deletion.AccountDeletionFeedbackTable
import com.revio.server.features.auth.AuthDAO
import com.revio.server.features.auth.AuthProvider
import com.revio.server.features.auth.AuthTable
import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.GoogleTokenVerifier
import com.revio.server.features.auth.GoogleUser
import com.revio.server.features.auth.dto.AuthResponse
import com.revio.server.features.auth.dto.DeleteAccountRequest
import com.revio.server.features.auth.dto.DeletionContextDTO
import com.revio.server.features.auth.dto.LoginRequest
import com.revio.server.features.auth.dto.OnboardingStep
import com.revio.server.features.auth.dto.RefreshRequest
import com.revio.server.features.auth.dto.RegisterRequest
import com.revio.server.features.auth.dto.SessionDTO
import com.revio.server.features.auth.dto.UpdatePasswordRequest
import com.revio.server.features.auth.dto.WaitlistUsernameStatus
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.auth.session.SessionStatus
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserTable
import com.revio.server.features.user_car.UserCarTable
import com.revio.server.features.waitlist.IWaitlistLookupService
import com.revio.server.features.waitlist.WaitlistEntry
import com.revio.server.core.error.AuthErrorCode
import com.revio.server.core.error.AuthErrorResponse
import io.ktor.client.call.*
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.testing.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import io.mockk.coEvery
import io.mockk.mockk
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
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
import testutils.testAuthModule
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthRoutesTest {

    /** Fake configurabil pentru testele Google login. */
    private class FakeGoogleVerifier : GoogleTokenVerifier {
        var response: GoogleUser? = null
        override fun verify(googleIdToken: String): GoogleUser? = response
    }

    /** Fake pentru testele de cleanup storage la ștergerea contului. */
    private class FakeStorageService(private val failing: Boolean = false) : IStorageService {
        val deletedKeys = mutableListOf<String>()

        override suspend fun uploadImage(bytes: ByteArray, objectKey: String, contentType: String): StoredImage {
            throw NotImplementedError("not used by these tests")
        }

        override suspend fun deleteImage(objectKey: String) {
            if (failing) throw RuntimeException("storage unavailable")
            deletedKeys += objectKey
        }

        override fun normalizeObjectKey(pathOrUrl: String): String =
            pathOrUrl.removePrefix("http://fake-storage/")

        override fun resolveUrl(objectKey: String): String = "http://fake-storage/$objectKey"
    }

    private val googleVerifier = FakeGoogleVerifier()
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
        googleVerifier.response = null
        stopKoinSafely()
    }

    // ---- helper: rulează un test in-app cu DB real + verifier fake ----
    private fun authTest(
        storage: IStorageService? = null,
        waitlistLookupService: IWaitlistLookupService = mockk(relaxed = true),
        block: suspend ApplicationTestBuilder.(io.ktor.client.HttpClient) -> Unit,
    ) =
        testApplication {
            application {
                testAuthModule(googleVerifier, storage, waitlistLookupService)
            }
            val client = createClient {
                install(ContentNegotiation) {
                    json(Json { ignoreUnknownKeys = true; isLenient = true })
                }
            }
            block(client)
        }

    // ---- REGISTER ----

    @Test
    fun `POST auth register REGULAR valid returns 201 with token pair and ONBOARDING session`() = authTest { client ->
        val resp = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    email = "alice@example.com",
                    password = "Passw0rd!",
                    provider = AuthProvider.REGULAR
                )
            )
        }
        assertEquals(HttpStatusCode.Created, resp.status)
        val body = resp.body<AuthResponse>()
        assertTrue(body.accessToken.isNotBlank())
        assertTrue(body.refreshToken.isNotBlank())
        assertEquals(900, body.expiresIn)
        assertEquals(SessionScope.ONBOARDING.name, body.scope)
        assertEquals(OnboardingStep.PROFILE_REQUIRED, body.onboardingStep)

        val sessions = AuthSessionDAO().listActiveSessions(
            AuthDAO().getCredentialsForLogin("alice@example.com")!!.id!!
        )
        assertEquals(1, sessions.size)
        assertEquals(SessionStatus.ACTIVE, sessions.single().status)
        assertEquals(SessionScope.ONBOARDING, sessions.single().scope)
    }

    @Test
    fun `register with invalid email returns 400`() = authTest { client ->
        val resp = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    email = "not-an-email",
                    password = "Passw0rd!",
                    provider = AuthProvider.REGULAR
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `register with missing password returns 400`() = authTest { client ->
        val resp = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    email = "alice@example.com",
                    password = null,
                    provider = AuthProvider.REGULAR
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `register with short password returns 400`() = authTest { client ->
        val resp = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    email = "alice@example.com",
                    password = "short",
                    provider = AuthProvider.REGULAR
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    @Test
    fun `register rejects passwords missing any required character class`() = authTest { client ->
        val weakPasswords = listOf(
            "lowercase1!",
            "UPPERCASE1!",
            "NoDigits!!",
            "NoSpecial1"
        )

        weakPasswords.forEachIndexed { index, password ->
            val response = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(
                    RegisterRequest(
                        email = "weak-$index@example.com",
                        password = password,
                        provider = AuthProvider.REGULAR
                    )
                )
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertEquals(AuthErrorCode.WEAK_PASSWORD, response.body<AuthErrorResponse>().error.code)
        }
    }

    @Test
    fun `register with duplicate email returns 409 EMAIL_TAKEN`() = authTest { client ->
        // first, OK
        val first = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("dup@example.com", "Passw0rd!", AuthProvider.REGULAR))
        }
        assertEquals(HttpStatusCode.Created, first.status)

        val second = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("dup@example.com", "Passw0rd!", AuthProvider.REGULAR))
        }
        assertEquals(HttpStatusCode.Conflict, second.status)
        assertEquals(AuthErrorCode.EMAIL_TAKEN, second.body<AuthErrorResponse>().error.code)
    }

    @Test
    fun `after register password is stored hashed not plain text`() = runTest {
        authTest { client ->
            val resp = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("alice@example.com", "Passw0rd!", AuthProvider.REGULAR))
            }
            assertEquals(HttpStatusCode.Created, resp.status)

            val dao = AuthDAO()
            val stored = dao.getCredentialsForLogin("alice@example.com")
            assertNotNull(stored)
            assertNotEquals("Passw0rd!", stored!!.password)
            assertTrue(stored.password!!.startsWith("$2"), "expected BCrypt hash")
        }
    }

    // ---- REGISTER: waitlist prefill ----

    private fun fakeWaitlistEntry(username: String?) = WaitlistEntry(
        id = UUID.randomUUID(),
        email = "waitlisted@example.com",
        emailNormalized = "waitlisted@example.com",
        username = username,
        platform = "ios",
        country = "RO",
        sourceCreatedAt = OffsetDateTime.now(ZoneOffset.UTC),
        sourceUpdatedAt = OffsetDateTime.now(ZoneOffset.UTC),
        syncedAt = OffsetDateTime.now(ZoneOffset.UTC),
    )

    @Test
    fun `register with an email found in the waitlist returns waitlist suggestedUsername`() = runTest {
        val waitlistLookupService = mockk<IWaitlistLookupService>(relaxed = true)
        coEvery { waitlistLookupService.lookup("waitlisted@example.com") } returns fakeWaitlistEntry("coolname")

        authTest(waitlistLookupService = waitlistLookupService) { client ->
            val resp = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("waitlisted@example.com", "Passw0rd!", AuthProvider.REGULAR))
            }

            assertEquals(HttpStatusCode.Created, resp.status)
            val body = resp.body<AuthResponse>()
            assertNotNull(body.waitlist)
            assertEquals("coolname", body.waitlist!!.suggestedUsername)
            assertEquals(WaitlistUsernameStatus.AVAILABLE, body.waitlist!!.suggestedUsernameStatus)
        }
    }

    @Test
    fun `register with an email not in the waitlist returns waitlist null`() = runTest {
        val waitlistLookupService = mockk<IWaitlistLookupService>(relaxed = true)
        coEvery { waitlistLookupService.lookup("unknown@example.com") } returns null

        authTest(waitlistLookupService = waitlistLookupService) { client ->
            val resp = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("unknown@example.com", "Passw0rd!", AuthProvider.REGULAR))
            }

            assertEquals(HttpStatusCode.Created, resp.status)
            val body = resp.body<AuthResponse>()
            assertNull(body.waitlist)
        }
    }

    @Test
    fun `register with a waitlist username already taken by another user reports TAKEN and 409, not 500`() = runTest {
        val waitlistLookupService = mockk<IWaitlistLookupService>(relaxed = true)
        coEvery { waitlistLookupService.lookup("waitlisted@example.com") } returns fakeWaitlistEntry("existinguser")

        authTest(waitlistLookupService = waitlistLookupService) { client ->
            // Seed an existing user with the username the waitlist will suggest.
            val takenResp = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("first@example.com", "Passw0rd!", AuthProvider.REGULAR))
            }
            assertEquals(HttpStatusCode.Created, takenResp.status)
            val takenBody = takenResp.body<AuthResponse>()
            client.post("/api/users") {
                header(HttpHeaders.Authorization, "Bearer ${takenBody.accessToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"fullName":"Existing User","birthDate":"1995-01-01","username":"existinguser","country":"RO"}"""
                )
            }

            // Register the waitlisted email — the suggestion collides with the user just created.
            val resp = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("waitlisted@example.com", "Passw0rd!", AuthProvider.REGULAR))
            }
            assertEquals(HttpStatusCode.Created, resp.status)
            val body = resp.body<AuthResponse>()
            assertNotNull(body.waitlist)
            assertEquals(WaitlistUsernameStatus.TAKEN, body.waitlist!!.suggestedUsernameStatus)

            val createResp = client.post("/api/users") {
                header(HttpHeaders.Authorization, "Bearer ${body.accessToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"fullName":"Waitlisted User","birthDate":"1995-01-01","username":"existinguser","country":"RO"}"""
                )
            }
            assertEquals(HttpStatusCode.Conflict, createResp.status)
        }
    }

    @Test
    fun `register with a waitlist username violating the format rules reports INVALID_FORMAT and 400 at creation`() = runTest {
        val waitlistLookupService = mockk<IWaitlistLookupService>(relaxed = true)
        coEvery { waitlistLookupService.lookup("waitlisted@example.com") } returns fakeWaitlistEntry("Bad Name!")

        authTest(waitlistLookupService = waitlistLookupService) { client ->
            val resp = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("waitlisted@example.com", "Passw0rd!", AuthProvider.REGULAR))
            }
            assertEquals(HttpStatusCode.Created, resp.status)
            val body = resp.body<AuthResponse>()
            assertNotNull(body.waitlist)
            assertEquals(WaitlistUsernameStatus.INVALID_FORMAT, body.waitlist!!.suggestedUsernameStatus)

            val createResp = client.post("/api/users") {
                header(HttpHeaders.Authorization, "Bearer ${body.accessToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"fullName":"Waitlisted User","birthDate":"1995-01-01","username":"Bad Name!","country":"RO"}"""
                )
            }
            assertEquals(HttpStatusCode.BadRequest, createResp.status)
        }
    }

    @Test
    fun `two users registering with the same waitlist-suggested username - first succeeds, second gets 409`() = runTest {
        val waitlistLookupService = mockk<IWaitlistLookupService>(relaxed = true)
        coEvery { waitlistLookupService.lookup(any()) } returns fakeWaitlistEntry("popularname")

        authTest(waitlistLookupService = waitlistLookupService) { client ->
            val firstAuth = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("first-user@example.com", "Passw0rd!", AuthProvider.REGULAR))
            }.body<AuthResponse>()
            val secondAuth = client.post("/api/auth/register") {
                contentType(ContentType.Application.Json)
                setBody(RegisterRequest("second-user@example.com", "Passw0rd!", AuthProvider.REGULAR))
            }.body<AuthResponse>()

            val firstCreate = client.post("/api/users") {
                header(HttpHeaders.Authorization, "Bearer ${firstAuth.accessToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"fullName":"First User","birthDate":"1995-01-01","username":"popularname","country":"RO"}"""
                )
            }
            val secondCreate = client.post("/api/users") {
                header(HttpHeaders.Authorization, "Bearer ${secondAuth.accessToken}")
                contentType(ContentType.Application.Json)
                setBody(
                    """{"fullName":"Second User","birthDate":"1995-01-01","username":"popularname","country":"RO"}"""
                )
            }

            assertEquals(HttpStatusCode.Created, firstCreate.status)
            assertEquals(HttpStatusCode.Conflict, secondCreate.status)
        }
    }

    // ---- LOGIN ----

    @Test
    fun `POST auth login REGULAR valid returns 200 with token pair`() = authTest { client ->
        val registerResponse = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("alice@example.com", "Passw0rd!", AuthProvider.REGULAR))
        }
        val firstRefreshToken = registerResponse.body<AuthResponse>().refreshToken

        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = "alice@example.com", password = "Passw0rd!", provider = AuthProvider.REGULAR))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<AuthResponse>()
        assertTrue(body.accessToken.isNotBlank())
        assertTrue(body.refreshToken.isNotBlank())
        assertEquals(900, body.expiresIn)
        assertEquals(SessionScope.ONBOARDING.name, body.scope)
        assertEquals(OnboardingStep.PROFILE_REQUIRED, body.onboardingStep)

        val previousSession = AuthSessionDAO().findByRefreshHash(
            RefreshTokenGenerator().hashOf(firstRefreshToken)
        )
        assertEquals(SessionStatus.REVOKED, previousSession?.status)
    }

    @Test
    fun `login with unknown email returns 401`() = authTest { client ->
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = "nobody@example.com", password = "Passw0rd!", provider = AuthProvider.REGULAR))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `login with wrong password returns 401`() = authTest { client ->
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("alice@example.com", "Passw0rd!", AuthProvider.REGULAR))
        }

        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = "alice@example.com", password = "WrongPass!", provider = AuthProvider.REGULAR))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `login with uppercase and spaces in email is normalized`() = authTest { client ->
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("alice@example.com", "Passw0rd!", AuthProvider.REGULAR))
        }

        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = "  ALICE@Example.COM  ", password = "Passw0rd!", provider = AuthProvider.REGULAR))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
    }

    @Test
    fun `google login with valid token returns 200`() = authTest { client ->
        googleVerifier.response = GoogleUser(email = "bob@example.com", googleId = "gid-1")

        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(googleIdToken = "any-fake-token", provider = AuthProvider.GOOGLE))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<AuthResponse>()
        assertTrue(body.accessToken.isNotBlank())
        assertTrue(body.refreshToken.isNotBlank())
    }

    @Test
    fun `google login with invalid token returns 401`() = authTest { client ->
        googleVerifier.response = null

        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(googleIdToken = "bad-token", provider = AuthProvider.GOOGLE))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    @Test
    fun `google login for regular account returns 409 PROVIDER_MISMATCH`() = authTest { client ->
        client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(RegisterRequest("alice@example.com", "Passw0rd!", AuthProvider.REGULAR))
        }
        googleVerifier.response = GoogleUser(email = "alice@example.com", googleId = "gid-regular-email")

        val response = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(googleIdToken = "valid-token", provider = AuthProvider.GOOGLE))
        }

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(AuthErrorCode.PROVIDER_MISMATCH, response.body<AuthErrorResponse>().error.code)
    }

    @Test
    fun `google login missing token returns 400`() = authTest { client ->
        val resp = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(googleIdToken = null, provider = AuthProvider.GOOGLE))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
    }

    // ---- SESSION MANAGEMENT ----

    @Test
    fun `refresh rotates token pair and consumes previous refresh token`() = authTest { client ->
        val registered = registerAndGetAuthResponse(client)

        val refreshed = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(registered.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, refreshed.status)
        val refreshedBody = refreshed.body<AuthResponse>()
        assertNotEquals(registered.accessToken, refreshedBody.accessToken)
        assertNotEquals(registered.refreshToken, refreshedBody.refreshToken)

        val consumed = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(registered.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, consumed.status)
        assertEquals(
            AuthErrorCode.REFRESH_TOKEN_CONSUMED,
            consumed.body<AuthErrorResponse>().error.code
        )

        val currentStillWorks = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(refreshedBody.refreshToken))
        }
        assertEquals(HttpStatusCode.OK, currentStillWorks.status)
    }

    @Test
    fun `logout revokes session and refresh token is rejected`() = authTest { client ->
        val registered = registerAndGetAuthResponse(client)

        val logout = client.post("/api/auth/logout") {
            bearerAuth(registered.accessToken)
        }
        assertEquals(HttpStatusCode.NoContent, logout.status)

        val refresh = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(registered.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, refresh.status)
        assertEquals(AuthErrorCode.SESSION_REVOKED, refresh.body<AuthErrorResponse>().error.code)
    }

    @Test
    fun `sessions returns active current session with device metadata`() = authTest { client ->
        val registered = registerAndGetAuthResponse(
            client,
            deviceId = "install-123",
            deviceName = "Pixel Test"
        )

        val response = client.get("/api/auth/sessions") {
            bearerAuth(registered.accessToken)
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val sessions = response.body<List<SessionDTO>>()
        assertEquals(1, sessions.size)
        assertTrue(sessions.single().current)
        assertEquals("install-123", sessions.single().deviceId)
        assertEquals("Pixel Test", sessions.single().deviceName)
    }

    @Test
    fun `logout-all revokes active session`() = authTest { client ->
        val registered = registerAndGetAuthResponse(client)

        val logoutAll = client.post("/api/auth/logout-all") {
            bearerAuth(registered.accessToken)
        }
        assertEquals(HttpStatusCode.NoContent, logoutAll.status)

        val credentialId = AuthDAO().getCredentialsForLogin("alice@example.com")!!.id!!
        assertTrue(AuthSessionDAO().listActiveSessions(credentialId).isEmpty())
    }

    // ---- PUT /auth/password ----

    @Test
    fun `update password with valid token and correct old password returns 200`() = authTest { client ->
        val registered = registerAndGetAuthResponse(client, password = "OldPass!1")

        val resp = client.put("/api/auth/password") {
            contentType(ContentType.Application.Json)
            bearerAuth(registered.accessToken)
            setBody(UpdatePasswordRequest(oldPassword = "OldPass!1", newPassword = "NewPass!2"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val rotated = resp.body<AuthResponse>()
        assertNotEquals(registered.accessToken, rotated.accessToken)
        assertNotEquals(registered.refreshToken, rotated.refreshToken)

        val oldAccess = client.get("/api/auth/sessions") {
            bearerAuth(registered.accessToken)
        }
        assertEquals(HttpStatusCode.Unauthorized, oldAccess.status)
        assertEquals(AuthErrorCode.SESSION_REVOKED, oldAccess.body<AuthErrorResponse>().error.code)

        val oldRefresh = client.post("/api/auth/refresh") {
            contentType(ContentType.Application.Json)
            setBody(RefreshRequest(registered.refreshToken))
        }
        assertEquals(HttpStatusCode.Unauthorized, oldRefresh.status)

        // old password should no longer work
        val oldLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = "alice@example.com", password = "OldPass!1", provider = AuthProvider.REGULAR))
        }
        assertEquals(HttpStatusCode.Unauthorized, oldLogin.status)

        // new password should work
        val newLogin = client.post("/api/auth/login") {
            contentType(ContentType.Application.Json)
            setBody(LoginRequest(email = "alice@example.com", password = "NewPass!2", provider = AuthProvider.REGULAR))
        }
        assertEquals(HttpStatusCode.OK, newLogin.status)
    }

    @Test
    fun `update password without token returns 401`() = authTest { client ->
        val resp = client.put("/api/auth/password") {
            contentType(ContentType.Application.Json)
            setBody(UpdatePasswordRequest(oldPassword = "x", newPassword = "NewPass!2"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ---- DELETE /auth/account ----

    @Test
    fun `delete account REGULAR with correct password returns 200, removes credential, cascades, and stores anonymous feedback`() =
        authTest { client ->
            val (credentialId, userId, token) = seedRegularAccountWithToken()

            val resp = client.delete("/api/auth/account") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(DeleteAccountRequest(password = "Passw0rd!", reason = "TAKING_A_BREAK"))
            }
            assertEquals(HttpStatusCode.OK, resp.status)

            assertNull(AuthDAO().getCredentialsById(credentialId), "credential should have been deleted")
            assertTrue(!userExists(userId), "user row should have cascaded away")

            val feedback = feedbackRows()
            assertEquals(1, feedback.size)
            assertEquals("TAKING_A_BREAK", feedback.single().first)

            val afterDelete = client.get("/api/auth/sessions") {
                bearerAuth(token)
            }
            assertEquals(HttpStatusCode.Unauthorized, afterDelete.status)
            assertEquals(AuthErrorCode.SESSION_REVOKED, afterDelete.body<AuthErrorResponse>().error.code)
        }

    @Test
    fun `delete account REGULAR with wrong password returns 401 and account still exists`() = authTest { client ->
        val (credentialId, _, token) = seedRegularAccountWithToken()

        val resp = client.delete("/api/auth/account") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(DeleteAccountRequest(password = "WrongPass!", reason = "OTHER"))
        }
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
        assertEquals(AuthErrorCode.INVALID_CURRENT_PASSWORD, resp.body<AuthErrorResponse>().error.code)

        assertNotNull(AuthDAO().getCredentialsById(credentialId), "account should still exist")
    }

    @Test
    fun `delete account without request body returns 400 VALIDATION_ERROR`() = authTest { client ->
        val (_, _, token) = seedRegularAccountWithToken()

        val resp = client.delete("/api/auth/account") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(AuthErrorCode.VALIDATION_ERROR, resp.body<AuthErrorResponse>().error.code)
    }

    @Test
    fun `delete account REGULAR with blank password returns 400 VALIDATION_ERROR`() = authTest { client ->
        val (_, _, token) = seedRegularAccountWithToken()

        val resp = client.delete("/api/auth/account") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(DeleteAccountRequest(password = ""))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(AuthErrorCode.VALIDATION_ERROR, resp.body<AuthErrorResponse>().error.code)
    }

    @Test
    fun `delete account GOOGLE with matching username returns 200 (case and whitespace insensitive)`() =
        authTest { client ->
            val (credentialId, _, token) = seedGoogleAccountWithToken(username = "bob")

            val resp = client.delete("/api/auth/account") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(DeleteAccountRequest(usernameConfirmation = "  BOB  "))
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertNull(AuthDAO().getCredentialsById(credentialId))
        }

    @Test
    fun `delete account GOOGLE with wrong username returns 400 USERNAME_CONFIRMATION_MISMATCH`() = authTest { client ->
        val (credentialId, _, token) = seedGoogleAccountWithToken(username = "bob")

        val resp = client.delete("/api/auth/account") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(DeleteAccountRequest(usernameConfirmation = "not-bob"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(AuthErrorCode.USERNAME_CONFIRMATION_MISMATCH, resp.body<AuthErrorResponse>().error.code)
        assertNotNull(AuthDAO().getCredentialsById(credentialId))
    }

    @Test
    fun `delete account GOOGLE with password instead of username returns 400`() = authTest { client ->
        val (_, _, token) = seedGoogleAccountWithToken(username = "bob")

        val resp = client.delete("/api/auth/account") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(DeleteAccountRequest(password = "Passw0rd!"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(AuthErrorCode.VALIDATION_ERROR, resp.body<AuthErrorResponse>().error.code)
    }

    @Test
    fun `delete account with OTHER reason persists details, surviving user deletion`() = authTest { client ->
        val (_, _, token) = seedRegularAccountWithToken()

        val resp = client.delete("/api/auth/account") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(DeleteAccountRequest(password = "Passw0rd!", reason = "OTHER", details = "test feedback"))
        }
        assertEquals(HttpStatusCode.OK, resp.status)

        val feedback = feedbackRows()
        assertEquals(1, feedback.size)
        assertEquals("OTHER" to "test feedback", feedback.single())
    }

    @Test
    fun `delete account with unknown reason returns 400 VALIDATION_ERROR`() = authTest { client ->
        val (credentialId, _, token) = seedRegularAccountWithToken()

        val resp = client.delete("/api/auth/account") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(DeleteAccountRequest(password = "Passw0rd!", reason = "NOT_A_REAL_REASON"))
        }
        assertEquals(HttpStatusCode.BadRequest, resp.status)
        assertEquals(AuthErrorCode.VALIDATION_ERROR, resp.body<AuthErrorResponse>().error.code)
        assertNotNull(AuthDAO().getCredentialsById(credentialId))
    }

    @Test
    fun `delete account deletes collected storage objects for profile picture, posts, and user car`() {
        val fakeStorage = FakeStorageService()
        authTest(storage = fakeStorage) { client ->
            val (_, userId, token) = seedRegularAccountWithToken(profilePicturePath = "avatars/alice.jpg")
            seedPost(userId, "posts/alice-1.jpg")
            seedUserCar(userId, "cars/alice.jpg")

            val resp = client.delete("/api/auth/account") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(DeleteAccountRequest(password = "Passw0rd!"))
            }
            assertEquals(HttpStatusCode.OK, resp.status)

            assertEquals(
                setOf("avatars/alice.jpg", "posts/alice-1.jpg", "cars/alice.jpg"),
                fakeStorage.deletedKeys.toSet(),
            )
        }
    }

    @Test
    fun `delete account still returns 200 when storage deletion fails`() {
        val fakeStorage = FakeStorageService(failing = true)
        authTest(storage = fakeStorage) { client ->
            val (credentialId, _, token) = seedRegularAccountWithToken(profilePicturePath = "avatars/alice.jpg")

            val resp = client.delete("/api/auth/account") {
                bearerAuth(token)
                contentType(ContentType.Application.Json)
                setBody(DeleteAccountRequest(password = "Passw0rd!"))
            }
            assertEquals(HttpStatusCode.OK, resp.status)
            assertNull(AuthDAO().getCredentialsById(credentialId))
        }
    }

    @Test
    fun `delete account without token returns 401`() = authTest { client ->
        val resp = client.delete("/api/auth/account")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ---- GET /auth/account/deletion-context ----

    @Test
    fun `GET deletion-context returns REGULAR provider and stats`() = authTest { client ->
        val (_, _, token) = seedRegularAccountWithToken()

        val resp = client.get("/api/auth/account/deletion-context") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        val body = resp.body<DeletionContextDTO>()
        assertEquals(AuthProvider.REGULAR, body.provider)
        assertEquals(0, body.postCount)
        assertEquals(0, body.likesReceived)
        assertEquals(0, body.streakDays)
        assertTrue(body.accountAgeDays >= 0)
    }

    @Test
    fun `GET deletion-context returns GOOGLE provider`() = authTest { client ->
        val (_, _, token) = seedGoogleAccountWithToken(username = "bob")

        val resp = client.get("/api/auth/account/deletion-context") {
            bearerAuth(token)
        }
        assertEquals(HttpStatusCode.OK, resp.status)
        assertEquals(AuthProvider.GOOGLE, resp.body<DeletionContextDTO>().provider)
    }

    @Test
    fun `GET deletion-context without token returns 401`() = authTest { client ->
        val resp = client.get("/api/auth/account/deletion-context")
        assertEquals(HttpStatusCode.Unauthorized, resp.status)
    }

    // ---- helpers ----

    private suspend fun mintFullToken(credentialId: UUID, userId: UUID, email: String): String {
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

    /** Seed a REGULAR credential + full profile and mint a FULL-scope token for it. */
    private suspend fun seedRegularAccountWithToken(
        email: String = "alice@example.com",
        password: String = "Passw0rd!",
        username: String = "alice",
        profilePicturePath: String? = null,
    ): Triple<UUID, UUID, String> {
        val credential = UserTestSeed.seedAuthCredential(email = email, password = password)
        val userId = UserTestSeed.seedUser(
            authCredentialId = credential.authCredentialId,
            username = username,
            profilePicturePath = profilePicturePath,
        )
        val token = mintFullToken(credential.authCredentialId, userId, email)
        return Triple(credential.authCredentialId, userId, token)
    }

    /** Seed a GOOGLE credential + full profile and mint a FULL-scope token for it. */
    private suspend fun seedGoogleAccountWithToken(
        email: String = "bob@example.com",
        googleId: String = "gid-1",
        username: String = "bob",
    ): Triple<UUID, UUID, String> {
        val credentialId = transaction {
            AuthTable.insert {
                it[AuthTable.email] = email
                it[AuthTable.password] = null
                it[AuthTable.provider] = AuthProvider.GOOGLE.name
                it[AuthTable.googleId] = googleId
            }[AuthTable.id].value
        }
        val userId = UserTestSeed.seedUser(authCredentialId = credentialId, username = username)
        val token = mintFullToken(credentialId, userId, email)
        return Triple(credentialId, userId, token)
    }

    private fun userExists(userId: UUID): Boolean = transaction {
        UserTable.select(UserTable.id).where { UserTable.id eq userId }.any()
    }

    private fun feedbackRows(): List<Pair<String, String?>> = transaction {
        AccountDeletionFeedbackTable.selectAll().map {
            it[AccountDeletionFeedbackTable.reason] to it[AccountDeletionFeedbackTable.details]
        }
    }

    private fun seedPost(userId: UUID, imageKey: String) {
        transaction {
            PostTable.insert {
                it[PostTable.userId] = userId
                it[PostTable.imageKey] = imageKey
                it[PostTable.customBrand] = "CustomBrand"
                it[PostTable.customModel] = "CustomModel"
            }
        }
    }

    private fun seedUserCar(userId: UUID, imageKey: String) {
        transaction {
            UserCarTable.insert {
                it[UserCarTable.userId] = userId
                it[UserCarTable.imageKey] = imageKey
                it[UserCarTable.customBrand] = "CustomBrand"
                it[UserCarTable.customModel] = "CustomModel"
            }
        }
    }

    private suspend fun registerAndGetAuthResponse(
        client: io.ktor.client.HttpClient,
        deviceId: String? = null,
        deviceName: String? = null,
        password: String = "Passw0rd!",
    ): AuthResponse {
        val response = client.post("/api/auth/register") {
            contentType(ContentType.Application.Json)
            setBody(
                RegisterRequest(
                    email = "alice@example.com",
                    password = password,
                    provider = AuthProvider.REGULAR,
                    deviceId = deviceId,
                    deviceName = deviceName,
                )
            )
        }
        assertEquals(HttpStatusCode.Created, response.status)
        return response.body()
    }
}
