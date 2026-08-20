package ai.nodera.app

import ai.nodera.api.rest.ReadinessReport
import ai.nodera.persistence.SchemaState

private const val CURRENT = "schema is current"

/**
 * Deliberately without the driver's exception class. `/health/ready` is unauthenticated and, in the
 * published compose topology, reachable from the host — a class name is a technology fingerprint
 * offered to anyone who asks. The category is logged instead, where the operator can see it and a
 * stranger cannot.
 */
private const val UNREACHABLE = "database unreachable"

/**
 * Maps the schema state onto the readiness answer.
 *
 * Extracted from the server wiring and made `internal` so all three branches are reachable from a
 * test. Left inline it was the one place a `SchemaState` became a `ReadinessReport`, nothing
 * constructed it, and turning `Unreachable` into `ready = true` would have left every test green
 * while four places in the tree went on promising the opposite.
 *
 * The polarity is the invariant: **unknown is never ready.** A probe that could not read the
 * migration history does not know the schema is current, and an instance that cannot prove it is
 * current must not receive traffic.
 */
internal fun readinessReport(state: SchemaState): ReadinessReport =
    when (state) {
        is SchemaState.UpToDate -> ReadinessReport(ready = true, detail = CURRENT)

        is SchemaState.Pending ->
            ReadinessReport(ready = false, detail = "${state.count} migration(s) pending")

        is SchemaState.Unreachable -> ReadinessReport(ready = false, detail = UNREACHABLE)
    }
