plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
    alias(libs.plugins.kover)
}

android {
    namespace = "com.streamvault.data"
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
    }

    room {
        schemaDirectory("$projectDir/schemas")
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

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    implementation(libs.kotlinx.serialization.json)

    // Google Sign-In (Drive sync)
    implementation(libs.play.services.auth)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // DataStore
    implementation(libs.datastore.preferences)
    implementation(libs.security.crypto)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.documentfile)
    implementation(libs.tracing)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
    // kxml2: JVM XmlPullParser implementation needed for XmltvParser unit tests
    // (Android platform provides its own impl; the JVM test runner needs an explicit one)
    testImplementation(libs.kxml2)
    // Mocking for SyncManagerTest
    testImplementation(libs.mockito.kotlin)

    // Android instrumentation tests
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.work.testing)
}

afterEvaluate {
    val debugUnitTests = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest")
    tasks.register<org.gradle.api.tasks.testing.Test>("lowHeapBackupAdmissionTest") {
        group = "verification"
        description = "Runs backup admission tests with a TV-class constrained heap."
        dependsOn(
            "transformDebugUnitTestClassesWithAsm",
            "processDebugUnitTestJavaRes"
        )
        testClassesDirs = debugUnitTests.get().testClassesDirs
        classpath = debugUnitTests.get().classpath
        maxHeapSize = "128m"
        filter {
            includeTestsMatching("com.streamvault.data.manager.BackupManagerImplTest")
        }
    }
}
