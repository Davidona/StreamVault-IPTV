# External launcher integration

StreamVault accepts `android.intent.action.VIEW` links for items in its installed
IPTV catalogue. Search intents remain supported. These links do not expose stream
URLs, request headers, provider passwords, or the app's private database.

## Item links (v1)

```text
streamvault://movie/<id>?provider=<providerId>&action=<action>
streamvault://series/<id>?provider=<providerId>&action=<action>
streamvault://episode/<id>?provider=<providerId>&action=<action>
streamvault://live/<id>?provider=<providerId>&action=play
```

Both IDs are positive **local StreamVault catalogue IDs** on this installation,
not Xtream stream IDs, TMDB IDs, or IDs from another device. The provider is
required and must be the current active provider. A link never switches providers.
Keep links returned in published TV rows rather than trying to derive these IDs.
Re-query rows after library/provider changes; links are not portable across a
database reset, restore, or provider removal and re-addition.

| Item | Supported actions | Default when omitted |
| --- | --- | --- |
| Movie | `details`, `play`, `resume`, `start_over` | `details` |
| Series | `details`, `resume` | `details` |
| Episode | `play`, `resume`, `start_over` | `resume` |
| Live channel | `play` | `play` |

`play` keeps the normal in-app resume prompt. `resume` seeks directly to the
latest saved unfinished position without that prompt. With no resumable position,
a movie/episode starts from the beginning. `start_over` ignores saved progress for
this launch; it does not erase history. Completed items do not resume near the end.

A series `resume` opens its most recently played episode only when that episode
has unfinished progress and still exists in that series. Otherwise it opens the
series details so the viewer can choose an episode. It does not guess the next
episode. Ordinary Back from externally opened playback/details returns to Home.

Unknown/malformed links, unavailable items, wrong/inactive providers, hidden
channels/categories, and adult or user-protected items/categories return to Home.
External links do not inherit a previous PIN unlock. Provider credentials and
playback URLs are resolved internally from the catalogue when opening the item.
TMDB lookup and Stremio public URI links are not part of v1; existing Stremio
Watch Next links retain their exact-item string routes.

For example, replacing the IDs with real local IDs:

```sh
adb shell 'am start -a android.intent.action.VIEW -p com.streamvault.app -d "streamvault://movie/42?provider=7&action=resume"'
adb shell 'am start -a android.intent.action.VIEW -p com.streamvault.app -d "streamvault://series/43?provider=7&action=details"'
adb shell 'am start -a android.intent.action.VIEW -p com.streamvault.app -d "streamvault://episode/44?provider=7&action=start_over"'
```

Use `Intent(ACTION_VIEW, uri).setPackage("com.streamvault.app")` in a launcher and
handle `ActivityNotFoundException` if StreamVault is not installed. A browser can
also open the registered scheme. Exported links accept only the documented
identity/action fields; titles, stream URLs, resume timestamps, and arbitrary
navigation extras are not accepted as link parameters.

Launchers can read integer activity metadata `com.streamvault.app.ITEM_LINK_VERSION`
on `MainActivity` to detect support (v1 = `1`). Debug builds use package
`com.streamvault.app.debug`; replace the package in commands when testing those builds.

## Reading Android TV rows

StreamVault publishes unfinished playback to Watch Next and publishes preview
channels for Favorites, Continue Watching, Recommended Movies, and Fresh Series. The
`intent_uri` values contain URI-safe playback/detail targets; launchers should
parse them with `Intent.parseUri(value, Intent.URI_INTENT_SCHEME)` and start the
returned intent. Do not treat custom Java/Kotlin object extras as a URI contract.

On the standard Android 12 TV provider a third-party launcher can declare and
request the runtime permission `android.permission.READ_TV_LISTINGS`. It can then
query searchable rows from:

```text
content://android.media.tv/watch_next_program
content://android.media.tv/preview_program
content://android.media.tv/channel
```

Use a null SQL selection/selectionArgs and filter `package_name` to
`com.streamvault.app` in the returned cursor. Standard TV providers reject SQL
selections from clients without `ACCESS_ALL_EPG_DATA`. That privileged permission
is not required for reading searchable rows with `READ_TV_LISTINGS`.

Do not conclude that an app publishes nothing from a shell `content query` alone:
the shell caller's permission/visibility differs from the launcher app's. Test
from the actual launcher after its TV-listings permission is granted. OEM provider
behavior can differ; verify on the target TV.

Sources: [Android 12 TV provider query access rules](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android12-release/src/com/android/providers/tv/TvProvider.java),
[TV provider permissions](https://android.googlesource.com/platform/packages/providers/TvProvider/+/refs/heads/android12-release/AndroidManifest.xml).

## Favorites preview channel

The channel has internal provider ID `streamvault_favorites`. Its programs carry
stable keys `favorite:<providerId>:<movie|series|live>:<localId>` and the public
item intents described above. Movies and series use `resume`; live channels use
`play`. A series without unfinished progress opens its details.

The row contains up to 24 eligible global Favorites from the active provider, in
saved order (higher `weight` first). Group-only items, unsupported legacy/episode
favorites, hidden channels/categories, adult/protected items/categories, deleted
items, and items belonging to another provider are excluded. Catalogue IDs are
queried in batches; publication does not load the entire catalogue. The publisher
observes favorite additions, removals, reordering, metadata, visibility, protection,
and provider changes while the app process is alive. Initial publication happens
after the first TV UI frame. Returning after the process was stopped refreshes the
row at startup.

Additional preview channels may need to be enabled in the launcher's channel
controls. StreamVault publishes the row without repeatedly requesting that it be
shown. The empty channel is retained after removing its last card to preserve the
launcher's enable/disable choice; old cards are cleared when providers change or
data cannot be read. This is an Android TV provider integration, so OEM visibility
and third-party launcher permission support still need device-specific testing.

A separate, opt-in read-only library ContentProvider remains follow-up work.
No unrestricted library/history provider is exported.
