package ai.nodera.app

import ai.nodera.persistence.SchemaState
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

/**
 * The readiness mapping is where "fail closed" either holds or quietly stops holding, so each
 * branch is pinned here. Without these, turning the `Unreachable` branch into `ready = true` left
 * every backend test green while the code comment, the OpenAPI description, `API_CONTRACT.md` and
 * the ADR all went on promising that unknown is never ready.
 */
class ReadinessTest :
    StringSpec({

        "reports ready when the schema is current" {
            val report = readinessReport(SchemaState.UpToDate)

            report.ready shouldBe true
            report.detail shouldContain "current"
        }

        // Guard: the Pending branch's `ready = false`. Flip it and this goes red.
        "refuses while migrations are pending, and says how many" {
            val report = readinessReport(SchemaState.Pending(count = 2))

            report.ready shouldBe false
            report.detail shouldContain "2 migration(s) pending"
        }

        // Guard: the Unreachable branch's `ready = false`. This is the fail-closed polarity itself —
        // a probe that could not read the history does not know the schema is current.
        "never reports ready when the database could not be read" {
            readinessReport(SchemaState.Unreachable(category = "FlywaySqlException")).ready shouldBe false
        }

        // Guard: leaving the driver's exception class out of the public detail. Put it back and this
        // goes red. The endpoint is unauthenticated and, in the published compose file, host-reachable.
        "never puts the driver's exception class into the body of an unauthenticated endpoint" {
            val report = readinessReport(SchemaState.Unreachable(category = "FlywaySqlException"))

            report.detail shouldNotContain "Flyway"
            report.detail shouldNotContain "Exception"
        }
    })
