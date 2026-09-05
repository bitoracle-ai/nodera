package ai.nodera.app

import ai.nodera.domain.actor.ActorId
import ai.nodera.domain.identity.SECRET_LENGTH
import ai.nodera.domain.identity.SELECTOR_LENGTH
import ai.nodera.domain.identity.SIGN_IN_CODE_LENGTH
import ai.nodera.domain.identity.SecretHash
import ai.nodera.domain.identity.SignInCode
import ai.nodera.domain.identity.TokenSecret
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.uuid.Uuid

// OWASP's floor, which is also the minimum Configuration accepts. Running the real parameters is
// the point: a hasher proved at a cost nobody deploys proves the API, not the protection.
private val FLOOR = HashCost(memoryKib = 19456, iterations = 2, parallelism = 1)

private val ACTOR = ActorId(Uuid.parse("11111111-1111-4111-8111-111111111111"))
private const val ISSUER = "https://nodera.test"

// Two obviously fake keys, base64 of ASCII sentences that say what they are.
private val KEY = "nodera-test-only-not-a-secret-0000000000".toByteArray()
private val OTHER_KEY = "nodera-other-key-not-a-secret-00000000000".toByteArray()

class IdentityAdapterTest :
    StringSpec({

        "Argon2id accepts the secret it hashed and refuses every other" {
            val hasher = Argon2idSecretHasher(FLOOR)
            val secret = SecureRandomSecrets().tokenSecret()
            val other = SecureRandomSecrets().tokenSecret()

            val hash = hasher.hash(secret)

            hasher.verify(hash, secret) shouldBe true
            hasher.verify(hash, other) shouldBe false
        }

        /*
         * The fact the whole selector design follows from: Argon2id salts, so the same secret hashes
         * differently every time and `where token_hash = hash(presented)` can never find a row.
         * If this ever went green as an equality, the credential table would have a usable lookup
         * key and docs/plan/SEC-01.md § 3 would be wrong.
         */
        "hashes the same secret to two different values, which is why a selector exists" {
            val hasher = Argon2idSecretHasher(FLOOR)
            val secret = SecureRandomSecrets().tokenSecret()

            hasher.hash(secret) shouldNotBe hasher.hash(secret)
        }

        "keeps the plaintext out of the hash it stores" {
            val secret = TokenSecret("f".repeat(SECRET_LENGTH))

            Argon2idSecretHasher(FLOOR).hash(secret).value shouldNotContain secret.value
        }

        // Guard: the catch in Argon2idSecretHasher.verify. Remove it and one corrupted row takes the
        // authentication path down with an exception instead of refusing that one credential.
        "answers a hash it cannot read with false rather than an exception" {
            val hasher = Argon2idSecretHasher(FLOOR)

            hasher.verify(SecretHash("not-an-argon2-encoded-hash"), SignInCode("12345678")) shouldBe false
        }

        "mints selectors and secrets the domain accepts, and never the same one twice" {
            val secrets = SecureRandomSecrets()

            secrets.selector().value.length shouldBe SELECTOR_LENGTH
            secrets.tokenSecret().value.length shouldBe SECRET_LENGTH
            secrets.signInCode().value.length shouldBe SIGN_IN_CODE_LENGTH
            secrets.selector() shouldNotBe secrets.selector()
            secrets.tokenSecret() shouldNotBe secrets.tokenSecret()
        }

        "issues an access token that verifies back to the actor it names" {
            val tokens = JwtAccessTokens(KEY, ISSUER, 15.minutes)
            val issued = tokens.issue(ACTOR, Clock.System.now())

            tokens.verify(issued.value) shouldBe ACTOR
            issued.value.count { it == '.' } shouldBe 2
        }

        // Guard: the signature check. Verify without the algorithm and this goes red — a token
        // anybody can mint is not a credential.
        "refuses a token signed with another key" {
            val issued = JwtAccessTokens(OTHER_KEY, ISSUER, 15.minutes).issue(ACTOR, Clock.System.now())

            JwtAccessTokens(KEY, ISSUER, 15.minutes).verify(issued.value) shouldBe null
        }

        // Guard: withExpiresAt plus the verifier's expiry check. Drop either and a fifteen-minute
        // access token is a permanent one.
        "refuses a token whose expiry has passed" {
            val tokens = JwtAccessTokens(KEY, ISSUER, 15.minutes)
            val issued = tokens.issue(ACTOR, Clock.System.now() - 1.hours)

            tokens.verify(issued.value) shouldBe null
        }

        "refuses a token minted for another deployment" {
            val issued = JwtAccessTokens(KEY, "https://elsewhere.test", 15.minutes).issue(ACTOR, Clock.System.now())

            JwtAccessTokens(KEY, ISSUER, 15.minutes).verify(issued.value) shouldBe null
        }

        "refuses something that is not a token at all, rather than failing loudly" {
            val tokens = JwtAccessTokens(KEY, ISSUER, 15.minutes)

            tokens.verify("not-a-token") shouldBe null
            tokens.verify("") shouldBe null
        }

        "reports the expiry it signed, so a client is not left to decode the token to learn it" {
            val issuedAt = Clock.System.now()

            JwtAccessTokens(KEY, ISSUER, 15.minutes).issue(ACTOR, issuedAt).expiresAt shouldBe issuedAt + 15.minutes
        }

        "never renders the token when the result is printed" {
            val issued = JwtAccessTokens(KEY, ISSUER, 15.minutes).issue(ACTOR, Clock.System.now())

            issued.toString() shouldNotContain issued.value
        }
    })
