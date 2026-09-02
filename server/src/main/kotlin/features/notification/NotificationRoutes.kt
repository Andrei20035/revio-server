package com.revio.server.features.notification

import com.revio.server.core.util.getUuidClaim
import com.revio.server.core.util.toUuidOrNull
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

fun Route.notificationRoutes() {
    val notificationService: INotificationService by application.inject()

    authenticate("jwt") {
        route("/notifications") {
            get {
                val userId = call.getUuidClaim("userId")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT)
                    .coerceIn(1, MAX_LIMIT)
                val cursorCreatedAt = call.request.queryParameters["cursorCreatedAt"]
                val cursorNotificationId = call.request.queryParameters["cursorNotificationId"]

                try {
                    val category = call.request.queryParameters["category"]?.let { NotificationCategory.fromParam(it) }
                    call.respond(
                        HttpStatusCode.OK,
                        notificationService.listForUserPage(userId, limit, cursorCreatedAt, cursorNotificationId, category),
                    )
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            post("/{id}/read") {
                val userId = call.getUuidClaim("userId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                val updated = notificationService.markRead(id, userId)
                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("status" to "read"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Notification not found"))
                }
            }

            post("/read-all") {
                val userId = call.getUuidClaim("userId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                try {
                    val category = call.request.queryParameters["category"]?.let { NotificationCategory.fromParam(it) }
                    val count = notificationService.markAllRead(userId, category)
                    call.respond(HttpStatusCode.OK, mapOf("updated" to count))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }
        }
    }
}
