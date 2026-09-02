package ai.nodera.application.audit

import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.audit.AuditEvent
import ai.nodera.domain.audit.AuditOutcome
import ai.nodera.domain.audit.DENIED_CAPABILITY_KEY
import ai.nodera.domain.permission.Capability

/**
 * The single writer of the audit trail (invariant #3).
 *
 * It opens no transaction and decides nothing: the use case is the transaction boundary, and this
 * appends one row inside it. Everything about *who acted* comes from the context rather than from
 * the caller's arguments, which is what makes `actor_kind` (AU2) and `on_behalf_of_actor_id` (AU4)
 * impossible to omit or to misstate.
 */
public class AuditRecorder(
    private val sink: AuditEventSink,
) {
    /** One call, one row. */
    public suspend fun record(
        ctx: ActorContext,
        entry: AuditEntry,
    ) {
        sink.append(AuditEvent(ctx, entry))
    }

    /**
     * Records a refusal, naming the capability the actor lacked.
     *
     * `after` is **replaced**, not extended: `before`/`after` are the changed fields
     * (`docs/DOMAIN_MODEL.md` § 9), a denial changed none, and an `after` carrying the state the
     * caller was aiming for would have the trail assert an entity state that never existed.
     */
    public suspend fun recordDenied(
        ctx: ActorContext,
        entry: AuditEntry,
        missing: Capability,
    ) {
        record(
            ctx,
            entry.copy(
                outcome = AuditOutcome.DENIED,
                diff = AuditDiff(before = entry.diff.before, after = mapOf(DENIED_CAPABILITY_KEY to missing.verb)),
            ),
        )
    }
}
