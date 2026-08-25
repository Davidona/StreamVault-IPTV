# StreamVault Plugin API

StreamVault plugins are companion Android APKs. They do not inject code into the
main application. Instead, StreamVault discovers installed APKs that expose a
bound service with the action `com.streamvault.plugin.API` and talks to them
through Android `Messenger` IPC.

This keeps the plugin boundary installable, removable, and compatible with
Android package isolation while still allowing plugins to add provider, playback,
Cast, and configuration capabilities. Configuration can be rendered by
StreamVault from a declarative schema, or opened as a native plugin Activity when
the plugin needs a richer UI.

## Package Discovery

The host queries services for:

```xml
<action android:name="com.streamvault.plugin.API" />
```

The host app must declare the same action in `<queries>`. Plugin APKs should
declare an exported service:

```xml
<service
    android:name=".MyPluginService"
    android:exported="true">
    <intent-filter>
        <action android:name="com.streamvault.plugin.API" />
    </intent-filter>
</service>
```

Plugins can be installed from:

- A direct HTTP/HTTPS APK URL.
- A local APK selected with the system file picker.
- Any manual Android package install. StreamVault detects it after refreshing the
  Plugins screen.

## Manifest

StreamVault first asks the plugin service for its manifest. As a fallback, it
reads `com.streamvault.plugin.MANIFEST_JSON` service metadata, then individual
metadata fields if the JSON is missing or invalid.

Example manifest for host-rendered configuration:

```json
{
  "schemaVersion": 1,
  "id": "com.example.plugin",
  "name": "Example Plugin",
  "versionName": "1.0.0",
  "versionCode": 1,
  "description": "Adds external capabilities to StreamVault.",
  "providerName": "Example Provider",
  "configurationMode": "host.schema",
  "configurationActivityAction": "",
  "capabilities": [
    "provider.m3u",
    "playback.prepare",
    "cast.rewriteUrl",
    "configuration.schema"
  ],
  "playbackUrlSchemes": ["http", "https"],
  "playbackUrlHosts": ["127.0.0.1", "cdn.example.com"]
}
```

Example manifest for native Activity configuration:

```json
{
  "schemaVersion": 1,
  "id": "com.example.richplugin",
  "name": "Example Rich Plugin",
  "versionName": "1.0.0",
  "versionCode": 1,
  "description": "Adds a rich native configuration surface.",
  "providerName": "Example Provider",
  "configurationMode": "activity",
  "configurationActivityAction": "com.example.richplugin.CONFIGURE",
  "capabilities": [
    "provider.m3u",
    "configuration.activity"
  ]
}
```

Recommended fallback metadata:

```xml
<meta-data android:name="com.streamvault.plugin.ID" android:value="com.example.plugin" />
<meta-data android:name="com.streamvault.plugin.NAME" android:value="Example Plugin" />
<meta-data android:name="com.streamvault.plugin.VERSION_NAME" android:value="1.0.0" />
<meta-data android:name="com.streamvault.plugin.VERSION_CODE" android:value="1" />
<meta-data android:name="com.streamvault.plugin.DESCRIPTION" android:value="Adds external capabilities to StreamVault." />
<meta-data android:name="com.streamvault.plugin.PROVIDER_NAME" android:value="Example Provider" />
<meta-data android:name="com.streamvault.plugin.CONFIGURATION_MODE" android:value="host.schema" />
<meta-data android:name="com.streamvault.plugin.CONFIGURATION_ACTIVITY_ACTION" android:value="" />
<meta-data android:name="com.streamvault.plugin.CAPABILITIES" android:value="provider.m3u,playback.prepare,cast.rewriteUrl,configuration.schema" />
```

For Activity configuration, set `CONFIGURATION_MODE` to `activity`, set
`CONFIGURATION_ACTIVITY_ACTION`, and advertise `configuration.activity`.

### `playbackUrlSchemes` / `playbackUrlHosts`

Required for `playback.prepare` and `cast.rewriteUrl` to ever be invoked.
StreamVault only calls a plugin for these capabilities if the plugin's manifest
declares the URL's scheme and host — an absent or empty list means the plugin
owns **no** URLs, it is not treated as a wildcard match. Use `"*"` in either
list to explicitly opt into matching every scheme or every host.

This is a separate, earlier filter than the `handled` field described below:
StreamVault first narrows to plugins whose declared scheme/host own the URL,
then sends `MSG_PREPARE_PLAYBACK` only to that narrowed set. A plugin that
advertises `playback.prepare` but never declares `playbackUrlSchemes` /
`playbackUrlHosts` will never receive `MSG_PREPARE_PLAYBACK` at all, even for
URLs it generated itself (for example, its own `provider.m3u` playlist
pointing back at a local `http://127.0.0.1:<port>/...` proxy).

These fields are only read from `manifest_json` (live `MSG_GET_MANIFEST`
response or the static `MANIFEST_JSON` fallback metadata) — there is no
individual fallback `meta-data` field for them, unlike `CAPABILITIES` or
`DESCRIPTION`, so a plugin relying purely on individual fallback metadata
fields (no `MANIFEST_JSON`) cannot declare playback URL ownership at all and
must supply a full `MANIFEST_JSON` value to use `playback.prepare` or
`cast.rewriteUrl`.

Capability names:

- `provider.m3u`: the plugin can expose an M3U URL that StreamVault imports as a
  provider when enabled.
- `playback.prepare`: the plugin can prepare a stream URL before playback starts.
- `cast.rewriteUrl`: the plugin can rewrite a playback URL before Google Cast
  loads it.
- `configuration.schema`: the plugin exposes a declarative configuration schema
  that StreamVault renders with its own UI.
- `configuration.activity`: the plugin exposes a native Android Activity that
  StreamVault opens for configuration.

## IPC Messages

Every request includes:

- `api_version`: current value is `1`.
- `request_id`: opaque ID copied back by the plugin response.

Every response should include:

- `api_version`
- `request_id`
- `success`
- Optional `message`

Messages:

| ID | Name | Purpose |
| --- | --- | --- |
| 1 | `MSG_GET_MANIFEST` | Return `manifest_json`. |
| 2 | `MSG_SET_ENABLED` | Start or stop plugin functionality using `enabled`. |
| 3 | `MSG_GET_STATUS` | Return a short `status_label` and optional `message`. |
| 4 | `MSG_GET_PROVIDER_URL` | Return `url` and optional `provider_name`. |
| 5 | `MSG_PREPARE_PLAYBACK` | Prepare `input_url`; set `handled` when relevant. |
| 6 | `MSG_REWRITE_CAST_URL` | Rewrite `input_url`; return `output_url` when relevant. |
| 7 | `MSG_GET_CONFIGURATION_SCHEMA` | Return `configuration_schema_json`. |
| 8 | `MSG_GET_CONFIGURATION_VALUES` | Return `configuration_values_json`. |
| 9 | `MSG_SET_CONFIGURATION_VALUES` | Persist `configuration_values_json`. |
| 10 | `MSG_RUN_CONFIGURATION_ACTION` | Run `configuration_action_id`. |

For `playback.prepare` and `cast.rewriteUrl`, StreamVault only sends the message
to plugins whose manifest already owns the URL (see `playbackUrlSchemes` /
`playbackUrlHosts` above). Within that narrowed set, plugins should still set
`handled=false` when the specific URL is not one they can actually prepare.
StreamVault then continues with other matching enabled plugins or the original
URL.

`MSG_REWRITE_CAST_URL` is Cast-specific. StreamVault sends the stream context that
made direct receiver playback unsafe:

- `input_url`: original media URL.
- `stream_type`: MIME/stream hint when known.
- `headers_json`: JSON object with HTTP headers needed by local playback.
- `user_agent`: custom user agent needed by local playback.
- `allow_invalid_ssl`: whether local playback accepted invalid TLS certificates.
- `proxy_host` and optional `proxy_port`: proxy settings used by local playback.
- `cast_rewrite_reason`: one of `LOCAL_URI`, `CUSTOM_HEADERS`,
  `CUSTOM_USER_AGENT`, `PROXY`, or `INVALID_SSL`.

Plugins should return a receiver-reachable `output_url` that no longer requires
device-local URLs, app-only headers, app-only user agent handling, app proxy
settings, or invalid TLS handling on the Cast receiver.

`MSG_PREPARE_PLAYBACK` may also enrich the stream that StreamVault sends to the
player. When `handled=true` and `success=true`, the plugin can return:

- `output_url`: final playable URL. If omitted, StreamVault keeps `input_url`.
- `stream_type`: optional playback hint. Supported values are `DASH`, `HLS`,
  `SMOOTH_STREAMING`, `MPEG_TS`, `PROGRESSIVE`, and `RTSP`.
- `headers_json`: JSON object with HTTP request headers for the media request.
- `user_agent`: optional user agent applied to the media request.
- `drm_json`: JSON object with DRM information:

```json
{
  "scheme": "clearkey",
  "licenseUrl": "http://127.0.0.1:39077/license/clearkey/channel-id",
  "headers": {
    "Authorization": "Bearer token"
  },
  "multiSession": false,
  "forceDefaultLicenseUrl": true,
  "playClearContentWithoutKey": true
}
```

`scheme` accepts `widevine`, `playready`, `clearkey`, or their platform UUID
aliases (`com.widevine.alpha`, `com.microsoft.playready`, `org.w3.clearkey`).
For `SMOOTH_STREAMING` + `clearkey`, StreamVault normalizes the ISML
`ProtectionHeader` into ClearKey PSSH v1 init data before creating the Android
MediaDrm session.

## Choosing a Configuration Mode

Plugins should choose one primary configuration mode.

Use `host.schema` when the configuration is mostly fields, switches, selects, and
simple actions. StreamVault owns the visual shell, validation placement, focus
behavior, typography, controls, and feedback.

Use `activity` when the plugin needs a custom or highly interactive
configuration surface, for example:

- Runtime dashboards with live state.
- Source managers with add/edit/delete flows.
- Channel testing workflows.
- Embedded logs.
- Native pairing, sign-in, or device setup.
- Custom layouts that must look the same when opened directly and from
  StreamVault.

Do not advertise `configuration.schema` for a partial or stale schema. If
`configurationMode` is `activity`, StreamVault treats the Activity as the active
configuration path and opens `configurationActivityAction` instead of loading a
host-rendered schema.

For backward compatibility, a plugin that omits `configurationMode` but
advertises `configuration.schema` is treated as host-rendered. New plugins should
set `configurationMode` explicitly.

## Host-Rendered Configuration

Host-rendered configuration lets a plugin describe fields and actions while
StreamVault owns the visual implementation.

A plugin opts in with:

- Manifest field `configurationMode: "host.schema"`.
- Capability `configuration.schema`.
- IPC handlers for messages 7 through 10.

Schema response:

```json
{
  "schemaVersion": 1,
  "title": "Example Plugin",
  "description": "Settings rendered by StreamVault.",
  "sections": [
    {
      "id": "connection",
      "title": "Connection",
      "description": "Endpoint used by this plugin.",
      "fields": [
        {
          "key": "serverUrl",
          "type": "url",
          "label": "Server URL",
          "placeholder": "http://192.168.1.20:8080",
          "required": true
        },
        {
          "key": "token",
          "type": "password",
          "label": "Token",
          "secret": true
        },
        {
          "key": "lanMode",
          "type": "boolean",
          "label": "LAN mode",
          "description": "Expose local URLs to other devices."
        },
        {
          "key": "quality",
          "type": "select",
          "label": "Preferred quality",
          "options": [
            { "value": "auto", "label": "Auto" },
            { "value": "1080p", "label": "1080p" }
          ]
        },
        {
          "key": "status",
          "type": "info",
          "label": "Status",
          "readOnly": true
        }
      ]
    }
  ],
  "actions": [
    {
      "id": "testConnection",
      "label": "Test connection",
      "description": "Ask the plugin to validate its current settings.",
      "refreshAfterRun": true
    }
  ]
}
```

Supported field types:

- `info`: read-only text rendered by StreamVault.
- `text`: single-line text.
- `password`: single-line secret text.
- `url`: URL text field.
- `number`: numeric text field.
- `boolean`: switch.
- `select`: one value from `options`.
- `textarea`: multi-line text.

Values response:

```json
{
  "serverUrl": "http://192.168.1.20:8080",
  "token": "",
  "lanMode": true,
  "quality": "auto",
  "status": "Ready"
}
```

For `MSG_SET_CONFIGURATION_VALUES`, StreamVault sends the same JSON object in
`configuration_values_json`. Plugins should persist supported writable keys,
ignore unknown or read-only keys, and return `success=false` with `message` when
validation fails.

For `MSG_RUN_CONFIGURATION_ACTION`, StreamVault sends `configuration_action_id`.
Plugins should execute the action, return a user-facing `message`, and refresh
their values when `refreshAfterRun` is true.

## Configuration Activity Mode

Activity configuration lets the plugin own the complete configuration UI.
StreamVault starts `configurationActivityAction` with the plugin package set
explicitly, so the action does not resolve to another app.

A plugin opts in with:

- Manifest field `configurationMode: "activity"`.
- Capability `configuration.activity`.
- Manifest field `configurationActivityAction`.
- An exported Activity with an intent filter for that action and the `DEFAULT`
  category.

Example:

```xml
<activity
    android:name=".MyConfigActivity"
    android:exported="true"
    android:label="@string/app_name">
    <intent-filter>
        <action android:name="com.example.richplugin.CONFIGURE" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

Use Activity mode for flows that cannot be represented faithfully as fields and
actions. The HaP plugin is the reference example: it uses Activity mode for
runtime state, source management, custom AceStream channels, channel tests,
connected clients, and logs.

## Native Activity Visual Guidance

Native plugin Activities should feel at home when launched from StreamVault and
remain usable when opened directly from Android or Android TV.

Recommended visual and interaction rules:

- Design TV-first: every important control must be D-pad focusable and readable
  from couch distance.
- Keep the style restrained and close to StreamVault: dark background, compact
  panels, clear focus states, and meaningful status colors for running, stopped,
  warning, and error states.
- Collapse heavy or secondary sections by default, especially logs, source
  editors, and long channel-status lists.
- Keep layout dimensions stable. Avoid buttons, rows, or status chips changing
  size when text or loading state changes.
- Truncate or wrap long URLs, channel names, and error messages so text never
  overlaps adjacent controls.
- Use Android resources and the system locale for all user-facing text.
- Show direct feedback for loading, validation, success, and failure states.
- Test both entry points: launched directly from the plugin APK and launched from
  StreamVault's Plugins screen.
- Test on the same device classes StreamVault supports, including TV devices such
  as Chromecast and phone-sized devices such as Nexus 5X.

## Integration Checklist

Before publishing a plugin:

- Expose exactly one `com.streamvault.plugin.API` service.
- Return a complete manifest from `MSG_GET_MANIFEST`.
- Keep service metadata in sync with the runtime manifest as fallback.
- Choose `host.schema` or `activity` as the primary configuration mode.
- Advertise only capabilities that are fully implemented.
- Validate user-provided URLs and secrets inside the plugin before persisting
  them.
- Return user-facing error messages from IPC failures.
- Verify install, enable, disable, configure, provider URL, playback prepare, and
  Cast URL rewrite on real devices.
