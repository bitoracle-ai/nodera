package ai.nodera.domain.audit

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class AuditEventTest :
    StringSpec({

        // Guard: the require() in AuditAction. Drop it and the domain accepts what V4's check
        // constraint refuses, which turns a validation error into an insert that fails at the end
        // of the mutation's own transaction.
        "an audit action matches the column's own grammar" {
            AuditAction("ticket.closed").value shouldBe "ticket.closed"
            AuditAction("review_finding.resolved").value shouldBe "review_finding.resolved"

            shouldThrow<IllegalArgumentException> { AuditAction("ticket") }
            shouldThrow<IllegalArgumentException> { AuditAction("Ticket.Closed") }
            shouldThrow<IllegalArgumentException> { AuditAction("ticket.closed.twice") }
            shouldThrow<IllegalArgumentException> { AuditAction("") }
        }

        "an entry names the kind of entity it happened to" {
            shouldThrow<IllegalArgumentException> {
                AuditEntry(action = AuditAction("ticket.closed"), entityType = "  ")
            }
        }

        "an entry records a success unless it says otherwise" {
            AuditEntry(action = AuditAction("ticket.closed"), entityType = "ticket").outcome shouldBe
                AuditOutcome.SUCCESS
        }
    })
