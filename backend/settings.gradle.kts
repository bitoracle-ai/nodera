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
}
