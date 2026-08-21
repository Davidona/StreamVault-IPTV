# Phase 2 lazy-list audit

This audit covers the Phase 2 hot paths: Home and Dashboard shelves, Search
results, the EPG channel rail, reusable media rows, and player/dialog lists.
The rule is to keep a stable domain identifier as the key and add
`contentType` when a list mixes item layouts or when a dynamic dialog list is
otherwise difficult for Compose to classify.

| Area / list | Key | `contentType` | Result |
| --- | --- | --- | --- |
| Dashboard sections | `AppHomeDashboardShelf.storageValue` | shelf storage value | Fixed; sections render materially different shelf layouts. |
| Dashboard recent/favorite/media `CategoryRow` shelves | Existing item IDs | CategoryRow supports an optional selector; omitted for homogeneous item renderers | Reviewed; stable keys are supplied by each caller and no mixed item layouts are present inside a shelf. |
| Dashboard shortcuts, stats, favorite-logo row | Existing shortcut/stat label or channel ID | Not required | Reviewed; each list is homogeneous and already keyed. |
| Home visible categories | Category ID | `live_category` | Fixed. |
| Home filtered channel list | Channel ID | `live_channel` | Fixed; this is also the owner of the shared channel clock. |
| Search result rails | Item ID through `CategoryRow` | Not required | Reviewed; each rail is homogeneous and uses the existing ID selector. |
| Search LIVE/MOVIES/SERIES result rows | Joined stable item IDs | `search_live_row`, `search_movie_row`, `search_series_row` | Fixed; the selected tab chooses a different row renderer. |
| Search tabs and recent queries | Tab name / query text | Not required | Reviewed; both lists are homogeneous and keyed. |
| EPG channel rows | `epgChannelKey(channel, index)` | `epg_channel` | Fixed; the index remains part of the existing collision-safe key policy. |
| `CategoryRow` reusable media shelf | Caller key selector | Caller selector, when needed | Reviewed; the reusable primitive already forwards keys and content types; homogeneous shelves only need the stable caller key. |
| `ContinueWatchingRow` | Playback-history ID | History content type | Already compliant. |
| Player channel/category/EPG overlay lists | Existing player item-key helpers | Existing channel/category/program types | Already compliant from the overlay audit. |
| Player track/variant/format/speed/timer/episode lists | Track/variant/format/speed/minutes/season/episode IDs | Homogeneous list types | Reviewed; stable keys are present and each list has one renderer. |
| Program history dialog | Channel/start/end/ID composite | `program` | Fixed; prevents index-key fallback when EPG data refreshes. |
| Add-to-group dialog | Category ID | `category` | Fixed. |
| M3U series-assignment dialog | Channel ID | `series_assignment` | Fixed. |
| Player quick-action row | Semantic `PlayerActionSpec.id` | `player_quick_action` | Fixed; labels can change for mute, casting, playback speed, aspect ratio, or timer state without changing item identity. |
| Channel-info action tray | Existing `label:index` composite | `channel_info_action` | Reviewed; the private action type has no semantic ID, so the short homogeneous tray retains its collision-safe composite key. |

No display-text key was introduced where a stable domain ID was available. The
private channel-info action type has no domain ID, so its short tray keeps the
existing collision-safe `label:index` key.
The channel progress clock is collected once by Dashboard, Home, and Search;
`ChannelCard` and `LiveChannelRowCard` now receive the timestamp as a value and
do not collect a flow from inside a lazy-list item.
