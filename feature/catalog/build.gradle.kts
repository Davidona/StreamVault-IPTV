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
    namespace = "com.streamvault.feature.catalog"
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
)

val forbiddenFeatureCatalogSourceTokens = listOf(
    "import com.streamvault.app",
    "com.streamvault.app",
    "import com.streamvault.player",
    "com.streamvault.player",
    "import com.streamvault.feature.playback",
    "com.streamvault.feature.playback",
    "import com.streamvault.feature.provider",
    "com.streamvault.feature.provider",
    "import com.streamvault.feature.settings",
    "com.streamvault.feature.settings",
    "import com.streamvault.feature.live",
    "com.streamvault.feature.live",
    "MainActivity",
    "NavHostController",
    "NavController",
)

fun findForbiddenFeatureCatalogSourceReferences(sourceRoot: java.io.File): List<String> = sourceRoot
    .walkTopDown()
    .filter { it.isFile && it.extension in setOf("kt", "java") }
    .flatMap { file ->
        file.readLines().flatMapIndexed { index, line ->
            forbiddenFeatureCatalogSourceTokens.filter(line::contains).map { token ->
                "${file.relativeTo(sourceRoot)}:${index + 1}: ${token}"
            }
        }
    }
    .toList()

val featureCatalogBoundaryReport = layout.buildDirectory.file(
    "reports/feature-catalog-boundary/report.txt"
)

val verifyFeatureCatalogBoundary = tasks.register("verifyFeatureCatalogBoundary") {
    group = "verification"
    description = "Verifies that the Catalog feature source and dependencies remain app-independent."
    outputs.file(featureCatalogBoundaryReport)
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
            ":feature:catalog project dependencies must be exactly " +
                "${allowedProjectDependencies.sorted()}; found ${projectDependencyPaths.sorted()}"
        }

        val sourceRoot = layout.projectDirectory.asFile.resolve("src/main")
        val violations = findForbiddenFeatureCatalogSourceReferences(sourceRoot)
        check(violations.isEmpty()) {
            ":feature:catalog contains forbidden app, player, feature, or root navigation references:\n" +
                violations.joinToString("\n")
        }

        val fixtureRoot = layout.projectDirectory.asFile.resolve("src/test/resources/boundary-fixtures")
        val fixtureViolations = findForbiddenFeatureCatalogSourceReferences(fixtureRoot)
        val requiredFixtureViolations = setOf(
            "AppPackageImport.kt:3: import com.streamvault.app",
            "FullyQualifiedAppReference.kt:3: com.streamvault.app",
            "MainActivityReference.java:4: MainActivity",
            "RootNavigation.kt:3: NavHostController",
            "RootNavigation.java:4: NavController",
            "PlaybackFeatureImport.kt:3: import com.streamvault.feature.playback",
            "ProviderFeatureImport.kt:3: import com.streamvault.feature.provider",
            "SettingsFeatureImport.java:4: com.streamvault.feature.settings",
            "LiveFeatureImport.kt:3: import com.streamvault.feature.live",
            "PlayerModuleImport.java:4: com.streamvault.player",
        )
        check(fixtureViolations.containsAll(requiredFixtureViolations)) {
            ":feature:catalog boundary fixtures are not detected: " +
                "${requiredFixtureViolations - fixtureViolations.toSet()}"
        }

        val reportFile = featureCatalogBoundaryReport.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(
            listOf(
                "projectDependencies=${projectDependencyPaths.sorted().joinToString(",")}",
                "mainSourceViolations=${violations.joinToString("|")}",
                "fixtureViolations=${fixtureViolations.joinToString("|")}"
            ).joinToString("\n")
        )
        println(
            "Verified :feature:catalog boundary: approved dependencies, no forbidden source references, " +
                "and Kotlin/Java fixture coverage."
        )
    }
}

tasks.named("check") {
    dependsOn(verifyFeatureCatalogBoundary)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(verifyFeatureCatalogBoundary)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))
    implementation(project(":domain"))
    implementation(project(":data"))

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
