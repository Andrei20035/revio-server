package testutils

import com.revio.server.config.configureSecurity
import com.revio.server.config.configureSerialization
import com.revio.server.config.configureAuthStatusPages
import com.revio.server.core.storage.IStorageService
import com.revio.server.core.storage.LocalImageStorageService
import com.revio.server.features.account_deletion.AccountDeletionFeedbackDAO
import com.revio.server.features.account_deletion.AccountDeletionService
import com.revio.server.features.account_deletion.IAccountDeletionFeedbackDAO
import com.revio.server.features.account_deletion.IAccountDeletionService
import com.revio.server.features.auth.AuthDAO
import com.revio.server.features.auth.AuthService
import com.revio.server.features.auth.GoogleTokenVerifier
import com.revio.server.features.auth.IAuthDAO
import com.revio.server.features.auth.IAuthService
import com.revio.server.features.auth.JwtService
import com.revio.server.features.auth.authRoutes
import com.revio.server.features.auth.RefreshTokenGenerator
import com.revio.server.features.auth.session.AuthSessionDAO
import com.revio.server.features.auth.session.IAuthSessionDAO
import com.revio.server.features.auth.session.ISessionService
import com.revio.server.features.auth.session.SessionService
import com.revio.server.features.car_model.CarModelDAO
import com.revio.server.features.car_model.CarModelService
import com.revio.server.features.car_model.ICarModelDAO
import com.revio.server.features.car_model.ICarModelService
import com.revio.server.features.car_model.carModelRoutes
import com.revio.server.features.car_family.CarFamilyDAO
import com.revio.server.features.car_family.CarFamilyService
import com.revio.server.features.car_family.ICarFamilyDAO
import com.revio.server.features.car_family.ICarFamilyService
import com.revio.server.features.car_family.carFamilyAdminRoutes
import com.revio.server.features.challenge.ChallengeDAO
import com.revio.server.features.challenge.ChallengeFinalizationService
import com.revio.server.features.challenge.ChallengeProgressDAO
import com.revio.server.features.challenge.ChallengeProgressService
import com.revio.server.features.challenge.ChallengeService
import com.revio.server.features.challenge.IChallengeDAO
import com.revio.server.features.challenge.IChallengeFinalizationService
import com.revio.server.features.challenge.IChallengeProgressDAO
import com.revio.server.features.challenge.IChallengeProgressService
import com.revio.server.features.challenge.IChallengeService
import com.revio.server.features.challenge.challengeAdminRoutes
import com.revio.server.features.challenge.challengeRoutes
import com.revio.server.features.feedback.FeedbackDAO
import com.revio.server.features.feedback.FeedbackService
import com.revio.server.features.feedback.IFeedbackDAO
import com.revio.server.features.feedback.IFeedbackService
import com.revio.server.features.feedback.feedbackRoutes
import com.revio.server.features.leaderboard.ILeaderboardDAO
import com.revio.server.features.leaderboard.ILeaderboardService
import com.revio.server.features.leaderboard.ILeaderboardSnapshotDAO
import com.revio.server.features.leaderboard.LeaderboardDAO
import com.revio.server.features.leaderboard.LeaderboardService
import com.revio.server.features.leaderboard.LeaderboardSnapshotDAO
import com.revio.server.features.leaderboard.adminLeaderboardRoutes
import com.revio.server.features.leaderboard.leaderboardRoutes
import com.revio.server.features.moderation.AdminAuditLogDAO
import com.revio.server.features.moderation.AdminUserQueryDAO
import com.revio.server.features.moderation.BanDAO
import com.revio.server.features.moderation.IAdminAuditLogDAO
import com.revio.server.features.moderation.IAdminUserQueryDAO
import com.revio.server.features.moderation.IBanDAO
import com.revio.server.features.moderation.IModerationService
import com.revio.server.features.moderation.IModerationViolationDAO
import com.revio.server.features.moderation.IOrphanedStorageObjectDAO
import com.revio.server.features.moderation.ModerationService
import com.revio.server.features.moderation.ModerationViolationDAO
import com.revio.server.features.moderation.OrphanedStorageObjectDAO
import com.revio.server.features.moderation.moderationAdminRoutes
import com.revio.server.features.notification.INotificationDAO
import com.revio.server.features.notification.INotificationService
import com.revio.server.features.notification.NotificationDAO
import com.revio.server.features.notification.NotificationService
import com.revio.server.features.notification.notificationRoutes
import com.revio.server.features.post.IPostDAO
import com.revio.server.features.post.IPostService
import com.revio.server.features.post.PostDAO
import com.revio.server.features.post.IPostRemovalDAO
import com.revio.server.features.post.PostRemovalDAO
import com.revio.server.features.post.PostServiceImpl
import com.revio.server.features.post.postRoutes
import com.revio.server.features.scoring.IScoringDao
import com.revio.server.features.scoring.IScoringService
import com.revio.server.features.scoring.ScoringDaoImpl
import com.revio.server.features.scoring.ScoringServiceImpl
import com.revio.server.features.user.IUserDAO
import com.revio.server.features.user.IUserService
import com.revio.server.features.user.UserDao
import com.revio.server.features.user.UserService
import com.revio.server.features.user.userRoutes
import com.revio.server.features.user_car.IUserCarDAO
import com.revio.server.features.user_car.IUserCarService
import com.revio.server.features.user_car.UserCarDAO
import com.revio.server.features.user_car.UserCarServiceImpl
import com.revio.server.features.user_car.userCarRoutes
import com.revio.server.features.waitlist.ISupabaseWaitlistClient
import com.revio.server.features.waitlist.IWaitlistDAO
import com.revio.server.features.waitlist.IWaitlistLookupService
import com.revio.server.features.waitlist.IWaitlistSyncService
import com.revio.server.features.waitlist.WaitlistDAO
import com.revio.server.features.waitlist.WaitlistSyncService
import com.revio.server.features.waitlist.waitlistRoutes
import features.comment.CommentDAO
import features.comment.ICommentDAO
import features.comment.CommentService
import features.comment.ICommentService
import features.comment.commentRoutes
import features.like.ILikeDAO
import features.like.ILikeService
import features.like.LikeDAO
import features.like.LikeService
import features.like.likeRoutes
import features.report.IReportDAO
import features.report.IReportService
import features.report.ReportDAO
import features.report.reportAdminRoutes
import features.report.ReportService
import features.report.reportRoutes
import io.ktor.server.application.*
import io.ktor.server.routing.*
import io.mockk.coEvery
import io.mockk.mockk
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import org.koin.ktor.ext.getKoin
import java.nio.file.Files

object TestEnv {
    val JWT_SECRET: String = "test-${java.util.UUID.randomUUID()}"
    const val JWT_ISSUER = "test-issuer"
    const val JWT_AUDIENCE = "test-audience"
}

/**
 * Seteaz-un set minim de variabile de mediu pentru testele de route.
 * Îl apelăm în @BeforeAll înainte de testApplication.
 */
fun setTestEnv() {
    // Hack pentru setarea env în test (JVM-internal, doar pentru teste)
    // Alternativ: folosește system properties și citește din ele în cod.
    setEnv("JWT_SECRET", TestEnv.JWT_SECRET)
    setEnv("JWT_ISSUER", TestEnv.JWT_ISSUER)
    setEnv("JWT_AUDIENCE", TestEnv.JWT_AUDIENCE)
}

/**
 * Setează o variabilă de mediu pentru procesul JVM curent.
 * Funcționează pe majoritatea JDK-urilor prin reflecție asupra mapei interne.
 */
@Suppress("UNCHECKED_CAST")
private fun setEnv(key: String, value: String) {
    try {
        val env = System.getenv()
        val cl = env.javaClass
        val field = cl.getDeclaredField("m")
        field.isAccessible = true
        val writable = field.get(env) as MutableMap<String, String>
        writable[key] = value
    } catch (_: Exception) {
        // Fallback: system property. Dacă ajungem aici, codul care citește
        // System.getenv() nu va vedea valoarea; e responsabilitatea apelantului
        // să folosească un workaround.
        System.setProperty(key, value)
    }
}

/**
 * Construiește modulul Ktor pentru testele de route.
 * NU invocă configureDatabases (DB-ul e deja pornit de TestDatabaseFactory).
 * NU invocă configureSockets / configureHTTP (inutile pentru testele /auth).
 */
fun Application.testAuthModule(
    googleTokenVerifier: GoogleTokenVerifier,
    storage: IStorageService? = null,
    waitlistLookupService: IWaitlistLookupService = mockk(relaxed = true),
) {
    val uploadsDir = Files.createTempDirectory("auth-route-test-uploads")
    val koinTestModule = module {
        single<IAuthDAO> { AuthDAO() }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single<IUserDAO> { UserDao() }
        single<IStorageService> { storage ?: LocalImageStorageService(uploadsDir, "http://localhost:8080") }
        single<IUserService> { UserService(get(), get()) }
        single<GoogleTokenVerifier> { googleTokenVerifier }
        single<IWaitlistLookupService> { waitlistLookupService }
        single<IAuthService> { AuthService(get(), get(), get(), get()) }
        single<ILikeDAO> { LikeDAO() }
        single<ILeaderboardDAO> { LeaderboardDAO() }
        single<ILeaderboardSnapshotDAO> { LeaderboardSnapshotDAO() }
        single<ILeaderboardService> { LeaderboardService(get(), get(), get()) }
        single<IAccountDeletionFeedbackDAO> { AccountDeletionFeedbackDAO() }
        single<IAccountDeletionService> { AccountDeletionService(get(), get(), get(), get(), get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) {
        modules(koinTestModule)
    }

    configureSerialization()
    configureAuthStatusPages()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            authRoutes()
            userRoutes()
        }
    }
}

/**
 * Modul Ktor pentru testele rutei /car-models.
 * NU invocă configureDatabases (DB-ul e deja pornit de TestDatabaseFactory).
 * NU pornește Koin pentru auth — ține doar dependențele necesare aici.
 */
fun Application.testCarModelModule() {
    val koinTestModule = module {
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single<ICarModelDAO> { CarModelDAO() }
        single<ICarModelService> { CarModelService(get()) }
    }

    install(Koin) {
        modules(koinTestModule)
    }

    configureSerialization()

    install(RoutingRoot)

    routing {
        route("/api") {
            carModelRoutes()
        }
    }
}

/**
 * Modul Ktor pentru testele rutei /posts/{postId}/comments și /comments/{id}.
 * Folosește același config JWT ca testele de auth.
 */
fun Application.testCommentModule(storage: IStorageService? = null) {
    val uploadsDir = Files.createTempDirectory("comment-route-test-uploads")
    val koinTestModule = module {
        single<ICommentDAO> { CommentDAO() }
        single<IPostDAO> { PostDAO() }
        single<IUserDAO> { UserDao() }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single<IStorageService> { storage ?: LocalImageStorageService(uploadsDir, "http://localhost:8080") }
        single<IScoringDao> { ScoringDaoImpl() }
        single<IScoringService> { ScoringServiceImpl(get(), get(), get()) }
        single<ICommentService> { CommentService(get(), get(), get(), get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())  // instalează autentificarea "jwt" cu setările din TestEnv

    install(RoutingRoot)

    routing {
        route("/api") {
            commentRoutes()
        }
    }
}

/**
 * Modul Ktor pentru testele rutelor /posts/{postId}/likes.
 * Folosește același config JWT ca testele de auth.
 */
fun Application.testLikeModule() {
    val koinTestModule = module {
        single<ILikeDAO> { LikeDAO() }
        single<IPostDAO> { PostDAO() }
        single<IUserDAO> { UserDao() }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single<IScoringDao> { ScoringDaoImpl() }
        single<IScoringService> { ScoringServiceImpl(get(), get(), get()) }
        single<ILikeService> { LikeService(get(), get(), get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            likeRoutes()
        }
    }
}

/**
 * Modul Ktor pentru testele rutei /posts/{postId}/reports.
 * Folosește același config JWT ca testele de auth.
 */
fun Application.testReportModule() {
    val koinTestModule = module {
        single<IReportDAO> { ReportDAO() }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        // Report submission (the only route this module wires) never calls removePostAsModerator,
        // so a mock is enough here — unlike testPostModule(), which needs the real challenge stack.
        single<IPostService> { mockk(relaxed = true) }
        single<IReportService> { ReportService(get(), get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            reportRoutes()
        }
    }
}

/**
 * Modul Ktor pentru testele rutelor /feedback.
 * Folosește același config JWT ca testele de auth.
 */
fun Application.testFeedbackModule() {
    val koinTestModule = module {
        single<IFeedbackDAO> { FeedbackDAO() }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single<IFeedbackService> { FeedbackService(get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            feedbackRoutes()
        }
    }
}

/**
 * Modul Ktor pentru testele rutelor /notifications.
 * Folosește același config JWT ca testele de auth.
 */
fun Application.testNotificationModule() {
    val koinTestModule = module {
        single<INotificationDAO> { NotificationDAO() }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single<INotificationService> { NotificationService(get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            notificationRoutes()
        }
    }
}

fun Application.testPostModule(storage: IStorageService? = null) {
    val uploadsDir = Files.createTempDirectory("posts-route-test-uploads")
    val koinTestModule = module {
        single<ICarModelDAO> { CarModelDAO() }
        single<IPostDAO> { PostDAO() }
        single<ILikeDAO> { LikeDAO() }
        single<ICommentDAO> { CommentDAO() }
        single<IUserDAO> { UserDao() }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single<IStorageService> { storage ?: LocalImageStorageService(uploadsDir, "http://localhost:8080") }
        single<IScoringDao> { ScoringDaoImpl() }
        single<IScoringService> { ScoringServiceImpl(get(), get(), get()) }
        single<IUserService> { UserService(get(), get()) }
        single<ICarFamilyDAO> { CarFamilyDAO() }
        single<IChallengeDAO> { ChallengeDAO() }
        single<IChallengeProgressDAO> { ChallengeProgressDAO() }
        single<IChallengeProgressService> { ChallengeProgressService(get(), get()) }
        single<IPostRemovalDAO> { PostRemovalDAO(get(), get()) }
        single<IPostService> { PostServiceImpl(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            postRoutes()
        }
    }
}

/**
 * Ktor module for the admin post-moderation routes (/api/admin/posts, /api/admin/storage).
 * Wires the same real post/challenge/scoring stack as [testPostModule] plus the moderation and
 * notification DAOs, so a removal exercises the full atomic transaction end to end.
 */
fun Application.testModerationModule() {
    val uploadsDir = Files.createTempDirectory("moderation-route-test-uploads")
    val koinTestModule = module {
        single<ICarModelDAO> { CarModelDAO() }
        single<IPostDAO> { PostDAO() }
        single<ILikeDAO> { LikeDAO() }
        single<ICommentDAO> { CommentDAO() }
        single<IUserDAO> { UserDao() }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single<IStorageService> { LocalImageStorageService(uploadsDir, "http://localhost:8080") }
        single<IScoringDao> { ScoringDaoImpl() }
        single<IScoringService> { ScoringServiceImpl(get(), get(), get()) }
        single<IUserService> { UserService(get(), get()) }
        single<ICarFamilyDAO> { CarFamilyDAO() }
        single<IChallengeDAO> { ChallengeDAO() }
        single<IChallengeProgressDAO> { ChallengeProgressDAO() }
        single<IChallengeProgressService> { ChallengeProgressService(get(), get()) }
        single<INotificationDAO> { NotificationDAO() }
        single<INotificationService> { NotificationService(get()) }
        single<IAdminAuditLogDAO> { AdminAuditLogDAO() }
        single<IModerationViolationDAO> { ModerationViolationDAO(get()) }
        single<IOrphanedStorageObjectDAO> { OrphanedStorageObjectDAO() }
        single<IAdminUserQueryDAO> { AdminUserQueryDAO() }
        single<IBanDAO> { BanDAO(get(), get()) }
        single<IPostRemovalDAO> { PostRemovalDAO(get(), get(), get(), get(), get()) }
        single<IPostService> { PostServiceImpl(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single<IModerationService> { ModerationService(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            moderationAdminRoutes()
        }
    }
}

/**
 * Ktor module for the admin moderation queue (/api/admin/reports). Takes the IReportService as a
 * parameter so a test can stub how resolveReport fails — the point of these tests is the HTTP
 * mapping of domain exceptions, not the service's own logic.
 */
fun Application.testReportAdminModule(reportService: IReportService) {
    val koinTestModule = module {
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single<IReportService> { reportService }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            reportAdminRoutes()
        }
    }
}

/**
 * Both challenge-admin and car-family-admin routes, with the real DAO/service stack (not mocks)
 * behind them — these tests exercise actual DB behavior (pagination, atomic assignment, DRAFT
 * status races), and the end-to-end admin workflow test spans both route groups in one flow.
 */
fun Application.testChallengeAdminModule(cronSecret: String? = null) {
    val koinTestModule = module {
        single<ICarModelDAO> { CarModelDAO() }
        single<ICarFamilyDAO> { CarFamilyDAO() }
        single<ICarFamilyService> { CarFamilyService(get(), get()) }
        single<IChallengeDAO> { ChallengeDAO() }
        single<IChallengeProgressDAO> { ChallengeProgressDAO() }
        single<IChallengeService> { ChallengeService(get(), get(), get()) }
        single<IChallengeProgressService> { ChallengeProgressService(get(), get()) }
        single<IChallengeFinalizationService> { ChallengeFinalizationService(get(), get()) }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            challengeAdminRoutes(cronSecretProvider = { cronSecret })
            carFamilyAdminRoutes()
        }
    }
}

/**
 * The read-only, user-facing challenge routes (GET /challenges/current, GET /challenges/{id}/progress),
 * gated by the plain "jwt" realm (not "admin") — same real DAO/service stack as
 * [testChallengeAdminModule], so an admin-created/published challenge in one test is visible via
 * these routes without any mocking.
 */
fun Application.testChallengeModule() {
    val koinTestModule = module {
        single<ICarModelDAO> { CarModelDAO() }
        single<ICarFamilyDAO> { CarFamilyDAO() }
        single<ICarFamilyService> { CarFamilyService(get(), get()) }
        single<IChallengeDAO> { ChallengeDAO() }
        single<IChallengeProgressDAO> { ChallengeProgressDAO() }
        single<IChallengeService> { ChallengeService(get(), get(), get()) }
        single<IChallengeProgressService> { ChallengeProgressService(get(), get()) }
        single<IChallengeFinalizationService> { ChallengeFinalizationService(get(), get()) }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            challengeRoutes()
        }
    }
}

fun Application.testUserCarModule(storage: IStorageService? = null) {
    val uploadsDir = Files.createTempDirectory("user-car-route-test-uploads")
    val koinTestModule = module {
        single<ICarModelDAO> { CarModelDAO() }
        single<IUserCarDAO> { UserCarDAO() }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single<IStorageService> { storage ?: LocalImageStorageService(uploadsDir, "http://localhost:8080") }
        single<IUserCarService> { UserCarServiceImpl(get(), get(), get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            userCarRoutes()
        }
    }
}

fun Application.testUserModule(storage: IStorageService? = null) {
    val uploadsDir = Files.createTempDirectory("user-route-test-uploads")
    val koinTestModule = module {
        single<IUserDAO> { UserDao() }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single<IStorageService> { storage ?: LocalImageStorageService(uploadsDir, "http://localhost:8080") }
        single<IUserService> { UserService(get(), get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            userRoutes()
        }
    }
}

fun Application.testLeaderboardModule(storage: IStorageService? = null) {
    val uploadsDir = Files.createTempDirectory("leaderboard-route-test-uploads")
    val koinTestModule = module {
        single<ILeaderboardDAO> { LeaderboardDAO() }
        single<ILeaderboardSnapshotDAO> { LeaderboardSnapshotDAO() }
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single { RefreshTokenGenerator() }
        single<ISessionService> { SessionService(get(), get()) }
        single<IStorageService> { storage ?: LocalImageStorageService(uploadsDir, "http://localhost:8080") }
        single<ILeaderboardService> { LeaderboardService(get(), get(), get()) }
        single {
            JwtService(
                jwtSecret = TestEnv.JWT_SECRET,
                jwtIssuer = TestEnv.JWT_ISSUER,
                jwtAudience = TestEnv.JWT_AUDIENCE,
            )
        }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()
    configureSecurity(getKoin().get())

    install(RoutingRoot)

    routing {
        route("/api") {
            leaderboardRoutes()
        }
    }
}

fun Application.testAdminLeaderboardModule(adminToken: String, cronSecret: String? = null) {
    val koinTestModule = module {
        single<IAuthSessionDAO> { AuthSessionDAO() }
        single<ILeaderboardSnapshotDAO> { LeaderboardSnapshotDAO() }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()

    install(RoutingRoot)

    routing {
        route("/api") {
            adminLeaderboardRoutes(adminTokenProvider = { adminToken }, cronSecretProvider = { cronSecret })
        }
    }
}

/**
 * The Supabase client is mocked (always returns an empty page) — route tests exercise the
 * secret gate, response shape, and idempotency, not the real Supabase HTTP call, which is
 * already covered by WaitlistSyncServiceTest with the same mocking approach.
 */
fun Application.testWaitlistModule(cronSecret: String? = null, webhookSecret: String? = null) {
    val koinTestModule = module {
        single<IWaitlistDAO> { WaitlistDAO() }
        single<ISupabaseWaitlistClient> {
            mockk<ISupabaseWaitlistClient>().also {
                coEvery { it.fetchPage(any(), any(), any()) } returns emptyList()
            }
        }
        single<IWaitlistSyncService> { WaitlistSyncService(get(), get()) }
    }

    install(Koin) { modules(koinTestModule) }

    configureSerialization()

    install(RoutingRoot)

    routing {
        route("/api") {
            waitlistRoutes(cronSecretProvider = { cronSecret }, webhookSecretProvider = { webhookSecret })
        }
    }
}

/**
 * Helper pentru a opri Koin între teste (important, altfel a doua rulare crapă).
 */
fun stopKoinSafely() {
    try {
        stopKoin()
    } catch (_: Throwable) {
        // idempotent
    }
}
