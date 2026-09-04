import org.gradle.api.artifacts.ProjectDependency

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.streamvault.feature.system"
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
)

val forbiddenFeatureSystemSourceTokens = listOf(
    "import com.streamvault.app",
    "com.streamvault.app",
    "import com.streamvault.data",
    "import com.streamvault.player",
    "import com.streamvault.feature.playback",
    "import com.streamvault.feature.provider",
    "import com.streamvault.feature.settings",
    "import com.streamvault.feature.live",
    "import com.streamvault.feature.catalog",
    "MainActivity",
    "NavHostController",
    "NavController",
)

fun findForbiddenFeatureSystemSourceReferences(sourceRoot: java.io.File): List<String> = sourceRoot
    .walkTopDown()
    .filter { it.isFile && it.extension in setOf("kt", "java") }
    .flatMap { file ->
        file.readLines().flatMapIndexed { index, line ->
            forbiddenFeatureSystemSourceTokens.filter(line::contains).map { token ->
                "${file.relativeTo(sourceRoot)}:${index + 1}: ${token}"
            }
        }
    }
    .toList()

val featureSystemBoundaryReport = layout.buildDirectory.file(
    "reports/feature-system-boundary/report.txt"
)

val verifyFeatureSystemBoundary = tasks.register("verifyFeatureSystemBoundary") {
    group = "verification"
    description = "Verifies that the System feature source and dependencies remain app-independent."
    outputs.file(featureSystemBoundaryReport)
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
            ":feature:system project dependencies must be exactly " +
                "${allowedProjectDependencies.sorted()}; found ${projectDependencyPaths.sorted()}"
        }

        val sourceRoot = layout.projectDirectory.asFile.resolve("src/main")
        val violations = findForbiddenFeatureSystemSourceReferences(sourceRoot)
        check(violations.isEmpty()) {
            ":feature:system contains forbidden app, data, player, feature, or root navigation references:\n" +
                violations.joinToString("\n")
        }

        val fixtureRoot = layout.projectDirectory.asFile.resolve("src/test/resources/boundary-fixtures")
        val fixtureViolations = findForbiddenFeatureSystemSourceReferences(fixtureRoot)
        val requiredFixtureViolations = setOf(
            "AppPackageImport.kt:3: import com.streamvault.app",
            "FullyQualifiedAppReference.kt:3: com.streamvault.app",
            "MainActivityReference.java:4: MainActivity",
            "RootNavigation.kt:3: NavHostController",
            "RootNavigation.java:4: NavController",
            "DataImport.kt:3: import com.streamvault.data",
            "PlayerImport.java:4: import com.streamvault.player",
            "PlaybackFeatureImport.kt:3: import com.streamvault.feature.playback",
            "ProviderFeatureImport.kt:3: import com.streamvault.feature.provider",
            "SettingsFeatureImport.java:4: import com.streamvault.feature.settings",
            "LiveFeatureImport.kt:3: import com.streamvault.feature.live",
            "CatalogFeatureImport.java:4: import com.streamvault.feature.catalog",
        )
        check(fixtureViolations.containsAll(requiredFixtureViolations)) {
            ":feature:system boundary fixtures are not detected: " +
                "${requiredFixtureViolations - fixtureViolations.toSet()}"
        }

        val reportFile = featureSystemBoundaryReport.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            listOf(
                "projectDependencies=${projectDependencyPaths.sorted().joinToString(",")}",
                "mainSourceViolations=${violations.joinToString("|")}",
                "fixtureViolations=${fixtureViolations.joinToString("|")}"
            ).joinToString("\n")
        )
        println(
            "Verified :feature:system boundary: approved dependencies, no forbidden source references, " +
                "and Kotlin/Java fixture coverage."
        )
    }
}

tasks.named("check") {
    dependsOn(verifyFeatureSystemBoundary)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(verifyFeatureSystemBoundary)
}

dependencies {
    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))
    implementation(project(":domain"))

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
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coil.compose)
    implementation(libs.core.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.robolectric)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.navigation.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}
