package ai.nodera.domain.collaboration

import ai.nodera.domain.actor.Handle

private const val ESCAPED_LESS_THAN = "&lt;"

/**
 * The mention grammar, which `Handle`'s own documentation leaves to the package that parses mentions.
 *
 * The lookbehind is what stops `user@example.com` naming `example.com`. Trailing `.` and `-` are
 * dropped afterwards rather than excluded here: a sentence-final period is commoner than a handle
 * that ends in one.
 */
private val MENTION = Regex("""(?<![A-Za-z0-9_])@([A-Za-z0-9][A-Za-z0-9_.-]*)""")

private const val TRAILING_PUNCTUATION = ".-"

/**
 * Raw HTML, neutralised at the one point of entry (`skills/secure-coding.md` § Input validation).
 *
 * A tag needs a `<`, so **every** `<` becomes `&lt;` — which a Markdown renderer emits as a literal
 * `<` in a text node: visible, inert, and not a tag. There is no exemption for code, and that is the
 * whole design: with nothing to classify there is nothing to classify wrongly.
 *
 * **Four review rounds argued for an exemption and each one shipped a hole.** The scanner that
 * preceded this recognised inline code spans, then only fenced blocks; each round produced a body it
 * called code and a renderer called live HTML, and each fix was defeated by the next round. The
 * reason is structural rather than a run of bad luck: a construct this file declines to open leaves
 * its CommonMark partner unconsumed, a later marker pairs with that partner instead, and the result
 * is a stretch marked as code that the renderer reads as a tag. It is true of backtick runs and it
 * is equally true of fences, so "recognise fewer constructs" is not the conservative act it looks
 * like. Agreeing with CommonMark about where code begins requires CommonMark, and this is
 * `:domain`, which is framework-free, on the path that decides whether a script tag is stored.
 *
 * **The price is real and is not hidden:** a `<` inside a code sample is stored as `&lt;` and shows
 * that way, in a fence as much as inline. `docs/plan/CORE-04.md` § 8 raises the fix — the sanitising
 * renderer the same skill assumes, which knows what code is because it parses the document — as a
 * decision for the maintainers rather than taking it here.
 *
 * **`&` is deliberately not escaped.** Nothing can become a tag without a `<`, so escaping `&` buys
 * no safety and costs the author's literal `&amp;`.
 *
 * **Idempotent**, and that is a tested property rather than an observation: the output holds no `<`,
 * so a second pass changes nothing. An edit re-sanitises, and a transformation that is not
 * idempotent eats one more character of the author's text every time.
 *
 * It does **not** filter URL schemes in link destinations — `docs/plan/CORE-04.md` § 4.1 says so
 * rather than leaving it to be discovered.
 */
public fun sanitiseMarkdown(body: String): String = body.replace("<", ESCAPED_LESS_THAN)

/**
 * Who a body mentions, in order of first appearance and without repeats.
 *
 * Repeats are collapsed because `comment_mention` is keyed `(comment_id, actor_id)`: `@anna @anna`
 * without this is a primary-key violation where the answer is one row. Two spellings that differ
 * only in case are not collapsed here — `actor.handle` is `citext` and unique, so they name one
 * actor and the directory returns one row.
 *
 * A handle inside a code sample is a mention, for the same reason [sanitiseMarkdown] escapes one:
 * nothing here knows what code is. The consequence has the opposite polarity and is why it is
 * acceptable — an unwanted notification rather than an unescaped tag.
 */
public fun mentionedHandles(body: String): List<Handle> =
    MENTION
        .findAll(body)
        // No emptiness filter after the trim: the grammar's first character is a letter or a digit
        // and never punctuation, so trimming cannot empty the match. One was here and could not
        // execute, which in this file of all files is worth removing rather than leaving to read as
        // a guard. `Handle` refuses a blank one loudly if that ever stops being true.
        .map { match -> match.groupValues[1].trimEnd { it in TRAILING_PUNCTUATION } }
        .distinct()
        .map { Handle(it) }
        .toList()
