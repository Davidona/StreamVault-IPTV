# Player Capability Boundary Design

Date: 2026-09-06  
Status: Approved for planning  
Phase: 7, checkpoint 3

## Goal

Complete the player-capability portion of Phase 7 by ensuring playback
presentation consumes player-owned capabilities and never depends on concrete
Media3 types. Preserve playback, recovery, timeshift, rendering, translation,
and MultiView behavior.

## Current state

`Media3PlayerEngine` construction is already confined to application dependency
injection, and `:feature:playback` no longer casts `PlayerEngine` to that concrete
class. The earlier feature extraction added the capability methods needed by
presentation directly to `PlayerEngine`.

Two leaks remain:

1. `LiveTranslationSession` constructs Media3 `Cue` values and compares audio
   buffers against Media3's PCM encoding constant.
2. `:feature:playback` declares Media3 ExoPlayer directly and its boundary task
   does not prevent future Media3 imports or concrete-engine references.

The existing architecture deliberately keeps the player API, Media3
implementation, playback policies, timeshift, and surface binding together in
`:player`. Splitting out another API module is not required by Phase 7 and would
force Android/view concerns across a new module boundary without changing the
runtime architecture.

## Chosen approach

Keep `PlayerEngine` as the player capability API and remove the remaining
Media3-shaped values from its presentation-facing surface.

### Injected subtitle capability

Replace the cue-based presentation operation with a text-based capability:

```kotlin
fun setInjectedSubtitleText(text: String?)
```

A non-null, non-blank value displays one injected subtitle cue. A null or blank
value clears injected subtitles. `Media3PlayerEngine` converts text to a Media3
`Cue` internally and delegates to the existing view binder. Internal binder APIs
may continue to use Media3 `Cue` because they remain inside `:player`.

The old public `setInjectedSubtitleCues` and `clearInjectedSubtitleCues`
operations will be removed after all callers migrate. This keeps one operation
and makes clearing semantics explicit.

### Live-audio capability

Replace `LiveAudioPcmBuffer.encoding: Int` with a player-owned enum:

```kotlin
enum class PlayerPcmEncoding {
    PCM_16_BIT,
    UNSUPPORTED
}
```

The Media3 audio sink maps `C.ENCODING_PCM_16BIT` to `PCM_16_BIT`; every other
value maps to `UNSUPPORTED`. Presentation retains its existing rule of ignoring
anything except 16-bit PCM. No resampling, timing, buffering, channel mixing, or
upload behavior changes.

### Dependency enforcement

Remove the direct Media3 ExoPlayer dependency and Media3 imports from
`:feature:playback`. Extend `verifyFeaturePlaybackBoundary` to reject:

- `Media3PlayerEngine` in production presentation sources;
- `androidx.media3` imports or fully qualified references in production
  presentation sources;
- direct Media3 dependency aliases in the feature build file.

Kotlin and Java fixtures will prove that import and fully qualified forms are
detected. The feature continues to depend on `:player`, which is the approved
owner of both the capability API and implementation.

## Alternatives considered

### Add `:player:api`

This would make API-versus-implementation separation visible in Gradle, but the
current API intentionally owns Android render-view creation and Compose surface
binding. A new module would add configuration and dependency overhead while
leaving those platform contracts intact. Reconsider only if a second player
implementation or non-Android consumer appears.

### Move the player contract into `:domain`

Rejected because rendering, audio taps, subtitle styling, and lifecycle control
are platform playback capabilities rather than business-domain contracts. This
would either contaminate `:domain` with Android concerns or require a much larger
redesign.

### Only add a `Media3PlayerEngine` source guard

This would document the already-satisfied cast requirement but leave direct
Media3 types and dependencies in presentation. It is too weak for the Phase 7
cleanup goal.

## Behavior and error handling

This checkpoint does not alter preparation, retries, recovery selection,
timeshift, media sessions, render surfaces, track selection, audio focus, or
transport policy. Subtitle text still becomes a single visible cue and is
cleared on the same lifecycle paths. Unsupported PCM encodings continue to be
discarded before conversion.

No new user-visible error is introduced. Existing translation failures and
cleanup paths remain unchanged.

## Testing and validation

Implementation will be test-driven and include:

- contract tests for subtitle set/clear semantics;
- mapping tests for 16-bit and unsupported Media3 audio encodings;
- translation tests proving text rendering, clearing, and unsupported-buffer
  rejection remain unchanged;
- playback boundary fixtures for concrete engine and Media3 references;
- focused `:player` and `:feature:playback` unit tests;
- the playback boundary task and `:app:assembleDebug`;
- `graphify update .` after source changes.

Because this changes player-facing API paths, final acceptance also requires the
project's multi-channel, long-duration Live TV validation: screenshots every two
seconds for at least 90 seconds per channel, frame-progression hashes, a healthy
PLAYING media session, and sanitized logs with no fatal/stuck/unintended
MPEG-TS-fallback evidence.

## Completion criteria

- `:feature:playback/src/main` contains no `Media3PlayerEngine` or
  `androidx.media3` references.
- `:feature:playback` has no direct Media3 Gradle dependency.
- Presentation uses only player-owned subtitle and PCM models.
- The automated boundary prevents regression.
- Focused and integration verification passes.
- Required Live TV validation passes or is explicitly recorded as an external
  environment blocker; it must not be reported as passing without evidence.
