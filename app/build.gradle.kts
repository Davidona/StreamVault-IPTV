import java.util.Properties
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
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
        versionCode = 16
        versionName = "1.0.15"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OFFICIAL_APPLICATION_ID", "\"com.streamvault.app\"")
        buildConfigField("String", "OFFICIAL_SIGNING_CERT_SHA256", "\"$officialSigningCertSha256\"")
        buildConfigField("String", "APP_UPDATE_CHANNEL", "\"stable\"")
        buildConfigField("long", "BUILD_TIMESTAMP_UTC", "0L")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
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

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":player"))

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
    implementation(files("../player/libs/media3-decoder-ffmpeg-1.9.2.aar"))

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
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}

tasks.configureEach {
    if (name == "hiltJavaCompileDebugUnitTest") {
        enabled = false
    }
}
