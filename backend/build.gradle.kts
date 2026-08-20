plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

allprojects {
    group = "ai.nodera"
    version = providers.gradleProperty("version").getOrElse("0.1.0-SNAPSHOT")
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    repositories { mavenCentral() }

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(21)
        compilerOptions {
            // Warnings are errors. A warning nobody fixes is a warning everybody stops
            // reading, and the one that mattered arrives in the same colour as the rest.
            allWarningsAsErrors.set(true)
            freeCompilerArgs.add("-Xjsr305=strict")
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        }
    }

    dependencies {
        add("implementation", rootProject.libs.kotlin.stdlib)
        add("testImplementation", rootProject.libs.bundles.kotest)
        add("testImplementation", rootProject.libs.mockk)
    }
}

// ---------------------------------------------------------------------------
// Module boundaries — docs/ARCHITECTURE.md § 2
// ---------------------------------------------------------------------------
// Dependencies point inward only. Expressing the rule as a task rather than trusting
// each module's build file means a violation is a red build rather than a review finding
// somebody has to notice.
//
// The strictest case is :domain, which must stay framework-free: no Ktor, no SQL, no
// JSON, no logging framework. That is what makes every invariant in it testable without
// a database, a server or a container — and it is exactly the property that erodes the
// first time somebody needs "just a logger in here".

val forbiddenInDomain =
    listOf(
        "io.ktor",
        "org.jetbrains.exposed",
        "org.postgresql",
        "org.flywaydb",
        "org.slf4j",
        "ch.qos.logback",
        "com.zaxxer",
        "org.jetbrains.kotlinx:kotlinx-serialization",
    )

tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Dependencies point inward only; :domain stays framework-free."

    val domainDeps =
        provider {
            project(":domain")
                .configurations
                .getByName("compileClasspath")
                .allDependencies
                .map { "${it.group}:${it.name}" }
        }
    val adapterDeps =
        provider {
            listOf(":api-rest", ":api-mcp").associateWith { path ->
                project(path)
                    .configurations
                    .getByName("compileClasspath")
                    .allDependencies
                    .mapNotNull { (it as? ProjectDependency)?.path }
            }
        }

    doLast {
        val problems = mutableListOf<String>()

        domainDeps.get().forEach { coordinate ->
            forbiddenInDomain.firstOrNull { coordinate.startsWith(it) }?.let {
                problems += ":domain depends on $coordinate — the domain module is framework-free " +
                    "(docs/ARCHITECTURE.md § 2). Move the adapter concern outward."
            }
        }

        // An adapter that can see :persistence can issue SQL. Keeping the edge out of the
        // graph turns "no SQL in an adapter" from a rule into a compile error.
        adapterDeps.get().forEach { (adapter, deps) ->
            if (":persistence" in deps) {
                problems += "$adapter depends on :persistence — adapters must not reach the database directly."
            }
            deps.filter { it in setOf(":api-rest", ":api-mcp") && it != adapter }.forEach {
                problems += "$adapter depends on $it — REST and MCP are siblings, never one over the other (ADR-0005)."
            }
        }

        if (problems.isNotEmpty()) {
            problems.forEach { logger.error("  - $it") }
            throw GradleException("${problems.size} module boundary violation(s).")
        }
        logger.lifecycle("OK - module boundaries hold.")
    }
}

tasks.register("check") {
    dependsOn("checkModuleBoundaries")
}

ktlint {
    version.set("1.5.0")
    android.set(false)
    outputToConsole.set(true)
    filter { exclude("**/generated/**") }
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/detekt.yml"))
    parallel = true
}
