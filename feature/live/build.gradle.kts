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
    namespace = "com.streamvault.feature.live"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
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
    ":data",
    ":player",
)

val forbiddenFeatureLiveSourceTokens = listOf(
    "import com.streamvault.app",
    "com.streamvault.app",
    "import com.streamvault.feature.provider",
    "com.streamvault.feature.provider",
    "import com.streamvault.feature.playback",
    "com.streamvault.feature.playback",
    "MainActivity",
    "NavHostController",
    "NavController",
)

fun findForbiddenFeatureLiveSourceReferences(sourceRoot: java.io.File): List<String> = sourceRoot
    .walkTopDown()
    .filter { it.isFile && it.extension in setOf("kt", "java") }
    .flatMap { file ->
        file.readLines().flatMapIndexed { index, line ->
            forbiddenFeatureLiveSourceTokens.filter(line::contains).map { token ->
                "${file.relativeTo(sourceRoot)}:${index + 1}: ${token}"
            }
        }
    }
    .toList()

val featureLiveBoundaryReport = layout.buildDirectory.file(
    "reports/feature-live-boundary/report.txt"
)

val verifyFeatureLiveBoundary = tasks.register("verifyFeatureLiveBoundary") {
    group = "verification"
    description = "Verifies that the live feature source and dependencies remain app-independent."
    outputs.file(featureLiveBoundaryReport)
    outputs.upToDateWhen { false }
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
            ":feature:live project dependencies must be exactly " +
                "${allowedProjectDependencies.sorted()}; found ${projectDependencyPaths.sorted()}"
        }

        val sourceRoot = layout.projectDirectory.asFile.resolve("src/main")
        val violations = findForbiddenFeatureLiveSourceReferences(sourceRoot)
        check(violations.isEmpty()) {
            ":feature:live contains forbidden app, feature, or root navigation references:\n" +
                violations.joinToString("\n")
        }

        val fixtureRoot = layout.projectDirectory.asFile.resolve("src/test/resources/boundary-fixtures")
        val fixtureViolations = findForbiddenFeatureLiveSourceReferences(fixtureRoot)
        val requiredFixtureViolations = setOf(
            "AppPackageImport.kt:3: import com.streamvault.app",
            "FullyQualifiedAppReference.kt:3: com.streamvault.app",
            "MainActivityReference.java:4: MainActivity",
            "RootNavigation.kt:3: NavHostController",
            "RootNavigation.java:4: NavController",
            "ProviderFeatureImport.kt:3: import com.streamvault.feature.provider",
            "PlaybackFeatureImport.java:3: import com.streamvault.feature.playback"
        )
        check(fixtureViolations.containsAll(requiredFixtureViolations)) {
            ":feature:live boundary fixtures are not detected: " +
                "${requiredFixtureViolations - fixtureViolations.toSet()}"
        }

        val reportFile = featureLiveBoundaryReport.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            listOf(
                "projectDependencies=${projectDependencyPaths.sorted().joinToString(",")}",
                "mainSourceViolations=${violations.joinToString("|")}",
                "fixtureViolations=${fixtureViolations.joinToString("|")}"
            ).joinToString("\n")
        )

        println(
            "Verified :feature:live boundary: approved dependencies, no forbidden source references, " +
                "and Kotlin/Java fixture coverage."
        )
    }
}

tasks.named("check") {
    dependsOn(verifyFeatureLiveBoundary)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(verifyFeatureLiveBoundary)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":player"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.tv.foundation)
    implementation(libs.compose.tv.material)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.core.ktx)
    implementation(libs.documentfile)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.mockito.kotlin)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.navigation.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
