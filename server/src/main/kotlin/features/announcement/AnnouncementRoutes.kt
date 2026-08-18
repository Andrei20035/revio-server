package com.revio.server.features.announcement

import com.revio.server.core.util.getUuidClaim
import com.revio.server.features.announcement.dto.AnnouncementAckRequest
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.announcementRoutes() {
    val announcementService: IAnnouncementService by application.inject()

    route("/users/me/announcements") {
        authenticate("jwt") {
            get {
                val userId = call.getUuidClaim("userId")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

                call.respond(HttpStatusCode.OK, announcementService.getPending(userId))
            }

            post("/ack") {
                val userId = call.getUuidClaim("userId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid userId claim"))

                val request = try {
                    call.receive<AnnouncementAckRequest>()
                } catch (e: BadRequestException) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid ack payload"))
                }

                try {
                    announcementService.acknowledge(userId, request.key)
                    call.respond(HttpStatusCode.OK, mapOf("status" to "acknowledged"))
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid announcement key")))
                }
            }
        }
    }
}
