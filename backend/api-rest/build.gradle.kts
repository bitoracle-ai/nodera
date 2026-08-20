plugins {
    alias(libs.plugins.kotlin.serialization)
}

// :api-rest — Ktor routing, DTOs, error mapping. Translation only.
//
// Deliberately NO dependency on :persistence, so "no SQL in an adapter" is a compile
// error rather than a review finding.

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(libs.bundles.ktor.server)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.slf4j.api)

    testImplementation(libs.ktor.server.test.host)
}
