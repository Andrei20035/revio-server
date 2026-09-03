package com.revio.server.config

import com.revio.server.features.activity.activityRoutes
import com.revio.server.features.announcement.announcementRoutes
import com.revio.server.features.auth.authRoutes
import com.revio.server.features.car_family.carFamilyAdminRoutes
import com.revio.server.features.car_model.carModelRoutes
import com.revio.server.features.challenge.challengeAdminRoutes
import com.revio.server.features.challenge.challengeRoutes
import features.comment.commentRoutes
import com.revio.server.features.feedback.feedbackRoutes
import com.revio.server.features.friend.friendRoutes
import com.revio.server.features.friend_request.friendRequestRoutes
import com.revio.server.features.leaderboard.adminLeaderboardRoutes
import com.revio.server.features.leaderboard.leaderboardRoutes
import com.revio.server.features.moderation.moderationAdminRoutes
import com.revio.server.features.notification.challengeStartRoutes
import com.revio.server.features.notification.deviceRoutes
import com.revio.server.features.notification.discoveryRoutes
import com.revio.server.features.notification.inactivityRoutes
import com.revio.server.features.notification.notificationPrefsRoutes
import com.revio.server.features.notification.notificationRoutes
import com.revio.server.features.notification.pushDispatchRoutes
import features.like.likeRoutes
import features.report.reportAdminRoutes
import features.report.reportRoutes
import com.revio.server.features.post.postRoutes
import com.revio.server.features.user.userRoutes
import com.revio.server.features.user_car.userCarRoutes
import com.revio.server.features.waitlist.waitlistRoutes
import io.ktor.server.application.*
import io.ktor.server.http.content.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File

fun Application.configureRouting() {
    routing {
        // Static resources
        staticResources("/static", "static")
        if ((System.getenv("STORAGE_PROVIDER") ?: "local") != "r2") {
            staticFiles("/uploads", File(System.getenv("LOCAL_STORAGE_BASE_DIR") ?: "uploads"))
        }

        // API routes
        route("/api") {
            activityRoutes()
            announcementRoutes()
            authRoutes()
            carFamilyAdminRoutes()
            carModelRoutes()
            challengeAdminRoutes()
            challengeRoutes()
            challengeStartRoutes()
            commentRoutes()
            deviceRoutes()
            discoveryRoutes()
            feedbackRoutes()
            friendRequestRoutes()
            friendRoutes()
            inactivityRoutes()
            adminLeaderboardRoutes()
            leaderboardRoutes()
            likeRoutes()
            moderationAdminRoutes()
            notificationPrefsRoutes()
            notificationRoutes()
            pushDispatchRoutes()
            reportAdminRoutes()
            reportRoutes()
            postRoutes()
            userCarRoutes()
            userRoutes()
            waitlistRoutes()
            get("/") {
                call.respondText(
                    """Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt
                        | ut labore et dolore magna aliqua. Ut enim ad minim veniam, quis nostrud exercitation ullamco
                        | laboris nisi ut aliquip ex ea commodo consequat. Duis aute irure dolor in reprehenderit 
                        | in voluptate velit esse cillum dolore eu fugiat nulla pariatur. Excepteur sint occaecat
                        | cupidatat non proident, sunt in culpa qui officia deserunt mollit anim id est laborum.""".trimMargin())
            }

        }
    }
}

