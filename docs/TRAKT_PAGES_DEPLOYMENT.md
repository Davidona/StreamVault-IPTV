# Deploy the Trakt callback with GitHub Pages

The files in `docs/trakt-pages/` were published at the root of the GitHub user site
repository `Davidona/davidona.github.io` on October 11, 2026, before the user's
instruction to keep further code local. The integration worktree has not been
pushed. Do not publish further code until the user requests it.

Deployment commit: `17d72f1f9ece8b9b907ad299ed9da481dc6da50c`.
Both public URLs below returned HTTP 200 without redirects and with the expected
content types. Trakt's existing StreamVault app has the exact callback registered;
local debug configuration matches it. The configured APK built and installed, and
Android reports `davidona.github.io: verified` for `com.streamvault.app.debug`.
Real OAuth login/consent and callback delivery passed on the isolated Codex_Trakt
emulator (5558) using Firefox 157.0.1. Connected state and username survived an app
process restart. Remaining account acceptance cases are tracked in TRAKT_SETUP.md.
GitHub Actions callback
variable configuration and integration workflow
changes remain pending publication approval.

## Publish

1. Check whether `Davidona/davidona.github.io` already exists. If it does, preserve
   its existing site and merge only the needed callback and verification files.
2. Otherwise, create a public repository named `davidona.github.io` under Davidona.
3. Copy the **contents** of `docs/trakt-pages/` into the repository root, including
   `.nojekyll` and `.well-known/assetlinks.json`. Commit them on `main`.
4. In repository Settings → Pages, select Deploy from a branch, `main`, and `/ (root)`.
5. Wait for deployment and verify these two addresses return HTTP 200 directly,
   without a redirect:
   - `https://davidona.github.io/.well-known/assetlinks.json` (application/json)
   - `https://davidona.github.io/oauth/trakt/callback/index.html` (text/html)

Use the explicit `index.html` callback URL because directory paths on static hosts
may redirect to add a trailing slash. Register the exact explicit URL everywhere.

## Configure Trakt and builds after deployment

Keep `https://github.com/Davidona/StreamVault-IPTV` in Trakt's **Website** field.
Set Trakt's **Redirect URI** to:

```text
https://davidona.github.io/oauth/trakt/callback/index.html
```

Set the same value in local.properties as `trakt.redirect.uri`, and in GitHub
repository variable `TRAKT_REDIRECT_URI` for the StreamVault build workflows.
Set repository secret `TRAKT_CLIENT_ID` to the supplied public app ID. No client
secret is needed. Local callback configuration is complete. The public Client ID
was also saved as an Actions secret before publication was paused; no client secret
was configured. Add the callback variable when preparing CI for publication.

## Certificates included

The verification file authorizes only these application/certificate combinations:

| Package | Certificate |
| --- | --- |
| `com.streamvault.app` | Existing release APK signing certificate |
| `com.streamvault.app.beta` | Same official certificate used by the beta build configuration |
| `com.streamvault.app.debug` | This machine's debug signing certificate |

The release certificate was confirmed using Android apksigner against the existing
release APK on October 11, 2026. The debug certificate was confirmed with this
worktree's Gradle signingReport. Certificates are public; no keystore/private key,
password, OAuth code, or user token is included. New signing keys require updating
this file. A debug build from another machine may use a different certificate.

## Validate

Install the new debug APK with the configured callback. On Android 12+:

```text
adb shell pm verify-app-links --re-verify com.streamvault.app.debug
adb shell pm get-app-links com.streamvault.app.debug
```

Confirm `davidona.github.io` is verified. A 200 response alone does not prove
Android verification. Complete browser consent from Settings → Trakt and confirm
the app returns with the connected username. Keep one matching StreamVault build
installed during routing tests. Then validate restart, cancellation, disconnect,
and refresh behavior from `TRAKT_SETUP.md`.

The fallback page removes the OAuth query from browser history and neither reads
nor exchanges OAuth codes. Domain verification causes Android to route the link
to the app before the page is loaded. GitHub still receives a normal web request
if the browser loads the fallback page; this static page cannot control hosting
provider logs.
