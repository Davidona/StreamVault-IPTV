import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.room) apply false
    alias(libs.plugins.kover)
}

dependencies {
    kover(project(":app"))
    kover(project(":data"))
    kover(project(":domain"))
    kover(project(":player"))
    kover(project(":feature:playback"))
    kover(project(":feature:provider"))
    kover(project(":feature:system"))
}

tasks.register("verifyLintBaseline") {
    group = "verification"
    description = "Verifies that the committed lint baseline is present and non-empty."
    doLast {
        val baselinePaths = listOf(
            "app/lint-baseline.xml",
            "data/lint-baseline.xml",
            "player/lint-baseline.xml"
        )
        val issuePattern = Regex("""<issue(?:\s|>)""")
        val issueIdPattern = Regex("""<issue\b[^>]*\bid=\"([^\"]+)\"""")

        baselinePaths.forEach { path ->
            val baseline = rootProject.file(path)
            check(baseline.isFile) {
                "Lint baseline not found: $path"
            }

            val content = baseline.readText()
            val issueCount = issuePattern.findAll(content).count()
            check(issueCount > 0) {
                "Lint baseline is empty; remove it and require a clean lint run: $path"
            }

            val issueTypeCount = issueIdPattern.findAll(content)
                .map { it.groupValues[1] }
                .toSet()
                .size
            check(issueTypeCount > 0) {
                "Lint baseline contains no issue identifiers: $path"
            }
            println("$path contains $issueCount issue records across $issueTypeCount issue types.")

            // Baselines are intentionally committed and owned. This check prevents a later
            // change from silently deleting the accepted backlog to make CI green.
            check("by=\"lint " in content) {
                "Lint baseline must retain the generated marker for reviewability: $path"
            }
        }
    }
}

tasks.register("verifyCoreUiBoundary") {
    group = "verification"
    description = "Verifies that :core:ui remains independent from app and feature implementations."
    dependsOn(":core:ui:verifyCoreUiBoundary")
}

tasks.register("verifyCoreNavigationBoundary") {
    group = "verification"
    description = "Verifies that :core:navigation remains contract-only."
    dependsOn(":core:navigation:verifyCoreNavigationBoundary")
}

tasks.register("verifyFeaturePlaybackBoundary") {
    group = "verification"
    description = "Verifies that :feature:playback remains independent from :app, root navigation, and Media3 implementation details."
    dependsOn(":feature:playback:verifyFeaturePlaybackBoundary")
}

tasks.register("verifyFeatureProviderBoundary") {
    group = "verification"
    description = "Verifies that :feature:provider remains independent from :app and root navigation controllers."
    dependsOn(":feature:provider:verifyFeatureProviderBoundary")
}

abstract class VerifyBaselineProfileSourcesTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val profileDirectory: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val baselineProfile: RegularFileProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val startupProfile: RegularFileProperty

    @TaskAction
    fun verify() {
        val profileDirectoryFile = profileDirectory.get().asFile
        val baselineProfileFile = baselineProfile.get().asFile
        val startupProfileFile = startupProfile.get().asFile

        check(baselineProfileFile.isFile && baselineProfileFile.length() > 0) {
            "Missing or empty baseline profile source: ${baselineProfileFile.path}. " +
                "Run :app:generateBaselineProfile after installing a seeded target."
        }
        check(startupProfileFile.isFile && startupProfileFile.length() > 0) {
            "Missing or empty startup profile source: ${startupProfileFile.path}. " +
                "Run :app:generateBaselineProfile after installing a seeded target."
        }

        fun rules(file: java.io.File): Set<String> = file.readLines()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toSet()

        // A startup profile may mark a method with a stronger startup flag (for example,
        // SPL instead of PL). Subset validation is about covered methods/classes, not the
        // exact flag strength emitted by two independent stable collections.
        fun ruleKeys(rules: Set<String>): Set<String> = rules.map { rule ->
            rule.replace(Regex("^[HSP]+(?=L)"), "")
        }.toSet()

        val baselineRules = rules(baselineProfileFile)
        val startupRules = rules(startupProfileFile)
        val baselineRuleKeys = ruleKeys(baselineRules)
        val startupRuleKeys = ruleKeys(startupRules)
        check(baselineRules.isNotEmpty()) { "Baseline profile contains no rules." }
        check(startupRules.isNotEmpty()) { "Startup profile contains no rules." }
        check(baselineRuleKeys != startupRuleKeys) {
            "Startup and baseline profile sources are identical; keep startup collection startup-only."
        }
        check(baselineRuleKeys.containsAll(startupRuleKeys)) {
            "Startup profile contains methods/classes that are not present in the baseline profile."
        }

        val duplicateVariantDirectories = profileDirectoryFile.walkTopDown()
            .filter { it.isDirectory && it != profileDirectoryFile }
            .filter { directory ->
                directory.name.contains("beta", ignoreCase = true) ||
                    directory.name.contains("release", ignoreCase = true)
            }
            .toList()
        check(duplicateVariantDirectories.isEmpty()) {
            "Do not keep beta/release generated-profile directories under app/src/main/generated."
        }

        println(
            "Verified baseline profile sources: baseline=${baselineRules.size} rules, " +
                "startup=${startupRules.size} rules."
        )
    }
}

tasks.register<VerifyBaselineProfileSourcesTask>("verifyBaselineProfileSources") {
    group = "verification"
    description = "Checks the maintained baseline and startup profile source files."
    profileDirectory.set(layout.projectDirectory.dir("app/src/main/generated/baselineProfiles"))
    baselineProfile.set(layout.projectDirectory.file("app/src/main/generated/baselineProfiles/baseline-prof.txt"))
    startupProfile.set(layout.projectDirectory.file("app/src/main/generated/baselineProfiles/startup-prof.txt"))
    dependsOn(":app:installMergedBaselineProfile")
}

kover {
    currentProject {
        createVariant("ci") {}
    }
    reports {
        variant("ci") {
            xml {
                onCheck = false
                xmlFile = layout.buildDirectory.file("reports/kover/report.xml")
            }
            html {
                onCheck = false
                htmlDir = layout.buildDirectory.dir("reports/kover/html")
            }
        }
        filters {
            excludes {
                classes(
                    "*.BuildConfig",
                    "*.Manifest",
                    "*.Manifest*",
                    "*.R",
                    "*.R$*",
                    "*.ComposableSingletons*",
                    "dagger.hilt.internal.*",
                    "hilt_aggregated_deps.*",
                    "*Hilt*",
                    "*_Factory",
                    "*_Factory$*",
                    "*_MembersInjector",
                    "*_HiltModules*"
                )
            }
        }
    }
}
