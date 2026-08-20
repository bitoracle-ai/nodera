// :application — use cases, PermissionService, AuditRecorder, port interfaces.
//
// Owns the transaction boundary. Both adapters call into here and nowhere else, which is
// what makes "one permission engine" structurally true rather than a promise.

dependencies {
    api(project(":domain"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.slf4j.api)
}
