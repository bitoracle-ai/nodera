plugins {
    alias(libs.plugins.flyway)
}

// :persistence — PostgreSQL adapters, migrations, RLS session wiring.
//
// Exposed's DSL only; the DAO layer is deliberately unused. Implicit lazy loading hides
// exactly the N+1 and cross-project reads this project cares about most.

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(libs.bundles.exposed)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.slf4j.api)

    // RLS policies and append-only triggers cannot be tested against a substitute.
    testImplementation(libs.bundles.testcontainers)
    testImplementation(libs.postgresql)
}

flyway {
    url = System.getenv("NODERA_DB_URL") ?: "jdbc:postgresql://localhost:5432/nodera"
    user = System.getenv("NODERA_DB_USER") ?: "nodera"
    password = System.getenv("NODERA_DB_PASSWORD") ?: "nodera-local-dev-only"
    locations = arrayOf("filesystem:${rootProject.projectDir.parentFile}/db/migrations")
    // Forward-only: an applied migration is never edited. Leaving this at the default
    // would let a changed checksum be "repaired" away, which is the failure the rule exists
    // to prevent. scripts/lint_sql.py catches the edit before it reaches a database at all.
    validateOnMigrate = true
    cleanDisabled = true
    placeholders = mapOf(
        "nodera_app_password" to (System.getenv("NODERA_APP_PASSWORD") ?: "nodera-local-dev-only"),
    )
}
