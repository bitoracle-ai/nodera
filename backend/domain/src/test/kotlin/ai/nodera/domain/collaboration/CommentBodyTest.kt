package ai.nodera.domain.collaboration

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

class CommentBodyTest :
    StringSpec({

        // The one way to build a body runs the sanitiser, so "the stored text has been through it"
        // is a property of the type rather than of every caller's care.
        "a body can only be built through the sanitiser" {
            CommentBody.of("<script>alert(1)</script>").value shouldNotContain "<"
        }

        // V3: `check (deleted_at is not null or length(trim(body)) > 0)`, restated on the text the
        // insert will actually carry.
        "a blank body is refused before any statement runs" {
            listOf("", "   ", "\n\t ").forEach {
                shouldThrow<IllegalArgumentException> { CommentBody.of(it) }
            }
        }

        "a body that is only markup is kept, because escaping never empties one" {
            CommentBody.of("<b></b>").value shouldBe "&lt;b>&lt;/b>"
        }
    })
