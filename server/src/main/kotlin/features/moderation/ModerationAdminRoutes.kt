package com.revio.server.features.moderation

import com.revio.server.core.serialization.InstantSerializer
import com.revio.server.core.serialization.UUIDSerializer
import com.revio.server.core.util.getUuidClaim
import com.revio.server.core.util.toUuidOrNull
import com.revio.server.features.notification.dto.NotificationDTO
import com.revio.server.features.notification.dto.toDTO
import com.revio.server.features.post.PostNotFoundException
import com.revio.server.features.user.UserRole
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import java.time.Instant
import java.util.UUID

@Serializable
data class RemovePostRequest(val reason: ModerationReason, val reasonDetails: String? = null)

@Serializable
data class BulkRemovePostsRequest(
    val postIds: List<@Serializable(with = UUIDSerializer::class) UUID>,
    val reason: ModerationReason,
    val reasonDetails: String? = null,
)

@Serializable
data class RemovePostResponseDTO(
    @Serializable(with = UUIDSerializer::class) val violationId: UUID,
    @Serializable(with = UUIDSerializer::class) val postId: UUID,
)

@Serializable
data class BulkRemovePostsResponseDTO(val removedCount: Int, val notifiedUserCount: Int)

@Serializable
data class RetryOrphansResponseDTO(val attempted: Int, val succeeded: Int)

@Serializable
data class BanStateDTO(
    val isBanned: Boolean,
    val permanent: Boolean,
    @Serializable(with = InstantSerializer::class) val bannedUntil: Instant? = null,
    val reason: String? = null,
    @Serializable(with = InstantSerializer::class) val bannedAt: Instant? = null,
    @Serializable(with = UUIDSerializer::class) val bannedBy: UUID? = null,
)

@Serializable
data class AdminUserSummaryDTO(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    val username: String,
    val email: String,
    val fullName: String,
    val role: UserRole,
    val banState: BanStateDTO,
)

@Serializable
data class ModerationViolationDTO(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    @Serializable(with = UUIDSerializer::class) val postId: UUID,
    val postImageKey: String? = null,
    val postCaption: String? = null,
    val reason: ModerationReason,
    val reasonDetails: String? = null,
    @Serializable(with = UUIDSerializer::class) val adminId: UUID,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
    @Serializable(with = InstantSerializer::class) val revokedAt: Instant? = null,
    @Serializable(with = UUIDSerializer::class) val revokedBy: UUID? = null,
)

@Serializable
data class UserModerationResponseDTO(
    val user: AdminUserSummaryDTO,
    val activeViolationCount: Long,
    val needsReview: Boolean,
    val violations: List<ModerationViolationDTO>,
    val recentNotifications: List<NotificationDTO>,
)

@Serializable
data class BanUserRequest(val durationDays: Int? = null, val permanent: Boolean = false, val reason: String? = null)

@Serializable
data class AdminAuditLogEntryDTO(
    @Serializable(with = UUIDSerializer::class) val id: UUID,
    @Serializable(with = UUIDSerializer::class) val adminId: UUID,
    val action: String,
    val targetType: String,
    @Serializable(with = UUIDSerializer::class) val targetId: UUID,
    val metadata: String? = null,
    @Serializable(with = InstantSerializer::class) val createdAt: Instant,
)

/** reasonDetails is required (and must be non-blank) when reason is OTHER — the card's only free-text reason. */
private fun ModerationReason.requiresDetails() = this == ModerationReason.OTHER

private fun BanState.toDTO() = BanStateDTO(
    isBanned = isBanned,
    permanent = permanent,
    bannedUntil = bannedUntil,
    reason = reason,
    bannedAt = bannedAt,
    bannedBy = bannedBy,
)

private fun AdminUserSummary.toDTO() = AdminUserSummaryDTO(
    id = id,
    username = username,
    email = email,
    fullName = fullName,
    role = role,
    banState = banState.toDTO(),
)

private fun ModerationViolation.toDTO() = ModerationViolationDTO(
    id = id,
    postId = postId,
    postImageKey = postImageKey,
    postCaption = postCaption,
    reason = reason,
    reasonDetails = reasonDetails,
    adminId = adminId,
    createdAt = createdAt,
    revokedAt = revokedAt,
    revokedBy = revokedBy,
)

private fun UserModerationDetail.toDTO() = UserModerationResponseDTO(
    user = user.toDTO(),
    activeViolationCount = activeViolationCount,
    needsReview = needsReview,
    violations = violations.map { it.toDTO() },
    recentNotifications = recentNotifications.map { it.toDTO() },
)

private fun AdminAuditLogEntry.toDTO() = AdminAuditLogEntryDTO(
    id = id,
    adminId = adminId,
    action = action,
    targetType = targetType,
    targetId = targetId,
    metadata = metadata,
    createdAt = createdAt,
)

fun Route.moderationAdminRoutes() {
    val moderationService: IModerationService by application.inject()

    authenticate("admin") {
        route("/admin/posts") {
            post("/{postId}/remove") {
                val postId = call.parameters["postId"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid postId"))

                val adminId = call.getUuidClaim("userId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

                val req = try {
                    call.receive<RemovePostRequest>()
                } catch (e: BadRequestException) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                }

                if (req.reason.requiresDetails() && req.reasonDetails.isNullOrBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "reasonDetails is required when reason is OTHER"),
                    )
                }

                try {
                    val violationId = moderationService.removePost(postId, adminId, req.reason, req.reasonDetails)
                    call.respond(HttpStatusCode.OK, RemovePostResponseDTO(violationId = violationId, postId = postId))
                } catch (e: PostNotFoundException) {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Post not found"))
                }
            }

            post("/bulk-remove") {
                val adminId = call.getUuidClaim("userId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

                val req = try {
                    call.receive<BulkRemovePostsRequest>()
                } catch (e: BadRequestException) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                }

                if (req.postIds.isEmpty()) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "postIds must not be empty"))
                }
                if (req.reason.requiresDetails() && req.reasonDetails.isNullOrBlank()) {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "reasonDetails is required when reason is OTHER"),
                    )
                }

                try {
                    val result = moderationService.bulkRemovePosts(req.postIds, adminId, req.reason, req.reasonDetails)
                    call.respond(
                        HttpStatusCode.OK,
                        BulkRemovePostsResponseDTO(
                            removedCount = result.removedPostIds.size,
                            notifiedUserCount = result.notifiedUserIds.size,
                        ),
                    )
                } catch (e: PostNotFoundException) {
                    // Posts removed earlier in this same call stay removed — see bulkRemovePosts's KDoc.
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Post not found: ${e.postId}"))
                }
            }
        }

        route("/admin/storage") {
            post("/retry-orphans") {
                val result = moderationService.retryOrphanedStorage()
                call.respond(HttpStatusCode.OK, RetryOrphansResponseDTO(attempted = result.attempted, succeeded = result.succeeded))
            }
        }

        route("/admin/users") {
            get {
                val query = call.request.queryParameters["query"]?.trim()
                if (query.isNullOrEmpty()) {
                    return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "query is required"))
                }
                val results = moderationService.searchUsers(query, limit = 20)
                call.respond(HttpStatusCode.OK, results.map { it.toDTO() })
            }

            route("/{userId}") {
                get("/moderation") {
                    val userId = call.parameters["userId"].toUuidOrNull()
                        ?: return@get call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid userId"))

                    try {
                        call.respond(HttpStatusCode.OK, moderationService.getUserModeration(userId).toDTO())
                    } catch (e: AdminUserNotFoundException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                    }
                }

                post("/ban") {
                    val userId = call.parameters["userId"].toUuidOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid userId"))
                    val adminId = call.getUuidClaim("userId")
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

                    val req = try {
                        call.receive<BanUserRequest>()
                    } catch (e: BadRequestException) {
                        return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid request body"))
                    }

                    if (!req.permanent && req.durationDays !in setOf(3, 7, 30)) {
                        return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "durationDays must be 3, 7 or 30 unless permanent is true"),
                        )
                    }

                    try {
                        moderationService.banUser(userId, req.durationDays, req.permanent, req.reason, adminId)
                        call.respond(HttpStatusCode.OK, mapOf("status" to "banned"))
                    } catch (e: AdminUserNotFoundException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                    }
                }

                post("/unban") {
                    val userId = call.parameters["userId"].toUuidOrNull()
                        ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid userId"))
                    val adminId = call.getUuidClaim("userId")
                        ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

                    try {
                        moderationService.unbanUser(userId, adminId)
                        call.respond(HttpStatusCode.OK, mapOf("status" to "unbanned"))
                    } catch (e: AdminUserNotFoundException) {
                        call.respond(HttpStatusCode.NotFound, mapOf("error" to "User not found"))
                    }
                }
            }
        }

        post("/admin/violations/{id}/revoke") {
            val violationId = call.parameters["id"].toUuidOrNull()
                ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))
            val adminId = call.getUuidClaim("userId")
                ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

            try {
                moderationService.revokeViolation(violationId, adminId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "revoked"))
            } catch (e: ViolationNotFoundException) {
                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Violation not found"))
            }
        }

        get("/admin/audit") {
            val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: 50).coerceIn(1, 200)
            call.respond(HttpStatusCode.OK, moderationService.listAuditLog(limit).map { it.toDTO() })
        }
    }
}
