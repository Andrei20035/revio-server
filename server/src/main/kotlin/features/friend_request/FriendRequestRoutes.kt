package com.revio.server.features.friend_request

import com.revio.server.core.util.getUuidClaim
import com.revio.server.core.util.toUuidOrNull
import io.ktor.http.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.koin.ktor.ext.inject

/**
 * Inactive: not called from the Android app (pas 4.4). The endpoints exist in the API and pass
 * their own tests, but nothing in the client wires up friends/friend-requests today — backlog,
 * not in scope for closed testing. See revio-server/README.md's Domain Modules table.
 */
fun Route.friendRequestRoutes() {
    val friendRequestService: IFriendRequestService by application.inject()

    authenticate("jwt") {
        route("/friend-requests") {
            authenticate("admin") {
                get("/admin") {
                    val principal = call.principal<JWTPrincipal>()
                    val isAdmin = principal?.getClaim("isAdmin", Boolean::class) ?: false
                    if (!isAdmin) {
                        call.respond(HttpStatusCode.Forbidden, mapOf("error" to "Admin access required"))
                        return@get
                    }

                    val allRequests = friendRequestService.getAllFriendReqFromDB()
                    call.respond(HttpStatusCode.OK, allRequests )
                }
            }

            post("/{receiverId}") {
                val senderId = call.getUuidClaim("userId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing senderId"))

                val receiverId = call.parameters["receiverId"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or missing receiverId"))

                if (senderId == receiverId) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "You cannot send a friend request to yourself"))
                }

                try {
                    friendRequestService.sendFriendRequest(senderId, receiverId)
                    call.respond(HttpStatusCode.Created, mapOf("message" to "Friend request sent"))

                } catch (e: Exception) {
                    call.respond(HttpStatusCode.InternalServerError, mapOf("error" to "A problem occurred when sending friend request"))
                }
            }

            post("/{senderId}/accept") {
                val receiverId = call.getUuidClaim("userId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing receiverId"))

                val senderId = call.parameters["senderId"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or missing senderId"))

                if (senderId == receiverId) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "You cannot accept a friend request from yourself"))
                }

                val result = friendRequestService.acceptFriendRequest(senderId, receiverId)

                if (result) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Friend request accepted"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Friend request not found"))
                }
            }

            post("/{senderId}/decline") {
                val receiverId = call.getUuidClaim("userId")
                    ?: return@post call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing receiverId"))

                val senderId = call.parameters["senderId"].toUuidOrNull()
                    ?: return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Invalid or missing senderId"))

                if (senderId == receiverId) {
                    return@post call.respond(HttpStatusCode.BadRequest, mapOf("error" to "You cannot decline a friend request from yourself"))
                }

                val rowsAffected = friendRequestService.declineFriendRequest(senderId, receiverId)

                if (rowsAffected > 0) {
                    call.respond(HttpStatusCode.OK, mapOf("message" to "Friend request declined"))
                } else {
                    call.respond(HttpStatusCode.NotFound, mapOf("error" to "Friend request not found"))
                }
            }

            get {
                val userId = call.getUuidClaim("userId")
                    ?: return@get call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Invalid or missing userId"))

                val friendRequests = friendRequestService.getAllFriendRequests(userId)

                if (friendRequests.isEmpty()) {
                    return@get call.respond(HttpStatusCode.NoContent)
                }

                call.respond(HttpStatusCode.OK, friendRequests)
            }

        }
    }
}
