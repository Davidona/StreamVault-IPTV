# Trakt setup and validation

Settings → Trakt supports browser sign-in, profile refresh, disconnect, VOD
scrobbling, and manual two-way history sync. Movie/show detail pages support Trakt
watchlist actions and 1–10 user ratings.
The client ID and HTTPS callback configuration below apply to all variants.

## Build configuration

Register one StreamVault application at https://developer.trakt.tv/apps. Set the
public Client ID and an HTTPS callback URI owned by the project using either:

```properties
# local.properties (gitignored)
trakt.client.id=YOUR_PUBLIC_CLIENT_ID
trakt.redirect.uri=https://YOUR_DOMAIN/oauth/trakt/callback
```

Or environment variables `TRAKT_CLIENT_ID` and `TRAKT_REDIRECT_URI`, which take
precedence. These apply to all variants. CI beta/release workflows read repository
secret `TRAKT_CLIENT_ID` and repository variable `TRAKT_REDIRECT_URI`. The Client ID
is public configuration. Do not configure a client secret.

Unconfigured builds show a disabled Connect button. No production domain or Client
ID is invented. The callback must match the registered URI exactly. The manifest
host and path are generated from the same build value as the OAuth requests.
Use a normal HTTPS URI without a port, query, fragment, or embedded user info.

## Verified App Link deployment

Host `https://YOUR_DOMAIN/.well-known/assetlinks.json` over HTTPS, without redirects:

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "com.streamvault.app",
      "sha256_cert_fingerprints": ["YOUR_RELEASE_SIGNING_CERTIFICATE_SHA256"]
    }
  }
]
```

For development/beta testing, add separate entries for `com.streamvault.app.debug`
and `com.streamvault.app.beta` with the corresponding signing certificates. Publish
only variants/certificates you control. Keep only the intended app variant installed
while checking callback routing. Obtain fingerprints with Gradle `:app:signingReport`.

On Android 12+ use `adb shell pm verify-app-links --re-verify PACKAGE`, then
`adb shell pm get-app-links PACKAGE` to confirm the domain is verified. On older
Android versions, inspect domain verification with `adb shell dumpsys package PACKAGE`.
The manifest alone does not establish domain ownership; deployment is required.

### GitHub Website field versus callback

`https://github.com/Davidona/StreamVault-IPTV` is a suitable project Website value
in the Trakt registration form. It is not an Android OAuth callback: StreamVault
cannot publish domain verification files on `github.com`.

A GitHub Pages user site is deployed from the repository `davidona.github.io`. It
serves `https://davidona.github.io/oauth/trakt/callback/index.html`, with verification
at `https://davidona.github.io/.well-known/assetlinks.json`. Both addresses returned
HTTP 200 without redirects on October 11, 2026. A project Pages site under `/StreamVault-IPTV/` does
not provide control of the domain-root `/.well-known/` path required by Android.
Include `.nojekyll` when serving the static verification directory from a branch.

The complete static hosting package is prepared in `docs/trakt-pages/`, including
the verified release signing certificate and this machine's debug certificate.
See [TRAKT_PAGES_DEPLOYMENT.md](TRAKT_PAGES_DEPLOYMENT.md) for deployment status.
The explicit `index.html` callback avoids a directory-slash redirect on static hosts.

References: [GitHub Pages setup](https://docs.github.com/en/pages/getting-started-with-github-pages/creating-a-github-pages-site)
and [Android website associations](https://developer.android.com/training/app-links/configure-assetlinks).

## Token and failure behavior

Access/refresh tokens and pending PKCE state are encrypted with the existing
CredentialCrypto implementation and saved as one AtomicFile record in no-backup
storage. Neither tokens nor callback URLs are logged. The existing crypto layer
uses Android Keystore and has an app-private software fallback on broken TV devices.

Login transactions expire after ten minutes and are consumed once, including state
mismatch, duplicate query parameters, denial, or a bad callback path. Valid pending
transactions survive process death while the browser is open.

A mutex serializes account operations and refreshes. Both tokens are replaced
together. Before a refresh, the old session is cleared durably so a crash, network
timeout, or persistence failure cannot reuse a possibly consumed single-use token.
Such failures require reconnecting. This deliberately favors credential safety over
silently retrying an ambiguous refresh. Disconnect clears local tokens and pending
sign-in; it does not revoke the application grant on Trakt's website.
An explicit 429 rejection preserves the old session for retry after cooldown because
the server issued no replacement tokens.

The dedicated HTTP client has no body logger/cache, redirects, or transport retries.
Writes are spaced at least one second apart. A 429 establishes a cooldown from
Retry-After (seconds or HTTP date), or exponential backoff with jitter. Further
requests fail locally during that cooldown; the client does not loop or queue events.

## End-to-end acceptance still required

### Playback tracking implementation (October 12, 2026)

Identified movies and episodes are wired to start, pause/resume, completion, content
switch, and cleanup reporting. Episodes resolve a show TMDB ID and exact numbers to
a Trakt episode ID before posting. Live TV, catch-up, recordings, trailers, unknown
external URLs, cast, and external-player tracking are excluded. Mismatched local
episode ownership is skipped rather than guessed. Tracking sends only for a connected
account, shares token rotation with profile requests, and never retries ambiguous
writes. No real watched-history writes were performed during automated validation.

The current Trakt contract was checked against its official source:
- [Scrobble endpoints](https://github.com/trakt/trakt-api/blob/master/projects/api/src/contracts/scrobble/index.ts)
- [Episode scrobble IDs](https://github.com/trakt/trakt-api/blob/master/projects/api/src/contracts/scrobble/schema/request/episodeScrobbleRequestSchema.ts)
- [External ID lookup](https://github.com/trakt/trakt-api/blob/master/projects/api/src/contracts/search/index.ts)

Real acceptance still needs an identified movie and episode: verify start on Trakt,
pause/resume, completion, switching titles, and disconnect during playback. This is
separate from the successful real account sign-in and token-rotation checks below.

`TraktPlaybackIntegrationTest` covers the production coordinator → account → HTTP
client → encrypted-store path with a synthetic account and local MockWebServer.
It verifies authenticated movie and resolved-episode requests through pause/resume,
switching, and completion. It passed alongside the debug build. Failed/skipped starts
also cannot submit a later stop and accidentally create watched history. These local
checks do not establish real-device VOD playback or backend history acceptance.

The existing StreamVault app registration and local configuration now match the
deployed callback. Android verifies the domain for the installed debug APK. Real
login and consent completed in Firefox 157.0.1 on the isolated Codex_Trakt AVD
(emulator-5558). The callback returned to StreamVault, which displayed Connected
and the account username. Profile refresh completed without an error. Force-stop
and relaunch restored Connected and the same username (old PID 2256, new PID 3115).
No tokens or authorization codes were collected in the validation evidence.

Firefox's login page is difficult to navigate with a TV D-pad. Codex_Trakt uses
`hw.screen=multi-touch` so the host mouse can provide touch input for browser login.
This is an emulator configuration change, not a change to StreamVault's TV input.

The original Television_4K_2 device has database version 81; this worktree uses 78.
Its newer original-checkout APK was restored without clearing data. Use Codex_Trakt
for these checks and reconcile the newer app/database changes before release.
On a device with a real browser:

1. **Passed:** Connect from Settings → Trakt and confirm browser consent returns to StreamVault.
2. **Passed:** Confirm username and connected state, including after app process restart.
3. Deny/cancel consent and confirm the account stays disconnected without an error.
4. Restart the app process while the browser is open and complete the same login.
5. Disconnect and confirm a stale callback cannot reconnect the account.
6. **Passed:** Rotate a real token pair, persist it encrypted, and reload the same
   profile without another refresh. The opt-in device test passed on Codex_Trakt;
   output is in `build/trakt-live-refresh-validation.log`.

The real-device Disconnect action also displayed Disconnected. A new login was
started and the app process stopped while Firefox was open; completing that login
is pending. Browser denial/cancel and stale-callback end-to-end checks remain pending.

`TraktAccountRecoveryInstrumentationTest` passed on Codex_Trakt with production
Android encrypted storage in a separate test directory and synthetic OAuth API
responses. It verifies pending-login recreation, denial, cancellation, disconnect,
and late callback rejection without changing the real account. Output is in
`build/trakt-device-recovery-validation.log`. This does not replace browser handoff
acceptance. The app and Android test APK both built successfully.

`TraktLiveAccountInstrumentationTest` is skipped unless `live_trakt=true` is
explicitly supplied. It uses the isolated device's existing signed-in session and
rotates its real tokens; run it only with the app stopped. A test clock expires the
old pair in memory, and the production service performs the refresh and persistence.
It does not log or export token values. Run the specific test with:

```powershell
adb -s emulator-5558 shell am instrument -w -e class com.streamvault.app.trakt.TraktLiveAccountInstrumentationTest -e live_trakt true com.streamvault.app.debug.test/androidx.test.runner.AndroidJUnitRunner
```

Use the installed Firefox browser on Codex_Trakt for these checks. A
package that only resolves web intents to Android's framework browser stub cannot
complete consent. If there is no handler at all, Settings shows an install-browser
message and clears the pending login so Connect can be tried again.

Unit tests cover PKCE, callback rejection, single-use refresh concurrency/failure,
request headers/payloads, rate-limit cooldown, and encrypted atomic persistence.
Local integration tests also combine the production HTTP client, CredentialCrypto,
and disk store against MockWebServer: complete login after service/store recreation,
reload the username, rotate and persist the token pair, disconnect, and reject late
callbacks after cancellation. Robolectric uses the crypto layer's software fallback;
this does not verify a device's hardware Keystore or browser/App Link handoff.
They do not establish real Trakt sign-in. Domain verification was checked separately
with Android's package manager.

Official protocol references: https://developer.trakt.tv/docs/pkce and
https://developer.trakt.tv/docs/authentication-oauth.
