package com.revio.server.config

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.plugins.callid.*
import kotlinx.coroutines.ThreadContextElement
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import java.util.UUID
import kotlin.coroutines.CoroutineContext

/** MDC key read by `%X{callId}` in logback.xml — see pas 3.2a. */
const val CALL_ID_MDC_KEY = "callId"

/**
 * Reads the client's `X-Request-Id` header (pas 1.8, Android), generating one when it's missing,
 * and always echoes it back on the response so client and server logs can be correlated — see
 * pas 3.1b.
 *
 * Also puts the resolved id into MDC (`callId`) for the whole downstream request pipeline —
 * route handlers, services, DAOs — not just this plugin's own concern, so any log line emitted
 * while handling the request can carry it via `%X{callId}` (logback.xml). Wrapped in
 * [CallIdMdcElement] (a minimal [ThreadContextElement], not a plain `MDC.put`/`remove` pair)
 * because Ktor's suspending pipeline can resume on a different thread after a dispatcher switch
 * (e.g. a DB call) — a plain thread-local put wouldn't survive that hop.
 */
fun Application.configureCallId() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        verify { it.isNotBlank() }
        generate { UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }

    intercept(ApplicationCallPipeline.Setup) {
        val id = call.callId
        if (id != null) {
            withContext(CallIdMdcElement(id)) {
                proceed()
            }
        } else {
            proceed()
        }
    }
}

/** Copies [CALL_ID_MDC_KEY] into SLF4J's MDC across every thread a coroutine resumes on. */
private class CallIdMdcElement(private val callId: String) : ThreadContextElement<String?> {
    companion object Key : CoroutineContext.Key<CallIdMdcElement>

    override val key: CoroutineContext.Key<*> get() = Key

    override fun updateThreadContext(context: CoroutineContext): String? {
        val previous = MDC.get(CALL_ID_MDC_KEY)
        MDC.put(CALL_ID_MDC_KEY, callId)
        return previous
    }

    override fun restoreThreadContext(context: CoroutineContext, oldState: String?) {
        if (oldState == null) MDC.remove(CALL_ID_MDC_KEY) else MDC.put(CALL_ID_MDC_KEY, oldState)
    }
}
