# Native Kodi Adaptive M3U Support Design

## Summary

StreamVault will import and play the commonly used Kodi `inputstream.adaptive`
metadata embedded in M3U playlists. The feature covers remote-license Widevine,
PlayReady, and ClearKey streams, plus static ClearKey material such as the
`KID:key` form reported in GitHub issue #169.

The implementation will parse only explicitly supported directives, normalize
them into a typed model, persist that model as versioned JSON with each imported
channel or movie, and translate it into the existing `StreamInfo` DRM and HTTP
configuration at playback time. Static ClearKey responses will use Media3's
local DRM callback and will never be placed in URLs or logs.

## Goals

- Play provider-style DASH entries whose Kodi properties specify a Widevine license
  URL.
- Play URL-licensed PlayReady and ClearKey entries through the existing Media3
  DRM configuration path.
- Play DASH ClearKey entries whose license value contains hexadecimal, base64,
  JSON-map, or JWK-form static key material.
- Preserve recognized per-entry manifest, stream, common, and license headers.
- Preserve `#EXTVLCOPT` user-agent and referer directives.
- Retain the metadata across sync, application restart, channel grouping, and
  movie classification.
- Reject malformed or excessive metadata without rejecting an otherwise valid
  playlist entry.
- Keep DRM secrets out of URLs, media identifiers, and diagnostic logs.

## Non-goals

- Rewriting or proxying an MPD that lacks usable ClearKey `ContentProtection`,
  `default_KID`, or equivalent initialization data.
- Supporting arbitrary Kodi directives or executing opaque directive content.
- Implementing DRM for external players or Cast receivers.
- Discovering, downloading, or deriving keys that are not already supplied by
  an authorized playlist.
- Changing Xtream, Stalker, Jellyfin, or plugin DRM behavior.

## Accepted M3U directives

The parser will recognize these directives between an `#EXTINF` line and its
media URL:

- `#KODIPROP:inputstream=inputstream.adaptive`
- `#KODIPROP:inputstream.adaptive.manifest_type`
- `#KODIPROP:inputstream.adaptive.license_type`
- `#KODIPROP:inputstream.adaptive.license_key`
- `#KODIPROP:inputstream.adaptive.license_headers`
- `#KODIPROP:inputstream.adaptive.stream_headers`
- `#KODIPROP:inputstream.adaptive.common_headers`
- `#EXTVLCOPT:http-user-agent`
- `#EXTVLCOPT:http-referrer`
- `#EXTVLCOPT:http-referer`

Names will be matched case-insensitively. Unknown directives will continue to be
ignored. A later duplicate of a scalar directive will replace the earlier value;
header maps will merge in encounter order, with later values replacing earlier
values for the same case-insensitive header name.

## Normalized model

The data module will define a focused `M3uPlaybackMetadata` model. It will hold:

- an optional manifest type;
- an optional normalized DRM scheme;
- either a validated remote license URL or validated static ClearKey material;
- manifest, stream, common, and license header maps;
- an optional user-agent;
- an optional referer;
- a serialization version.

Static ClearKey material will be represented structurally as key-ID/key byte
pairs. It will not be represented as a fake license URL. The model will expose
only redacted diagnostics.

The persisted JSON codec will be owned by the data module. Unknown JSON fields
will be ignored for forward compatibility. Unknown serialization versions,
malformed JSON, or invalid values will decode to no playback metadata rather
than preventing catalog access.

## Parsing and validation

`M3uParser` will accumulate supported directives in the pending entry alongside
the existing `#EXTINF` metadata. Parsing remains streaming and bounded.

Validation rules:

- Only `http` and `https` remote license URLs are accepted.
- DRM aliases normalize as follows:
  - `widevine` and `com.widevine.alpha` -> Widevine;
  - `playready` and `com.microsoft.playready` -> PlayReady;
  - `clearkey` and `org.w3.clearkey` -> ClearKey.
- Manifest aliases normalize to DASH, HLS, or SmoothStreaming. Unknown values do
  not override normal stream-type detection.
- Static ClearKey KIDs and keys must decode to exactly 16 bytes.
- Hex pairs, comma-separated pairs, JSON key maps, and JWK `keys` arrays are
  accepted. Duplicate KIDs use the last valid value.
- Header names and values must pass explicit count and length limits. Hop-by-hop
  headers and headers that would override `Host` or content framing are rejected.
- Directive and serialized-payload lengths are bounded through the existing M3U
  ingestion limits or tighter feature-specific limits.
- A DRM scheme without usable license data is discarded. Non-DRM headers and the
  playlist entry remain usable.
- Static key material is accepted only for ClearKey.

The parser will preserve enough information for import warnings and tests, but
will not log raw license values or static keys.

## Persistence

Room will add a nullable `playback_metadata_json` column to the `channels`,
`movies`, `channel_import_stage`, and `movie_import_stage` tables. The staging
columns allow the existing atomic catalog-apply path to copy metadata without a
side table or post-commit update. Existing rows receive `NULL`. The database
version and migration registry will advance by one version, and exported Room
schemas will be updated through the normal schema-generation task.

M3U synchronization will encode normalized metadata when constructing staged
`ChannelEntity` and `MovieEntity` rows. Sync fingerprints must include a stable,
non-secret digest of the normalized metadata so that metadata-only playlist
changes update the row. Raw DRM key values must not appear in fingerprints or
logs.

Entity/domain mapping will carry the persisted payload through raw and grouped
channel variants. Selecting a grouped variant must select that variant's
playback metadata. Movies must retain the metadata after automatic or manual M3U
classification.

Backup and restore behavior will follow existing database backup semantics. No
new plaintext diagnostics or exported human-readable key fields will be added.

## Playback translation

When an M3U channel or movie is resolved, its decoded metadata will enrich the
resulting `StreamInfo`:

- normalized manifest type supplies an explicit `StreamType`;
- common and stream headers apply to manifest and segment requests;
- user-agent and referer are mapped to the existing request configuration;
- license headers apply only to remote DRM license requests;
- a remote license URL creates the existing `DrmInfo` configuration;
- static ClearKey material creates a separate local ClearKey payload on
  `DrmInfo`.

`DrmInfo` will enforce that exactly one license source is present: a remote URL
or a local ClearKey response. Existing plugin callers remain source-compatible
by retaining the remote-license constructor defaults.

For static ClearKey, the player will:

1. Convert each validated 16-byte KID and key to unpadded base64url.
2. Build a temporary ClearKey JWK response in memory.
3. Create a `DefaultDrmSessionManager` for `C.CLEARKEY_UUID` using
   `LocalMediaDrmCallback`.
4. Install that manager through the selected media-source factory's DRM session
   manager provider.
5. Configure the `MediaItem` for ClearKey without a license URI.

Remote licenses continue through Media3's default HTTP DRM callback. The
media-source cache/preload identity will include only a digest of DRM material,
never the material itself. Existing policy that disables preload, frame
thumbnails, recording, timeshift, and Cast where DRM is unsupported remains in
force.

## Error handling

Malformed adaptive metadata will not abort the full playlist import. The entry
will be imported with every independently valid piece of metadata. If the DRM
combination is unusable, playback proceeds as an ordinary stream and Media3 can
report encryption failure normally.

Parser and codec failures will produce bounded, sanitized warnings containing
the provider and entry identity but no license URL query values, headers, KIDs,
or keys. Playback failures will continue through the existing DRM error
classification.

## Compatibility and rollout

Existing playlists without Kodi properties retain identical behavior. Existing
plugin-provided DRM remains on the remote-license path. Database migration must
be additive and safe for current installations.

Native support is intentionally narrower than Adaptive Bridge. Streams whose
manifests require ClearKey signaling injection remain unsupported natively and
may continue to use the bridge. If device validation shows that otherwise valid
issue #169 streams fail solely because the MPD lacks ClearKey initialization
data, manifest rewriting will be designed as a separate feature.

## Testing

Automated tests will be written before production changes and will cover:

- parsing each supported directive and alias;
- directive ordering and duplicate behavior;
- per-entry user-agent and referer handling;
- remote Widevine, PlayReady, and ClearKey license URLs;
- single and multiple static ClearKey pairs in hex, base64, JSON-map, and JWK
  formats;
- rejection of malformed keys, unsafe URLs, forbidden headers, and oversized
  values;
- codec round trips and unknown/malformed versions;
- Room migration and generated schema validation;
- sync persistence for live channels and movies;
- grouped-channel variant metadata selection;
- repository translation into `StreamInfo`;
- remote DRM `MediaItem` configuration;
- local ClearKey JWK generation and local DRM session-manager selection;
- media identifiers and logs not containing supplied DRM secrets;
- non-DRM M3U regression behavior.

Device validation requires authorized test streams. For each live DRM channel,
capture 61 screenshots at two-second intervals, verify continuing frame-hash
changes, confirm the media session remains `PLAYING` with no error, and inspect
sanitized logs for DRM/player failures. Validate at least one Widevine DASH
channel, one static-ClearKey DASH channel, and one non-DRM regression channel.

## Acceptance criteria

- A direct provider-style M3U entry with a Widevine HTTPS license URL plays without
  Adaptive Bridge.
- An issue #169-style DASH entry with a valid static ClearKey `KID:key` reaches
  Media3 through a local DRM callback and plays when its MPD contains compatible
  ClearKey signaling.
- Recognized headers, user-agent, referer, and manifest type survive sync and
  application restart.
- Grouped channels use the selected variant's DRM metadata.
- Invalid DRM metadata cannot crash or reject the complete provider import.
- No test output, production log, media ID, or URL contains raw static keys.
- Existing non-DRM and plugin DRM tests continue to pass.
