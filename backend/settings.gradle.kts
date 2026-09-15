rootProject.name = "nodera"

// Hexagonal, six modules. Dependencies point inward only, and the build enforces it —
// see docs/ARCHITECTURE.md § 2. Adding a module means adding it here AND stating what it
// may depend on; a module with an unconsidered dependency set is how a layer erodes.
include(
    ":domain",
    ":application",
    ":persistence",
    ":api-rest",
    ":api-mcp",
    ":app",
)

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }

    // CodeQL's Kotlin extractor is a compiler plugin, so it refuses one it does not know: bundle
    // 2.27.0 aborts the compile it traces on Kotlin >= 2.4.20 and extracts nothing. The CodeQL
    // workflow — and nothing else — passes a compiler it accepts; the shipped build keeps the
    // catalogue's version, where CVE-2026-53914 is fixed. Both read the same source. See CI-03.
    versionCatalogs {
        create("libs") {
            providers.gradleProperty("codeqlKotlinVersion").orNull?.let { version("kotlin", it) }
        }
    }
}
