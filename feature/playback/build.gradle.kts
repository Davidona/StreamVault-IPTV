import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.streamvault.feature.playback"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

kover {
    currentProject {
        createVariant("ci") {
            add("debug")
        }
    }
}

val allowedProjectDependencies = setOf(
    ":core:navigation",
    ":core:ui",
    ":domain",
    ":player",
    ":data"
)

val verifyFeaturePlaybackBoundary = tasks.register("verifyFeaturePlaybackBoundary") {
    group = "verification"
    description = "Verifies that playback feature source and dependencies remain app-independent."
    notCompatibleWithConfigurationCache(
        "The boundary scan reads resolved Gradle model state at execution time."
    )

    doLast {
        val projectDependencyPaths = configurations
            .flatMap { configuration ->
                configuration.dependencies
                    .withType<ProjectDependency>()
                    .filter { dependency -> dependency.path != project.path }
                    .map { dependency -> dependency.path }
            }
            .toSet()

        check(projectDependencyPaths == allowedProjectDependencies) {
            ":feature:playback project dependencies must be exactly " +
                "${allowedProjectDependencies.sorted()}; found ${projectDependencyPaths.sorted()}"
        }

        val sourceRoot = layout.projectDirectory.asFile.resolve("src/main")
        val forbiddenTokens = listOf(
            "import com.streamvault.app",
            "NavHostController",
            "NavController"
        )
        val violations = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                file.readLines().flatMapIndexed { index, line ->
                    forbiddenTokens.filter(line::contains).map { token ->
                        "${file.relativeTo(sourceRoot)}:${index + 1}: $token"
                    }
                }
            }
            .toList()

        check(violations.isEmpty()) {
            ":feature:playback contains forbidden app or root navigation references:\n" +
                violations.joinToString("\n")
        }

        println("Verified :feature:playback boundary: approved dependencies and no forbidden source references.")
    }
}

tasks.named("check") {
    dependsOn(verifyFeaturePlaybackBoundary)
}

dependencies {
    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))
    implementation(project(":domain"))
    implementation(project(":player"))
    implementation(project(":data"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.tv.foundation)
    implementation(libs.compose.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.core.ktx)
    implementation(libs.mediarouter)
    implementation(libs.play.services.cast.framework)
    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
