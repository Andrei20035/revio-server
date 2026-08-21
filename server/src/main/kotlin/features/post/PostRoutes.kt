package com.revio.server.features.post

import com.revio.server.core.error.AuthErrorCode
import com.revio.server.core.error.AuthApiError
import com.revio.server.core.error.AuthErrorResponse
import com.revio.server.core.util.getUuidClaim
import com.revio.server.core.util.toUuidOrNull
import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.session.ISessionService
import com.revio.server.features.auth.session.TokenResult
import com.revio.server.features.post.dto.CreatePostDTO
import com.revio.server.features.post.dto.CreatePostMetadata
import com.revio.server.features.post.dto.CreatePostResponse
import com.revio.server.features.post.dto.UpdatePostRequest
import com.revio.server.features.user.IUserService
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.http.content.streamProvider
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.authenticate
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.request.receive
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respond
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.koin.ktor.ext.inject
import org.slf4j.LoggerFactory
import java.util.UUID

private val logger = LoggerFactory.getLogger("com.revio.server.features.post.PostRoutes")

private const val DEFAULT_LIMIT = 20
private const val MAX_IMAGE_SIZE_BYTES = 10 * 1024 * 1024

/**
 * Resolves the optional viewer identity from the Authorization header.
 *
 * - No header → [ViewerResult.Resolved] with `null` (anonymous, public access allowed).
 * - Header present but malformed → responds 401 and returns [ViewerResult.AlreadyResponded].
 * - Valid token → [ViewerResult.Resolved] with the viewer's UUID.
 * - Expired / invalid / revoked token → responds 401 and returns [ViewerResult.AlreadyResponded].
 */
private sealed interface ViewerResult {
    data class Resolved(val userId: UUID?) : ViewerResult
    data object AlreadyResponded : ViewerResult
}

private suspend fun ApplicationCall.resolveOptionalViewer(
    jwtService: JwtService,
    sessionService: ISessionService,
): ViewerResult {
    val authHeader = request.headers[HttpHeaders.Authorization]
        ?: return ViewerResult.Resolved(null)

    val rawToken = authHeader
        .takeIf { it.startsWith("Bearer ", ignoreCase = true) }
        ?.substringAfter(' ')
        ?.takeIf { it.isNotBlank() }
        ?: run {
            respond(
                HttpStatusCode.Unauthorized,
                AuthErrorResponse(AuthApiError(AuthErrorCode.ACCESS_TOKEN_INVALID, "Access token is invalid")),
            )
            return ViewerResult.AlreadyResponded
        }

    return when (val result = jwtService.parseAndValidateToken(rawToken, sessionService)) {
        is TokenResult.Valid -> ViewerResult.Resolved(result.userId)
        TokenResult.Expired -> {
            respond(
                HttpStatusCode.Unauthorized,
                AuthErrorResponse(AuthApiError(AuthErrorCode.ACCESS_TOKEN_EXPIRED, "Access token has expired")),
            )
            ViewerResult.AlreadyResponded
        }
        TokenResult.Invalid -> {
            respond(
                HttpStatusCode.Unauthorized,
                AuthErrorResponse(AuthApiError(AuthErrorCode.ACCESS_TOKEN_INVALID, "Access token is invalid")),
            )
            ViewerResult.AlreadyResponded
        }
        TokenResult.SessionRevoked -> {
            respond(
                HttpStatusCode.Unauthorized,
                AuthErrorResponse(AuthApiError(AuthErrorCode.SESSION_REVOKED, "Session is revoked")),
            )
            ViewerResult.AlreadyResponded
        }
    }
}

fun Route.postRoutes() {
    val postService: IPostService by application.inject()
    val jwtService: JwtService by application.inject()
    val sessionService: ISessionService by application.inject()
    val userService: IUserService by application.inject()
    val json = Json { ignoreUnknownKeys = true }

    get("/posts/feed") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
        val cursorCreatedAt = call.request.queryParameters["cursorCreatedAt"]
        val cursorPostId = call.request.queryParameters["cursorPostId"]
        val currentUserId = when (val viewer = call.resolveOptionalViewer(jwtService, sessionService)) {
            is ViewerResult.AlreadyResponded -> return@get
            is ViewerResult.Resolved -> viewer.userId
        }

        try {
            call.respond(
                HttpStatusCode.OK,
                postService.listFeed(limit, cursorCreatedAt, cursorPostId, currentUserId),
            )
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        }
    }

    get("/users/{userId}/posts") {
        val userId = call.parameters["userId"].toUuidOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid userId"))
        val limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT
        val cursorCreatedAt = call.request.queryParameters["cursorCreatedAt"]
        val cursorPostId = call.request.queryParameters["cursorPostId"]
        val currentUserId = when (val viewer = call.resolveOptionalViewer(jwtService, sessionService)) {
            is ViewerResult.AlreadyResponded -> return@get
            is ViewerResult.Resolved -> viewer.userId
        }

        try {
            call.respond(HttpStatusCode.OK, postService.listPostsByUser(userId, limit, cursorCreatedAt, cursorPostId, currentUserId))
        } catch (e: IllegalArgumentException) {
            call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
        }
    }

    get("/posts/{postId}") {
        val postId = call.parameters["postId"].toUuidOrNull()
            ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid postId"))

        val currentUserId = when (val viewer = call.resolveOptionalViewer(jwtService, sessionService)) {
            is ViewerResult.AlreadyResponded -> return@get
            is ViewerResult.Resolved -> viewer.userId
        }

        val post = postService.findPostById(postId, currentUserId)
            ?: return@get call.respond(HttpStatusCode.NotFound, mapOf("error" to "Post not found"))

        call.respond(HttpStatusCode.OK, post)
    }

    authenticate("jwt") {
        post("/posts") {
            val userId = call.getUuidClaim("userId")
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

            try {
                val multipart = call.receiveMultipart()
                var metadata: CreatePostMetadata? = null
                var imageBytes: ByteArray? = null
                var contentType: String? = null

                multipart.forEachPart { part ->
                    when (part) {
                        is PartData.FormItem -> if (part.name == "metadata") {
                            metadata = runCatching { json.decodeFromString<CreatePostMetadata>(part.value) }
                                .getOrElse { throw BadRequestException("Invalid metadata JSON") }
                        }

                        is PartData.FileItem -> if (part.name == "image") {
                            val bytes = part.streamProvider().readBytes()
                            if (bytes.size > MAX_IMAGE_SIZE_BYTES) {
                                throw BadRequestException("Image exceeds max size of $MAX_IMAGE_SIZE_BYTES bytes")
                            }
                            imageBytes = bytes
                            contentType = part.contentType?.toString()
                        }

                        else -> Unit
                    }
                    part.dispose()
                }

                val meta = metadata ?: throw BadRequestException("Missing metadata")
                val bytes = imageBytes ?: throw BadRequestException("Missing image")
                val ct = contentType ?: throw BadRequestException("Missing image content-type")

                val postId = postService.createPost(
                    CreatePostDTO(
                        authorId = userId,
                        carModelId = meta.carModelId,
                        customBrand = meta.customBrand,
                        customModel = meta.customModel,
                        latitude = meta.latitude,
                        longitude = meta.longitude,
                        town = meta.town,
                        country = meta.country,
                        caption = meta.caption,
                        imageBytes = bytes,
                        contentType = ct,
                        source = PostSource.fromStringOrGallery(meta.source),
                        createdAtTimezone = meta.createdAtTimezone,
                    )
                )

                val user = runCatching { userService.getSelf(userId) }
                    .onFailure { logger.warn("getSelf failed after creating post {} for user {}", postId, userId, it) }
                    .getOrNull()
                if (user == null) {
                    logger.warn("Post {} created with user=null in the response for user {}", postId, userId)
                }
                call.respond(HttpStatusCode.Created, CreatePostResponse(postId.toString(), user))
            } catch (e: BadRequestException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
            } catch (e: IllegalArgumentException) {
                call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request"))
            } catch (e: PostCreationException) {
                logger.error("Post creation failed for user {} at stage={}", userId, e.stage, e)
                call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "Failed to create post"))
            }
        }

        route("/posts") {
            patch("/{postId}") {
                val postId = call.parameters["postId"].toUuidOrNull()
                    ?: return@patch call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid postId"))
                val userId = call.getUuidClaim("userId")
                    ?: return@patch call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                try {
                    val request = call.receive<UpdatePostRequest>()
                    val updated = postService.updatePostAsAuthor(postId, userId, request)
                    call.respond(HttpStatusCode.OK, updated)
                } catch (e: PostNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Post not found"))
                } catch (e: PostForbiddenException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to edit this post"))
                } catch (e: PostVehicleLockedException) {
                    call.respond(
                        HttpStatusCode.Conflict,
                        mapOf(
                            "error" to "This post's vehicle can no longer be changed because it has contributed to a challenge",
                            "code" to "CHALLENGE_POST_VEHICLE_LOCKED",
                        ),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            delete("/{postId}") {
                val postId = call.parameters["postId"].toUuidOrNull()
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid postId"))
                val userId = call.getUuidClaim("userId")
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                try {
                    postService.deletePostAsAuthor(postId, userId)
                    call.respond(HttpStatusCode.NoContent, "")
                } catch (e: PostNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Post not found"))
                } catch (e: PostForbiddenException) {
                    call.respond(HttpStatusCode.Forbidden, mapOf("error" to "You do not have permission to delete this post"))
                }
            }
        }
    }
}
