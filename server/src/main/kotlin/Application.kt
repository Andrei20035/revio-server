package com.revio.server

import com.revio.server.config.configureDatabases
import com.revio.server.config.configureAuthStatusPages
import com.revio.server.config.configureHTTP
import com.revio.server.config.configureMonitoring
import com.revio.server.config.configureRouting
import com.revio.server.config.configureSecurity
import com.revio.server.config.configureSerialization
import com.revio.server.config.configureSockets
import com.revio.server.core.di.appModule
import com.revio.server.core.util.resolveZone
import com.revio.server.features.account_deletion.accountDeletionModule
import com.revio.server.features.activity.activityModule
import com.revio.server.features.auth.authModule
import com.revio.server.features.car_family.carFamilyModule
import com.revio.server.features.car_model.carModelModule
import com.revio.server.features.challenge.challengeModule
import features.comment.commentModule
import com.revio.server.features.feedback.feedbackModule
import com.revio.server.features.friend.friendModule
import com.revio.server.features.friend_request.friendRequestModule
import features.like.likeModule
import features.report.reportModule
import com.revio.server.features.leaderboard.ILeaderboardSnapshotDAO
import com.revio.server.features.leaderboard.leaderboardModule
import com.revio.server.features.moderation.moderationModule
import com.revio.server.features.notification.notificationModule
import com.revio.server.features.post.postModule
import com.revio.server.features.scoring.scoringModule
import com.revio.server.features.user.userModule
import com.revio.server.features.user_car.userCarModule
import com.revio.server.features.waitlist.waitlistModule
import io.ktor.server.application.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.openapi.*
import io.ktor.server.plugins.swagger.*
import io.ktor.server.routing.*
import kotlinx.coroutines.runBlocking
import org.koin.ktor.ext.getKoin
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger
import java.time.Instant

fun main(args: Array<String>) {
    EngineMain.main(args)
}

fun Application.module() {
    install(Koin) {
        slf4jLogger()
        modules(appModule,
            authModule,
            userModule,
            scoringModule,
            commentModule,
            postModule,
            carModelModule,
            friendModule,
            friendRequestModule,
            likeModule,
            reportModule,
            userCarModule,
            leaderboardModule,
            activityModule,
            accountDeletionModule,
            feedbackModule,
            carFamilyModule,
            challengeModule,
            notificationModule,
            moderationModule,
            waitlistModule
        )
    }

    install(RoutingRoot)

    configureSockets()
    configureSecurity()
    configureHTTP()
    configureSerialization()
    configureAuthStatusPages()
    configureDatabases()
    configureMonitoring()
    configureSwagger()
    configureRouting()

    // Dev-only convenience: when the external cron (see /admin/leaderboard/snapshot/today)
    // hasn't run yet, this backfills today's leaderboard snapshot once at boot so
    // LEADERBOARD_UP/weeklySpotScore have something to work with locally. Off by default —
    // production always relies on the external cron, never on this.
    if (System.getenv("ENABLE_SNAPSHOT_CATCHUP_ON_STARTUP") == "true") {
        val snapshotDao = getKoin().get<ILeaderboardSnapshotDAO>()
        val snapshotZone = resolveZone(System.getenv("LEADERBOARD_SNAPSHOT_ZONE"))
        runBlocking { snapshotDao.snapshotAllRanks(Instant.now().atZone(snapshotZone).toLocalDate()) }
    }
}

fun Application.configureSwagger() {
    routing {
        swaggerUI(path = "swagger", swaggerFile = "openapi/documentation.yaml")
        openAPI(path = "openapi", swaggerFile = "openapi/documentation.yaml")
    }
}
