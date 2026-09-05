package ai.nodera.domain.identity

private class Rule(
    val pattern: Regex,
    val replacement: (MatchResult) -> String,
)

private val RULES =
    listOf(
        // Loose rather than the exact grammar: a truncated or mangled token is still a credential
        // somebody typed, and this layer exists to contain a mistake made upstream.
        Rule(Regex("""nod_[a-z]{3}_[A-Za-z0-9_\-]{4,}""")) { "nod_${SecretRedaction.PLACEHOLDER}" },
        Rule(Regex("""eyJ[A-Za-z0-9_\-]{4,}\.[A-Za-z0-9_\-]{4,}\.[A-Za-z0-9_\-]{4,}""")) {
            SecretRedaction.PLACEHOLDER
        },
        Rule(Regex("""(?i)\b(bearer|basic)\s+\S+""")) { "${it.groupValues[1]} ${SecretRedaction.PLACEHOLDER}" },
    )

/**
 * Takes a secret out of a string at the logging boundary.
 *
 * The types in this package refuse to render their own contents, which stops the mistake being
 * made. This stops it *arriving*, over text nobody in this repository composed. It is not a
 * validator: text carrying no secret comes back unchanged.
 */
public object SecretRedaction {
    public const val PLACEHOLDER: String = "***"

    public fun redact(text: String): String =
        RULES.fold(text) { carried, rule -> rule.pattern.replace(carried, rule.replacement) }
}
