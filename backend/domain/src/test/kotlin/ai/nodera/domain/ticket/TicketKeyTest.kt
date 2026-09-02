package ai.nodera.domain.ticket

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class TicketKeyTest :
    StringSpec({

        // V2 checks `key = prefix || '-' || number::text`, so a key rendered any other way is a row
        // the database refuses.
        "a key renders as the column's own form" {
            TicketKey(TicketPrefix("core"), TicketNumber(12)).rendered shouldBe "core-12"
        }

        listOf("Core", "1core", "core-01", "", "a".repeat(17), "core prefix").forEach { candidate ->
            "the prefix '$candidate' is refused" {
                shouldThrow<IllegalArgumentException> { TicketPrefix(candidate) }
            }
        }

        listOf("c", "core", "core_2", "a".repeat(16)).forEach { candidate ->
            "the prefix '$candidate' is accepted" {
                TicketPrefix(candidate).value shouldBe candidate
            }
        }

        "a ticket number is positive" {
            shouldThrow<IllegalArgumentException> { TicketNumber(0) }
            shouldThrow<IllegalArgumentException> { TicketNumber(-1) }
            TicketNumber(1).value shouldBe 1
        }

        "a draft title is bounded the way the column is" {
            shouldThrow<IllegalArgumentException> { TicketDraft(TicketPrefix("core"), "   ") }
            shouldThrow<IllegalArgumentException> { TicketDraft(TicketPrefix("core"), "t".repeat(301)) }
            TicketDraft(TicketPrefix("core"), "t".repeat(300)).title.length shouldBe 300
        }
    })
