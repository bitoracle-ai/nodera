package ai.nodera.domain.collaboration

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain

private val PAYLOADS =
    listOf(
        "<script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "<iframe src='javascript:alert(1)'></iframe>",
        "<!-- <script>alert(1)</script> -->",
        "<svg/onload=alert(1)>",
        "a < b and c <d> e",
    )

/**
 * Every shape that once carried a payload past this file, kept as a regression corpus.
 *
 * All of them are escaped now by construction rather than by a rule — there is no code exemption
 * left to get wrong — so none of these can single out a defect any more. They stay because they are
 * the record of what four review rounds actually found, and because the day somebody reintroduces an
 * exemption, this list is what it has to survive.
 *
 * The three groups, in the order the rounds found them: a backtick run that opened a span the
 * renderer did not (rounds 1–3), a fence the scanner opened or closed where the renderer would not
 * (round 4), and the line-ending and indentation variants of both.
 */
private val ONCE_LEAKED =
    listOf(
        "{}",
        "\\`x {} y\\`",
        "`{}`",
        "use ` for code\n`{}`",
        "`\n`{}`",
        "``\n``{}``",
        "\\``x`{}`",
        "`a\n{}\nc`",
        "`a\n\n{}\n\nc`",
        "`a\r\r{}\r\rc`",
        "`a\r\n\r\n{}\r\n\r\nc`",
        "`a\n# {}\nc`",
        "`a\n> {}\nc`",
        "`a\n- {}\nc`",
        "`a\n***\n{}\nc`",
        "`a\n    {}\nc`",
        "``x {} y`",
        "`x {} y",
        "```x``` {}",
        "~~x {} y~~",
        "    {}",
        "~~~\r~~~\r{}",
        "```\n```\r{}",
        "```\n```\r\n{}",
        "```\r\n```\r\n{}",
        "hi\n\n```\n```\r{}\n\nbye",
        "x ```\n{}\n```",
        "```\n    ```\n```\n{}\n",
        "```\n\t```\n```\n{}\n",
        " ```\nfoo\n```\n{}\n",
        "   ```\nfoo\n```\n{}\n",
        "  ~~~\nfoo\n~~~\n{}\n",
        "- ```\n  foo\n  ```\n{}\n",
        "```\nfoo\n```\n{}\n",
    )

class CommentMarkdownTest :
    StringSpec({

        // The claim the sanitiser rests on, and now the whole of it: nothing can open a tag.
        PAYLOADS.forEach { payload ->
            "a script payload loses every angle bracket that could open a tag: $payload" {
                val sanitised = sanitiseMarkdown(payload)

                sanitised shouldNotContain "<"
                sanitised shouldContain "&lt;"
            }
        }

        /*
         * The property, over a corpus, rather than a test per payload.
         *
         * Four rounds of phase 4 broke this file, and each round broke the previous round's fix,
         * because every fix was pinned by the input that defeated it. The claim is one sentence — no
         * payload survives any of these shapes — and it holds for every shape now, not only these.
         */
        ONCE_LEAKED.forEach { wrapper ->
            PAYLOADS.forEach { payload ->
                "no angle bracket survives ${wrapper.describe()} around ${payload.describe()}" {
                    sanitiseMarkdown(wrapper.replace("{}", payload)) shouldNotContain "<"
                }
            }
        }

        // The price of having no code exemption, asserted so that changing it is a decision and not
        // a drift. Both halves: inline and fenced.
        "a code sample's angle brackets are escaped like any others" {
            sanitiseMarkdown("`Map<String, Int>`") shouldBe "`Map&lt;String, Int>`"
            sanitiseMarkdown("```\n<script>alert(1)</script>\n```") shouldBe
                "```\n&lt;script>alert(1)&lt;/script>\n```"
        }

        "a body with nothing to escape comes back unchanged" {
            listOf("plain prose", "```\ncode()\n```", "@anna please look").forEach {
                sanitiseMarkdown(it) shouldBe it
            }
        }

        // An edit re-sanitises the stored body, and the read path re-sanitises again. A
        // transformation that is not idempotent eats one more character of the author's text on
        // every pass.
        (PAYLOADS + ONCE_LEAKED).forEach { body ->
            "sanitising is idempotent: ${body.describe()}" {
                val once = sanitiseMarkdown(body)

                sanitiseMarkdown(once) shouldBe once
            }
        }

        "a mention is found in prose, in order of first appearance" {
            mentionedHandles("@anna asked @deploy-bot to look").map { it.value } shouldContainExactly
                listOf("anna", "deploy-bot")
        }

        // comment_mention is keyed (comment_id, actor_id): the second row is a primary-key
        // violation where the answer is one row.
        "a handle repeated in one body is mentioned once" {
            mentionedHandles("@anna and @anna again").map { it.value } shouldContainExactly listOf("anna")
        }

        "an address is not a mention" {
            mentionedHandles("write to user@example.com").shouldBeEmpty()
        }

        // The other half of having no code exemption, asserted rather than left as a surprise: an
        // unwanted notification is a cost worth paying to remove a whole class of unescaped tag.
        "a handle inside a code sample is still a mention" {
            mentionedHandles("run `notify @deploy-bot` now").map { it.value } shouldContainExactly
                listOf("deploy-bot")
            mentionedHandles("look at\n```\n@deploy-bot\n```").map { it.value } shouldContainExactly
                listOf("deploy-bot")
        }

        "trailing punctuation is not part of a handle" {
            mentionedHandles("ask @anna.").map { it.value } shouldContainExactly listOf("anna")
            mentionedHandles("ask @a.b.c, then").map { it.value } shouldContainExactly listOf("a.b.c")
        }

        "a bare at-sign names nobody" {
            mentionedHandles("@ @. @-x").shouldBeEmpty()
        }

        // The extraction runs on the sanitised text, so what the rows describe is what was stored.
        "a mention survives sanitising" {
            mentionedHandles(sanitiseMarkdown("<b>@anna</b>")).map { it.value } shouldContainExactly listOf("anna")
        }
    })

/** A short, stable name for a test title — the corpus entries carry line breaks. */
private fun String.describe(): String =
    replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t").let {
        if (it.length <= 40) it else it.take(37) + "..."
    }
