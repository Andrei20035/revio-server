package com.revio.server.features.notification

import com.revio.server.core.util.getUuidClaim
import com.revio.server.core.util.toUuidOrNull
import com.revio.server.features.notification.dto.NotificationListResponseDTO
import com.revio.server.features.notification.dto.toDTO
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

fun Route.notificationRoutes() {
    val notificationDao: INotificationDAO by application.inject()

    authenticate("jwt") {
        route("/notifications") {
            get {
                val userId = call.getUuidClaim("userId")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                val limit = (call.request.queryParameters["limit"]?.toIntOrNull() ?: DEFAULT_LIMIT)
                    .coerceIn(1, MAX_LIMIT)

                val items = notificationDao.listForUser(userId, limit).map { it.toDTO() }
                val unreadCount = notificationDao.countUnread(userId)

                call.respond(HttpStatusCode.OK, NotificationListResponseDTO(unreadCount, items))
            }

            post("/{id}/read") {
                val userId = call.getUuidClaim("userId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))
                val id = call.parameters["id"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid id"))

                val updated = notificationDao.markRead(id, userId)
                if (updated) {
                    call.respond(HttpStatusCode.OK, mapOf("status" to "read"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Notification not found"))
                }
            }

            post("/read-all") {
                val userId = call.getUuidClaim("userId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                val count = notificationDao.markAllRead(userId)
                call.respond(HttpStatusCode.OK, mapOf("updated" to count))
            }
        }
    }
}
