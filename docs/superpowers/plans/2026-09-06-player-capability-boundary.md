# Player Capability Boundary Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove Media3-shaped values and direct Media3 dependencies from playback presentation while preserving every player behavior.

**Architecture:** Keep `PlayerEngine` in `:player` as the approved capability API and keep Media3 conversion inside that module. Presentation sends nullable subtitle text and consumes a player-owned PCM encoding enum; the playback boundary task prevents concrete-engine and Media3 references from returning.

**Tech Stack:** Kotlin, Android, Media3 1.9.2, Compose, Coroutines, JUnit 4, Truth, Robolectric, Gradle Kotlin DSL

**Spec:** `docs/superpowers/specs/2026-09-06-player-capability-boundary-design.md`

## Global Constraints

- Do not change preparation, retries, recovery selection, timeshift, media sessions, render surfaces, track selection, audio focus, or transport policy.
- A non-null, non-blank injected subtitle string displays one cue; null or blank clears injected subtitles.
- Only Media3 `C.ENCODING_PCM_16BIT` maps to `PlayerPcmEncoding.PCM_16_BIT`; every other value maps to `UNSUPPORTED`.
- `:feature:playback/src/main` must contain no `Media3PlayerEngine` or `androidx.media3` references.
- `:feature:playback` must have no direct Media3 Gradle dependency.
- Keep `:feature:playback -> :player`; do not create a new API module.
- Use TDD and commit each independently reviewable task.
- Run `graphify update .` after code changes.
- Do not report player acceptance complete without the required multi-channel long-duration Live TV evidence.

## File map

- `player/src/main/java/com/streamvault/player/PlayerEngine.kt`: presentation-facing capability signatures.
- `player/src/main/java/com/streamvault/player/LiveAudioTap.kt`: player-owned PCM buffer and encoding model.
- `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt`: Media3 subtitle adaptation.
- `player/src/main/java/com/streamvault/player/ui/PlayerViewBinder.kt`: internal conversion from nullable text to Media3 cues.
- `player/src/main/java/com/streamvault/player/playback/LiveAudioTapAudioSink.kt`: Media3-to-player PCM encoding mapping.
- `feature/playback/src/main/java/com/streamvault/feature/playback/translation/LiveTranslationSession.kt`: capability-only translation consumer.
- `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerLiveTranslationActions.kt`: nullable-text clear call.
- `feature/playback/build.gradle.kts`: direct dependency removal and automated boundary enforcement.
- `feature/playback/src/test/resources/boundary-fixtures/`: source guard regression fixtures.
- `docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`: checkpoint evidence and remaining Phase 7 scope.

---

### Task 1: Define the player boundary regression in red

**Files:**

- Modify: `feature/playback/src/test/java/com/streamvault/feature/playback/PlaybackModuleBoundaryTest.kt`
- Create: `feature/playback/src/test/resources/boundary-fixtures/Media3EngineImport.kt`
- Create: `feature/playback/src/test/resources/boundary-fixtures/Media3FullyQualifiedReference.java`

**Interfaces:**

- Consumes: existing `fixtureViolations` field from the playback boundary report.
- Produces: executable requirements for detecting `Media3PlayerEngine` and `androidx.media3` source references.

- [ ] **Step 1: Add source fixtures**

```kotlin
package fixtures

import com.streamvault.player.Media3PlayerEngine
```

```java
package fixtures;

final class Media3FullyQualifiedReference {
    androidx.media3.common.Player player;
}
```

- [ ] **Step 2: Add failing boundary assertions**

Extend `boundary guard rejects Kotlin and Java app and root navigation fixtures`:

```kotlin
assertThat(fixtureViolations)
    .contains("Media3EngineImport.kt:3: Media3PlayerEngine")
assertThat(fixtureViolations)
    .contains("Media3FullyQualifiedReference.java:4: androidx.media3")
```

Also add a build-file assertion that reads `build.gradle.kts` from the module
root and requires the eventual absence of `implementation(libs.media3.exoplayer)`:

```kotlin
@Test
fun `playback feature does not declare Media3 directly`() {
    val buildFile = java.io.File("build.gradle.kts").readText()
    assertThat(buildFile).doesNotContain("implementation(libs.media3.exoplayer)")
}
```

- [ ] **Step 3: Run the focused test to prove red**

Run:

```powershell
.\gradlew.bat :feature:playback:testDebugUnitTest --tests "com.streamvault.feature.playback.PlaybackModuleBoundaryTest" --console=plain --no-daemon
```

Expected: FAIL because the current guard does not report either new fixture and the build file still declares Media3 ExoPlayer.

- [ ] **Step 4: Commit the red contract**

```powershell
git add feature/playback/src/test/java/com/streamvault/feature/playback/PlaybackModuleBoundaryTest.kt feature/playback/src/test/resources/boundary-fixtures
git commit -m "test(playback): define Media3 boundary red"
```

---

### Task 2: Replace cue exposure with a subtitle-text capability

**Files:**

- Modify: `player/src/main/java/com/streamvault/player/PlayerEngine.kt`
- Modify: `player/src/main/java/com/streamvault/player/Media3PlayerEngine.kt`
- Modify: `player/src/main/java/com/streamvault/player/ui/PlayerViewBinder.kt`
- Create: `player/src/test/java/com/streamvault/player/ui/PlayerInjectedSubtitleTest.kt`
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/translation/LiveTranslationSession.kt`
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerLiveTranslationActions.kt`

**Interfaces:**

- Consumes: `PlayerEngine` and the existing internal Media3 `Cue` rendering behavior.
- Produces: `PlayerEngine.setInjectedSubtitleText(text: String?)` with null/blank clear semantics.

- [ ] **Step 1: Write failing subtitle conversion tests**

Add an internal mapper beside `PlayerViewBinder` and test its required result:

```kotlin
package com.streamvault.player.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PlayerInjectedSubtitleTest {
    @Test
    fun `non-blank text maps to exactly one cue`() {
        val cues = buildInjectedSubtitleCues("Translated line")

        assertThat(cues).hasSize(1)
        assertThat(cues.single().text.toString()).isEqualTo("Translated line")
    }

    @Test
    fun `null and blank text clear cues`() {
        assertThat(buildInjectedSubtitleCues(null)).isEmpty()
        assertThat(buildInjectedSubtitleCues("   ")).isEmpty()
    }
}
```

- [ ] **Step 2: Run the player test to prove red**

Run:

```powershell
.\gradlew.bat :player:testDebugUnitTest --tests "com.streamvault.player.ui.PlayerInjectedSubtitleTest" --console=plain --no-daemon
```

Expected: compilation FAIL because `buildInjectedSubtitleCues` does not exist.

- [ ] **Step 3: Implement the minimal player-side conversion**

In `PlayerViewBinder.kt` add:

```kotlin
internal fun buildInjectedSubtitleCues(text: String?): List<Cue> =
    text?.takeIf(String::isNotBlank)
        ?.let { listOf(Cue.Builder().setText(it).build()) }
        .orEmpty()
```

Replace binder set/clear methods with:

```kotlin
fun setInjectedSubtitleText(text: String?) {
    injectedSubtitleCues = buildInjectedSubtitleCues(text)
    applyInjectedCues(boundPlayerView)
}
```

Replace both methods in `PlayerEngine` with:

```kotlin
fun setInjectedSubtitleText(text: String?)
```

Implement in `Media3PlayerEngine` by delegating unchanged to the binder. Change
its internal reset paths from `clearInjectedSubtitleCues()` to
`setInjectedSubtitleText(null)`.

- [ ] **Step 4: Migrate presentation call sites**

In `LiveTranslationSession`, replace cue construction and clearing with:

```kotlin
private fun renderCaption(text: String) {
    playerEngine.setInjectedSubtitleText(text)
}

private fun clearCues() {
    playerEngine.setInjectedSubtitleText(null)
}
```

In `PlayerLiveTranslationActions`, clear with:

```kotlin
playerEngine.setInjectedSubtitleText(null)
```

Remove the Media3 `Cue` import. Do not yet remove the file-level `UnstableApi` or
Media3 `C` import; Task 3 removes the remaining audio leak.

- [ ] **Step 5: Run focused player and translation tests**

Run:

```powershell
.\gradlew.bat :player:testDebugUnitTest --tests "com.streamvault.player.ui.PlayerInjectedSubtitleTest" --console=plain --no-daemon
.\gradlew.bat :feature:playback:testDebugUnitTest --tests "com.streamvault.feature.playback.translation.LiveTranslationCaptionPacingTest" --console=plain --no-daemon
```

Expected: PASS. Task 1's complete boundary test remains intentionally red until Task 4 activates the full guard and removes the dependency.

- [ ] **Step 6: Commit the subtitle capability**

```powershell
git add player/src/main feature/playback/src/main player/src/test/java/com/streamvault/player/ui/PlayerInjectedSubtitleTest.kt
git commit -m "refactor(player): expose subtitle text capability"
```

---

### Task 3: Replace Media3 PCM encoding with a player-owned model

**Files:**

- Modify: `player/src/main/java/com/streamvault/player/LiveAudioTap.kt`
- Modify: `player/src/main/java/com/streamvault/player/playback/LiveAudioTapAudioSink.kt`
- Create: `player/src/test/java/com/streamvault/player/playback/PlayerPcmEncodingTest.kt`
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/translation/LiveTranslationSession.kt`
- Create: `feature/playback/src/test/java/com/streamvault/feature/playback/translation/LiveTranslationPcmConversionTest.kt`

**Interfaces:**

- Consumes: Media3 integer encoding only inside `:player`.
- Produces: `PlayerPcmEncoding`, `LiveAudioPcmBuffer.encoding: PlayerPcmEncoding`, and an internal translation conversion test seam.

- [ ] **Step 1: Write failing Media3 mapping tests**

```kotlin
package com.streamvault.player.playback

import androidx.media3.common.C
import com.google.common.truth.Truth.assertThat
import com.streamvault.player.PlayerPcmEncoding
import org.junit.Test

class PlayerPcmEncodingTest {
    @Test
    fun `16 bit Media3 PCM maps to player 16 bit encoding`() {
        assertThat(toPlayerPcmEncoding(C.ENCODING_PCM_16BIT))
            .isEqualTo(PlayerPcmEncoding.PCM_16_BIT)
    }

    @Test
    fun `all other Media3 encodings map to unsupported`() {
        assertThat(toPlayerPcmEncoding(C.ENCODING_PCM_FLOAT))
            .isEqualTo(PlayerPcmEncoding.UNSUPPORTED)
        assertThat(toPlayerPcmEncoding(C.ENCODING_INVALID))
            .isEqualTo(PlayerPcmEncoding.UNSUPPORTED)
    }
}
```

- [ ] **Step 2: Run the mapping test to prove red**

Run:

```powershell
.\gradlew.bat :player:testDebugUnitTest --tests "com.streamvault.player.playback.PlayerPcmEncodingTest" --console=plain --no-daemon
```

Expected: compilation FAIL because the enum and mapper do not exist.

- [ ] **Step 3: Add the player-owned model and mapping**

In `LiveAudioTap.kt` define:

```kotlin
enum class PlayerPcmEncoding {
    PCM_16_BIT,
    UNSUPPORTED
}
```

Change `LiveAudioPcmBuffer.encoding` to `PlayerPcmEncoding`. In
`LiveAudioTapAudioSink.kt` define:

```kotlin
internal fun toPlayerPcmEncoding(encoding: Int): PlayerPcmEncoding =
    if (encoding == C.ENCODING_PCM_16BIT) {
        PlayerPcmEncoding.PCM_16_BIT
    } else {
        PlayerPcmEncoding.UNSUPPORTED
    }
```

Map the configured encoding before validating and constructing the buffer. Keep
the existing early return for unsupported encoding, invalid sample rate/channel
count, and empty consumption range.

- [ ] **Step 4: Write a failing feature conversion test**

Make both `ConvertedPcmChunk` and `convertToPcm16Mono16k` internal, then test the
defensive check directly:

```kotlin
@Test
fun `translation rejects unsupported player PCM encoding`() {
    val result = convertToPcm16Mono16k(
        buffer = LiveAudioPcmBuffer(
            data = byteArrayOf(0, 0),
            presentationTimeUs = 0,
            sampleRate = 16_000,
            channelCount = 1,
            encoding = PlayerPcmEncoding.UNSUPPORTED
        ),
        fallbackStartMs = 0
    )

    assertThat(result).isNull()
}
```

Also test a two-byte `PCM_16_BIT` sample returns non-null data and preserves the
existing fallback start time:

```kotlin
@Test
fun `translation accepts player 16 bit PCM`() {
    val result = convertToPcm16Mono16k(
        buffer = LiveAudioPcmBuffer(
            data = byteArrayOf(1, 0),
            presentationTimeUs = 0,
            sampleRate = 16_000,
            channelCount = 1,
            encoding = PlayerPcmEncoding.PCM_16_BIT
        ),
        fallbackStartMs = 123
    )

    assertThat(result).isNotNull()
    assertThat(result!!.data).isEqualTo(byteArrayOf(1, 0))
    assertThat(result.startMs).isEqualTo(123)
}
```

- [ ] **Step 5: Migrate translation off Media3**

Replace the `C.ENCODING_PCM_16BIT` comparison with:

```kotlin
buffer.encoding != PlayerPcmEncoding.PCM_16_BIT
```

Remove the Media3 `C` import and file-level `UnstableApi` annotation from
`LiveTranslationSession.kt`.

- [ ] **Step 6: Run focused PCM and translation tests**

Run:

```powershell
.\gradlew.bat :player:testDebugUnitTest --tests "com.streamvault.player.playback.PlayerPcmEncodingTest" --console=plain --no-daemon
.\gradlew.bat :feature:playback:testDebugUnitTest --tests "com.streamvault.feature.playback.translation.*" --console=plain --no-daemon
```

Expected: PASS for player mapping and all translation tests.

- [ ] **Step 7: Commit the PCM capability model**

```powershell
git add player/src/main player/src/test feature/playback/src/main feature/playback/src/test/java/com/streamvault/feature/playback/translation
git commit -m "refactor(player): own live audio encoding model"
```

---

### Task 4: Activate enforcement and close checkpoint 3

**Files:**

- Modify: `feature/playback/build.gradle.kts`
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/player/PlayerScreen.kt`
- Modify: `feature/playback/src/main/java/com/streamvault/feature/playback/multiview/MultiViewScreen.kt`
- Modify: `feature/playback/src/test/java/com/streamvault/feature/playback/PlaybackModuleBoundaryTest.kt`
- Modify: `docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md`

**Interfaces:**

- Consumes: player-owned subtitle and PCM capabilities from Tasks 2 and 3.
- Produces: enforced Media3-free playback presentation source and dependency boundary.

- [ ] **Step 1: Expand the production and fixture source guard**

Add these tokens to `forbiddenFeaturePlaybackSourceTokens`:

```kotlin
"Media3PlayerEngine",
"androidx.media3"
```

Extend `requiredFixtureViolations` with the exact Task 1 fixture findings:

```kotlin
"Media3EngineImport.kt:3: Media3PlayerEngine",
"Media3FullyQualifiedReference.java:4: androidx.media3"
```

Rename the task description and success message from app-only wording to
presentation-implementation boundary wording. Keep existing app/navigation
tokens and fixtures.

- [ ] **Step 2: Enforce absence of direct Media3 dependencies**

Collect direct external dependencies without resolving transitive dependencies:

```kotlin
val directMedia3Dependencies = configurations
    .flatMap { configuration ->
        configuration.dependencies
            .filterIsInstance<org.gradle.api.artifacts.ExternalModuleDependency>()
            .filter { dependency -> dependency.group == "androidx.media3" }
            .map { dependency -> "${dependency.group}:${dependency.name}" }
    }
    .toSet()

check(directMedia3Dependencies.isEmpty()) {
    ":feature:playback must not declare Media3 directly; found ${directMedia3Dependencies.sorted()}"
}
```

Write `directMedia3Dependencies` into the boundary report and assert it is empty
in `PlaybackModuleBoundaryTest`.

- [ ] **Step 3: Remove remaining presentation annotations and dependency**

Remove Media3 `UnstableApi` imports/annotations from `PlayerScreen.kt` and
`MultiViewScreen.kt`. Remove:

```kotlin
implementation(libs.media3.exoplayer)
```

from `feature/playback/build.gradle.kts`. Do not remove Cast or Android media
router dependencies.

- [ ] **Step 4: Run the boundary contract from Task 1**

Run:

```powershell
.\gradlew.bat :feature:playback:verifyFeaturePlaybackBoundary :feature:playback:testDebugUnitTest --tests "com.streamvault.feature.playback.PlaybackModuleBoundaryTest" --console=plain --no-daemon
```

Expected: PASS, including both new fixture findings, empty production findings,
and no direct Media3 dependency.

- [ ] **Step 5: Run full automated verification**

Run:

```powershell
.\gradlew.bat :player:testDebugUnitTest :feature:playback:testDebugUnitTest :feature:live:testDebugUnitTest :app:assembleDebug --console=plain --no-daemon
```

Expected: BUILD SUCCESSFUL and zero failed tests.

- [ ] **Step 6: Refresh the graph and inspect repository state**

Run:

```powershell
graphify update .
git diff --check
git status --short
rg -n -F -e "Media3PlayerEngine" -e "androidx.media3" feature/playback/src/main
```

Expected: graph update succeeds; diff check is clean; `rg` reports no matches.

- [ ] **Step 7: Update Phase 7 documentation**

Add checkpoint 3 to the architecture plan with:

- `PlayerEngine` is the approved presentation capability API;
- nullable subtitle text and player-owned PCM encoding replace Media3 values;
- `:feature:playback` has no direct Media3 dependency or source references;
- the automated boundary rejects future regressions;
- automated verification results;
- Live TV validation status stated accurately.

- [ ] **Step 8: Commit checkpoint 3**

```powershell
git add feature/playback player docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md graphify-out
git commit -m "refactor(playback): enforce player capability boundary"
```

---

### Task 5: Perform playback acceptance validation

**Files:**

- Create: `validation/phase7_player_capability/README.md`
- Create: `validation/phase7_player_capability/live-validation.log` only after sanitizing secrets and provider URLs.

**Interfaces:**

- Consumes: checkpoint 3 APK and an emulator/device with at least two working live channels.
- Produces: auditable player acceptance evidence; no production code.

- [ ] **Step 1: Install the verified debug APK and clear log history**

```powershell
$adb = (Get-Command adb -ErrorAction Stop).Source
& $adb install -r app\build\outputs\apk\debug\app-debug.apk
& $adb logcat -c
```

Expected: install succeeds. Navigate through the app to the first available live
channel and record its displayed name in the validation README.

- [ ] **Step 2: Capture 61 frames for the first channel**

```powershell
$captureRoot = Join-Path $env:TEMP "streamvault_phase7_player_channel_1"
New-Item -ItemType Directory -Force -Path $captureRoot | Out-Null
0..60 | ForEach-Object {
    $name = "frame_{0:D2}.png" -f $_
    & $adb exec-out screencap -p > (Join-Path $captureRoot $name)
    Start-Sleep -Seconds 2
}
```

Expected: 61 screenshots spanning roughly two minutes.

- [ ] **Step 3: Verify first-channel progression and health**

```powershell
$hashes = Get-ChildItem $captureRoot -Filter *.png | Get-FileHash -Algorithm SHA256
$hashes.Hash | Sort-Object -Unique | Measure-Object
& $adb shell dumpsys media_session | Select-String -Pattern "package=com.streamvault.app","state=PlaybackState","error=" -Context 0,3
& $adb logcat -d -v time > (Join-Path $env:TEMP "streamvault_phase7_player_channel_1.log")
rg -n "fatal-error|live-recovery selected|live-recovery no-candidate|prepare resolvedStreamType=MPEG_TS_LIVE|source-malformed live-ts-fallback|Player stuck|state=ERROR" (Join-Path $env:TEMP "streamvault_phase7_player_channel_1.log")
rg -n "retry category=|first-frame-success|prepare resolvedStreamType=HLS|read-progress streamType=HLS" (Join-Path $env:TEMP "streamvault_phase7_player_channel_1.log")
```

Expected: hashes continue changing through the capture, media session remains
PLAYING with `error=null`, no fatal/stuck/unintended MPEG-TS fallback appears,
and HLS prepare/read/first-frame or valid recovery evidence is present.

- [ ] **Step 4: Repeat for a second channel**

Navigate to a different working live channel, record its displayed name, clear
logcat, and repeat Steps 2-3 using paths ending in `channel_2`. Do not reuse the
first channel's evidence.

- [ ] **Step 5: Sanitize and record results**

Create `validation/phase7_player_capability/README.md` containing each channel
name, 61 screenshot count, two-second cadence, unique hash count, media-session
state/error, and log findings. Copy only relevant sanitized log lines to
`live-validation.log`; remove credentials, tokens, query strings, and provider
URLs.

If no device or two working channels are available, record the exact missing
prerequisite in the README and leave acceptance open. Never substitute build,
install, launch, or a short visual check for this gate.

- [ ] **Step 6: Commit evidence**

```powershell
git add validation/phase7_player_capability docs/COMPOSE_REDUCTION_AND_UI_ARCHITECTURE_PLAN.md
git commit -m "test(playback): record player boundary acceptance"
```
