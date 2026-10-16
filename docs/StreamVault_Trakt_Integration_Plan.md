# StreamVault — Trakt Integration Plan

> **Status:** Implementation plan based on the current Trakt developer documentation as of **October 11, 2026**.
> **Target:** StreamVault Android / self-hosted open-source media player.
> **Primary use case:** Let users connect their own Trakt account, automatically track playback, and optionally synchronize Trakt data such as history and watchlists.

## Implementation progress — October 12, 2026

Phase 1 account code is implemented in the Trakt integration worktree: PKCE/state,
dedicated HTTPS callback activity, encrypted atomic persistence, serialized rotating
refresh tokens, profile/status, connect/cancel/disconnect, API headers, and rate-limit
cooldown. Build and deployment instructions are in [TRAKT_SETUP.md](TRAKT_SETUP.md).

The supplied public Client ID and HTTPS callback URL are configured locally. The
existing StreamVault Trakt registration now uses the exact callback below. The
callback site is deployed and Android verifies its domain for the installed debug
APK. Real browser sign-in and callback delivery are **validated**. Firefox
157.0.1 from Mozilla's official archive is installed on the Codex_Trakt Android 16
AVD. The user completed login/consent, and StreamVault displayed
Connected with the account username. Profile refresh completed without an error.
After force-stopping the app (PID 2256) and relaunching (PID 3115), Connected and the
same username returned. No tokens or authorization codes were collected in evidence.

Phase 2's domain identity foundation has started: `TraktMediaIdentity` and its
resolver accept attached positive movie/show TMDB IDs, with exact episode numbers
and matching local series/provider ownership. Missing IDs, unknown episode numbers,
and inconsistent ownership return no identity; no title guessing is performed.
Season-zero specials are supported. Six domain tests and the debug build passed;
output is in `build/trakt-identity-validation.log`.

Phase 2 now includes exact episode resolution (show TMDB lookup followed by season/
episode lookup), a bounded successful-ID cache, start/pause/stop payloads, serialized
authenticated requests, and player wiring. The player reports identified VOD only;
live TV, catch-up, recordings, trailers, and unknown external media are excluded.
Duplicate ticks and buffering produce no extra requests. Session switches preserve
the old item's last observed progress, and stale starts/pauses are suppressed. A
bounded transition queue avoids accumulating position ticks while network calls run.
Completion and cleanup submit a final stop; transient engine recovery preserves the
session. Tracking failures do not crash playback or trigger ambiguous write retries.
An explicit 401 can refresh once and retry; duplicate-stop 409 responses are terminal.
Opening playback loads the saved account without requiring a visit to Trakt settings.

Domain, transport/authentication, and player-coordinator tests cover these behaviors.
Build, playback boundary verification, and app/data lint passed against existing
baselines. Output is in `build/trakt-scrobble-final-validation.log`; the final
exception-isolation follow-up is in `build/trakt-scrobble-safety-validation.log`.
The earlier targeted suites contained 11 domain, 25 data, and 6 player tests, all passing.
Real-account episode start/pause reporting was exercised on the signed-in
Codex_Trakt emulator for the exact-matched S.W.A.T. Exiles S1E1. Trakt returned
successful HTTP responses for start and pause, and the player showed “Tracking with
Trakt” then “Trakt tracking paused”; playback reached a decoded first frame. The
test paused at about 2:11 of 44:35 (about 5%), so it did not test watched-history
completion. Trakt's scrobble API saves a pause as playback progress; a stop above
80% is needed to create a watched-history entry. StreamVault does not yet read the
saved `/sync/playback` item back, so its badge confirms request acceptance, not the
persisted server-side progress row. No watchlist, favorites, personal-list, or
history-add request was part of this test. A non-focusable player badge now fades
in for three seconds when tracking status changes and stays available while player
controls are open. It also explains when the account needs connecting, content
cannot be tracked, no exact Trakt match exists, or a request fails. The duration-
late start case and status transitions have player unit coverage. Real movie
scrobbling, Cast/external-player tracking, and variant episode ownership mapping
remain unvalidated or unsupported.
Phase 3's read/match foundation is described below.
Phase 4 watchlist support is implemented; real-account UI acceptance remains open.
Phase 5 now includes movie/show Favorites write-through, movie/show user ratings,
personal custom-list management, and public discovery categories for trending
and popular movies/shows. Settings can independently hide each Movies/Series
Trending/Popular category. Settings also show the connected account's expanded text
profile and provide independent playback-scrobbling and history-sync controls.

Phase 5 custom lists (October 16): Settings loads the connected account's personal
lists and lets the user create private lists, inspect movie/show/season/episode
items, remove items, reorder entries, reorder lists, and delete a list after an
explicit confirmation. Movie, show, season, and episode detail actions can add or
remove the exact supported item in a selected list. The Trakt Lists dialog also
offers Create new list and creates a private list before returning to the picker.
List metadata and item pages use a five-minute account-scoped cache; item pages are
fetched only when a list is opened and are staged before replacing the cached list.
Incognito mode blocks reads and writes, account changes invalidate the operation,
explicit 401 responses refresh once, and uncertain list writes are not retried.
Episodes and seasons are matched through their parent show's TMDB identity and
resolved to exact Trakt IDs before writes. People entries and reordering remain
unsupported when Trakt does not return stable list-entry IDs. Focused catalog,
settings, and data unit tests pass; debug APK assembly and emulator UI acceptance
remain to be completed. Real-account list mutations remain unvalidated.

Phase 2 validation follow-up (October 12): the coordinator now sends pause/stop only
for an identity whose start succeeded. This prevents skipped intermediate sessions
or failed starts from creating watched history through a later high-progress stop.
A Robolectric integration test drives the production player coordinator, account
service, HTTP client, and encrypted disk store through movie start/pause/resume,
movie-to-episode switching, exact episode resolution, and completion. It uses a
synthetic account and MockWebServer, never real Trakt writes. It passed with the
debug build; output is in `build/trakt-playback-integration-validation.log`.
The failed/skipped-start regressions are covered by the player tests; follow-up
test/boundary/lint output is in `build/trakt-playback-regression-validation.log`.
At the time of this synthetic follow-up, the isolated emulator was closed and real
VOD scrobbling acceptance had not yet been run; the later signed-in episode check
is recorded above.

Phase 3 read/match foundation (October 12): `TraktHistorySource` fetches one
authenticated page of mixed movie/episode history at a time (100 records per
request), sharing the same token lock and rotating refresh handling as playback.
Pagination uses Trakt's response header, with raw row-count fallback. Invalid dates,
missing IDs, fractional identifiers, and unknown types are skipped with a count;
IDs retain 64-bit precision. `TraktHistoryIndex` deduplicates repeated history IDs
and refuses conflicting media identities. Its merge policy produces explicit import
candidates, preserving newer local rewatch progress, manual completion, and larger
local play counts. A completion import advances older progress to its known duration
so existing catalog queries reflect the watched state. Remote absence never removes
local history.

Phase 3 now has a manual Settings → Trakt → Sync Now action. It stages up to 50
pages before writing; a failed read or exceeded limit imports nothing. Cached movie/
series rows are matched in bounded TMDB batches, and episodes require exact numbers
and provider/series ownership. Import candidates are rechecked under the playback
repository lock and inside a Room transaction before history and catalog projections
are updated together. Repeated imports do not increment plays or rewrite the watch
time. Incognito mode prevents imports, and account changes abort the run. The UI
reports imported, uploaded, unchanged, unmatched, and skipped counts, or partial
counts after a failure. Sync Now also uploads up to 100 newest completed local events
per run, resolved only by exact TMDB and episode ownership. It compares all staged
history first, uses stable UTC minute timestamps, and stops on a failed write; the
next manual run reconciles again before posting. Incognito mode blocks both sync
directions. The local catalog stores each item's latest watch time, so earlier
rewatches cannot be reconstructed as separate events. There is no automatic polling.
Real-account sync/device UI acceptance remains open. Test/build/lint output is in
`build/trakt-history-import-final-validation.log`, including Room persistence and
unwatched-count checks.
See [TRAKT_HISTORY_SYNC.md](TRAKT_HISTORY_SYNC.md).

Outbound synchronization now has focused MockWebServer and service-level coverage
for request payloads, exact episode lookup, UTC minute timestamps, accepted and
unmatched response counts, same-minute reconciliation, account ownership, one-time
401 refresh, ambiguous-write retry suppression, incognito blocking, and the 100-event
per-run cap. The full Trakt domain and data test packages pass (18 domain and 52 data
tests); all use local mocks, with no real-account history writes. Real-account
Sync Now also completed on the isolated Codex_Trakt emulator using the saved connected
account. The UI reported 0 imported, 0 uploaded, 0 unchanged, 0 unmatched, and 0
skipped. That emulator has no provider/catalog, so this confirms the authenticated
empty-history read and result UI without changing Trakt history; item matching,
non-empty import, and outbound upload still need acceptance against a real library.
Trakt's current API
normalizes `watched_at` to the minute and its API team says equal item/timestamp pairs
deduplicate, although the current contract still contains an older warning. StreamVault
preflights the complete staged history and canonicalizes timestamps before uploads.

Phase 4 watchlist implementation (October 12): authenticated movie/show list reads
and explicit add/remove actions share the account service's rotating-token lock.
Movie and show detail pages show Trakt bookmark actions only for positive TMDB IDs;
titles are never used as a fallback. Reads are staged before the account-scoped
five-minute cache is replaced, with a 50-page (100 items per page) bound per media
type. Incomplete reads do not expose a partial list, and Incognito mode blocks reads
and writes. Explicit 401 responses refresh once; uncertain write failures are not
retried. The Trakt domain/data/catalog test suites pass (18/65/46 tests), and
`:app:assembleDebug` succeeds. No real-account watchlist item was changed. Display,
add/remove, failure feedback, and reconnect behavior still need device acceptance
with a configured movie/show catalog.
The five watchlist action/status strings are translated across the catalog's supported locales; :feature:catalog:lintDebug and :data:lintDebug pass (18 existing data lint baseline findings remain filtered).

Local validation passed: 16 Trakt tests, 17 related Settings tests, app Kotlin
compilation, settings dependency boundary verification, and app/data debug lint
against the existing baselines. The configured debug APK built and installed, and
`pm get-app-links` reports `davidona.github.io: verified`. At that checkpoint,
emulator OAuth login and session persistence passed; playback had not yet been tested.
Detailed local command output is in
`build/trakt-final-validation.log` and `build/trakt-configured-build.log`.

Phase 1 follow-up adds an actionable missing-browser message with pending-login
cancellation and retry, plus HTTP/encrypted-storage integration coverage for login
after recreation, saved profile/session, token rotation, disconnect, denial and late
callbacks. These local tests pass. Real login, callback, profile, and session
persistence also passed. The opt-in device test rotated a real token pair once,
verified encrypted persistence, and reloaded the same profile without another
refresh. Output is in `build/trakt-live-refresh-validation.log`. Disconnect showed
Disconnected on the real device. Real deny/cancel, process death during pending
login, and stale callbacks remain browser acceptance checks. An isolated Android
device test with production encrypted storage and synthetic OAuth responses passed
pending-login service/store recreation, denial, cancellation, disconnect, and late
callback rejection. It leaves the real account session untouched; this establishes
device storage/service behavior, not end-to-end browser consent. Output is in
`build/trakt-device-recovery-validation.log`.
Follow-up build/test/lint output is in `build/trakt-phase1-followup-validation.log`.

Device validation found and fixed unreadable status text and a search-result focus
target that prevented the Connect button from receiving focus. The corrected screen
is readable, opens with Connect focused, and launches Firefox. The updated APK build,
17 related Settings tests, and app lint passed; output is in
`build/trakt-device-ui-validation.log`.

The Trakt worktree now uses Room schema 81. Codex_Trakt was upgraded in place from
schema 78; its provider and catalog rows were preserved. The separate default
checkout has advanced to schema 82, so reconcile its newer database changes before
merging or release validation. Code remains uncommitted and unpushed in this
worktree; do not push until requested.

A GitHub Pages hosting package is saved in `docs/trakt-pages/` with release,
beta, and debug App Link associations. The release fingerprint was verified against
the existing signed release APK. The intended callback is
`https://davidona.github.io/oauth/trakt/callback/index.html`; both this page and the
domain-root verification JSON return HTTP 200 without redirects. The hosting site
was published before the user's instruction to keep further code local. Integration
code remains uncommitted and unpushed in this worktree; do not push until requested.
See [TRAKT_PAGES_DEPLOYMENT.md](TRAKT_PAGES_DEPLOYMENT.md).

---

## 1. Executive decision

StreamVault should integrate Trakt as **one registered Trakt application** with a single StreamVault **Client ID**.

Users should **not** enter their Trakt username/password into StreamVault and should **not** create their own Trakt developer application.

For Android, StreamVault should use:

**OAuth 2.0 Authorization Code Flow + PKCE (`S256`) + HTTPS Verified App Link**

This is Trakt's current recommended authentication method for mobile apps.

### Architecture

```text
                       Trakt
                         │
                  StreamVault App
                    Client ID
                         │
        ┌────────────────┼────────────────┐
        │                │                │
     User A           User B           User C
       │                │                │
     OAuth            OAuth            OAuth
       │                │                │
 Access/Refresh    Access/Refresh    Access/Refresh
    tokens             tokens             tokens
       │                │                │
 Personal Trakt    Personal Trakt    Personal Trakt
    account            account            account
```

The **Client ID identifies StreamVault**.

Each user's **OAuth access token identifies and authorizes that individual user**.

---

# 2. Important 2026 Trakt changes

Trakt's authentication guidance has changed significantly.

## PKCE is now the recommended sign-in mechanism

Trakt explicitly recommends PKCE for the Authorization Code Flow.

A mobile application no longer needs to ship a Trakt `client_secret`.

Trakt states that the Client Secret is **deprecated for user sign-in** and should only be used in situations where it can remain on a trusted server.

For StreamVault:

```text
Use:
client_id
OAuth Authorization Code Flow
PKCE S256
HTTPS redirect URI
Android Verified App Link

Do NOT ship:
client_secret
```

This is especially suitable for StreamVault because the project is open source and distributed as an Android application.

---

## Client ID is public

Trakt explicitly describes the `client_id` as public.

It appears in OAuth authorization requests and therefore cannot be treated as a secret.

It is fine to keep the Client ID in a GitHub Actions secret for repository/configuration hygiene, but this does **not** provide real secrecy.

For example:

```text
GitHub Actions Secret
TRAKT_CLIENT_ID=...

        ↓ build

BuildConfig.TRAKT_CLIENT_ID

        ↓

StreamVault APK
```

Anyone sufficiently motivated can obtain the Client ID from the application or OAuth traffic.

That is expected.

### Recommendation

Use a GitHub secret if convenient, but document internally that:

> `TRAKT_CLIENT_ID` is configuration, not a credential that must remain secret.

Never place a Trakt `client_secret` in the APK.

---

# 3. Trakt application registration

Create **one Trakt API application for StreamVault**.

Current Trakt documentation says creating an application requires a **verified GitHub account** connected through the developer panel.

Developer applications are managed here:

https://developer.trakt.tv/apps

Suggested application identity:

```text
Name: StreamVault
Description: Open-source IPTV and media player with optional Trakt account integration.
```

Use StreamVault's real project/site information where Trakt requests it.

Trakt also requires developers to follow its branding requirements and developer announcements.

---

# 4. Self-hosted / open-source status

Trakt's current API Use Policy explicitly lists these as permitted examples:

- media player integrations that scrobble playback into a connected user's account;
- personal tools;
- self-hosted tools that synchronize a user's own media activity;
- applications that manage a user's watch history, ratings, or lists.

The policy also states that an application's popularity, where it runs, or whether it has a backend does not by itself determine whether the integration is permitted.

This makes StreamVault's architecture a direct fit for Trakt's permitted use cases.

## Important restrictions

StreamVault must not:

- scrape the Trakt website;
- mirror or bulk-harvest Trakt's database;
- sell or redistribute Trakt API data as a dataset/feed;
- provide another service access through StreamVault's Trakt credentials;
- create replacement API apps to evade limits or restrictions;
- use Trakt data in an application that promotes copyright infringement or piracy.

StreamVault should continue to present itself as a **media/IPTV player for user-provided sources**, not as a provider of unauthorized media.

---

# 5. User experience

The user-facing flow should be simple.

## Settings

```text
Settings
└── Trakt
    └── Connect Trakt
```

When the user presses **Connect Trakt**:

```text
StreamVault
    │
    │ Generate PKCE verifier + challenge
    ▼
Open Trakt authorization page in browser
    │
    ▼
User signs into Trakt
    │
    ▼
User approves StreamVault
    │
    ▼
Trakt redirects to StreamVault HTTPS callback
    │
    ▼
Android Verified App Link opens StreamVault
    │
    ▼
StreamVault exchanges authorization code + PKCE verifier
    │
    ▼
Access token + refresh token stored securely
```

After connection:

```text
Trakt

✓ Connected

Automatically track playback       ON
Sync watch history                  ON
Sync watchlist                      ON

[ Sync Now ]
[ Disconnect ]
```

If desired, StreamVault can fetch the user's Trakt profile after authentication and display their username.

---

# 6. OAuth protocol

## 6.1 Generate a PKCE verifier

For every sign-in attempt, generate a new random `code_verifier`.

Trakt currently specifies:

- length: **43–128 characters**
- allowed characters:
  - `A-Z`
  - `a-z`
  - `0-9`
  - `-._~`

The verifier exists only for the current authentication attempt.

Do not reuse it.

---

## 6.2 Generate the PKCE challenge

Hash the verifier using SHA-256:

```text
SHA256(code_verifier)
```

Then Base64URL encode the result **without padding**.

```text
code_challenge = BASE64URL_NO_PADDING(SHA256(code_verifier))
```

Trakt currently supports:

```text
code_challenge_method=S256
```

Use `S256`.

---

## 6.3 Open Trakt authorization

Open Trakt's authorization endpoint in the user's browser:

```text
https://auth.trakt.tv/oauth/authorize
```

Include the required OAuth parameters plus:

```text
client_id
redirect_uri
response_type=code
state
code_challenge
code_challenge_method=S256
```

`state` should be a cryptographically random value associated with the pending login attempt.

When the callback returns, StreamVault must verify that the returned `state` matches the original value.

If it does not match, abort the login.

---

# 7. Android redirect handling

Trakt's current PKCE documentation specifically recommends **HTTPS redirect URIs**.

For Android, use a **Verified App Link**.

Example conceptual redirect:

```text
https://streamvault.example.com/oauth/trakt/callback
```

That HTTPS URL is registered with Trakt.

Android is configured so that the verified domain opens StreamVault for that callback.

Do **not** use:

```text
streamvault://callback
localhost
urn:ietf:wg:oauth:2.0:oob
```

Trakt now advises against custom schemes and localhost-style callbacks because another application may be able to claim/intercept them.

### Requirement

The `redirect_uri` is case-sensitive and must match exactly:

1. the URI registered in the Trakt developer application;
2. the URI sent in `/oauth/authorize`;
3. the URI sent during token exchange.

---

# 8. Authorization code exchange

After authorization, Trakt redirects back with:

```text
?code=...
&state=...
```

Verify `state`.

Then exchange the temporary authorization code using:

```text
POST /oauth/token
```

Send the authorization code and the original `code_verifier`.

With PKCE:

```text
DO NOT send client_secret
```

The result includes an OAuth access token and refresh token.

---

# 9. Token lifecycle

## Access token

Current Trakt documentation states that access tokens are valid for:

**7 days**

Store the access token securely on the device.

Use it for authenticated Trakt calls:

```http
Authorization: Bearer <access_token>
```

---

## Refresh token

Use the refresh token to obtain a new access token without forcing the user to log in again.

Important 2026 behavior:

**Refresh tokens are single-use.**

Every successful refresh returns:

```text
new access_token
new refresh_token
```

The refresh token that was just used becomes invalid immediately.

Therefore token persistence must behave atomically:

```text
1. Send old refresh token
2. Receive new access token + new refresh token
3. Replace BOTH stored tokens
4. Never use the old refresh token again
```

### Avoid concurrent refreshes

Do not let several API requests independently refresh an expired token.

Use a synchronization mechanism such as a mutex/single-flight token refresh.

Otherwise:

```text
Request A uses refresh_token X → succeeds → X invalidated
Request B uses refresh_token X → fails
```

---

## Legacy token migration

Trakt notes that refresh tokens issued before its recent authentication migration may no longer be exchangeable.

If token refresh returns something equivalent to:

```json
{
  "error": "invalid_grant",
  "error_description": "session not found"
}
```

StreamVault should:

1. clear the invalid local Trakt session;
2. show that Trakt needs to be reconnected;
3. let the user perform OAuth again.

This should be treated as a one-time migration/re-authentication case rather than endlessly retrying the same refresh token.

---

# 10. API request authentication

Trakt says all applications should send the required Trakt API headers, including the app's API key / Client ID.

Authenticated calls additionally use the user's Bearer token.

Conceptually:

```http
trakt-api-key: <STREAMVAULT_CLIENT_ID>
Authorization: Bearer <USER_ACCESS_TOKEN>
```

Implement the remaining version/content headers according to the current endpoint documentation when building the HTTP client.

Centralize these headers in the Trakt API client rather than manually adding them in individual features.

---

# 11. Public vs authenticated requests

Some Trakt endpoints are public and need only the StreamVault Client ID.

Other endpoints require an authenticated user's OAuth token.

Some public endpoints optionally accept OAuth and return personalized information.

Architecture:

```text
Public Trakt request
    ↓
Client ID
    ↓
Trakt


User-specific request
    ↓
Client ID
+ Bearer access token
    ↓
Trakt
```

If a signed-in user's token can validly be supplied to an endpoint, doing so also affects which rate-limit bucket is used.

---

# 12. Current rate limits

As of the current Trakt documentation:

| Scope | Operations | Limit |
|---|---|---:|
| Authenticated user | GET | 500 requests / 5 minutes |
| Authenticated user | POST / PUT / DELETE | 1 request / second |
| Unauthenticated | GET | 500 requests / 5 minutes |

Unauthenticated GET requests are currently scoped by:

```text
Client ID + public IP
```

So devices behind the same public IP can share the unauthenticated allowance.

Authenticated GET requests use the individual user's authenticated bucket.

---

## 429 handling

On:

```http
429 Too Many Requests
```

StreamVault should:

1. inspect `Retry-After`;
2. wait at least that duration when present;
3. otherwise use bounded exponential backoff with jitter;
4. avoid immediate retry loops;
5. cache and de-duplicate requests where appropriate.

Trakt may also return an `X-Ratelimit` header containing diagnostic information.

Log:

```text
HTTP status
endpoint
HTTP method
authenticated vs unauthenticated
Retry-After
X-Ratelimit
```

Do not log OAuth tokens.

---

# 13. What "scrobbling" means

Trakt defines **scrobbling** as automatically tracking playback by reporting when a movie or episode:

```text
starts
pauses
stops
```

This is the correct Trakt feature for StreamVault's playback integration.

Conceptually:

```text
Player starts movie
        ↓
Trakt scrobble: start

Player pauses
        ↓
Trakt scrobble: pause

Player resumes
        ↓
Trakt scrobble: start/resume as required by endpoint semantics

Player stops / completes
        ↓
Trakt scrobble: stop
```

Implementation should follow the exact current scrobble endpoint payload requirements rather than inventing a custom watched-state protocol.

---

# 14. Media identification

Scrobbling is only reliable when StreamVault knows exactly what title the user is playing.

Do not rely only on the filename/title string if stronger metadata exists.

Prefer IDs already attached to StreamVault content.

Useful identifiers in Trakt media objects include IDs such as:

```text
Trakt
TMDB
IMDB
TVDB (where applicable)
```

For a TV episode, the local media model should ideally identify:

```text
Show ID
Season
Episode
```

Example:

```text
StreamVault item
TMDB show ID: 1396
Season: 2
Episode: 3

        ↓

Resolve/match Trakt episode
        ↓

Scrobble exact episode
```

If StreamVault cannot confidently identify the movie/episode, do not silently scrobble the wrong title.

A future fallback may allow the user to manually match an unidentified item.

---

# 15. Recommended StreamVault Trakt features

## Phase 1 — Authentication foundation

Implement:

- Trakt Client ID configuration;
- OAuth Authorization Code Flow;
- PKCE S256;
- random OAuth `state`;
- HTTPS redirect;
- Android Verified App Link;
- access-token storage;
- refresh-token storage;
- single-use refresh rotation;
- disconnect/revoke-local-session behavior;
- user profile/status display.

Deliverable:

```text
Settings → Trakt → Connect
```

works reliably.

---

## Phase 2 — Scrobbling

Implement playback tracking for:

- movie start;
- episode start;
- pause;
- resume;
- stop;
- completion.

Build scrobbling around the central media identity model, not directly around raw IPTV/file names.

Include protection against:

- duplicate start events;
- pause-event spam;
- retry loops;
- scrobbling the wrong episode;
- stale events after the player changes media.

---

## Phase 3 — Watch history synchronization

Add:

- fetch user's Trakt history;
- mark local items as watched based on Trakt state;
- send explicitly watched local items to Trakt where appropriate;
- "Sync Now" action.

Define conflict behavior before implementing two-way sync.

Recommended principle:

```text
Do not erase either side automatically unless the user explicitly performs
an action whose meaning is unambiguous.
```

---

## Phase 4 — Watchlist

**Implementation status:** code complete; real-account device acceptance remains open.

Add optional support for:

- reading the user's Trakt watchlist;
- showing whether a StreamVault movie/show is on the watchlist;
- add/remove watchlist actions.

The home dashboard also offers optional **Trakt Watchlist Movies** and **Trakt
Watchlist Series** shelves. They preserve Trakt's list order, resolve only against
the active provider by TMDB ID, and omit titles without a playable catalog match.
The shelves are separate from StreamVault's local Favorites. The Movies and
Series sections also expose a Trakt Watchlist action in shelf mode and a
watchlist category in the category picker; Classic mode shows it as another
clickable category. These views use targeted provider/TMDB lookups, preserve
Trakt order, and explain when no watchlisted titles match the current provider
or filters.

Keep this separate from playback history.

---

## Phase 5 — Additional Trakt functionality

**Implementation status:** the first Phase 5 slice is implemented in code: the
existing movie/show heart continues to save to StreamVault Favorites and, when
Trakt is connected, adds or removes that TMDB-matched movie/show in Trakt
Favorites. A failed remote write leaves the local change intact and reports the
sync failure. This applies to detail, search, and saved-item heart actions;
group-only changes and live-channel favorites remain local. Items without a
TMDB ID remain local-only. Existing local favorites are not bulk-uploaded, and
an explicit provider-scoped import action adds existing Trakt Favorites when an
exact TMDB match is present in the active provider's catalog.

The ratings slice is also implemented. Movie and show detail pages read the
connected account's ratings from Trakt, stage each read before replacing its
account-scoped cache, and keep the cache for five minutes. Reads are bounded to
50 pages of 100 items per media type; missing or invalid TMDB IDs and malformed
ratings are skipped. The current Trakt rating appears in the rating dialog, where
the user can submit a value from 1 to 10, and on the detail page beside the title
metadata when a rating exists. Writes use the exact TMDB ID, share the
account service's serialized token refresh, retry once only after an explicit 401,
and are never retried after an ambiguous transport failure. Incognito mode blocks
rating reads and writes. Ratings do not replace provider ratings or external rating
metadata, and episodes are not included. Local unit/API tests cover read pagination,
staging, cache behavior, write payloads, account changes, incognito, and detail-page
identity mapping. Real-account rating UI acceptance remains open; no live rating was
submitted during implementation.

Discovery/trending (October 16): Movies and Series expose **Trending on Trakt** and
**Popular on Trakt** in modern shelf browsing and classic category browsing. These
public endpoints do not require a Trakt sign-in. StreamVault caches each first page
for 30 minutes, preserves Trakt order, and shows only exact TMDB matches from the
active provider, so discovery never imports a remote catalog into the app. Failed
refreshes retain the last good page and surface an error state. API parsing, cache,
and catalog-order projection have focused tests. On October 16, debug version
1.0.19 was installed on the Codex_Trakt Android 16 emulator; Trending on Trakt
and Popular on Trakt each loaded provider-matched results in both Movies and
Series. The Classic-mode category rail still needs device acceptance.

Discovery visibility settings (October 16): Settings → Movies & Series → Library
browsing now has four independent switches for Trending and Popular in Movies and
Series. All four default to visible and persist with the rest of user preferences.
Turning one off removes it from the modern browse actions, category picker, and
Classic category rail; if that category was selected, the view returns to the full
library. The preferences are included in portable backup classification. The
switches are visible on the Codex_Trakt emulator. Hiding and restoring Trending
movies was verified in modern browsing: the action disappears and returns while
Popular remains. Classic-mode rail acceptance remains open.

Emulator compatibility (October 16): The Trakt AVD's database was at schema 78,
with an older schema-80 layout also present on a second development AVD. A
data-preserving 80→81 repair migration aligns the episode index and Xtream
onboarding receipt columns. The historical Room migration suite passed on
Codex_Trakt, and the app opened after upgrade with the existing provider and catalog
rows preserved. No app data was cleared.

Phase 5 account controls and import (October 16): Settings exposes persistent
switches for playback scrobbling and manual history import/upload. Disabling
scrobbling pauses an already active Trakt playback session so it cannot create a
watched-history stop, hides the player tracking status, and suppresses queued new
starts. Disabling history sync prevents the manual sync action from running. An
explicit **Import Trakt favorites to this provider** action reads Trakt movie/show
favorites in bounded pages, stages the complete read before local writes, and adds
only exact TMDB matches from the active provider to StreamVault Favorites. The
result reports added, already present, unmatched, and skipped titles; it never
uploads existing local favorites. The profile now displays available name,
location, joined date, gender, age, VIP status, and about text, with a refresh
action. It does not hotlink Trakt-hosted avatars. Favorite import, settings,
profile parsing, and scrobbling preference behavior have focused unit coverage;
the debug APK assembles successfully. Version 1.0.19-debug was installed in place
and launched on the Codex_Trakt Android 16 emulator; provider, catalog, and account
data were retained. Real-account import and toggle acceptance remain open.

Still deferred or unvalidated:

- Cast/external-player playback scrobbling and manual matching for unknown media.
- Custom-list people items and real-account acceptance for deletion/reordering and
  season/episode add/remove operations.
- Real-account acceptance for history sync, watchlist, Favorites import, ratings,
  and custom-list operations.

Authentication, matching, scrobbling, history sync, watchlist, personal lists with
movie/show/season/episode support, list deletion and ordering, movie/show Favorites,
movie/show ratings, and public trending/popular
categories, Favorites import, and expanded text profile are implemented;
real-account acceptance remains open for the operations listed above.

---

# 16. Suggested internal module design

```text
Trakt/
├── TraktApiClient
├── TraktAuthService
├── TraktTokenStore
├── TraktTokenRefreshCoordinator
├── TraktScrobbleService
├── TraktSyncService
├── TraktMediaMatcher
└── Models/
```

## TraktApiClient

Responsibilities:

- base URL;
- required headers;
- Client ID;
- Bearer token injection;
- JSON serialization;
- HTTP response handling;
- rate-limit metadata.

Do not place OAuth UI logic here.

---

## TraktAuthService

Responsibilities:

- generate PKCE verifier/challenge;
- generate and validate `state`;
- construct authorization URL;
- process callback;
- exchange authorization code;
- refresh token;
- disconnect.

---

## TraktTokenStore

Responsibilities:

- securely persist:
  - access token;
  - refresh token;
  - token expiry information;
- atomically replace tokens after refresh;
- clear tokens on disconnect/re-authentication requirement.

---

## TraktTokenRefreshCoordinator

Responsibilities:

- ensure only one token refresh occurs at a time;
- let waiting requests reuse the newly refreshed token;
- prevent reuse of an invalidated single-use refresh token.

---

## TraktMediaMatcher

Responsibilities:

Resolve the currently playing StreamVault item to an exact Trakt movie/episode using authoritative media IDs whenever possible.

This module is deliberately independent from the player.

---

## TraktScrobbleService

Responsibilities:

Translate player lifecycle events into Trakt scrobble requests.

It should not be responsible for identifying media.

Input should already be something like:

```text
ResolvedTraktMovie
or
ResolvedTraktEpisode
```

---

# 17. GitHub / build configuration

Recommended:

```text
GitHub repository
    │
    ├── no Trakt client secret
    │
    └── workflow reads TRAKT_CLIENT_ID
                 │
                 ▼
             Android build
                 │
                 ▼
      BuildConfig.TRAKT_CLIENT_ID
```

Keeping the Client ID in GitHub Actions Secrets is acceptable for convenience.

However:

**Do not build security assumptions around hiding the Client ID.**

PKCE is what protects OAuth sign-in when the client itself cannot keep a secret.

---

# 18. Local token security

The following are real user credentials and must be protected:

```text
access_token
refresh_token
```

Do not:

- commit them to Git;
- include them in crash reports;
- write them to normal application logs;
- put them into analytics payloads;
- expose them in UI/debug screens in release builds.

Use Android secure storage appropriate to StreamVault's architecture.

The Client ID does not require this secrecy.

---

# 19. Trakt image handling

If StreamVault later uses images returned directly by Trakt:

Trakt currently requires applications to **cache the images** rather than hotlink them from Trakt's CDN.

Its current image guidance says image data can be included with:

```text
?extended=full
```

and images are returned in WebP form.

Do not add Trakt image support by simply pointing permanent UI image URLs at Trakt's CDN.

If StreamVault already uses TMDB or another media metadata provider for artwork, keeping artwork responsibility with that provider may be cleaner.

---

# 20. Pagination and API efficiency

For paginated Trakt endpoints:

```text
?page=...
&limit=...
```

Trakt recommends explicitly sending pagination parameters when supported.

Do not assume a universal page size.

Use response headers such as:

```text
X-Pagination-Page
X-Pagination-Limit
X-Pagination-Page-Count
X-Pagination-Item-Count
```

Use these values instead of calculating page count from the requested limit.

Cache API data where appropriate and avoid repeatedly requesting data StreamVault already has.

---

# 21. API application lifecycle

A noteworthy current policy:

Trakt says API application registrations may be automatically deleted after **30 consecutive days with no usage**.

Registrations used only for testing during their first 30 days may also be removed.

Therefore:

- create the real StreamVault integration when implementation work is active;
- keep developer account/contact information current;
- do not create unnecessary duplicate apps.

---

# 22. Failure handling

StreamVault should explicitly handle:

## Authentication cancelled

User closes browser or denies access:

```text
Keep Trakt disconnected.
Do not show an application error.
```

## OAuth state mismatch

```text
Abort login immediately.
Discard pending PKCE transaction.
```

## Expired access token

```text
Refresh once through TraktTokenRefreshCoordinator.
Retry original operation after successful refresh.
```

## Refresh `invalid_grant`

Especially:

```text
session not found
```

Treat as requiring reconnection.

Do not repeatedly retry.

## 401 after refresh

Clear invalid session and request reconnection.

## 429

Respect `Retry-After`.

## Offline

Queue only operations where delayed execution has clear semantics.

Do not create an unbounded playback-event queue.

## Unknown media identity

Do not scrobble until a reliable match exists.

---

# 23. Recommended implementation order

```text
1. Register StreamVault Trakt app
        ↓
2. Configure Client ID
        ↓
3. Configure HTTPS redirect + Android App Link
        ↓
4. Implement PKCE + OAuth state
        ↓
5. Implement callback + token exchange
        ↓
6. Secure token persistence
        ↓
7. Single-flight token refresh
        ↓
8. Connected-account settings UI
        ↓
9. Media ID matching
        ↓
10. Playback scrobbling
        ↓
11. History synchronization
        ↓
12. Watchlist support
        ↓
13. Optional ratings/lists/discovery
```

Do not start history/watchlist synchronization before authentication and media matching are reliable.

---

# 24. Acceptance checklist

## Registration

- [ ] StreamVault Trakt developer application created.
- [ ] Developer GitHub account verified/connected if required by Trakt.
- [ ] Client ID configured.
- [ ] No Client Secret included in StreamVault.

## OAuth

- [ ] Authorization Code Flow implemented.
- [ ] PKCE verifier generated per login.
- [ ] PKCE uses SHA-256 / `S256`.
- [ ] OAuth `state` generated and validated.
- [ ] HTTPS callback registered.
- [ ] Android Verified App Link configured.
- [ ] Redirect URI matches exactly in all OAuth stages.
- [ ] Browser-based Trakt login works.

## Tokens

- [ ] Access token securely stored.
- [ ] Refresh token securely stored.
- [ ] 7-day access-token expiry handled.
- [ ] Refresh token rotation replaces both tokens.
- [ ] Concurrent refresh is prevented.
- [ ] `invalid_grant` migration case prompts reconnect.
- [ ] Tokens are never logged.

## API

- [ ] Client ID header centrally added.
- [ ] Bearer token centrally added for authenticated calls.
- [ ] 401 handling implemented.
- [ ] 429 handling respects `Retry-After`.
- [ ] Request caching/de-duplication implemented where useful.
- [ ] Pagination response headers handled correctly.

## Playback

- [ ] Movie matching works.
- [ ] Show/season/episode matching works.
- [ ] Start scrobble works.
- [ ] Pause scrobble works.
- [ ] Stop/completion scrobble works.
- [ ] Duplicate playback events suppressed.
- [ ] Unknown media does not get incorrectly scrobbled.

## User experience

- [ ] Connect Trakt button.
- [ ] Browser authentication.
- [ ] Return to StreamVault automatically.
- [ ] Connected state visible.
- [ ] Disconnect option.
- [ ] Scrobbling toggle.
- [ ] History-sync toggle.
- [ ] Watchlist-sync toggle if implemented.
- [x] Read and set Trakt movie/show ratings from detail pages.

---

# 25. Things StreamVault should explicitly NOT do

```text
✗ Ask for the user's Trakt password
✗ Store a Trakt password
✗ Embed a client_secret in Android
✗ Require every StreamVault user to create a developer application
✗ Use replacement API keys to bypass restrictions
✗ Scrape trakt.tv
✗ Mirror the Trakt catalog
✗ Redistribute Trakt's dataset
✗ Hotlink Trakt-hosted artwork
✗ Treat the Client ID as a true secret
✗ Reuse old refresh tokens after a successful refresh
✗ Use custom-scheme OAuth callbacks when the recommended verified HTTPS App Link can be used
```

---

# 26. Sources

All core decisions in this plan are based on Trakt's official developer documentation.

## Trakt API introduction

https://developer.trakt.tv/

Covers Trakt terminology including scrobble, history, watchlist, lists and favorites.

---

## Authentication / OAuth

https://developer.trakt.tv/docs/authentication-oauth

Key findings:

- OAuth 2.0 is used for account authorization.
- Authorization Code Flow is intended for apps with browser/callback support.
- Device Code Flow is intended for TVs/media centers/limited-input devices.
- PKCE is recommended.
- Access tokens are currently valid for 7 days.
- Refresh tokens are single-use.
- Older refresh tokens affected by the authentication migration may require users to reconnect.

---

## PKCE

https://developer.trakt.tv/docs/pkce

Key findings:

- PKCE is recommended for apps that sign users in.
- Trakt only supports `S256`.
- `client_id` is public.
- A `client_secret` is not required for PKCE login.
- Client Secret is deprecated for user sign-in.
- Android should use HTTPS Verified App Links.
- Custom schemes, localhost and out-of-band callbacks are discouraged.

---

## Create a Trakt application

https://developer.trakt.tv/docs/create-an-app

Key findings:

- New apps use a Client ID.
- PKCE does not require a Client Secret.
- A verified GitHub account is currently required to create an API app.
- Developers must follow Trakt's API/branding rules.
- Trakt data must not be used in applications promoting copyright infringement or piracy.

App management:

https://developer.trakt.tv/apps

---

## API use policy

https://developer.trakt.tv/docs/api-use-policy

Key findings:

- media-player scrobbling is explicitly permitted;
- personal/self-hosted tools are explicitly permitted;
- app popularity/location/backend does not itself decide permission;
- bulk harvesting, resale and dataset redistribution are prohibited;
- rate-limit circumvention is prohibited;
- API app registrations can be removed after 30 consecutive days without usage;
- applications should provide clear privacy information for their own handling of user data.

Policy page was updated by Trakt in September 2026.

---

## Rate limits

https://developer.trakt.tv/docs/rate-limiting

Current documented limits:

```text
Authenticated GET:
500 / 5 minutes / user

Authenticated POST/PUT/DELETE:
1 / second / user

Unauthenticated GET:
500 / 5 minutes / Client ID + public IP
```

Also documents:

```text
429 Too Many Requests
Retry-After
X-Ratelimit
```

---

## Pagination

https://developer.trakt.tv/docs/pagination

Documents:

```text
page
limit
X-Pagination-Page
X-Pagination-Limit
X-Pagination-Page-Count
X-Pagination-Item-Count
```

Page sizes vary by endpoint and should not be assumed globally.

---

## Extended information

https://developer.trakt.tv/docs/extended-info

Trakt returns minimal media information by default and supports extended data where required.

Prefer requesting only the amount of data StreamVault actually needs.

---

## Images

https://developer.trakt.tv/docs/images

Important:

- Trakt-hosted images must be cached;
- hotlinking is not allowed;
- `extended=full` is the current preferred way to request image information where supported;
- images are currently returned as WebP URLs.

---

# 27. Final StreamVault decision

For StreamVault Android:

```text
ONE StreamVault Trakt application
ONE public Client ID
NO client secret in the app

OAuth 2.0 Authorization Code
+ PKCE S256
+ HTTPS callback
+ Android Verified App Link

Each user:
    own access token
    own rotating refresh token
    own Trakt account
    own authenticated rate-limit bucket

Playback:
    resolve exact movie/episode
    → scrobble start/pause/stop

Later:
    history
    watchlist
    ratings/lists if useful
```

This should be treated as the baseline Trakt architecture unless Trakt changes its developer documentation again.
