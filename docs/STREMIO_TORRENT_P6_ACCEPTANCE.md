# Stremio torrent playback — P6 acceptance

The companion implementation and debug APK are ready for manual testing. This
pass belongs to the user; no real torrent playback has been performed by the
build workflow. Cinemeta is catalog/metadata only, so test with an installed
Stremio addon that actually returns torrent streams.

The host app was rebuilt from the current Stremio worktree as
`1.0.19-debug`. If your installed host predates this handoff, update it from:

`D:\antigravity-scratch\iptv-player\.worktrees\stremio-addons\app\build\outputs\apk\debug\app-debug.apk`

Then install the companion from StreamVault → Extensions → Plugins → Install
plugin APK, using:

`D:\antigravity-scratch\streamvault-torrent-plugin\app\build\outputs\apk\debug\app-debug.apk`

The companion is `0.1.0-debug`; its SHA-256 is
`F3908E11E1DECB38F2589AEE8F33F164E7722FEDBFAC488F83D512B627588C06`.

Enable **StreamVault Torrent** in Plugins. Open its configuration Activity to
confirm the service is ready. Keep the host app and companion APK versions noted
when reporting results.

## Playback

- [ ] Open a movie with a torrent stream, select it, and confirm playback starts
  without exposing the magnet URI to the media player.
- [ ] Seek forward and back. Confirm playback continues and the selected title
  remains usable.
- [ ] Stop playback, reopen the same torrent, and confirm it can start again.
- [ ] Open the plugin configuration while playing and confirm status shows the
  active file, peers, and transfer rates.
- [ ] Stop playback and confirm the session disappears from status shortly after
  the host sends its stop message.
- [ ] Use **Stop all torrent sessions** and confirm playback stops and status
  returns to ready.
- [ ] Disable the plugin and select a torrent stream. Confirm StreamVault gives
  a clear disabled or missing-plugin message and does not start the magnet in
  the media player. Re-enable the plugin afterward if desired.

## Configuration and cleanup

- [ ] Change the buffer target and download/upload caps; confirm the displayed
  values persist after leaving and reopening the configuration Activity.
- [ ] If the device has removable app-private storage, choose it and confirm a
  new session can use it. Otherwise verify the default private app storage.
- [ ] Clear downloaded torrent data while idle and confirm the action succeeds.
- [ ] Confirm the stream server remains local to the device and is not reachable
  from another device on the network.

If a multi-file torrent is available, also verify a stream with `fileIdx` selects
that file and a stream without an index chooses the largest supported video.
Record addon name, title, device/AVD, first-frame result, seek result, stop/replay
result, and any error shown. Redact magnet hashes, tracker URLs, and account or
authorization data from screenshots and logs.
