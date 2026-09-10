package ai.nodera.api.rest

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsBytes
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respondText
import io.ktor.server.routing.HttpMethodRouteSelector
import io.ktor.server.routing.RoutingNode
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.routing.routingRoot
import io.ktor.server.testing.testApplication
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/** OpenAPI's path-item keys that are operations. The rest — `parameters`, `summary` — are not. */
private val OPERATIONS =
    setOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

/** What this build answers. `docs/API_CONTRACT.md` § 2b says the same thing in prose. */
private val SERVED =
    setOf(
        "GET /health/live",
        "GET /health/ready",
        "GET /openapi.yaml",
        "POST /api/v1/auth/refresh",
        "GET /api/v1/me",
        "POST /api/v1/me/credentials",
        "DELETE /api/v1/me/credentials/{id}",
    )

/**
 * The committed OpenAPI document and the routes this server exposes are the same set.
 *
 * Both directions matter, and the one a contributor actually produces — an endpoint served with no
 * contract entry — is **watched**: registering one more route inside `noderaApi` makes the first
 * case fail, naming the operation. The opposite direction is demonstrated rather than watched,
 * because the two cases below are the check itself run against a known difference; making it red
 * would mean deleting a path from the committed file.
 *
 * **What the walk cannot see**, said here rather than in a claim that it sees everything: a route
 * mounted by `serve` outside [noderaApiRoutes]. `serve` mounts that function and the web assets,
 * and the assets are excluded on purpose (`docs/API_CONTRACT.md` § 2b) — but nothing mechanical
 * stops a later package registering a route beside it. A handler that is *not* method-selected is
 * covered: [routesOf] renders it as `* <path>`, so it appears in the set and fails the comparison
 * rather than passing silently, which is the shape the assets' own routing takes.
 */
class ContractDriftTest :
    StringSpec({

        // Both sets are also pinned to a literal, because two empty sets are equal: without it a
        // routing walk that stopped seeing handlers would agree with a parser that stopped seeing
        // paths, and the comparison would be green with nothing in it.
        "the routes the server exposes are exactly the paths the document describes" {
            testApplication {
                application { noderaApi() }
                startApplication()

                routesOf(application) shouldBe SERVED
                documentedRoutes() shouldBe SERVED
            }
        }

        "a route with no contract entry is a difference the check reports" {
            testApplication {
                application {
                    noderaApi()
                    routing { get("/api/v1/undocumented") { call.respondText("nothing describes me") } }
                }
                startApplication()

                (routesOf(application) - documentedRoutes()) shouldBe setOf("GET /api/v1/undocumented")
            }
        }

        // The mirror image, from the same two sets. The path is added to the parsed document rather
        // than removed from the file, so proving this direction cannot leave the repository dirty.
        "a documented path with no route is a difference the check reports" {
            testApplication {
                application { noderaApi() }
                startApplication()

                val pretending = documentedRoutes() + "GET /api/v1/imaginary"

                (pretending - routesOf(application)) shouldBe setOf("GET /api/v1/imaginary")
            }
        }

        // The middleware's exemption list and the contract's `security: []` are two statements of
        // one fact, so they are compared rather than trusted to agree. Both directions watched:
        // a path added to UNAUTHENTICATED_PATHS, and a path removed from it.
        "the paths the middleware leaves alone are the ones the contract gives no security" {
            unauthenticatedPaths() shouldBe UNAUTHENTICATED_PATHS
        }

        // Matching by path is only sound while a path's operations agree about security. This is
        // what makes the comparison above a comparison of the same thing.
        "no path mixes secured and unsecured operations" {
            mixedSecurityPaths() shouldBe emptySet()
        }

        // Three things, not two: the file in the repository, the copy on the classpath, and the
        // bytes on the wire. Comparing only the last two would leave a resource-filtering step free
        // to rewrite the contract between the source and the jar with this case still green — and
        // `generate-zod.mjs` reads the source, so the client and the server would then disagree.
        "the document the server serves is the document the repository holds, byte for byte" {
            val committed = java.io.File("src/main/resources/openapi.yaml").readBytes()

            testApplication {
                application { noderaApi() }

                val response = client.get("/openapi.yaml")

                response.status shouldBe HttpStatusCode.OK
                openApiBytes().toList() shouldBe committed.toList()
                response.bodyAsBytes().toList() shouldBe committed.toList()
            }
        }
    })

/**
 * Every operation the routing tree answers, rendered the way the document names one.
 *
 * A handler that is not method-selected is rendered `* <path>` rather than dropped. Dropping it is
 * how a served route becomes invisible to its own drift check, and `handle { }` — which the static
 * content routing uses — is exactly that shape.
 */
private fun routesOf(application: Application): Set<String> =
    application.routingRoot
        .descendants()
        .filterIsInstance<RoutingNode>()
        .filter { it.hasHandler() }
        .map { node ->
            when (val selector = node.selector) {
                is HttpMethodRouteSelector -> "${selector.method.value} ${pathOf(node.parent)}"
                else -> "* ${pathOf(node)}"
            }
        }.toSet()

private fun pathOf(node: RoutingNode?): String {
    val rendered = node?.toString().orEmpty()
    return if (rendered.length > 1) rendered.trimEnd('/') else rendered
}

/** The paths whose every operation declares `security: []`. */
private fun unauthenticatedPaths(): Set<String> =
    operationsByPath()
        .filterValues { operations -> operations.all(::declaresNoSecurity) }
        .keys

/** The paths where the two disagree, which would make a path-level comparison meaningless. */
private fun mixedSecurityPaths(): Set<String> =
    operationsByPath()
        .filterValues { operations -> operations.any(::declaresNoSecurity) && !operations.all(::declaresNoSecurity) }
        .keys

private fun declaresNoSecurity(operation: Map<*, *>): Boolean = (operation["security"] as? List<*>)?.isEmpty() == true

private fun operationsByPath(): Map<String, List<Map<*, *>>> {
    val document = Load(LoadSettings.builder().build()).loadFromString(String(openApiBytes(), Charsets.UTF_8))
    val paths = (document as Map<*, *>)["paths"] as Map<*, *>

    return paths.entries.associate { (path, item) ->
        path.toString() to
            (item as Map<*, *>)
                .filterKeys { it.toString() in OPERATIONS }
                .values
                .filterIsInstance<Map<*, *>>()
    }
}

/**
 * The committed document's operations, read with a real YAML parser.
 *
 * Deliberately not a regular expression over the file: a pattern that stops matching reports no
 * paths and the comparison then passes for the wrong reason, which is the shape of gate this
 * repository has been bitten by more than once.
 */
private fun documentedRoutes(): Set<String> {
    val document = Load(LoadSettings.builder().build()).loadFromString(String(openApiBytes(), Charsets.UTF_8))
    val paths = (document as Map<*, *>)["paths"] as Map<*, *>

    return paths.entries
        .flatMap { (path, item) ->
            (item as Map<*, *>)
                .keys
                .map { it.toString() }
                .filter { it in OPERATIONS }
                .map { "${it.uppercase()} $path" }
        }.toSet()
}
