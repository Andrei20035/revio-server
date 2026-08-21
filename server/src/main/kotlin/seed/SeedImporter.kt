package com.revio.server.seed

import at.favre.lib.crypto.bcrypt.BCrypt
import com.revio.server.core.storage.LocalImageStorageService
import com.revio.server.features.auth.AuthProvider
import com.revio.server.features.auth.AuthTable
import com.revio.server.features.post.PostTable
import com.revio.server.features.user.UserTable
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insertReturning
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.transactions.transaction
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * Local seed importer for the Revio dev/test database.
 *
 * Reads the seed JSON files plus the compressed images and inserts auth_credentials ->
 * users -> posts, copying the (already client-equivalent compressed) images into
 * the local uploads/ store. Reuses the server's own Exposed tables, BCrypt
 * (cost 12, identical to AuthService) and LocalImageStorageService so the rows
 * are indistinguishable from real app data.
 *
 * Idempotent: deletes any previously-seeded accounts (by email) and their
 * uploaded files first, then re-inserts. Safe to run repeatedly.
 *
 * Run:  ./gradlew seed     (reads server/.env automatically)
 */

// ----------------------------- JSON models ---------------------------------

@Serializable
private data class SeedAuth(
    @SerialName("auth_ref") val authRef: String,
    val email: String,
    val provider: String,
    @SerialName("google_id") val googleId: String? = null,
    @SerialName("password_plain") val passwordPlain: String? = null,
    @SerialName("password_hash") val passwordHash: String? = null,
)

@Serializable
private data class SeedUser(
    @SerialName("user_ref") val userRef: String,
    @SerialName("auth_ref") val authRef: String,
    @SerialName("full_name") val fullName: String,
    val username: String,
    val country: String,
    @SerialName("birth_date") val birthDate: String,
    @SerialName("phone_number") val phoneNumber: String? = null,
    @SerialName("profile_picture_path") val profilePicturePath: String? = null,
    @SerialName("spot_score") val spotScore: Int = 0,
)

@Serializable
private data class SeedPost(
    @SerialName("post_ref") val postRef: String,
    @SerialName("user_ref") val userRef: String,
    @SerialName("image_path") val imagePath: String,
    @SerialName("car_model_id") val carModelId: String? = null,
    @SerialName("custom_brand") val customBrand: String? = null,
    @SerialName("custom_model") val customModel: String? = null,
    val description: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val town: String? = null,
    val country: String? = null,
    @SerialName("created_at") val createdAt: String,
)

// ----------------------------- entry point ---------------------------------

private val json = Json { ignoreUnknownKeys = true }

fun main() {
    val env = DotEnv(Paths.get(".env"))
    val seedDir = Paths.get("seed")
    val compressedDir = seedDir.resolve("compressed")

    val storageBaseDir = Paths.get(env["LOCAL_STORAGE_BASE_DIR"] ?: "uploads")
    val publicBaseUrl = env["PUBLIC_BASE_URL"] ?: "http://localhost:8080"
    Files.createDirectories(storageBaseDir)
    val storage = LocalImageStorageService(baseDir = storageBaseDir, publicBaseUrl = publicBaseUrl)

    connectDatabase(env)

    val auths = json.decodeFromString<List<SeedAuth>>(Files.readString(seedDir.resolve("seed_auth_credentials.json")))
    val users = json.decodeFromString<List<SeedUser>>(Files.readString(seedDir.resolve("seed_users.json")))
    val posts = json.decodeFromString<List<SeedPost>>(Files.readString(seedDir.resolve("seed_posts.json")))

    println("Loaded ${auths.size} auth, ${users.size} users, ${posts.size} posts")

    transaction {
        cleanupExistingSeed(auths.map { it.email.trim().lowercase() }, storage)

        // 1) auth_credentials
        val authIdByRef = HashMap<String, UUID>()
        for (a in auths) {
            require(a.provider.uppercase() == AuthProvider.REGULAR.name) {
                "Seed only supports REGULAR provider, got ${a.provider} for ${a.authRef}"
            }
            val hash = a.passwordHash
                ?: a.passwordPlain?.let { BCrypt.withDefaults().hashToString(12, it.toCharArray()) }
                ?: error("auth ${a.authRef} has neither password_hash nor password_plain")

            val id = AuthTable.insertReturning(listOf(AuthTable.id)) {
                it[email] = a.email.trim().lowercase()
                it[password] = hash
                it[provider] = AuthProvider.REGULAR.name
                it[googleId] = null
            }.single()[AuthTable.id].value
            authIdByRef[a.authRef] = id
        }
        println("Inserted ${authIdByRef.size} auth_credentials")

        // 2) users
        val userIdByRef = HashMap<String, UUID>()
        for (u in users) {
            val credentialId = authIdByRef[u.authRef]
                ?: error("user ${u.userRef} references unknown auth_ref ${u.authRef}")

            val profileKey = u.profilePicturePath?.let { srcRef ->
                val bytes = readSeedImage(compressedDir.resolve("profile_pictures"), srcRef)
                val key = imageKey("profile-pictures", LocalDate.now())
                runBlocking { storage.uploadImage(bytes, key, JPEG) }
                key
            }

            val id = UserTable.insertReturning(listOf(UserTable.id)) {
                it[authCredentialId] = credentialId
                it[profilePicturePath] = profileKey
                it[fullName] = u.fullName
                it[phoneNumber] = u.phoneNumber
                it[birthDate] = LocalDate.parse(u.birthDate)
                it[username] = u.username.trim().lowercase()
                it[country] = u.country
                it[spotScore] = u.spotScore
                // `role` is intentionally omitted: not in the Exposed table; DB default 'USER' applies.
            }.single()[UserTable.id].value
            userIdByRef[u.userRef] = id
        }
        println("Inserted ${userIdByRef.size} users")

        // 3) posts
        var postCount = 0
        for (p in posts) {
            val userId = userIdByRef[p.userRef]
                ?: error("post ${p.postRef} references unknown user_ref ${p.userRef}")

            val createdAt = Instant.parse(p.createdAt)
            val bytes = readSeedImage(compressedDir.resolve("posts"), p.imagePath)
            val key = imageKey("posts", LocalDate.ofInstant(createdAt, ZoneOffset.UTC))
            runBlocking { storage.uploadImage(bytes, key, JPEG) }

            PostTable.insertReturning(listOf(PostTable.id)) {
                it[PostTable.userId] = userId
                it[carModelId] = p.carModelId?.let(UUID::fromString)
                it[customBrand] = p.customBrand
                it[customModel] = p.customModel
                it[imageKey] = key // posts store the object KEY (matches the app)
                it[caption] = p.description
                it[latitude] = p.latitude
                it[longitude] = p.longitude
                it[town] = p.town
                it[country] = p.country
                it[PostTable.createdAt] = createdAt
            }.single()
            postCount++
        }
        println("Inserted $postCount posts")
    }

    println("✅ Seed import complete.")
}

// ----------------------------- helpers -------------------------------------

private const val JPEG = "image/jpeg"

/** Mirrors the app's object-key format: <prefix>/yyyy/MM/dd/<uuid>.jpg */
private fun imageKey(prefix: String, date: LocalDate): String =
    "%s/%04d/%02d/%02d/%s.jpg".format(prefix, date.year, date.monthValue, date.dayOfMonth, UUID.randomUUID())

/** Reads a compressed seed image by the basename referenced in the JSON (forced to .jpg). */
private fun readSeedImage(dir: Path, jsonPath: String): ByteArray {
    val name = jsonPath.substringAfterLast('/').substringBeforeLast('.') + ".jpg"
    val file = dir.resolve(name)
    require(Files.exists(file)) { "Missing compressed image: $file (referenced as $jsonPath)" }
    return Files.readAllBytes(file)
}

/** Deletes previously-seeded accounts (by email) + their uploaded files, so re-runs stay clean. */
private fun cleanupExistingSeed(emails: List<String>, storage: LocalImageStorageService) {
    val authIds = AuthTable.selectAll().where { AuthTable.email inList emails }
        .map { it[AuthTable.id].value }
    if (authIds.isEmpty()) return

    val userIds = UserTable.selectAll().where { UserTable.authCredentialId inList authIds }
        .map { row ->
            row[UserTable.profilePicturePath]?.let { pathOrUrl ->
                val key = storage.normalizeObjectKey(pathOrUrl)
                if (key.isNotEmpty()) runBlocking { storage.deleteImage(key) }
            }
            row[UserTable.id].value
        }

    if (userIds.isNotEmpty()) {
        PostTable.selectAll().where { PostTable.userId inList userIds }.forEach { row ->
            runBlocking { storage.deleteImage(row[PostTable.imageKey]) }
        }
    }

    // Deleting auth cascades to users -> posts (ON DELETE CASCADE).
    var deleted = 0
    authIds.forEach { id -> deleted += AuthTable.deleteWhere { AuthTable.id eq id } }
    println("Cleanup: removed $deleted existing seed account(s) and their images")
}

private fun connectDatabase(env: DotEnv) {
    val ktorEnv = env["KTOR_ENV"] ?: "development"
    val prefix = when (ktorEnv) {
        "development" -> "DEV"
        "testing" -> "TEST_DB"
        "production" -> "PROD"
        else -> error("Unknown KTOR_ENV: $ktorEnv")
    }
    val rawUrl = env["${prefix}_DB_URL"] ?: error("${prefix}_DB_URL not set")
    val user = env[if (prefix == "TEST_DB") "TEST_DB_USER" else "${prefix}_USER"] ?: error("DB user not set")
    val password = env[if (prefix == "TEST_DB") "TEST_DB_PASSWORD" else "${prefix}_PASSWORD"] ?: error("DB password not set")

    val jdbcUrl = if (rawUrl.startsWith("jdbc:")) rawUrl else rawUrl.replaceFirst("postgresql://", "jdbc:postgresql://")

    val dataSource = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        driverClassName = "org.postgresql.Driver"
        username = user
        this.password = password
        maximumPoolSize = 4
        isAutoCommit = false
        transactionIsolation = "TRANSACTION_REPEATABLE_READ"
        validate()
    })

    // Ensure the schema exists (idempotent) so the seeder works without booting the server.
    Flyway.configure().dataSource(dataSource).locations("classpath:db/migrations").load().migrate()
    Database.connect(dataSource)
    println("Connected to database (env=$ktorEnv)")
}

/** Minimal .env reader: file values fall back to real process env. */
private class DotEnv(path: Path) {
    private val values: Map<String, String> = buildMap {
        if (Files.exists(path)) {
            Files.readAllLines(path).forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
                val idx = trimmed.indexOf('=')
                if (idx <= 0) return@forEach
                val key = trimmed.substring(0, idx).trim()
                val value = trimmed.substring(idx + 1).trim().trim('"', '\'')
                put(key, value)
            }
        }
    }

    operator fun get(key: String): String? = values[key] ?: System.getenv(key)
}
