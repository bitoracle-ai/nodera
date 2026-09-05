package ai.nodera.domain.identity

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

private const val SELECTOR = "6f1c9a4b2e8d70a3c5f2b1e4"
private const val SECRET = "9c2e4a17b30df85629e1c47a0b6d3f92548ea7c1063b9df24e85a170c93b6e42"

class TokenTest :
    StringSpec({

        "renders a personal access token as prefix, selector, separator and secret" {
            val rendered =
                CredentialToken.render(
                    CredentialKind.PERSONAL_ACCESS_TOKEN,
                    CredentialSelector(SELECTOR),
                    TokenSecret(SECRET),
                )

            rendered.value shouldBe "nod_pat_${SELECTOR}_$SECRET"
        }

        "parses back exactly what it rendered, for every kind that mints a token" {
            CredentialKind.entries
                .filter { it.tokenPrefix != null }
                .forEach { kind ->
                    val rendered = CredentialToken.render(kind, CredentialSelector(SELECTOR), TokenSecret(SECRET))

                    CredentialToken.parse(rendered.value) shouldBe
                        PresentedToken(kind, CredentialSelector(SELECTOR), TokenSecret(SECRET))
                }
        }

        // The promise .gitleaks.toml already makes on this repository's behalf: the documented
        // example token is not merely unlikely, it is outside the grammar. Uppercase is not
        // hexadecimal here, so EXAMPLE can never be a selector.
        "refuses the documented example token, which is what makes it safe to publish" {
            CredentialToken.parse("nod_pat_EXAMPLE00000000000000000_$SECRET").shouldBeNull()
            shouldThrow<IllegalArgumentException> { CredentialSelector("EXAMPLE00000000000000000") }
        }

        "refuses a token whose halves are the wrong length rather than truncating them" {
            CredentialToken.parse("nod_pat_${SELECTOR}_${SECRET.dropLast(1)}").shouldBeNull()
            CredentialToken.parse("nod_pat_${SELECTOR.dropLast(1)}_$SECRET").shouldBeNull()
        }

        "refuses a token with no separator, so a selector cannot absorb the secret" {
            CredentialToken.parse("nod_pat_$SELECTOR$SECRET").shouldBeNull()
        }

        "refuses a token with a third part, so trailing input is never ignored" {
            CredentialToken.parse("nod_pat_${SELECTOR}_${SECRET}_extra").shouldBeNull()
        }

        "returns null for anything that is not a Nodera token at all" {
            CredentialToken.parse("Bearer something").shouldBeNull()
            CredentialToken.parse("").shouldBeNull()
            CredentialToken.parse("nod_xxx_${SELECTOR}_$SECRET").shouldBeNull()
        }

        "refuses to render a kind that mints no token of its own" {
            shouldThrow<IllegalArgumentException> {
                CredentialToken.render(
                    CredentialKind.OIDC_LINK,
                    CredentialSelector(SELECTOR),
                    TokenSecret(SECRET),
                )
            }
        }

        // Guard: the toString overrides. Remove either and this goes red — which is the first of
        // the two layers that keep a secret out of a log line, the one that stops it being composed.
        "never renders its own secret when interpolated into a string" {
            val secret = TokenSecret(SECRET)
            val plaintext = TokenPlaintext("nod_pat_${SELECTOR}_$SECRET")
            val hash = SecretHash("\$argon2id\$v=19\$m=19456,t=2,p=1\$c2FsdA\$aGFzaA")

            "$secret $plaintext $hash" shouldNotContain SECRET
            "$secret $plaintext $hash" shouldNotContain "argon2id"
        }

        "carries the secret through a data class without printing it" {
            val presented =
                PresentedToken(
                    CredentialKind.PERSONAL_ACCESS_TOKEN,
                    CredentialSelector(SELECTOR),
                    TokenSecret(SECRET),
                )

            presented.toString() shouldNotContain SECRET
            presented.secret.value shouldBe SECRET
        }
    })
