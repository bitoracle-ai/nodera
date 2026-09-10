package ai.nodera.app

import ai.nodera.api.rest.IdentitySurface
import ai.nodera.api.rest.ReadinessProbe
import ai.nodera.api.rest.installCredentialAuthentication
import ai.nodera.api.rest.installRequestCorrelation
import ai.nodera.api.rest.noderaApiRoutes
import ai.nodera.api.rest.noderaJson
import ai.nodera.application.audit.AuditRecorder
import ai.nodera.application.identity.CredentialAuthenticator
import ai.nodera.application.identity.CredentialVerifier
import ai.nodera.application.identity.RefreshSession
import ai.nodera.application.identity.Secrets
import ai.nodera.application.identity.SessionIssuer
import ai.nodera.application.identity.usecase.IssuePersonalAccessToken
import ai.nodera.application.identity.usecase.RevokeCredential
import ai.nodera.application.identity.usecase.WhoAmI
import ai.nodera.persistence.ConnectionPool
import ai.nodera.persistence.DatabaseSettings
import ai.nodera.persistence.JdbcUnitOfWork
import ai.nodera.persistence.Migrator
import ai.nodera.persistence.SchemaState
import ai.nodera.persistence.audit.AuditEventRepository
import ai.nodera.persistence.identity.JdbcActorDirectory
import ai.nodera.persistence.identity.JdbcActorProfiles
import ai.nodera.persistence.identity.JdbcCredentialStore
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.singlePageApplication
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.PrintStream
import kotlin.time.Clock

private val logger = LoggerFactory.getLogger("ai.nodera.app")

/**
 * How long in-flight requests get to finish on `SIGTERM`, and the hard ceiling after that.
 *
 * An orchestrator sends `SIGTERM` and then waits; a process that ignores it is killed, and every
 * request it was serving becomes an error the client sees. Draining is the difference between a
 * rolling deployment nobody notices and one that shows up in the error rate.
 */
private const val SHUTDOWN_GRACE_MILLIS = 5_000L
private const val SHUTDOWN_TIMEOUT_MILLIS = 15_000L

/**
 * The `serve` entrypoint: REST and the web assets, from one process and one origin.
 *
 * Serving the assets here rather than from a second container is what makes the frontend's API base
 * URL relative, which is what keeps CORS out of the self-hosting path entirely (ADR-0006). No CORS
 * plugin is installed, and that absence is the point rather than an omission.
 */
internal fun runServe(
    config: ServeConfig,
    out: PrintStream,
): Int {
    val database =
        DatabaseSettings(
            url = config.database.url,
            user = config.database.user,
            password = config.database.password,
        )
    val migrator = Migrator(database)
    // In the image the assets are always present. On a development machine they are not, because
    // Vite serves them — so their absence is reported rather than fatal, and reported rather than
    // silent, so a broken image cannot look like a development machine.
    val assets = File(config.staticRoot)
    val pool = ConnectionPool(database)
    val identity = identityGraph(config.identity, pool)
    val server =
        embeddedServer(Netty, port = config.httpPort) {
            noderaServer(config, identity, migrator, assets.isDirectory)
        }
    if (!assets.isDirectory) {
        out.println("No web assets at ${assets.absolutePath}; serving the API only.")
    }
    Runtime.getRuntime().addShutdownHook(
        Thread {
            server.stop(SHUTDOWN_GRACE_MILLIS, SHUTDOWN_TIMEOUT_MILLIS)
            pool.close()
        },
    )
    out.println("Nodera $buildVersion listening on port ${config.httpPort}")
    server.start(wait = true)
    return EXIT_OK
}

/**
 * The whole application, in the order a request meets it.
 *
 * The API is one call — `noderaApiRoutes` — because that is the function `ContractDriftTest` walks:
 * a route mounted here instead of there would be served with the drift check still green.
 *
 * @param serveAssets the SPA fallback, which serves `index.html` for a deep link so the client
 *   router can resolve it. Without it every route but `/` is a 404 on a hard refresh. Absent on a
 *   development machine, where Vite serves the assets instead.
 */
private fun Application.noderaServer(
    config: ServeConfig,
    identity: IdentityGraph,
    migrator: Migrator,
    serveAssets: Boolean,
) {
    install(ContentNegotiation) { json(noderaJson) }
    installRequestCorrelation()
    installCredentialAuthentication(identity.authenticator)
    routing {
        noderaApiRoutes(buildVersion, readinessProbe(migrator), identity.surface)
        if (serveAssets) {
            singlePageApplication {
                filesPath = config.staticRoot
                defaultPage = "index.html"
                useResources = false
            }
        }
    }
}

/**
 * Everything the identity surface needs, wired here and nowhere else — `docs/plan/SEC-01.md` § 7.
 *
 * One `JdbcUnitOfWork` for the whole graph: `scripts/lint_invariants.py` allows it only in this
 * module, and a second instance would open transactions the audit-completeness harness is not
 * watching.
 *
 * The project-scoped half is not here; `docs/plan/API-01.md` § 1 states why and § 8 recommends the
 * way out.
 */
private class IdentityGraph(
    val authenticator: CredentialAuthenticator,
    val surface: IdentitySurface,
)

private fun identityGraph(
    identity: IdentityConfig,
    pool: ConnectionPool,
): IdentityGraph {
    val clock = Clock.System
    val unitOfWork = JdbcUnitOfWork(pool.dataSource)
    val recorder = AuditRecorder(AuditEventRepository())
    val credentials = JdbcCredentialStore()
    val secrets = Secrets(SecureRandomSecrets(), Argon2idSecretHasher(identity.hashCost))
    val accessTokens = JwtAccessTokens(identity.signingKey, identity.issuer, identity.accessTtl)
    val verifier = CredentialVerifier(credentials, secrets.hasher, clock)
    val sessions = SessionIssuer(credentials, accessTokens, secrets, identity.refreshTtl, clock)

    return IdentityGraph(
        authenticator =
            CredentialAuthenticator(
                unitOfWork = unitOfWork,
                verifier = verifier,
                actors = JdbcActorDirectory(),
                accessTokens = accessTokens,
            ),
        surface =
            IdentitySurface(
                refreshSession = RefreshSession(unitOfWork, recorder, verifier, credentials, sessions, clock),
                whoAmI = WhoAmI(unitOfWork, JdbcActorProfiles()),
                issueToken = IssuePersonalAccessToken(unitOfWork, recorder, credentials, secrets),
                revokeCredential = RevokeCredential(unitOfWork, recorder, credentials, clock),
                clock = clock,
            ),
    )
}

/**
 * Asks the database, off the event loop, and hands the answer to [readinessReport].
 *
 * The JDBC calls behind this block, so they run on [Dispatchers.IO]: a slow database must not stall
 * the event loop, or an unrelated liveness probe times out and the orchestrator kills a container
 * that was merely waiting.
 *
 * The driver's exception class is logged, never returned — see [readinessReport] for why that
 * distinction is about failure detail rather than about hiding the instance.
 */
private fun readinessProbe(migrator: Migrator): ReadinessProbe =
    ReadinessProbe {
        withContext(Dispatchers.IO) {
            val state = migrator.state()
            if (state is SchemaState.Unreachable) {
                logger.warn("Readiness: the database could not be read ({})", state.category)
            }
            readinessReport(state)
        }
    }
