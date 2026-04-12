import java.util.Properties
import java.io.FileInputStream
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use(keystoreProperties::load)
}

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
        minSdk = 28
        targetSdk = 36
        versionCode = 5
        versionName = "1.0.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "OFFICIAL_APPLICATION_ID", "\"com.streamvault.app\"")
        buildConfigField("String", "OFFICIAL_SIGNING_CERT_SHA256", "\"$officialSigningCertSha256\"")
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
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":player"))

    // Compose BOM
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
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
    implementation(libs.media3.exoplayer.rtsp)
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.ui)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
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
    implementation(libs.play.services.auth)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.mockito.kotlin)

    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
