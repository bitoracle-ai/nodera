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

// The inward-only graph, stated once, exactly as the table in docs/ARCHITECTURE.md § 2 states it.
// A module may depend on the modules listed for it and on no others — so `:persistence` cannot
// reach an adapter, nothing may depend on `:app`, and a new module has to declare its allowed set
// here before it can depend on anything, which is what settings.gradle.kts already asks for in
// prose.
val allowedProjectDependencies =
    mapOf(
        ":domain" to emptySet(),
        ":application" to setOf(":domain"),
        ":persistence" to setOf(":domain", ":application"),
        ":api-rest" to setOf(":domain", ":application"),
        ":api-mcp" to setOf(":domain", ":application"),
        ":app" to setOf(":domain", ":application", ":persistence", ":api-rest", ":api-mcp"),
    )

// Filled by the check below. The reporting task asserts it is non-empty, so deleting the check
// cannot leave a task that prints reassurance — which is the exact shape this guard was in before:
// present, named in CI, and never executed.
val boundaryCheckRan = mutableListOf<String>()

// The check runs at CONFIGURATION time, after every project has been evaluated, and throws there
// rather than inside a task action.
//
// That is not a style choice. `org.gradle.configuration-cache=true` means a task action declared in
// a `.kts` script cannot be serialised once it touches anything on the script — `logger`, `project`,
// a lazy provider that reaches across projects — and the previous version did all three. It failed
// with "cannot serialize Gradle script object references" the first time anyone actually invoked
// it, which nobody had: CI addressed it as `:domain:checkModuleBoundaries`, and that task does not
// exist on `:domain`. The guard was dead twice over while reading as present, and the claim it
// backs — that an `:api-rest` → `:persistence` edge is a build failure — was false for as long as
// it was.
//
// Checking here also makes it unavoidable: a violation fails EVERY Gradle invocation, not only the
// one command someone remembered to wire into a pipeline.
gradle.projectsEvaluated {
    val problems = mutableListOf<String>()

    project(":domain")
        .configurations
        .getByName("compileClasspath")
        .allDependencies
        .forEach { dependency ->
            val coordinate = "${dependency.group}:${dependency.name}"
            forbiddenInDomain.firstOrNull { coordinate.startsWith(it) }?.let {
                problems += ":domain depends on $coordinate — the domain module is framework-free " +
                    "(docs/ARCHITECTURE.md § 2). Move the adapter concern outward."
            }
        }

    val unlisted = subprojects.map { it.path }.toSet() - allowedProjectDependencies.keys
    unlisted.forEach {
        problems += "$it is not listed in allowedProjectDependencies — a new module states what it " +
            "may depend on before it depends on anything."
    }

    allowedProjectDependencies.forEach { (module, allowed) ->
        val actual =
            project(module)
                .configurations
                .getByName("compileClasspath")
                .allDependencies
                .mapNotNull { (it as? ProjectDependency)?.path }
                .toSet()

        (actual - allowed).forEach { forbidden ->
            val adapters = setOf(":api-rest", ":api-mcp")
            problems +=
                when {
                    // An adapter that can see :persistence can issue SQL. Keeping the edge out of
                    // the graph turns "no SQL in an adapter" from a rule into a compile error.
                    forbidden == ":persistence" && module in adapters ->
                        "$module depends on :persistence — adapters must not reach the database directly."

                    forbidden in adapters && module in adapters ->
                        "$module depends on $forbidden — REST and MCP are siblings, never one over " +
                            "the other (ADR-0005)."

                    else ->
                        "$module depends on $forbidden — dependencies point inward only " +
                            "(docs/ARCHITECTURE.md § 2); $module may depend on " +
                            "${allowed.sorted().joinToString(", ").ifEmpty { "nothing" }}."
                }
        }
    }

    if (problems.isNotEmpty()) {
        throw GradleException(
            "${problems.size} module boundary violation(s):" +
                problems.joinToString("") { "\n  - $it" },
        )
    }
    boundaryCheckRan += "ok"
}

// Exists so `./gradlew checkModuleBoundaries` is a real command for CI and the Makefile to call.
// It asserts the check ran rather than assuming it: a task that prints "OK" unconditionally is the
// same dead guard in a new costume.
tasks.register("checkModuleBoundaries") {
    group = "verification"
    description = "Dependencies point inward only; :domain stays framework-free."
    val ran = boundaryCheckRan
    doLast {
        check(ran.isNotEmpty()) {
            "The module boundary check did not run. The check itself is the configuration-time " +
                "block in backend/build.gradle.kts; if it is gone, this task fails rather than " +
                "reporting success it cannot vouch for."
        }
        println("OK - module boundaries hold.")
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
