package routes

import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.SessionScope
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.car_family.AssignCarModelsRequest
import com.revio.server.features.car_family.CarFamilyAdminDTO
import com.revio.server.features.car_family.CarFamilyTable
import com.revio.server.features.car_family.CreateCarFamilyRequest
import com.revio.server.features.car_model.CarModelTable
import com.revio.server.features.car_model.dto.CarModelOption
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.transactions.transaction
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import testutils.CommentTestSeed
import testutils.TestDatabaseFactory
import testutils.TestEnv
import testutils.setTestEnv
import testutils.stopKoinSafely
import testutils.testChallengeAdminModule
import java.util.UUID

/**
 * HTTP contract of POST /api/admin/car-families/{id}/models (plan §9-E4). DAO-level
 * all-or-nothing correctness is covered by dao.CarModelAssignFamilyTest; these tests are about
 * the route's request/response contract and status-code mapping.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CarFamilyAdminRoutesTest {

    private val jwt = JwtService(
        jwtSecret = TestEnv.JWT_SECRET,
        jwtIssuer = TestEnv.JWT_ISSUER,
        jwtAudience = TestEnv.JWT_AUDIENCE,
    )
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    @BeforeAll
    fun setup() {
        setTestEnv()
        TestDatabaseFactory.start()
    }

    @AfterAll
    fun tearDown() = TestDatabaseFactory.stop()

    @BeforeEach
    fun clean() {
        TestDatabaseFactory.cleanDatabase()
        stopKoinSafely()
    }

    private fun adminTest(block: suspend ApplicationTestBuilder.(HttpClient, String) -> Unit) = testApplication {
        application { testChallengeAdminModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        block(client, adminToken())
    }

    private suspend fun adminToken(): String {
        val seeded = CommentTestSeed.seedUser(username = "moderator")
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = seeded.authId,
            scope = SessionScope.FULL,
            userId = seeded.userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        return jwt.generateAccessToken(session, seeded.authId, seeded.email, seeded.userId, isAdmin = true)
    }

    private suspend fun HttpClient.createFamily(token: String, brand: String = "volkswagen", name: String = "Golf"): CarFamilyAdminDTO {
        val response = post("/api/admin/car-families") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(CreateCarFamilyRequest.serializer(), CreateCarFamilyRequest(brand, name)))
        }
        return response.body()
    }

    private fun seedModel(brand: String, model: String, familyId: UUID? = null): UUID = transaction {
        CarModelTable.insert {
            it[CarModelTable.brand] = brand
            it[CarModelTable.model] = model
            it[CarModelTable.familyId] = familyId
        }[CarModelTable.id].value
    }

    private suspend fun HttpClient.assign(token: String, familyId: UUID, modelIds: List<UUID>) =
        post("/api/admin/car-families/$familyId/models") {
            header(HttpHeaders.Authorization, "Bearer $token")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(AssignCarModelsRequest.serializer(), AssignCarModelsRequest(modelIds)))
        }

    @Test
    fun `assign models links the requested models and returns the family's models`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val golfR = seedModel("volkswagen", "golf r")
        val golfVariant = seedModel("volkswagen", "golf variant")

        val response = client.assign(token, family.id, listOf(golfR, golfVariant))

        assertEquals(HttpStatusCode.OK, response.status)
        val models = response.body<List<CarModelOption>>()
        assertEquals(setOf(golfR, golfVariant), models.map { it.id }.toSet())
    }

    @Test
    fun `assign models re-run with the same ids is idempotent`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val golfR = seedModel("volkswagen", "golf r")

        client.assign(token, family.id, listOf(golfR))
        val second = client.assign(token, family.id, listOf(golfR))

        assertEquals(HttpStatusCode.OK, second.status)
        val models = second.body<List<CarModelOption>>()
        assertEquals(listOf(golfR), models.map { it.id })
    }

    @Test
    fun `assign models returns 404 for an unknown family`() = adminTest { client, token ->
        val golfR = seedModel("volkswagen", "golf r")

        val response = client.assign(token, UUID.randomUUID(), listOf(golfR))

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `assign models returns 400 for a nonexistent model id and assigns nothing`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val golfR = seedModel("volkswagen", "golf r")

        val response = client.assign(token, family.id, listOf(golfR, UUID.randomUUID()))

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(transaction {
            CarModelTable.select(CarModelTable.familyId)
                .where { CarModelTable.id eq golfR }
                .single()[CarModelTable.familyId] == null
        })
    }

    @Test
    fun `assign models returns 400 for a brand mismatch`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val bmwM3 = seedModel("bmw", "m3")

        val response = client.assign(token, family.id, listOf(bmwM3))

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `assign models returns 409 when a model already belongs to a different family`() = adminTest { client, token ->
        val golfFamily = client.createFamily(token, name = "Golf")
        val idFamily = client.createFamily(token, name = "ID")
        val idThree = seedModel("volkswagen", "id.3", familyId = idFamily.id)

        val response = client.assign(token, golfFamily.id, listOf(idThree))

        assertEquals(HttpStatusCode.Conflict, response.status)
    }

    @Test
    fun `assign models rejects an empty id list with 400`() = adminTest { client, token ->
        val family = client.createFamily(token)

        val response = client.assign(token, family.id, emptyList())

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    // ---------- GET /admin/car-families/{id}/models ----------

    private suspend fun HttpClient.getModels(token: String, familyId: UUID) =
        get("/api/admin/car-families/$familyId/models") { header(HttpHeaders.Authorization, "Bearer $token") }

    @Test
    fun `GET family models returns every model linked to the family, ordered by name`() = adminTest { client, token ->
        val family = client.createFamily(token)
        val golfVariant = seedModel("volkswagen", "golf variant", familyId = family.id)
        val golf = seedModel("volkswagen", "golf", familyId = family.id)
        val golfPlus = seedModel("volkswagen", "golf plus", familyId = family.id)

        val response = client.getModels(token, family.id)

        assertEquals(HttpStatusCode.OK, response.status)
        val models = response.body<List<CarModelOption>>()
        assertEquals(listOf(golf, golfPlus, golfVariant), models.map { it.id })
    }

    @Test
    fun `GET family models excludes models belonging to a different family`() = adminTest { client, token ->
        val golfFamily = client.createFamily(token, name = "Golf")
        val idFamily = client.createFamily(token, name = "ID")
        val golfR = seedModel("volkswagen", "golf r", familyId = golfFamily.id)
        seedModel("volkswagen", "id.3", familyId = idFamily.id)

        val response = client.getModels(token, golfFamily.id)

        assertEquals(HttpStatusCode.OK, response.status)
        val models = response.body<List<CarModelOption>>()
        assertEquals(listOf(golfR), models.map { it.id })
    }

    @Test
    fun `GET family models returns an empty list for a family with no models`() = adminTest { client, token ->
        val family = client.createFamily(token)

        val response = client.getModels(token, family.id)

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(emptyList<UUID>(), response.body<List<CarModelOption>>().map { it.id })
    }

    @Test
    fun `GET family models returns 404 for an unknown family`() = adminTest { client, token ->
        val response = client.getModels(token, UUID.randomUUID())

        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `GET family models returns 400 for a malformed id`() = adminTest { client, token ->
        val response = client.get("/api/admin/car-families/not-a-uuid/models") { header(HttpHeaders.Authorization, "Bearer $token") }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun `GET family models rejects a non-admin token with 403`() = testApplication {
        application { testChallengeAdminModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }
        val seeded = CommentTestSeed.seedUser(username = "plainuser")
        val (session) = SessionService(AuthSessionDAO(), RefreshTokenGenerator()).createSession(
            credentialId = seeded.authId,
            scope = SessionScope.FULL,
            userId = seeded.userId,
            deviceId = null,
            deviceName = null,
            userAgent = null,
            ip = null,
        )
        val userToken = jwt.generateAccessToken(session, seeded.authId, seeded.email, seeded.userId)

        val response = client.getModels(userToken, UUID.randomUUID())

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }

    @Test
    fun `GET family models rejects a request with no token`() = testApplication {
        application { testChallengeAdminModule() }
        val client = createClient { install(ContentNegotiation) { json(json) } }

        val response = client.get("/api/admin/car-families/${UUID.randomUUID()}/models")

        assertEquals(HttpStatusCode.Forbidden, response.status)
    }
}
