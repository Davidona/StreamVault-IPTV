import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.streamvault.core.ui"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val bannedCoreUiTokens = listOf(
    "com.streamvault.app",
    "com.streamvault.data",
    "com.streamvault.domain",
    "com.streamvault.player",
    "androidx.navigation",
    "dagger.hilt",
    "MainActivity",
    "NavController",
    "NavHost"
)

val verifyCoreUiBoundary = tasks.register("verifyCoreUiBoundary") {
    group = "verification"
    description = "Verifies that core UI source and dependencies remain app-independent."
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
            ":core:ui must not declare project dependencies: ${projectDependencies.joinToString()}"
        }

        val sourceRoot = layout.projectDirectory.asFile.resolve("src/main")
        val violations = sourceRoot
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().flatMapIndexed { index, line ->
                    bannedCoreUiTokens
                        .filter(line::contains)
                        .map { token -> "${file.relativeTo(sourceRoot)}:${index + 1}: $token" }
                }
            }
            .toList()

        check(violations.isEmpty()) {
            ":core:ui contains forbidden app/feature references:\n${violations.joinToString("\n")}"
        }

        println("Verified :core:ui boundary: no project dependencies and no banned source references.")
    }
}

tasks.named("check") {
    dependsOn(verifyCoreUiBoundary)
}

dependencies {
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.tv.foundation)
    implementation(libs.compose.tv.material)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.truth)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
