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

// ---------------------------------------------------------------------------
// The migrations travel with the code, on the classpath
// ---------------------------------------------------------------------------
// There is one `migrate` implementation — the entrypoint in :app, using Flyway Core — and it finds
// the migrations at `classpath:db/migration` in a development checkout and inside the image alike.
//
// The Flyway *Gradle plugin* used to live here with its own url, locations and placeholders. That
// was a second implementation of "apply the migrations", configured separately from the one that
// actually runs in production, and the copy that drifts is always the one CI does not exercise.
// A single location also means "which migration ran" has exactly one answer, and that question only
// ever gets asked during an incident.
val migrationsDir =
    rootProject.layout.projectDirectory
        .dir("../db/migrations")
        .asFile

// Gradle treats a missing `from` directory as a no-op. Without this check, a build whose context
// lacks db/migrations would produce an image whose `migrate` command applies nothing and reports
// success — the worst possible shape for this particular failure. Checked at configuration time
// rather than in a task action: there is no valid build of this module without the migrations, and
// a task action closure here cannot be serialised by the configuration cache.
require(migrationsDir.listFiles { file -> file.name.endsWith(".sql") }?.isNotEmpty() == true) {
    "No migrations found at $migrationsDir. The image would ship without a schema."
}

tasks.processResources {
    from(migrationsDir) {
        into("db/migration")
        include("*.sql")
    }
}
