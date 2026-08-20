plugins {
    application
    alias(libs.plugins.kotlin.serialization)
}

// :app — the composition root. The ONLY place the object graph is wired, and therefore
// the only place PermissionService is constructed (invariant #2, enforced by
// scripts/lint_invariants.py).

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":persistence"))
    implementation(project(":api-rest"))
    implementation(project(":api-mcp"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.argon2)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("ai.nodera.app.MainKt")

    // Stamped at build time. An image that cannot say which version it is makes a fleet
    // unauditable (ADR-0006 § 4.3); `release.yml` passes -Pversion, and a build without one
    // reports "unknown" out loud rather than something silently plausible.
    applicationDefaultJvmArgs = listOf("-Dnodera.version=${project.version}")
}
