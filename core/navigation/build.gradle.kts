import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
}

val bannedCoreNavigationTokens = listOf(
    "com.streamvault.app",
    "com.streamvault.data",
    "com.streamvault.domain",
    "com.streamvault.player",
    "android.",
    "androidx.compose",
    "androidx.navigation",
    "dagger.hilt",
    "MainActivity",
    "NavController",
    "NavHost"
)

val verifyCoreNavigationBoundary = tasks.register("verifyCoreNavigationBoundary") {
    group = "verification"
    description = "Verifies that core navigation contains contracts only."
    notCompatibleWithConfigurationCache(
        "The boundary scan reads resolved Gradle model state at execution time."
    )
    doLast {
        val projectDependencies = configurations
            .flatMap { configuration ->
                configuration.dependencies
                    .withType<ProjectDependency>()
                    .filter { dependency -> dependency.path != project.path }
                    .map { dependency -> "${configuration.name}:${dependency.path}" }
            }
            .distinct()
        check(projectDependencies.isEmpty()) {
            ":core:navigation must not declare project dependencies: ${projectDependencies.joinToString()}"
        }

        val sourceRoot = layout.projectDirectory.asFile.resolve("src/main")
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().flatMapIndexed { index, line ->
                    bannedCoreNavigationTokens.filter(line::contains).map { token ->
                        "${file.relativeTo(sourceRoot)}:${index + 1}: $token"
                    }
                }
            }
            .toList()
        check(violations.isEmpty()) {
            ":core:navigation contains forbidden references:\n${violations.joinToString("\n")}"
        }

        println("Verified :core:navigation boundary: no project dependencies and no banned source references.")
    }
}

tasks.named("check") {
    dependsOn(verifyCoreNavigationBoundary)
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
