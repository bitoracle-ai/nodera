package ai.nodera.application.identity.usecase

import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.identity.CredentialStore
import ai.nodera.application.identity.CredentialTerms
import ai.nodera.application.identity.IssuedCredential
import ai.nodera.application.identity.Secrets
import ai.nodera.application.transaction.UnitOfWork
import ai.nodera.domain.actor.ActorContext
import ai.nodera.domain.audit.AuditAction
import ai.nodera.domain.audit.AuditDiff
import ai.nodera.domain.audit.AuditEntry
import ai.nodera.domain.identity.Credential
import ai.nodera.domain.identity.CredentialKind
import ai.nodera.domain.identity.CredentialToken

internal const val CREDENTIAL_ENTITY: String = "credential"

private val ISSUED = AuditAction("credential.issued")

/**
 * Mints a personal access token for the acting actor, and returns its plaintext once.
 *
 * **There is no parameter naming whose credential this is.** It is always the caller's, so the
 * mistake of minting one for somebody else cannot be made here — invariant CR2 as a signature
 * rather than as a check.
 *
 * The plaintext exists in this function and in the result it returns. It is never written, never
 * logged and never returned again: the row carries an Argon2id hash of the verifier and the public
 * selector, and neither reconstructs the token.
 */
public class IssuePersonalAccessToken(
    private val unitOfWork: UnitOfWork,
    private val recorder: AuditRecorder,
    private val credentials: CredentialStore,
    private val secrets: Secrets,
) {
    public suspend fun issue(
        ctx: ActorContext,
        terms: CredentialTerms,
    ): IssuedCredential =
        unitOfWork.inTransaction {
            val selector = secrets.generator.selector()
            val secret = secrets.generator.tokenSecret()
            val credential =
                credentials.insert(
                    actorId = ctx.actorId,
                    kind = CredentialKind.PERSONAL_ACCESS_TOKEN,
                    selector = selector,
                    secretHash = secrets.hasher.hash(secret),
                    terms = terms,
                )
            recorder.record(ctx, issued(credential))

            IssuedCredential(
                credential = credential,
                plaintext = CredentialToken.render(CredentialKind.PERSONAL_ACCESS_TOKEN, selector, secret),
            )
        }
}

/** The label and the expiry, never the selector: the trail records what was issued, not how to use it. */
private fun issued(credential: Credential): AuditEntry =
    AuditEntry(
        action = ISSUED,
        entityType = CREDENTIAL_ENTITY,
        entityId = credential.id.value,
        diff =
            AuditDiff(
                after =
                    mapOf(
                        "label" to credential.label.value,
                        "kind" to credential.kind.name.lowercase(),
                        "expires_at" to credential.expiresAt?.toString(),
                    ),
            ),
    )
