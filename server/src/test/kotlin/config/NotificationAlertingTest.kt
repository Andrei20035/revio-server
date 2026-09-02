package com.revio.server.config

import com.revio.server.features.notification.INotificationOutboxDAO
import com.revio.server.features.notification.NotificationOutboxEntry
import io.ktor.server.application.*
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

/**
 * configureNotificationAlertEvaluator's wiring (step 4.4): flag gating and clean shutdown,
 * mirroring PushDispatcherTest.kt exactly. The loop's own scheduling/delta-composition behavior
 * is covered by NotificationAlertEvaluatorLoopTest.kt; this only checks the on/off wiring and
 * that ApplicationStopping actually stops what was started.
 */
class NotificationAlertingTest {

    private class FakeOutboxDAO : INotificationOutboxDAO {
        override suspend fun enqueue(
            notificationId: java.util.UUID,
            deviceId: java.util.UUID,
            notBefore: java.time.OffsetDateTime?,
            expiresAt: java.time.OffsetDateTime?,
        ) = Unit

        override suspend fun find(notificationId: java.util.UUID, deviceId: java.util.UUID): NotificationOutboxEntry? = null

        override suspend fun findById(id: java.util.UUID): NotificationOutboxEntry? = null

        override suspend fun findDrainable(limit: Int): List<NotificationOutboxEntry> = emptyList()

        override suspend fun markAccepted(id: java.util.UUID, fcmMessageId: String) = Unit

        override suspend fun markRetriableFailure(
            id: java.util.UUID,
            attempts: Int,
            nextAttemptAt: java.time.OffsetDateTime,
            lastErrorCode: String?,
        ) = Unit

        override suspend fun markDead(id: java.util.UUID, lastErrorCode: String?) = Unit

        override suspend fun markDropped(id: java.util.UUID) = Unit

        override suspend fun countQueued(): Long = 0
    }

    private fun testModule() = module {
        single<INotificationOutboxDAO> { FakeOutboxDAO() }
    }

    @Test
    fun `flag off never creates an alert loop`() = testApplication {
        var result: Any? = "unset"
        application {
            install(Koin) { modules(testModule()) }
            result = configureNotificationAlertEvaluator(enabledProvider = { null })
        }
        startApplication()
        assertNull(result)
    }

    @Test
    fun `flag false (explicitly) never creates an alert loop`() = testApplication {
        var result: Any? = "unset"
        application {
            install(Koin) { modules(testModule()) }
            result = configureNotificationAlertEvaluator(enabledProvider = { "false" })
        }
        startApplication()
        assertNull(result)
    }

    @Test
    fun `flag on creates a running loop, which ApplicationStopping shuts down cleanly`() = testApplication {
        application {
            install(Koin) { modules(testModule()) }
            val loop = configureNotificationAlertEvaluator(enabledProvider = { "true" })

            assertTrue(loop?.isRunning == true, "loop should be running once started")

            environment.monitor.raise(ApplicationStopping, this)

            assertFalse(loop?.isRunning == true, "loop should be stopped once ApplicationStopping fires")
        }
        startApplication()
    }
}
