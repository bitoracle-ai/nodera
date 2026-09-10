package ai.nodera.api.rest

import io.ktor.server.routing.Route

/**
 * The paths the contract marks `security: []`, and therefore the ones the credential middleware
 * leaves alone — without them a stale `Authorization` header refuses the refresh that would replace
 * it. `ContractDriftTest` asserts this set is exactly the operations the document declares
 * `security: []`, so the two statements of one fact cannot drift apart.
 */
public val UNAUTHENTICATED_PATHS: Set<String> =
    setOf("/health/live", "/health/ready", "/openapi.yaml", "/api/v1/auth/refresh")

/**
 * Every route this server answers, registered in one place.
 *
 * `ContractDriftTest` walks what **this** registers and compares it with the committed OpenAPI
 * document. `serve` mounts this and the web assets and nothing else — the assets are not part of
 * the API contract, and on a development machine they are not present at all. What the walk cannot
 * see is stated where it is checked, in `ContractDriftTest`.
 */
public fun Route.noderaApiRoutes(
    version: String,
    readiness: ReadinessProbe,
    identity: IdentitySurface,
) {
    healthRoutes(version, readiness)
    openApiDocument()
    authenticationRoutes(identity.refreshSession)
    identityRoutes(identity.whoAmI, identity.issueToken, identity.revokeCredential, identity.clock)
}
