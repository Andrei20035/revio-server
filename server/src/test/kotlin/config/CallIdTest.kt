package com.revio.server.config

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingRoot
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.MDC
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallIdTest {

    private fun ApplicationTestBuilder.installTestApp() {
        application {
            configureCallId()
            install(RoutingRoot)
            routing {
                get("/echo") {
                    call.respondText("ok")
                }
                // Echoes what MDC actually holds while handling the request — this is what a
                // log line emitted from a route/service/DAO would see via %X{callId}.
                get("/mdc") {
                    call.respondText(MDC.get(CALL_ID_MDC_KEY) ?: "")
                }
                // Same, but after a dispatcher switch (like a DB call would cause) — proves the
                // MDC value survives resuming on a different thread, not just a plain MDC.put.
                get("/mdc-after-thread-hop") {
                    val afterHop = withContext(Dispatchers.IO) { MDC.get(CALL_ID_MDC_KEY) }
                    call.respondText(afterHop ?: "")
                }
            }
        }
    }

    @Test
    fun `X-Request-Id trimis de client este propagat neschimbat pe raspuns`() = testApplication {
        installTestApp()

        val response = client.get("/echo") {
            header(HttpHeaders.XRequestId, "client-supplied-id-123")
        }

        assertEquals("client-supplied-id-123", response.headers[HttpHeaders.XRequestId])
    }

    @Test
    fun `fara X-Request-Id de la client - serverul genereaza unul si il intoarce`() = testApplication {
        installTestApp()

        val response = client.get("/echo")

        val generated = response.headers[HttpHeaders.XRequestId]
        assertNotNull(generated)
        assertTrue(generated.isNotBlank())
    }

    @Test
    fun `doua request-uri fara header primesc id-uri generate diferite`() = testApplication {
        installTestApp()

        val first = client.get("/echo").headers[HttpHeaders.XRequestId]
        val second = client.get("/echo").headers[HttpHeaders.XRequestId]

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first != second)
    }

    // ----------------------------------------------------------------------
    // pas 3.2a — callId în MDC pentru tot pipeline-ul din aval, nu doar plugin-ul CallId
    // ----------------------------------------------------------------------

    @Test
    fun `MDC contine acelasi callId cat timp e procesat request-ul`() = testApplication {
        installTestApp()

        val response = client.get("/mdc") {
            header(HttpHeaders.XRequestId, "mdc-check-id-456")
        }

        assertEquals("mdc-check-id-456", response.bodyAsText())
    }

    @Test
    fun `MDC supravietuieste unui schimb de thread (dispatcher switch)`() = testApplication {
        installTestApp()

        val response = client.get("/mdc-after-thread-hop") {
            header(HttpHeaders.XRequestId, "mdc-thread-hop-id-789")
        }

        assertEquals("mdc-thread-hop-id-789", response.bodyAsText())
    }

    @Test
    fun `MDC nu se scurge intre request-uri`() = testApplication {
        installTestApp()

        client.get("/mdc") { header(HttpHeaders.XRequestId, "leak-check-id") }

        // Thread-ul de test în sine nu a trecut niciodată prin interceptorul de MDC — trebuie
        // să rămână curat, indiferent de ce s-a întâmplat pe thread-urile serverului.
        assertNull(MDC.get(CALL_ID_MDC_KEY))
    }
}
