package com.revio.server.config

import com.revio.server.features.notification.INotificationOutboxDAO
import com.revio.server.features.notification.INotificationOutboxProcessor
import com.revio.server.features.notification.PushDispatcherLoop
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.ktor.ext.inject

/**
 * Wires the push notification outbox dispatcher loop (plan §18, step 3.5; D2) into the
 * application lifecycle. Off by default: only starts when `ENABLE_PUSH_DISPATCHER=true`, the same
 * opt-in-by-env-var pattern as `ENABLE_SNAPSHOT_CATCHUP_ON_STARTUP` in Application.kt. Tests never
 * set this env var, and TestApplicationFactory.kt's per-feature test modules don't call
 * `Application.module()` (where this is wired) at all, so the loop never starts under test.
 *
 * @return the started [PushDispatcherLoop], or null if disabled — returned mainly so tests can
 * assert on whether a loop was actually created, without needing to wait on its schedule.
 */
fun Application.configurePushDispatcher(
    enabledProvider: () -> String? = { System.getenv("ENABLE_PUSH_DISPATCHER") },
): PushDispatcherLoop? {
    if (enabledProvider() != "true") return null

    val outboxDao by inject<INotificationOutboxDAO>()
    val processor by inject<INotificationOutboxProcessor>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val loop = PushDispatcherLoop(outboxDao, work = { processor.processDueBatch() })
    loop.start(scope)

    environment.monitor.subscribe(ApplicationStopping) {
        loop.stop()
        scope.cancel()
    }

    return loop
}
