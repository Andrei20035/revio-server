package wiring

import com.revio.server.core.di.appModule
import com.revio.server.features.account_deletion.IAccountDeletionService
import com.revio.server.features.account_deletion.accountDeletionModule
import com.revio.server.features.activity.IActivityService
import com.revio.server.features.activity.activityModule
import com.revio.server.features.announcement.IAnnouncementService
import com.revio.server.features.announcement.announcementModule
import com.revio.server.features.auth.GoogleTokenVerifier
import com.revio.server.features.auth.IAuthService
import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.authModule
import com.revio.server.features.auth.session.IAuthSessionDAO
import com.revio.server.features.auth.session.ISessionService
import com.revio.server.features.car_family.ICarFamilyService
import com.revio.server.features.car_family.carFamilyModule
import com.revio.server.features.car_model.ICarModelService
import com.revio.server.features.car_model.carModelModule
import com.revio.server.features.challenge.IChallengeDAO
import com.revio.server.features.challenge.IChallengeFinalizationService
import com.revio.server.features.challenge.IChallengeProgressService
import com.revio.server.features.challenge.IChallengeService
import com.revio.server.features.challenge.challengeModule
import com.revio.server.features.leaderboard.ILeaderboardService
import com.revio.server.features.leaderboard.ILeaderboardSnapshotDAO
import com.revio.server.features.leaderboard.leaderboardModule
import com.revio.server.features.moderation.IModerationService
import com.revio.server.features.moderation.moderationModule
import com.revio.server.features.notification.INotificationDAO
import com.revio.server.features.post.IPostService
import com.revio.server.features.post.postModule
import com.revio.server.features.scoring.scoringModule
import com.revio.server.features.user.IUserDAO
import com.revio.server.features.user.IUserService
import com.revio.server.features.user.userModule
import com.revio.server.features.user_car.IUserCarService
import com.revio.server.features.user_car.userCarModule
import com.revio.server.features.waitlist.IWaitlistDAO
import com.revio.server.features.waitlist.IWaitlistLookupService
import com.revio.server.features.waitlist.IWaitlistSyncService
import com.revio.server.features.waitlist.waitlistModule
import com.revio.server.core.storage.IStorageService
import features.comment.ICommentService
import features.comment.commentModule
import com.revio.server.features.friend.IFriendService
import com.revio.server.features.friend.friendModule
import com.revio.server.features.friend_request.IFriendRequestService
import com.revio.server.features.friend_request.friendRequestModule
import features.like.ILikeDAO
import features.like.ILikeService
import features.like.likeModule
import features.report.IReportService
import features.report.reportModule
import com.revio.server.features.feedback.IFeedbackService
import com.revio.server.features.feedback.feedbackModule
import com.revio.server.features.notification.INotificationService
import com.revio.server.features.notification.notificationModule
import io.mockk.mockk
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import testutils.TestDatabaseFactory
import testutils.stopKoinSafely

/**
 * Starts the SAME Koin module list [com.revio.server.Application.module] installs (imported
 * directly from production sources, not re-declared here), then resolves every dependency type
 * that a mounted *Routes.kt file injects via `by application.inject<T>()`. Per-feature route
 * tests each build their own minimal, hand-picked Koin graph, so a module simply missing from
 * the real [Application.kt] list (as [announcementModule] was — see AnnouncementRoutes.kt's
 * `IAnnouncementService` injection failing at request time, not at boot) is invisible to them.
 * This test exercises the actual production module list, so the same omission fails loudly here.
 *
 * [JwtService] and [GoogleTokenVerifier] read JWT_SECRET/JWT_ISSUER/JWT_AUDIENCE/GOOGLE_CLIENT_ID
 * straight off `System.getenv()` with no `System.getProperty` fallback (unlike `requireConfig` in
 * config/Security.kt, which every other test relies on). This JVM (Java 21, no `--add-opens
 * java.base/java.lang`) can't set real environment variables via reflection, so those two
 * definitions are overridden with test doubles here — this test's purpose is verifying that every
 * route-required *definition* resolves from the real module list, not re-verifying environment
 * configuration already covered by production startup.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ApplicationWiringTest {

    private val testAuthEnvOverridesModule = module {
        single { JwtService(jwtSecret = "wiring-test-secret", jwtIssuer = "wiring-test-issuer", jwtAudience = "wiring-test-audience") }
        single<GoogleTokenVerifier> { mockk(relaxed = true) }
    }

    @BeforeAll
    fun setup() {
        TestDatabaseFactory.start()
    }

    @AfterAll
    fun tearDown() {
        try {
            stopKoin()
        } catch (_: Throwable) {
            // idempotent
        }
        TestDatabaseFactory.stop()
    }

    @Test
    fun `every dependency injected by a mounted route resolves from the full production Koin graph`() {
        // Other test classes may run earlier in the same Gradle test worker JVM and leave a
        // global Koin context behind (their own stopKoinSafely() runs in @BeforeEach, not
        // guaranteed to run before this class specifically) — guard the same way they do.
        stopKoinSafely()

        val koinApp = startKoin {
            modules(
                appModule,
                announcementModule,
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
                waitlistModule,
                // Last so it wins the "later module wins" resolution for the two overridden
                // definitions — see the class doc for why they're overridden at all.
                testAuthEnvOverridesModule,
            )
        }
        val koin = koinApp.koin

        // One entry per distinct type injected via `by application.inject<T>()` across every
        // *Routes.kt mounted in config/Routing.kt — see that file for the full route list.
        val resolutions: List<Pair<String, () -> Any>> = listOf(
            "ILikeService" to { koin.get<ILikeService>() },
            "IFriendService" to { koin.get<IFriendService>() },
            "IWaitlistDAO" to { koin.get<IWaitlistDAO>() },
            "IWaitlistSyncService" to { koin.get<IWaitlistSyncService>() },
            "IPostService" to { koin.get<IPostService>() },
            "JwtService" to { koin.get<JwtService>() },
            "ISessionService" to { koin.get<ISessionService>() },
            "IUserService" to { koin.get<IUserService>() },
            "INotificationDAO" to { koin.get<INotificationDAO>() },
            "IActivityService" to { koin.get<IActivityService>() },
            "ICommentService" to { koin.get<ICommentService>() },
            "ILeaderboardSnapshotDAO" to { koin.get<ILeaderboardSnapshotDAO>() },
            "IUserDAO" to { koin.get<IUserDAO>() },
            "IStorageService" to { koin.get<IStorageService>() },
            "ILeaderboardService" to { koin.get<ILeaderboardService>() },
            "IFriendRequestService" to { koin.get<IFriendRequestService>() },
            "IAuthService" to { koin.get<IAuthService>() },
            "GoogleTokenVerifier" to { koin.get<GoogleTokenVerifier>() },
            "IAuthSessionDAO" to { koin.get<IAuthSessionDAO>() },
            "IAccountDeletionService" to { koin.get<IAccountDeletionService>() },
            "IWaitlistLookupService" to { koin.get<IWaitlistLookupService>() },
            "IReportService" to { koin.get<IReportService>() },
            "ICarFamilyService" to { koin.get<ICarFamilyService>() },
            "IFeedbackService" to { koin.get<IFeedbackService>() },
            "IAnnouncementService" to { koin.get<IAnnouncementService>() },
            "IChallengeService" to { koin.get<IChallengeService>() },
            "IChallengeProgressService" to { koin.get<IChallengeProgressService>() },
            "IChallengeDAO" to { koin.get<IChallengeDAO>() },
            "IChallengeFinalizationService" to { koin.get<IChallengeFinalizationService>() },
            "IUserCarService" to { koin.get<IUserCarService>() },
            "ICarModelService" to { koin.get<ICarModelService>() },
            "IModerationService" to { koin.get<IModerationService>() },
            "INotificationService" to { koin.get<INotificationService>() },
            "ILikeDAO" to { koin.get<ILikeDAO>() },
        )

        val failures = resolutions.mapNotNull { (name, resolve) ->
            try {
                resolve()
                null
            } catch (e: Exception) {
                var cause: Throwable? = e
                val chain = StringBuilder()
                while (cause != null) {
                    chain.append(cause::class.simpleName).append(": ").append(cause.message).append(" | ")
                    cause = cause.cause
                }
                "$name -> $chain"
            }
        }

        assertTrue(
            failures.isEmpty(),
            "The following route-injected dependencies failed to resolve from the full Koin graph " +
                "(a module is likely missing from Application.kt's modules(...) list):\n" +
                failures.joinToString("\n"),
        )
    }
}
