package com.streamvault.feature.system.api

object StreamVaultPluginContract {
    const val API_VERSION = 1

    const val ACTION_PLUGIN_SERVICE = "com.streamvault.plugin.API"
    const val META_MANIFEST_JSON = "com.streamvault.plugin.MANIFEST_JSON"
    const val META_ID = "com.streamvault.plugin.ID"
    const val META_NAME = "com.streamvault.plugin.NAME"
    const val META_VERSION_NAME = "com.streamvault.plugin.VERSION_NAME"
    const val META_VERSION_CODE = "com.streamvault.plugin.VERSION_CODE"
    const val META_DESCRIPTION = "com.streamvault.plugin.DESCRIPTION"
    const val META_CAPABILITIES = "com.streamvault.plugin.CAPABILITIES"
    const val META_CONFIGURATION_MODE = "com.streamvault.plugin.CONFIGURATION_MODE"
    const val META_CONFIGURATION_ACTIVITY_ACTION = "com.streamvault.plugin.CONFIGURATION_ACTIVITY_ACTION"
    const val META_PROVIDER_NAME = "com.streamvault.plugin.PROVIDER_NAME"

    const val MSG_GET_MANIFEST = 1
    const val MSG_SET_ENABLED = 2
    const val MSG_GET_STATUS = 3
    const val MSG_GET_PROVIDER_URL = 4
    const val MSG_PREPARE_PLAYBACK = 5
    const val MSG_REWRITE_CAST_URL = 6
    const val MSG_GET_CONFIGURATION_SCHEMA = 7
    const val MSG_GET_CONFIGURATION_VALUES = 8
    const val MSG_SET_CONFIGURATION_VALUES = 9
    const val MSG_RUN_CONFIGURATION_ACTION = 10

    const val KEY_API_VERSION = "api_version"
    const val KEY_REQUEST_ID = "request_id"
    const val KEY_SUCCESS = "success"
    const val KEY_HANDLED = "handled"
    const val KEY_ENABLED = "enabled"
    const val KEY_MESSAGE = "message"
    const val KEY_MANIFEST_JSON = "manifest_json"
    const val KEY_STATUS_LABEL = "status_label"
    const val KEY_URL = "url"
    const val KEY_PROVIDER_NAME = "provider_name"
    const val KEY_INPUT_URL = "input_url"
    const val KEY_OUTPUT_URL = "output_url"
    const val KEY_STREAM_TYPE = "stream_type"
    const val KEY_HEADERS_JSON = "headers_json"
    const val KEY_USER_AGENT = "user_agent"
    const val KEY_DRM_JSON = "drm_json"
    const val KEY_ALLOW_INVALID_SSL = "allow_invalid_ssl"
    const val KEY_TRANSPORT_MODE = "transport_mode"
    const val KEY_TRANSPORT_ORIGIN = "transport_origin"
    const val KEY_TRANSPORT_SPKI_SHA256 = "transport_spki_sha256"
    const val KEY_PROXY_HOST = "proxy_host"
    const val KEY_PROXY_PORT = "proxy_port"
    const val KEY_CAST_REWRITE_REASON = "cast_rewrite_reason"
    const val KEY_CONFIGURATION_SCHEMA_JSON = "configuration_schema_json"
    const val KEY_CONFIGURATION_VALUES_JSON = "configuration_values_json"
    const val KEY_CONFIGURATION_ACTION_ID = "configuration_action_id"
    const val KEY_CONFIGURATION_ERRORS_JSON = "configuration_errors_json"

    const val CAPABILITY_PROVIDER_M3U = "provider.m3u"
    const val CAPABILITY_PLAYBACK_PREPARE = "playback.prepare"
    const val CAPABILITY_CAST_REWRITE_URL = "cast.rewriteUrl"
    const val CAPABILITY_CONFIGURATION_SCHEMA = "configuration.schema"
    const val CAPABILITY_CONFIGURATION_ACTIVITY = "configuration.activity"

    const val CONFIGURATION_MODE_HOST_SCHEMA = "host.schema"
    const val CONFIGURATION_MODE_ACTIVITY = "activity"
}
