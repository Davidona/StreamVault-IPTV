import java.io.ByteArrayInputStream
import java.util.Properties
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kover)
}

abstract class VerifyLocalFfmpegArtifactTask : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpegAarFile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val ffmpegManifestFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val aarFile = ffmpegAarFile.get().asFile
        val manifestFile = ffmpegManifestFile.get().asFile
        val manifest = Properties().apply {
            manifestFile.inputStream().use(::load)
        }

        check(manifest.getProperty("media3Version") == "1.9.2") {
            "FFmpeg artifact must use Media3 version 1.9.2"
        }

        val aarEntries = ZipFile(aarFile).use { zipFile ->
            zipFile.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name }
                .toSet()
        }
        listOf("jni/arm64-v8a/", "jni/armeabi-v7a/").forEach { abiPrefix ->
            check(aarEntries.any { it.startsWith(abiPrefix) && it.endsWith(".so") }) {
                "FFmpeg artifact is missing a native library under $abiPrefix"
            }
        }

        val classesJarBytes = readZipEntry(aarFile, "classes.jar")
        val classEntries = zipEntryNames(classesJarBytes)
        listOf(
            "androidx/media3/decoder/ffmpeg/FfmpegLibrary.class",
            "androidx/media3/decoder/ffmpeg/FfmpegAudioRenderer.class"
        ).forEach { requiredClass ->
            check(requiredClass in classEntries) {
                "FFmpeg artifact is missing required class $requiredClass"
            }
        }

        val enabledDecoders = manifest.getProperty("enabledDecoders")
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
        check("mp2" in enabledDecoders) {
            "FFmpeg artifact must include the mp2 decoder for MPEG layer II audio streams"
        }

        val ffmpegLibraryClassText = zipEntryBytes(
            classesJarBytes,
            "androidx/media3/decoder/ffmpeg/FfmpegLibrary.class"
        ).toString(Charsets.ISO_8859_1)
        check("audio/mpeg-L2" in ffmpegLibraryClassText) {
            "FFmpeg FfmpegLibrary must expose audio/mpeg-L2 MIME type for MPEG layer II audio"
        }

        ZipFile(aarFile).use { zipFile ->
            zipFile.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory &&
                        entry.name.startsWith("jni/") &&
                        entry.name.endsWith("/libffmpegJNI.so")
                }
                .forEach { nativeLibrary ->
                    val nativeLibraryText = zipFile.getInputStream(nativeLibrary).use { input ->
                        input.readBytes().toString(Charsets.ISO_8859_1)
                    }
                    check("ff_mp2_decoder" in nativeLibraryText) {
                        "FFmpeg native library is missing the mp2 decoder: ${nativeLibrary.name}"
                    }
                }
        }
    }

    private fun readZipEntry(zipFile: java.io.File, entryName: String): ByteArray =
        ZipFile(zipFile).use { archive ->
            val entry = archive.getEntry(entryName)
                ?: error("FFmpeg artifact is missing $entryName")
            archive.getInputStream(entry).use { it.readBytes() }
        }

    private fun zipEntryNames(zipBytes: ByteArray): Set<String> {
        val names = mutableSetOf<String>()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                names += entry.name
                entry = zipInput.nextEntry
            }
        }
        return names
    }

    private fun zipEntryBytes(zipBytes: ByteArray, entryName: String): ByteArray {
        var bytes: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zipInput ->
            var entry = zipInput.nextEntry
            while (entry != null) {
                if (entry.name == entryName) {
                    bytes = zipInput.readBytes()
                    break
                }
                entry = zipInput.nextEntry
            }
        }
        return bytes ?: error("FFmpeg classes.jar is missing $entryName")
    }
}

android {
    namespace = "com.streamvault.player"
    compileSdk = 36

    defaultConfig {
        minSdk = 25
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        baseline = file("lint-baseline.xml")
        warningsAsErrors = true
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

val verifyLocalFfmpegArtifact = tasks.register<VerifyLocalFfmpegArtifactTask>("verifyLocalFfmpegArtifact") {
    group = "verification"
    description = "Verifies the bundled Media3 FFmpeg artifact, metadata, and supported ABIs."

    ffmpegAarFile.set(layout.projectDirectory.file("libs/media3-decoder-ffmpeg-1.9.2.aar"))
    ffmpegManifestFile.set(layout.projectDirectory.file("libs/media3-decoder-ffmpeg-1.9.2.properties"))
}

tasks.named("preBuild").configure {
    dependsOn(verifyLocalFfmpegArtifact)
}

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(project(":domain"))

    // Media3
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.exoplayer.smoothstreaming)
    implementation(libs.media3.exoplayer.rtsp)  // PE-H03: RTSP stream support
    implementation(libs.media3.datasource.okhttp)
    implementation(libs.media3.session)
    implementation(libs.media3.ui)

    // OkHttp (for custom data source)
    implementation(libs.okhttp)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // Core
    implementation(libs.core.ktx)

    // Test
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
}
