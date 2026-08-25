package com.revio.server.features.notification

import com.revio.server.core.util.getUuidClaim
import com.revio.server.features.notification.dto.UpdateNotificationPrefsRequest
import com.revio.server.features.notification.dto.toDTO
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.notificationPrefsRoutes() {
    val notificationPrefsService: INotificationPrefsService by application.inject()

    route("/users/me/notification-preferences") {
        authenticate("jwt") {
            get {
                val userId = call.getUuidClaim("userId")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

                call.respond(HttpStatusCode.OK, notificationPrefsService.get(userId).toDTO())
            }

            put {
                val userId = call.getUuidClaim("userId")
                    ?: return@put call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

                val request = call.receive<UpdateNotificationPrefsRequest>()

                try {
                    call.respond(HttpStatusCode.OK, notificationPrefsService.update(userId, request).toDTO())
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }
        }
    }
}
