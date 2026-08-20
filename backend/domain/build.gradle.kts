// :domain — the innermost module. NO dependency on any framework.
//
// No Ktor, no SQL, no JSON, no logging framework. Enforced by the root project's
// `checkModuleBoundaries` task, which fails the build rather than leaving it to review.
//
// The point is not purity for its own sake: every invariant in docs/DOMAIN_MODEL.md lives
// here, and a framework-free module is testable without a database, a server or a
// container. That property is worth more than the convenience of any single import.

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
}
