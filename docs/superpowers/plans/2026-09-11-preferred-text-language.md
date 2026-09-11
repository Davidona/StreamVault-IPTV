# Preferred Subtitle-Language Auto-Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task with verification checkpoints.

**Goal:** Use Media3 1.11's stable text-language selection parameters as the automatic fallback for a saved VOD subtitle-language preference while preserving StreamVault's existing per-title track ID/label overrides and disabled-subtitle behavior.

**Architecture:** Keep `VodTrackPreferences` and `VodTrackPreferenceResolver` as the app-owned persistence and exact-track layer. Add a small pure policy that translates the saved subtitle preference into Media3 text parameters (`preferredTextLanguage`, `selectTextByDefault`, and text enabled/disabled), then apply that policy when the player initializes and when VOD preferences are applied. No new UI or persistence schema is needed because develop already provides the subtitle preference picker and storage.

**Tech Stack:** Kotlin, Media3 1.11 `TrackSelectionParameters.Builder`, JUnit/Truth, existing Android player module.

**Spec:** `docs/upgrade.txt`, section “Media3 1.10.x findings” → “Existing app functions worth evaluating against 1.10 APIs” (preferred subtitle-language selection).

## Global Constraints

- Preserve explicit disabled-subtitle choices.
- Preserve exact saved track ID/label matching and language-family fallback.
- Do not enable subtitles for live playback when no VOD preference is supplied.
- Do not add a new dependency or expose Media3 types through `:feature:playback`.
- Keep external subtitle loading and the existing subtitle picker behavior unchanged.

---

### Task 1: Define the Media3 text-selection policy

**Files:**
- Create: `player/src/main/java/com/streamvault/player/tracks/PreferredTextTrackPolicy.kt`
- Test: `player/src/test/java/com/streamvault/player/tracks/PreferredTextTrackPolicyTest.kt`

**Interfaces:**
- Consumes: nullable `VodTrackPreferences`.
- Produces: an internal immutable policy containing `preferredLanguageTag: String?`, `selectTextByDefault: Boolean`, and `textDisabled: Boolean`.

- [x] **Step 1: Write the failing tests**

Cover these exact cases:

```kotlin
@Test
fun `no VOD preference keeps text disabled`() {
    assertThat(resolvePreferredTextTrackPolicy(null)).isEqualTo(
        PreferredTextTrackPolicy(preferredLanguageTag = null, selectTextByDefault = false, textDisabled = true)
    )
}

@Test
fun `saved subtitle language enables Media3 automatic text selection`() {
    val preferences = VodTrackPreferences(
        subtitle = VodTrackPreference(language = "en-US", label = "English")
    )

    assertThat(resolvePreferredTextTrackPolicy(preferences)).isEqualTo(
        PreferredTextTrackPolicy(preferredLanguageTag = "en-US", selectTextByDefault = true, textDisabled = false)
    )
}

@Test
fun `explicitly disabled subtitles stay disabled`() {
    val preferences = VodTrackPreferences(
        subtitle = VodTrackPreference(language = "en", disabled = true)
    )

    assertThat(resolvePreferredTextTrackPolicy(preferences)).isEqualTo(
        PreferredTextTrackPolicy(preferredLanguageTag = null, selectTextByDefault = false, textDisabled = true)
    )
}

@Test
fun `label-only preference waits for the app-owned exact-track override`() {
    val preferences = VodTrackPreferences(
        subtitle = VodTrackPreference(label = "Commentary")
    )

    assertThat(resolvePreferredTextTrackPolicy(preferences)).isEqualTo(
        PreferredTextTrackPolicy(preferredLanguageTag = null, selectTextByDefault = false, textDisabled = true)
    )
}
```

- [x] **Step 2: Run the policy test and verify it fails**

Run:

```text
.\gradlew.bat :player:testDebugUnitTest --tests com.streamvault.player.tracks.PreferredTextTrackPolicyTest --no-daemon
```

Expected: compilation failure because `PreferredTextTrackPolicy` and `resolvePreferredTextTrackPolicy` do not exist yet.

- [x] **Step 3: Implement the minimal policy**

Normalize blank language values to `null`. Return disabled text unless the subtitle preference exists, is not disabled, and contains a nonblank language. Do not infer a language from the label; the existing resolver owns that exact-match behavior.

- [x] **Step 4: Run the policy test and verify it passes**

Run the same Gradle command and expect all four tests to pass.

### Task 2: Apply the policy without changing custom precedence

**Files:**
- Modify: `player/src/main/java/com/streamvault/player/tracks/PlayerTrackController.kt`
- Test: `player/src/test/java/com/streamvault/player/tracks/PlayerTrackControllerSelectionTest.kt`

**Interfaces:**
- Consumes: `resolvePreferredTextTrackPolicy(vodTrackPreferences)` from Task 1.
- Produces: Media3 parameters with preferred text language only as fallback; `TrackSelectionOverride` remains authoritative when a saved track resolves.

- [x] **Step 1: Write the failing controller-policy assertions**

Add pure assertions around the policy-to-parameter decisions (without constructing a real player): a language preference must enable automatic text selection, a disabled preference must disable text, and a missing preference must remain disabled. Keep exact track resolver tests unchanged.

- [x] **Step 2: Run the focused controller tests and verify the new assertions fail**

Run:

```text
.\gradlew.bat :player:testDebugUnitTest --tests com.streamvault.player.tracks.PlayerTrackControllerSelectionTest --no-daemon
```

Expected: the new assertions fail before the controller applies the Media3 policy.

- [x] **Step 3: Apply Media3 parameters**

In `applyInitialParameters`, after clearing text overrides, apply:

```kotlin
val textPolicy = resolvePreferredTextTrackPolicy(vodTrackPreferences)
setPreferredTextLanguage(textPolicy.preferredLanguageTag)
setSelectTextByDefault(textPolicy.selectTextByDefault)
setTrackTypeDisabled(C.TRACK_TYPE_TEXT, textPolicy.textDisabled)
```

In `applyVodTrackPreferences`, apply the same preferred-language/default flags before the existing subtitle branch. Preserve the existing branch that resolves ID/language/label and installs an override; preserve its behavior of disabling text when an explicitly saved language/track cannot be resolved. This keeps the Media3 preference from selecting an arbitrary language when the app-owned preference has no match.

- [x] **Step 4: Run focused tests and verify they pass**

Run the policy and controller tests together:

```text
.\gradlew.bat :player:testDebugUnitTest --tests com.streamvault.player.tracks.PreferredTextTrackPolicyTest --tests com.streamvault.player.tracks.PlayerTrackControllerSelectionTest --no-daemon
```

### Task 3: Update the audit and run integration verification

**Files:**
- Modify: `docs/upgrade.txt`

- [x] **Step 1: Mark the preferred subtitle-language item complete**

Change the 1.10 “Preferred subtitle-language selection” item from `ADD` to `DONE`, explain that Media3 text parameters are now the automatic language fallback, and retain the custom persisted ID/label resolver for precedence and provider compatibility. Remove it from the remaining backlog.

- [x] **Step 2: Refresh the code graph**

Run:

```text
graphify update .
```

- [x] **Step 3: Run the combined verification**

Run:

```text
.\gradlew.bat :player:testDebugUnitTest :feature:playback:testDebugUnitTest :app:assembleDebug --no-daemon
```

Expected: build success, zero failures/errors in player and playback tests, and the existing feature-playback boundary check remains successful.

- [x] **Step 4: Check the final diff**

Run:

```text
git diff --check
git diff --name-only --diff-filter=U
```

Expected: no whitespace errors and no unmerged paths.
