package ai.nodera.api.rest

import ai.nodera.application.identity.ActorProfile
import ai.nodera.application.identity.Session
import ai.nodera.domain.actor.ActorSummary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The one JSON configuration this surface uses, so what a route emits and what the contract
 * describes cannot differ by a serializer setting.
 *
 * `explicitNulls = false` because the contract has no nullable field: an absent owner is an absent
 * key, which is what the generated Zod schema's `.optional()` accepts and what `null` is not. It is
 * the guard rather than decoration — the payloads below deliberately give an optional field **no
 * default**, because a defaulted property is dropped by `encodeDefaults` instead and this setting
 * would then be doing nothing while reading as though it were.
 *
 * Unknown keys stay refused — a request carrying a field this build does not know is a client
 * expecting behaviour it will not get, and `validation_failed` says so.
 */
public val noderaJson: Json =
    Json {
        explicitNulls = false
    }

/** `docs/API_CONTRACT.md` § 2. `kind` is always present so no client ever has to infer it. */
@Serializable
public data class ActorRefPayload(
    public val id: String,
    public val kind: String,
    public val handle: String,
    public val displayName: String,
)

@Serializable
public data class ActorPayload(
    public val id: String,
    public val kind: String,
    public val handle: String,
    public val displayName: String,
    /** No default on purpose — see [noderaJson]. Every construction site states it. */
    public val owner: ActorRefPayload?,
)

@Serializable
public data class RefreshRequest(
    public val refreshToken: String,
)

@Serializable
public data class SessionPayload(
    public val accessToken: String,
    public val expiresAt: String,
    public val refreshToken: String,
)

@Serializable
public data class IssueCredentialRequest(
    public val label: String,
    public val expiresAt: String,
)

@Serializable
public data class IssuedCredentialPayload(
    public val id: String,
    public val label: String,
    public val token: String,
    public val expiresAt: String,
)

internal fun ActorProfile.toPayload(): ActorPayload =
    ActorPayload(
        id = actor.id.value.toString(),
        kind = actor.kind.name.lowercase(),
        handle = actor.handle.value,
        displayName = actor.displayName.value,
        owner = owner?.toRefPayload(),
    )

internal fun ActorSummary.toRefPayload(): ActorRefPayload =
    ActorRefPayload(
        id = id.value.toString(),
        kind = kind.name.lowercase(),
        handle = handle.value,
        displayName = displayName.value,
    )

/** The refresh token leaves in the body exactly once per rotation, and is never read back. */
internal fun Session.toPayload(): SessionPayload =
    SessionPayload(
        accessToken = accessToken.value,
        expiresAt = accessToken.expiresAt.toString(),
        refreshToken = refreshToken.value,
    )
