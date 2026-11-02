# Stremio viewing-experience audit

Researched 2026-11-01 against StreamVault source and official Stremio sources.
Local viewing implementation validated on 2026-11-02.

## Implemented local viewing experience

- Home now hosts Stremio Continue Watching, including when no IPTV provider is
  configured. Cards show artwork, progress, remaining minutes and watch time.
- Recently Watched retains completed entries. Resume, Start Over, Details and
  Dismiss are available from card actions. Dismiss only hides the Continue
  Watching cursor; saved-library membership and playback history remain intact.
- Series use one cursor per scoped series. After completion, Up Next selects the
  next released, unwatched episode using metadata IDs and provider provenance.
  Upcoming episodes do not become playable Up Next cards.
- Playback history is authoritative for completion and explicit watched actions.
  Marking an unplayed item watched creates history; old manual markers remain a
  fallback. Season/show actions save history in one batch, with future episodes
  excluded when marking watched.
- Library sorting supports last watched, title, watched and unwatched. A series
  is fully watched only when all released metadata episodes are watched.
- Saved series have release tracking enabled by default, a per-series toggle,
  new-episode counts and a 90-day release calendar. Refresh runs on history or
  add-on changes, explicit refresh, and foreground resume when the metadata
  cache is older than ten minutes. This is in-app tracking, not background push
  notifications.
- Eligible unfinished Stremio history is published to Android TV Watch Next,
  with exact-item deep links. The app Home additionally supports synthetic Up
  Next cards; the platform shelf currently publishes unfinished progress only.
- Database migration 90→91 adds dismissal timestamps with a zero default. Backup
  exports/imports preserve the field, with old backups defaulting to zero.
- New copy is translated in every advertised locale (38 localized resources).

## Remaining compatibility boundaries

Optional Stremio device linking, two-way library/progress synchronization,
account add-on imports, background release notifications and an external
Stremio Service connection are now implemented. The service supplies archive,
FTP and Usenet engines; they are not bundled into the Android app.

Signed-in production account interoperability has not been exercised without
an account. Add-on collection edits are imported only. Native IPFS/IPNS P2P,
deprecated transports, and engine-dependent archive/Usenet playback remain
explicit boundaries. The broader compatibility report records current tests
and public PenguPlay/CNCVerse observations.

## Validation

The subsequent account/service extension passed 173 targeted unit tests and
three Android TV runtime tests. See `STREMIO_COMPATIBILITY.md` for the final
APK, real public endpoint observations, archive seek fix and validation limits.


All 180 targeted domain, data/backup, Stremio feature and app navigation/launcher
unit tests passed, together with two Android TV instrumentation checks. See
`STREMIO_COMPATIBILITY.md` for counts and the final APK fingerprint.

The isolated API 36 TV emulator verified Home without an IPTV provider,
completed history, Episode 2 Up Next after Episode 1, a newly released episode
count, tomorrow’s Episode 3 in the calendar, and the per-series tracking toggle.
Dismiss preserved the original 15,000 ms / 60,000 ms progress; Start Over reset
it to zero and started playback; Resume returned through the native resume
prompt and continued past the saved position. Media-session samples remained
PLAYING with error=null.

Season watched actions created watched records for Episodes 1 and 2 while
excluding the upcoming Episode 3. Unwatch reset progress. Newly created unplayed
unwatched markers use a zero watch timestamp, so they cannot displace played
episodes or appear as fabricated Recently Watched entries.

A real database upgrade from schema 84 to 91 preserved a controlled history row
at 12,000 ms / 60,000 ms and supplied dismissed_at_ms=0. Android instrumentation
verified the platform Watch Next title, saved position/duration, provider-scoped
identity and exact replay intent. It also exercises offset release timestamps
against the time library bundled in the APK: its Instant parser rejects offset
suffixes, so release parsing falls back to OffsetDateTime. Autoplay uses the
same parser and excludes malformed or future release dates.

## Official sources

- [Library sorting and Continue Watching predicates](https://github.com/Stremio/stremio-core/blob/development/src/models/library_with_filters.rs)
- [Watched, season and library actions](https://github.com/Stremio/stremio-core/blob/development/src/runtime/msg/action.rs)
- [Episode notifications and release behavior](https://blog.stremio.com/how-to-use-episode-notifications-in-stremio/)
- [Cross-device library and Continue Watching](https://github.com/Stremio/stremio-web)
- [Official UI actions and labels](https://github.com/Stremio/stremio-translations/blob/master/en-US.json)

- [Official authentication and datastore request types](https://github.com/Stremio/stremio-core/blob/development/src/types/api/request.rs)
- [Official API request routing](https://github.com/Stremio/stremio-core/blob/development/src/types/api/fetch_api.rs)
