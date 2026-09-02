package ai.nodera.application.audit

import ai.nodera.domain.audit.AuditEvent

/**
 * The one write the trail has — append-only in the port's own shape.
 *
 * The implementation writes in the **caller's** transaction and refuses when none is open; one that
 * opened its own would let the mutation and its audit row commit independently.
 */
public interface AuditEventSink {
    public suspend fun append(event: AuditEvent)
}
