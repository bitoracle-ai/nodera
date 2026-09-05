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
    implementation(libs.java.jwt)
    implementation(libs.slf4j.api)
    // Compile-time now, not runtimeOnly: Redaction.kt extends Logback's own converters so the
    // pattern layout cannot render a message without passing it through the redactor.
    implementation(libs.logback.classic)
}

application {
    mainClass.set("ai.nodera.app.MainKt")

    // Stamped at build time. An image that cannot say which version it is makes a fleet
    // unauditable (docs/plan/OPS-01.md § 4.3); `release.yml` passes -Pversion, and one without
    // reports "unknown" out loud rather than something silently plausible.
    applicationDefaultJvmArgs = listOf("-Dnodera.version=${project.version}")
}
