package ai.nodera.api.rest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication

/**
 * Liveness and readiness are different questions, and the tests exist to keep them different.
 *
 * The expensive mistake is wiring liveness to the database: an orchestrator then destroys a healthy
 * container every time the database blinks or a migration is still running, turning a wait into a
 * crash loop. Readiness removes an instance from rotation; liveness destroys it.
 */
class HealthTest : StringSpec({

    "readiness reports ready when the schema is current" {
        testApplication {
            health { ReadinessReport(ready = true, detail = "schema is current") }
            val response = client.get("/health/ready")

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"status\":\"ready\""
        }
    }

    // Guard: the ready/not-ready branch on the status code. Return OK unconditionally and this
    // goes red — which is the paired negative for "readiness fails while migrations are pending".
    "readiness refuses with 503 while migrations are pending" {
        testApplication {
            health { ReadinessReport(ready = false, detail = "2 migration(s) pending") }
            val response = client.get("/health/ready")

            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.bodyAsText() shouldContain "2 migration(s) pending"
        }
    }

    // Guard: healthRoutes not passing the probe to /health/live. Wire it there and this goes red.
    "liveness stays healthy while readiness reports the database unreachable" {
        testApplication {
            health { error("the probe must never be consulted for liveness") }
            val response = client.get("/health/live")

            response.status shouldBe HttpStatusCode.OK
            response.bodyAsText() shouldContain "\"status\":\"alive\""
        }
    }

    "both endpoints report the build version, so an instance can say what it is" {
        testApplication {
            health(version = "1.4.2") { ReadinessReport(ready = true, detail = "schema is current") }

            client.get("/health/live").bodyAsText() shouldContain "1.4.2"
            client.get("/health/ready").bodyAsText() shouldContain "1.4.2"
        }
    }
})

private fun ApplicationTestBuilder.health(
    version: String = "test",
    probe: suspend () -> ReadinessReport,
) {
    application {
        install(ContentNegotiation) { json() }
        routing { healthRoutes(version, ReadinessProbe { probe() }) }
    }
}
