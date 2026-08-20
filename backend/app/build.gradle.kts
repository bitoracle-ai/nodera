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
    implementation(libs.argon2)
    implementation(libs.slf4j.api)
    runtimeOnly(libs.logback.classic)
}

application {
    mainClass.set("ai.nodera.app.MainKt")
}
