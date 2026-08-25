package com.revio.server.features.notification

import com.revio.server.core.util.getUuidClaim
import com.revio.server.features.notification.dto.RegisterDeviceRequest
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

fun Route.deviceRoutes() {
    val deviceRegistryService: IDeviceRegistryService by application.inject()

    authenticate("jwt") {
        route("/devices") {
            post {
                val userId = call.getUuidClaim("userId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                val request = call.receive<RegisterDeviceRequest>()

                try {
                    val device = deviceRegistryService.register(userId, request)
                    call.respond(HttpStatusCode.OK, device)
                } catch (e: IllegalArgumentException) {
                    call.respond(HttpStatusCode.BadRequest, mapOf("error" to (e.message ?: "Invalid request")))
                }
            }

            delete("/{deviceId}") {
                val userId = call.getUuidClaim("userId")
                    ?: return@delete call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))
                val deviceId = call.parameters["deviceId"]
                    ?: return@delete call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid deviceId"))

                val existed = deviceRegistryService.unregister(userId, deviceId)
                if (existed) {
                    call.respond(HttpStatusCode.NoContent)
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Device not found"))
                }
            }
        }
    }
}
