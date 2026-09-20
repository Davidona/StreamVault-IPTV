import java.util.Properties
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}

val localPropertiesFile = rootProject.file("local.properties")
val localProperties = Properties()
if (localPropertiesFile.exists()) {
    FileInputStream(localPropertiesFile).use(localProperties::load)
}

fun localProp(key: String): String = localProperties.getProperty(key, "")

fun computeOfficialSigningCertSha256(): String {
    if (!keystorePropertiesFile.exists()) return ""

    val storePath = keystoreProperties.getProperty("storeFile") ?: return ""
    val storePassword = keystoreProperties.getProperty("storePassword") ?: return ""
    val keyAlias = keystoreProperties.getProperty("keyAlias") ?: return ""
    val storeFile = rootProject.file(storePath)
    if (!storeFile.exists()) return ""

    val keyStore = KeyStore.getInstance("JKS")
    storeFile.inputStream().use { input ->
        keyStore.load(input, storePassword.toCharArray())
    }

    val certificate = keyStore.getCertificate(keyAlias) ?: return ""
    return MessageDigest.getInstance("SHA-256")
        .digest(certificate.encoded)
        .joinToString(":") { byte -> "%02X".format(byte) }
}

val officialSigningCertSha256 = computeOfficialSigningCertSha256()

android {
    namespace = "com.streamvault.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.streamvault.app"
        minSdk = 25
        targetSdk = 36
        versionCode = 21
        versionName = "1.0.19"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        providers.gradleProperty("compatApi").orNull?.let { expectedApi ->
            testInstrumentationRunnerArguments["expected_api"] = expectedApi
        }
        buildConfigField("String", "OFFICIAL_APPLICATION_ID", "\"com.streamvault.app\"")
        buildConfigField("String", "OFFICIAL_SIGNING_CERT_SHA256", "\"$officialSigningCertSha256\"")
        buildConfigField("String", "APP_UPDATE_CHANNEL", "\"stable\"")
        buildConfigField("long", "BUILD_TIMESTAMP_UTC", "0L")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
            providers.gradleProperty("compatAbi").orNull
                ?.takeIf(String::isNotBlank)
                ?.let { abiFilters += it }
        }
        // Dev seeding hooks — populated from rootProject/local.properties in the
        // `debug` build type only. Release builds inherit these empty defaults so
        // a release APK can never ship a contributor's credentials. See
        // local.properties.example and docs/DEV_SEEDING.md.
        buildConfigField("String", "XTREAM_DEV_SERVER", "\"\"")
        buildConfigField("String", "XTREAM_DEV_USERNAME", "\"\"")
        buildConfigField("String", "XTREAM_DEV_PASSWORD", "\"\"")
        buildConfigField("String", "XTREAM_DEV_NAME", "\"\"")
        buildConfigField("String", "M3U_DEV_URL", "\"\"")
        buildConfigField("String", "M3U_DEV_NAME", "\"\"")
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            buildConfigField("String", "XTREAM_DEV_SERVER", "\"${localProp("xtream.dev.server")}\"")
            buildConfigField("String", "XTREAM_DEV_USERNAME", "\"${localProp("xtream.dev.username")}\"")
            buildConfigField("String", "XTREAM_DEV_PASSWORD", "\"${localProp("xtream.dev.password")}\"")
            buildConfigField("String", "XTREAM_DEV_NAME", "\"${localProp("xtream.dev.name")}\"")
            buildConfigField("String", "M3U_DEV_URL", "\"${localProp("m3u.dev.url")}\"")
            buildConfigField("String", "M3U_DEV_NAME", "\"${localProp("m3u.dev.name")}\"")
        }
        create("beta") {
            initWith(getByName("release"))
            applicationIdSuffix = ".beta"
            versionNameSuffix = "-beta"
            buildConfigField("String", "APP_UPDATE_CHANNEL", "\"beta\"")
            buildConfigField("long", "BUILD_TIMESTAMP_UTC", "${System.currentTimeMillis()}L")
            isDebuggable = false
            // Keep beta close to release behavior but faster for CI/test distribution.
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            matchingFallbacks += listOf("release")
        }
        create("benchmark") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            matchingFallbacks += listOf("release")
        }
        create("nonMinifiedRelease") {
            initWith(getByName("release"))
            isDebuggable = false
            isMinifyEnabled = false
            isShrinkResources = false
            // This build type exists only for local baseline-profile generation. It is the sole
            // release-like target permitted to consume local.properties development seed values.
            buildConfigField("String", "XTREAM_DEV_SERVER", "\"${localProp("xtream.dev.server")}\"")
            buildConfigField("String", "XTREAM_DEV_USERNAME", "\"${localProp("xtream.dev.username")}\"")
            buildConfigField("String", "XTREAM_DEV_PASSWORD", "\"${localProp("xtream.dev.password")}\"")
            buildConfigField("String", "XTREAM_DEV_NAME", "\"${localProp("xtream.dev.name")}\"")
            buildConfigField("String", "M3U_DEV_URL", "\"${localProp("m3u.dev.url")}\"")
            buildConfigField("String", "M3U_DEV_NAME", "\"${localProp("m3u.dev.name")}\"")
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            matchingFallbacks += listOf("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    baselineProfile {
        mergeIntoMain = true
        saveInSrc = true
        automaticGenerationDuringBuild = false
    }

    /**
     * AGP emits startup and general profile captures as separate source files. Startup rules
     * are also baseline rules, so keep the maintained baseline source as their union while
     * preserving startup-prof.txt as the startup-only subset consumed for DEX layout.
     */
abstract class MergeStartupRulesIntoBaselineProfileTask : DefaultTask() {
        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val baselineProfile: RegularFileProperty

        @get:InputFile
        @get:PathSensitive(PathSensitivity.RELATIVE)
        abstract val startupProfile: RegularFileProperty

        @get:OutputFile
        abstract val mergedProfile: RegularFileProperty

        @get:OutputFile
        abstract val mergedStartupProfile: RegularFileProperty

        @TaskAction
        fun merge() {
            fun normalizeRule(rule: String): String = rule
                .replace("\$app_nonMinifiedRelease", "\$streamvault_app")
                .replace("\$app_beta", "\$streamvault_app")
                .replace("\$app_release", "\$streamvault_app")

            fun rules(file: java.io.File): List<String> = file.readLines()
                .map(String::trim)
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map(::normalizeRule)

            fun ruleKey(rule: String): String {
                val descriptorStart = rule.indexOf('L')
                return if (descriptorStart >= 0 && rule.substring(0, descriptorStart)
                        .all { it in "HSP" }
                ) {
                    rule.substring(descriptorStart)
                } else {
                    rule
                }
            }

            fun mergeFlags(first: String, second: String): String {
                val descriptorStart = first.indexOf('L')
                if (descriptorStart < 0 || ruleKey(first) != ruleKey(second)) return first
                val flags = (first.substring(0, descriptorStart) +
                    second.substring(0, second.indexOf('L')))
                    .toSet()
                return buildString {
                    "HSP".forEach { flag -> if (flag in flags) append(flag) }
                    append(first.substring(descriptorStart))
                }
            }

            fun deduplicate(input: List<String>): List<String> {
                val output = ArrayList<String>(input.size)
                val indexByKey = LinkedHashMap<String, Int>(input.size)
                input.forEach { rule ->
                    val key = ruleKey(rule)
                    val existingIndex = indexByKey[key]
                    if (existingIndex == null) {
                        indexByKey[key] = output.size
                        output += rule
                    } else {
                        output[existingIndex] = mergeFlags(output[existingIndex], rule)
                    }
                }
                return output
            }

            val baselineRules = deduplicate(rules(baselineProfile.get().asFile))
            val startupRules = deduplicate(rules(startupProfile.get().asFile))
            val mergedRules = ArrayList<String>(baselineRules.size + startupRules.size)
            val indexByKey = LinkedHashMap<String, Int>(baselineRules.size)
            baselineRules.forEach { rule ->
                val key = ruleKey(rule)
                val existingIndex = indexByKey[key]
                if (existingIndex == null) {
                    indexByKey[key] = mergedRules.size
                    mergedRules += rule
                } else {
                    mergedRules[existingIndex] = mergeFlags(mergedRules[existingIndex], rule)
                }
            }
            startupRules.forEach { startupRule ->
                val key = ruleKey(startupRule)
                val existingIndex = indexByKey[key]
                if (existingIndex == null) {
                    indexByKey[key] = mergedRules.size
                    mergedRules += startupRule
                } else {
                    mergedRules[existingIndex] = mergeFlags(mergedRules[existingIndex], startupRule)
                }
            }
            val output = mergedProfile.get().asFile
            output.parentFile.mkdirs()
            output.writeText(mergedRules.joinToString(separator = "\n", postfix = "\n"))
            val normalizedStartupOutput = mergedStartupProfile.get().asFile
            normalizedStartupOutput.parentFile.mkdirs()
            normalizedStartupOutput.writeText(startupRules.joinToString(separator = "\n", postfix = "\n"))
            logger.lifecycle(
                "Merged startup rules into baseline source: " +
                    "baseline=${baselineRules.size}, startup=${startupRules.size}, " +
                    "merged=${mergedRules.size}."
            )
        }
    }

    val generatedProfileDirectory = layout.projectDirectory.dir("src/main/generated/baselineProfiles")
    val mergeStartupRulesIntoBaselineProfile = tasks.register<MergeStartupRulesIntoBaselineProfileTask>(
        "mergeStartupRulesIntoBaselineProfile"
    ) {
        baselineProfile.set(generatedProfileDirectory.file("baseline-prof.txt"))
        startupProfile.set(generatedProfileDirectory.file("startup-prof.txt"))
        mergedProfile.set(layout.buildDirectory.file("intermediates/merged-generated-baseline-profile/baseline-prof.txt"))
        mergedStartupProfile.set(
            layout.buildDirectory.file("intermediates/merged-generated-baseline-profile/startup-prof.txt")
        )
    }
    val installMergedBaselineProfile = tasks.register<Copy>("installMergedBaselineProfile") {
        dependsOn(mergeStartupRulesIntoBaselineProfile)
        from(mergeStartupRulesIntoBaselineProfile.flatMap { it.mergedProfile }) {
            rename { "baseline-prof.txt" }
        }
        from(mergeStartupRulesIntoBaselineProfile.flatMap { it.mergedStartupProfile }) {
            rename { "startup-prof.txt" }
        }
        into(generatedProfileDirectory)
    }
    tasks.matching { it.name == "copyBaselineProfileIntoSrc" }.configureEach {
        // The profile plugin owns this task and registers it after the app script is evaluated.
        // Declare ordering explicitly so Gradle knows the merge consumes its copied outputs.
        mergeStartupRulesIntoBaselineProfile.get().mustRunAfter(this)
        finalizedBy(installMergedBaselineProfile)
    }

    // Release-like art/startup merge tasks consume the normalized files written by the custom
    // installer above. Keep the dependency explicit so Gradle's task validation remains sound
    // when profile verification and a beta/release assembly are requested together. During
    // `generateBaselineProfile`, the producer must package the non-minified release before the
    // producer's copy task can install the newly collected files; only those non-minified merge
    // tasks omit the dependency to avoid a cycle. Beta/release merges remain explicit even when
    // generation and assembly are requested together.
    val profileGenerationRequested = gradle.startParameter.taskNames.any { taskName ->
        taskName.substringAfterLast(':') in setOf(
            "generateBaselineProfile",
            "copyBaselineProfileIntoSrc",
            "mergeBaselineProfile",
        )
    }
    setOf(
        "mergeBetaArtProfile",
        "mergeReleaseArtProfile",
        "mergeBetaStartupProfile",
        "mergeReleaseStartupProfile",
    )
        .forEach { mergeTaskName ->
            tasks.matching { it.name == mergeTaskName }.configureEach {
                dependsOn(installMergedBaselineProfile)
            }
        }
    if (!profileGenerationRequested) {
        setOf("mergeNonMinifiedReleaseArtProfile", "mergeNonMinifiedReleaseStartupProfile")
            .forEach { mergeTaskName ->
                tasks.matching { it.name == mergeTaskName }.configureEach {
                    dependsOn(installMergedBaselineProfile)
                }
            }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        animationsDisabled = true
    }

    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
        // Dependency freshness is tracked separately from the release gate. These checks are
        // time-sensitive and would otherwise fail whenever Google publishes a newer version.
        // TrustAllX509TrustManager only fires here on compiled dependency classes, whose
        // Gradle-cache paths and transform hashes differ per machine and therefore cannot be
        // baselined portably. The owning modules (:player, :data) keep their own TLS linting.
        disable += setOf("AndroidGradlePluginVersion", "GradleDependency", "TrustAllX509TrustManager")
    }
}

abstract class VerifyFeatureNavigationBoundaryTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceRoot: DirectoryProperty

    @TaskAction
    fun verify() {
        val expectedFiles = setOf("LiveGraph.kt")
        val files = sourceRoot.get().asFile.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()
        check(files.map { it.name }.toSet() == expectedFiles) {
            "App-local graph registrations must be exactly $expectedFiles, found ${files.map { it.name }}"
        }
        val violations = files
            .filter { source ->
                val text = source.readText()
                "NavHostController" in text || "NavController" in text
            }
            .map { it.name }
        check(violations.isEmpty()) {
            "Feature graph registrations must not reference a root navigation controller: $violations"
        }
    }
}

val verifyFeatureNavigationBoundary = tasks.register<VerifyFeatureNavigationBoundaryTask>(
    "verifyFeatureNavigationBoundary"
) {
    sourceRoot.set(layout.projectDirectory.dir("src/main/java/com/streamvault/app/navigation/graph"))
}
tasks.named("check") {
    dependsOn(verifyFeatureNavigationBoundary)
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Keep Kotlin `internal` JVM names stable across release-like variants. Baseline
        // profiles are collected from nonMinifiedRelease and packaged into both release and
        // beta; variant-derived module names would otherwise make profile rules miss in beta.
        // KGP/AGP supplies a variant-derived moduleName by default. Keep this explicit compiler
        // argument last so profile rules collected from one variant match the internal JVM
        // names packaged by every release-like variant.
        freeCompilerArgs.add("-module-name=streamvault_app")
    }
}

// Diagnostic-only output for the Compose reduction work. These reports are
// build artifacts and are intentionally not committed. They expose unstable
// parameters, restartable/skippable composables, and large generated groups
// before we change UI state ownership or module boundaries.
composeCompiler {
    reportsDestination = layout.buildDirectory.dir("reports/compose-compiler")
    metricsDestination = layout.buildDirectory.dir("reports/compose-compiler")
}

kover {
    currentProject {
        createVariant("ci") {
            add("debug")
        }
    }
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":core:navigation"))
    implementation(project(":core:ui"))
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":player"))
    implementation(project(":feature:playback"))
    implementation(project(":feature:provider"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:live"))
    implementation(project(":feature:catalog"))
    implementation(project(":feature:system"))
    implementation(libs.profileinstaller)
    baselineProfile(project(":benchmark"))

    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    debugImplementation(libs.leakcanary.android)

    // Compose TV
    implementation(libs.compose.tv.foundation)
    implementation(libs.compose.tv.material)

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)
    implementation(files("../player/libs/media3-decoder-ffmpeg-1.11.0.aar"))

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.serialization.json)

    // Activity & Lifecycle
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)

    // Navigation
    implementation(libs.navigation.compose)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)

    // Image Loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.documentfile)
    implementation(libs.coroutines.android)
    implementation(libs.appcompat)
    implementation(libs.mediarouter)
    implementation(libs.play.services.cast.framework)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockito.kotlin)
    testImplementation(libs.robolectric)

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.navigation.testing)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.uiautomator)
    androidTestImplementation(libs.truth)
}

tasks.configureEach {
    if (name == "hiltJavaCompileDebugUnitTest") {
        enabled = false
    }
}
