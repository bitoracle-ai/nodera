package ai.nodera.application.audit

import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.actor.ActorKind
import ai.nodera.domain.actor.RequestId
import ai.nodera.domain.actor.Surface
import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.audit.AuditEvent
import ai.nodera.domain.audit.AuditOutcome
import ai.nodera.domain.audit.DENIED_CAPABILITY_KEY
import ai.nodera.domain.permission.Capability
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

private val AGENT = ActorId(Uuid.parse("00000000-0000-4000-8000-000000000002"))
private val ANNA = ActorId(Uuid.parse("00000000-0000-4000-8000-000000000001"))

private val DELEGATED =
    ActorContext(
        actorId = AGENT,
        kind = ActorKind.AGENT,
        surface = Surface.MCP,
        onBehalfOf = ANNA,
        requestId = RequestId(Uuid.parse("66666666-6666-4666-8666-666666666666")),
    )

private val CLOSING =
    AuditEntry(
        action = AuditAction("ticket.closed"),
        entityType = "ticket",
        diff = AuditDiff(before = mapOf("status" to "in_review"), after = mapOf("status" to "done")),
    )

private class RecordingSink : AuditEventSink {
    val appended: MutableList<AuditEvent> = mutableListOf()

    override suspend fun append(event: AuditEvent) {
        appended += event
    }
}

class AuditRecorderTest :
    StringSpec({

        "one call appends exactly one event" {
            val sink = RecordingSink()

            AuditRecorder(sink).record(DELEGATED, CLOSING)

            sink.appended.size shouldBe 1
            sink.appended.single().entry shouldBe CLOSING
        }

        // The delegation chain (invariant AU4) and the denormalised kind (AU2) are taken from the
        // context, never from the caller's arguments — there is no parameter that could omit them.
        "the event carries the acting context unchanged" {
            val sink = RecordingSink()

            AuditRecorder(sink).record(DELEGATED, CLOSING)

            sink.appended.single().context shouldBe DELEGATED
        }

        "a denial is recorded as one denied event naming the verb the actor lacked" {
            val sink = RecordingSink()

            AuditRecorder(sink).recordDenied(DELEGATED, CLOSING, Capability.TICKET_CLOSE)

            val recorded = sink.appended.single().entry
            recorded.outcome shouldBe AuditOutcome.DENIED
            recorded.diff.after shouldContainExactly mapOf(DENIED_CAPABILITY_KEY to Capability.TICKET_CLOSE.verb)
        }

        // `before`/`after` are the changed fields, and a denial changed none — so the state the
        // caller was aiming for must not survive into `after`, where a reader would take it for a
        // state the ticket actually reached.
        "a denial does not record the state it was refused, only the state that stands" {
            val sink = RecordingSink()

            AuditRecorder(sink).recordDenied(DELEGATED, CLOSING, Capability.TICKET_CLOSE)

            val recorded = sink.appended.single().entry
            recorded.diff.after.keys shouldBe setOf(DENIED_CAPABILITY_KEY)
            recorded.diff.before shouldContainExactly CLOSING.diff.before
            recorded.action shouldBe CLOSING.action
            recorded.entityType shouldBe CLOSING.entityType
        }
    })
