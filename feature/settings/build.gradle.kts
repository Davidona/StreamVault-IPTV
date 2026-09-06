import org.gradle.api.DefaultTask
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.streamvault.feature.settings"
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
    ":player",
)

val forbiddenFeatureSettingsSourceTokens = listOf(
    "import com.streamvault.app",
    "com.streamvault.app",
    "import com.streamvault.feature.provider",
    "import com.streamvault.feature.playback",
    "import com.streamvault.data",
    "com.streamvault.data",
    "MainActivity",
    "NavHostController",
    "NavController",
)

abstract class VerifyFeatureSettingsBoundaryTask : DefaultTask() {
    @get:Input
    abstract val expectedProjectDependencies: SetProperty<String>

    @get:Input
    abstract val actualProjectDependencies: SetProperty<String>

    @get:Input
    abstract val forbiddenSourceTokens: ListProperty<String>

    @get:Input
    abstract val requiredFixtureViolations: SetProperty<String>

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val productionSourceRoot: DirectoryProperty

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val fixtureRoot: DirectoryProperty

    @get:OutputFile
    abstract val reportFile: RegularFileProperty

    @TaskAction
    fun verifyBoundary() {
        val projectDependencyPaths = actualProjectDependencies.get()
        check(projectDependencyPaths == expectedProjectDependencies.get()) {
            ":feature:settings project dependencies must be exactly " +
                "${expectedProjectDependencies.get().sorted()}; found ${projectDependencyPaths.sorted()}"
        }

        val violations = findForbiddenSourceReferences(productionSourceRoot.get().asFile)
        check(violations.isEmpty()) {
            ":feature:settings contains forbidden app, feature, or root navigation references:\n" +
                violations.joinToString("\n")
        }

        val fixtureViolations = findForbiddenSourceReferences(fixtureRoot.get().asFile)
        val requiredViolations = requiredFixtureViolations.get()
        check(fixtureViolations.containsAll(requiredViolations)) {
            ":feature:settings boundary fixtures are not detected: " +
                "${requiredViolations - fixtureViolations.toSet()}"
        }

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            listOf(
                "projectDependencies=${projectDependencyPaths.sorted().joinToString(",")}",
                "mainSourceViolations=${violations.joinToString("|")}",
                "fixtureViolations=${fixtureViolations.joinToString("|")}"
            ).joinToString("\n")
        )

        println(
            "Verified :feature:settings boundary: approved dependencies, no forbidden source references, " +
                "and Kotlin/Java fixture coverage."
        )
    }

    private fun findForbiddenSourceReferences(sourceRoot: java.io.File): List<String> = sourceRoot
        .walkTopDown()
        .filter { it.isFile && it.extension in setOf("kt", "java") }
        .flatMap { file ->
            file.readLines().flatMapIndexed { index, line ->
                forbiddenSourceTokens.get().filter(line::contains).map { token ->
                    "${file.relativeTo(sourceRoot)}:${index + 1}: $token"
                }
            }
        }
        .toList()
}

val featureSettingsBoundaryReport = layout.buildDirectory.file(
    "reports/feature-settings-boundary/report.txt"
)

val verifyFeatureSettingsBoundary = tasks.register<VerifyFeatureSettingsBoundaryTask>(
    "verifyFeatureSettingsBoundary"
) {
    group = "verification"
    description = "Verifies that settings feature source and dependencies remain app-independent."
    expectedProjectDependencies.set(allowedProjectDependencies)
    forbiddenSourceTokens.set(forbiddenFeatureSettingsSourceTokens)
    requiredFixtureViolations.set(
        setOf(
            "AppPackageImport.kt:3: import com.streamvault.app",
            "FullyQualifiedAppReference.kt:3: com.streamvault.app",
            "MainActivityReference.java:4: MainActivity",
            "RootNavigation.kt:3: NavHostController",
            "RootNavigation.java:4: NavController",
            "ProviderFeatureImport.kt:3: import com.streamvault.feature.provider",
            "PlaybackFeatureImport.java:3: import com.streamvault.feature.playback",
            "DataImport.kt:3: import com.streamvault.data",
            "DataImport.kt:3: com.streamvault.data",
            "FullyQualifiedDataReference.kt:3: com.streamvault.data"
        )
    )
    productionSourceRoot.set(layout.projectDirectory.dir("src/main"))
    fixtureRoot.set(layout.projectDirectory.dir("src/test/resources/boundary-fixtures"))
    reportFile.set(featureSettingsBoundaryReport)
}

tasks.named("check") {
    dependsOn(verifyFeatureSettingsBoundary)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(verifyFeatureSettingsBoundary)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))
    implementation(project(":domain"))
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

val featureSettingsProjectDependencies = configurations
    .flatMap { configuration ->
        configuration.dependencies
            .withType<ProjectDependency>()
            .filter { dependency -> dependency.path != project.path }
            .map { dependency -> dependency.path }
    }
    .toSet()

verifyFeatureSettingsBoundary.configure {
    actualProjectDependencies.set(featureSettingsProjectDependencies)
}
