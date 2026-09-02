package com.revio.server.config

import com.revio.server.features.notification.INotificationOutboxDAO
import com.revio.server.features.notification.NotificationAlertEvaluatorLoop
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.ktor.ext.inject

/**
 * Wires [NotificationAlertEvaluatorLoop] (step 4.4) into the application lifecycle — same
 * opt-in-by-env-var gate as [configurePushDispatcher], since these alerts (queue depth,
 * dispatcher stalled, dead rate) are about the dispatcher's own health and are meaningless noise
 * while it isn't even running.
 *
 * @return the started [NotificationAlertEvaluatorLoop], or null if disabled.
 */
fun Application.configureNotificationAlertEvaluator(
    enabledProvider: () -> String? = { System.getenv("ENABLE_PUSH_DISPATCHER") },
): NotificationAlertEvaluatorLoop? {
    if (enabledProvider() != "true") return null

    val outboxDao by inject<INotificationOutboxDAO>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val loop = NotificationAlertEvaluatorLoop(outboxDao)
    loop.start(scope)

    environment.monitor.subscribe(ApplicationStopping) {
        loop.stop()
        scope.cancel()
    }

    return loop
}
