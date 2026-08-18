package com.revio.server.features.user

import com.revio.server.core.error.ProfileErrorCode
import com.revio.server.core.storage.IStorageService
import com.revio.server.core.util.getUuidClaim
import com.revio.server.core.util.toUuidOrNull
import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.session.ISessionService
import com.revio.server.features.scoring.ScoringServiceImpl
import com.revio.server.features.user.dto.CreateUserRequest
import com.revio.server.features.user.dto.CreateUserResponse
import com.revio.server.features.user.dto.UpdateProfilePictureRequest
import com.revio.server.features.user.dto.UpdateUserRequest
import com.revio.server.features.user.dto.toUser
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.time.LocalDate
import java.util.UUID

private const val PROFILE_PICTURE_MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024
private val profilePictureAllowedContentTypes = setOf("image/jpeg", "image/png", "image/webp")
private val profilePictureExtensions = mapOf(
    "image/jpeg" to "jpg",
    "image/png" to "png",
    "image/webp" to "webp",
)

fun Route.userRoutes() {
    val userService: IUserService by application.inject()
    val userDao: IUserDAO by application.inject()
    val jwtService: JwtService by application.inject()
    val sessionService: ISessionService by application.inject()
    val storageService: IStorageService by application.inject()

    route("/users") {
        get("/{userId}") {
            val userId = call.parameters["userId"].toUuidOrNull()
                ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid userId"))

            val user = userService.getUserById(userId)
                ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))

            call.respond(HttpStatusCode.OK, user)
        }

        authenticate("jwt") {
            post {
                val request = call.receive<CreateUserRequest>()
                val credentialId = call.getUuidClaim("credentialId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing credentialId"))

                val sessionId = call.getUuidClaim("sid")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing sessionId"))

                val email = call.principal<JWTPrincipal>()
                    ?.payload
                    ?.getClaim("email")
                    ?.asString()
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing email"))

                try {
                    val createResult = userService.createUserProfile(
                        authCredentialId = credentialId,
                        user = request.toUser(credentialId),
                    )
                    val newUserId = createResult.userId
                    val (session, refreshToken) = sessionService.promoteSession(sessionId, newUserId)
                    // A freshly created profile is always UserRole.USER (createUser never sets role,
                    // it relies on the DB default) — re-read anyway so this call site stays uniform
                    // with every other place an access token is issued.
                    val isAdmin = userDao.getUserById(newUserId)?.role == UserRole.ADMIN
                    val accessToken = jwtService.generateAccessToken(
                        session = session,
                        credentialId = credentialId,
                        userId = newUserId,
                        email = email,
                        isAdmin = isAdmin,
                    )
                    call.respond(
                        HttpStatusCode.Created,
                        CreateUserResponse(
                            accessToken = accessToken,
                            refreshToken = refreshToken,
                            userId = newUserId,
                            isEarlySpotter = createResult.isEarlySpotter,
                            earlySpotterNumber = createResult.earlySpotterNumber,
                            earlySpotterBonusPoints = if (createResult.bonusGrantedNow) {
                                ScoringServiceImpl.EARLY_SPOTTER_BONUS_POINTS
                            } else {
                                null
                            },
                        )
                    )
                } catch (e: UsernameAlreadyExistsException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Username is already taken")))
                } catch (e: UserProfileAlreadyExistsException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Profile already exists")))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            get("/username-available") {
                val userId = call.getUuidClaim("userId")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                val username = call.request.queryParameters["username"]
                    ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Missing username"))

                val result = userService.checkUsernameAvailability(userId, username)
                call.respond(
                    HttpStatusCode.OK,
                    UsernameAvailabilityResponse(
                        available = result.available,
                        normalized = result.normalized,
                        reason = result.reason,
                    ),
                )
            }

            get("/me") {
                val userId = call.getUuidClaim("userId")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                val user = userService.getSelf(userId)
                    ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))

                call.respond(HttpStatusCode.OK, user)
            }

            patch("/me") {
                val userId = call.getUuidClaim("userId")
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                val request = call.receive<UpdateUserRequest>()

                try {
                    val updated = userService.updateUserProfile(userId, request)
                    call.respond(HttpStatusCode.OK, updated)
                } catch (e: UsernameAlreadyExistsException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Username is already taken")))
                } catch (e: PhoneNumberAlreadyExistsException) {
                    call.respond(HttpStatusCode.Conflict, mapOf("error" to (e.message ?: "Phone number is already in use")))
                } catch (e: FullNameAlreadyChangedException) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        forbiddenChangeError(ProfileErrorCode.FULL_NAME_ALREADY_CHANGED, e.message ?: "Full name can only be changed once"),
                    )
                } catch (e: CountryAlreadyChangedException) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        forbiddenChangeError(ProfileErrorCode.COUNTRY_ALREADY_CHANGED, e.message ?: "Country can only be changed once"),
                    )
                } catch (e: BirthDateAlreadyChangedException) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        forbiddenChangeError(ProfileErrorCode.BIRTH_DATE_ALREADY_CHANGED, e.message ?: "Date of birth can only be changed once"),
                    )
                } catch (e: UsernameChangeTooSoonException) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        forbiddenChangeError(ProfileErrorCode.USERNAME_CHANGE_TOO_SOON, e.message ?: "Username can only be changed once a month"),
                    )
                } catch (e: PhoneNumberChangeTooSoonException) {
                    call.respond(
                        HttpStatusCode.Forbidden,
                        forbiddenChangeError(ProfileErrorCode.PHONE_NUMBER_CHANGE_TOO_SOON, e.message ?: "Phone number can only be changed once a month"),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: UserNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                }
            }

            patch("/me/profile-picture") {
                val userId = call.getUuidClaim("userId")
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                try {
                    val updated = if (call.request.contentType().withoutParameters() == ContentType.MultiPart.FormData) {
                        val payload = parseProfilePictureMultipart(call.receiveMultipart())
                        val imageKey = createProfilePictureImageKey(payload.contentType)
                        storageService.uploadImage(payload.imageBytes, imageKey, payload.contentType)
                        try {
                            userService.updateProfilePicture(userId, imageKey)
                        } catch (e: Exception) {
                            runCatching { storageService.deleteImage(imageKey) }
                            throw e
                        }
                    } else {
                        val request = call.receive<UpdateProfilePictureRequest>()
                        userService.updateProfilePicture(userId, request.imagePath)
                    }
                    call.respond(HttpStatusCode.OK, updated)
                } catch (e: BadRequestException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                } catch (e: UserNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                }
            }
        }
    }
}

private fun forbiddenChangeError(code: ProfileErrorCode, message: String) =
    mapOf("error" to mapOf("code" to code.name, "message" to message))

@Serializable
private data class UsernameAvailabilityResponse(
    val available: Boolean,
    val normalized: String,
    val reason: String?,
)

private data class ProfilePictureMultipartPayload(
    val imageBytes: ByteArray,
    val contentType: String,
)

private suspend fun parseProfilePictureMultipart(
    multipart: io.ktor.http.content.MultiPartData,
): ProfilePictureMultipartPayload {
    var imageBytes: ByteArray? = null
    var contentType: String? = null

    multipart.forEachPart { part ->
        when (part) {
            is PartData.FileItem -> if (part.name == "image") {
                val bytes = part.streamProvider().readBytes()
                if (bytes.size > PROFILE_PICTURE_MAX_IMAGE_SIZE_BYTES) {
                    throw BadRequestException("Image exceeds max size of $PROFILE_PICTURE_MAX_IMAGE_SIZE_BYTES bytes")
                }
                imageBytes = bytes
                contentType = part.contentType?.toString()
            }

            else -> Unit
        }
        part.dispose()
    }

    val bytes = imageBytes ?: throw BadRequestException("Missing image")
    val ct = contentType ?: throw BadRequestException("Missing image content-type")
    require(bytes.isNotEmpty()) { "Image is required" }
    require(ct in profilePictureAllowedContentTypes) { "Unsupported image content type" }

    return ProfilePictureMultipartPayload(bytes, ct)
}

private fun createProfilePictureImageKey(contentType: String): String {
    val ext = profilePictureExtensions.getValue(contentType)
    val today = LocalDate.now()
    return "profile-pictures/%04d/%02d/%02d/%s.%s".format(
        today.year,
        today.monthValue,
        today.dayOfMonth,
        UUID.randomUUID(),
        ext,
    )
}
