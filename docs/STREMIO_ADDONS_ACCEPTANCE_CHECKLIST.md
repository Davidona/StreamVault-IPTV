# Stremio-compatible Add-ons acceptance checklist

Use this checklist for the Android TV UI, D-pad, and normal-use pass after the
direct/debrid and P6 companion implementations are handed over. Run it on the
dedicated `Codex_StremioProvider_Validation_20261013` AVD or another clean profile. The
public Cinemeta add-on is catalog/metadata only; stream and subtitle checks need
separately installed add-ons that provide those resources. Use a legal direct
or debrid HTTP(S) stream source for the direct-playback checks. Torrent/magnet
acceptance has its own checklist in `STREMIO_TORRENT_P6_ACCEPTANCE.md`.
## Navigation and install

- [ ] Extensions keeps the same heading on both tabs. Compact tabs distinguish
  the active section from D-pad focus and do not enlarge when focused.
- [ ] Plugin installation explicitly says APK or APK URL; Stremio installation
  explicitly asks for a manifest URL. Each section explains what it provides.
- [ ] With no add-ons installed, the Stremio-compatible Add-ons page shows an onboarding message and a
  visible Add Stremio-compatible add-on action without requiring navigation to Manage.

- [ ] The top navigation has one Extensions item; its focused tabs switch between
  Plugins and Stremio-compatible Add-ons and Back returns to the prior content.
- [ ] Install Cinemeta with `https://v3-cinemeta.strem.io/manifest.json`, then
  confirm the manager displays the add-on and its catalogs.
- [ ] Add a stream-capable add-on from its manifest URL. Confirm duplicate,
  malformed, unavailable, and configuration-required URLs give a useful result.
- [ ] Confirm Manage displays only the add-on server origin, not URL userinfo,
  path, query, or fragment. Disable/enable, reorder, and remove the add-on.
- [ ] Reopen the app and confirm retained add-ons and manager settings persist.

## Browse and choose a stream

- [ ] Browse an enabled catalog, move focus across cards, open an item, and use
  Back to return with focus and scroll position intact.
- [ ] Try catalog search and genre filters where offered. Load additional items
  and confirm paging ends without duplicated or endlessly repeated cards.
- [ ] Open an item whose metadata comes from a different add-on than its catalog.
  Confirm the detail page keeps the catalog provenance and shows stream rows
  from enabled stream add-ons.
- [ ] Test no-stream, unsupported-stream, partial-add-on-failure, retry, and
  parental-control states. Confirm one failing add-on does not hide other results.

## Playback and history

- [ ] Play a direct MP4 stream and an HLS stream. Confirm playback starts, seek
  works, and Back returns to a usable detail or browse screen.
- [ ] If available, try a stream requiring request headers. Confirm playback
  works internally and the credentials do not appear in UI or logs.
- [ ] Open the subtitle picker with inline and add-on-provided subtitles. Select
  a track, then switch episodes and confirm the old episode's results do not
  remain selected or appear in the new episode.
- [ ] Stop and reopen an item from Stremio progress/history. Confirm the app
  resolves a fresh stream and resumes near the saved position.
- [ ] Without the Torrent plugin enabled, confirm magnet rows show the clear
  missing-plugin or disabled-plugin message. With it enabled, use
  `STREMIO_TORRENT_P6_ACCEPTANCE.md` for torrent playback, seek, and teardown.
  Cast must reject add-on-origin-scoped headers rather than send them to a
  receiver. Stremio external-player handoff is not part of v1.

## Privacy and visibility — revised during user acceptance (2026-10-14)

Implemented in the UI/UX cleanup; user acceptance is pending. These criteria replace
the previous blanket Stremio parental-control gate.

- [ ] Stremio-compatible Add-ons are open by default, including browsing, installation,
  and playback, independently of the global parental-control level.
- [ ] Settings → Privacy offers "Hide Stremio-compatible add-ons", off by default.
- [ ] Enabling the option hides Stremio-compatible Add-ons; disabling it restores access.
- [ ] The visibility choice persists after restarting the app.
- [ ] Existing global parental-control settings continue to work for their
  other content without being changed to access Stremio-compatible Add-ons.

Record the device/profile, add-on names and capabilities, steps that failed, and
the screen/remote action where focus or expected behavior diverged. Redact
configured URLs, tokens, cookies, and authorization headers from any notes or
screenshots.
