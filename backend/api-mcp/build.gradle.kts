plugins {
    alias(libs.plugins.kotlin.serialization)
}

// :api-mcp — MCP tools, resources, prompts, transports.
//
// A SIBLING of :api-rest, never a client of it (ADR-0005). No dependency on it appears
// here, and the boundary task fails the build if one is added: an MCP server that speaks
// to its own REST API needs a credential to do so, and every option for that credential
// destroys per-agent identity.

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.slf4j.api)

    // The streamable HTTP transport shares Ktor with the REST adapter; the stdio
    // transport uses neither.
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
}
