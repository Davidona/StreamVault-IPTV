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
    namespace = "com.streamvault.feature.playback"
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
    ":player"
)

val forbiddenFeaturePlaybackSourceTokens = listOf(
    "import com.streamvault.app",
    "com.streamvault.app",
    "MainActivity",
    "NavHostController",
    "NavController",
    "Media3PlayerEngine",
    "androidx.media3",
    "import com.streamvault.data",
    "com.streamvault.data."
)

abstract class VerifyFeaturePlaybackBoundaryTask : DefaultTask() {
    @get:Input
    abstract val expectedProjectDependencies: SetProperty<String>

    @get:Input
    abstract val actualProjectDependencies: SetProperty<String>

    @get:Input
    abstract val directMedia3Dependencies: SetProperty<String>

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
            ":feature:playback project dependencies must be exactly " +
                "${expectedProjectDependencies.get().sorted()}; found ${projectDependencyPaths.sorted()}"
        }

        val media3Dependencies = directMedia3Dependencies.get()
        check(media3Dependencies.isEmpty()) {
            ":feature:playback must not declare Media3 directly; found ${media3Dependencies.sorted()}"
        }

        val violations = findForbiddenSourceReferences(productionSourceRoot.get().asFile)
        check(violations.isEmpty()) {
            ":feature:playback contains forbidden app, data, root navigation, or Media3 implementation references:\n" +
                violations.joinToString("\n")
        }

        val fixtureViolations = findForbiddenSourceReferences(fixtureRoot.get().asFile)
        val requiredViolations = requiredFixtureViolations.get()
        check(fixtureViolations.containsAll(requiredViolations)) {
            ":feature:playback boundary fixtures are not detected: " +
                "${requiredViolations - fixtureViolations.toSet()}"
        }

        val report = reportFile.get().asFile
        report.parentFile.mkdirs()
        report.writeText(
            listOf(
                "projectDependencies=${projectDependencyPaths.sorted().joinToString(",")}",
                "directMedia3Dependencies=${media3Dependencies.sorted().joinToString(",")}",
                "mainSourceViolations=${violations.joinToString("|")}",
                "fixtureViolations=${fixtureViolations.joinToString("|")}"
            ).joinToString("\n")
        )

        println(
            "Verified :feature:playback boundary: approved dependencies, no direct Media3 dependencies, " +
                "no forbidden source references, and Kotlin/Java fixture coverage."
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

val featurePlaybackBoundaryReport = layout.buildDirectory.file(
    "reports/feature-playback-boundary/report.txt"
)

val verifyFeaturePlaybackBoundary = tasks.register<VerifyFeaturePlaybackBoundaryTask>(
    "verifyFeaturePlaybackBoundary"
) {
    group = "verification"
    description = "Verifies the playback presentation-implementation source and dependency boundary."
    expectedProjectDependencies.set(allowedProjectDependencies)
    forbiddenSourceTokens.set(forbiddenFeaturePlaybackSourceTokens)
    requiredFixtureViolations.set(
        setOf(
            "AppPackageImport.kt:3: import com.streamvault.app",
            "FullyQualifiedAppReference.kt:3: com.streamvault.app",
            "MainActivityReference.java:4: MainActivity",
            "RootNavigation.kt:3: NavHostController",
            "RootNavigation.java:4: NavController",
            "Media3EngineImport.kt:3: Media3PlayerEngine",
            "Media3FullyQualifiedReference.java:4: androidx.media3",
            "DataPackageImport.kt:3: import com.streamvault.data",
            "DataFullyQualifiedReference.java:4: com.streamvault.data."
        )
    )
    productionSourceRoot.set(layout.projectDirectory.dir("src/main"))
    fixtureRoot.set(layout.projectDirectory.dir("src/test/resources/boundary-fixtures"))
    reportFile.set(featurePlaybackBoundaryReport)
}

tasks.named("check") {
    dependsOn(verifyFeaturePlaybackBoundary)
}

tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    dependsOn(verifyFeaturePlaybackBoundary)
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
    testImplementation(libs.mockito.kotlin)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.truth)
}

val featurePlaybackProjectDependencies = configurations
    .flatMap { configuration ->
        configuration.dependencies
            .withType<ProjectDependency>()
            .filter { dependency -> dependency.path != project.path }
            .map { dependency -> dependency.path }
    }
    .toSet()

val featurePlaybackDirectMedia3Dependencies = configurations
    .flatMap { configuration ->
        configuration.dependencies
            .filterIsInstance<org.gradle.api.artifacts.ExternalModuleDependency>()
            .filter { dependency -> dependency.group == "androidx.media3" }
            .map { dependency -> "${dependency.group}:${dependency.name}" }
    }
    .toSet()

verifyFeaturePlaybackBoundary.configure {
    actualProjectDependencies.set(featurePlaybackProjectDependencies)
    directMedia3Dependencies.set(featurePlaybackDirectMedia3Dependencies)
}
