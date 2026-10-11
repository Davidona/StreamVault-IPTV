package com.streamvault.data.remote.subtitle

import com.google.gson.annotations.SerializedName

data class OpenSubtitlesLoginRequestDto(
    @SerializedName("username") val username: String,
    @SerializedName("password") val password: String,
)

data class OpenSubtitlesLoginResponseDto(
    @SerializedName("token") val token: String? = null,
    @SerializedName("base_url") val baseUrl: String? = null,
    @SerializedName("status") val status: Int? = null,
    @SerializedName("user") val user: OpenSubtitlesUserDto? = null,
)

data class OpenSubtitlesUserDto(
    @SerializedName("allowed_downloads") val allowedDownloads: Int? = null,
    @SerializedName("level") val level: String? = null,
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("vip") val vip: Boolean? = null,
)

data class OpenSubtitlesDownloadRequestDto(
    @SerializedName("file_id") val fileId: Long,
    @SerializedName("sub_format") val subFormat: String? = null,
    @SerializedName("file_name") val fileName: String? = null,
)

data class OpenSubtitlesDownloadResponseDto(
    @SerializedName("link") val link: String? = null,
    @SerializedName("file_name") val fileName: String? = null,
    @SerializedName("requests") val requests: Int? = null,
    @SerializedName("remaining") val remaining: Int? = null,
    @SerializedName("message") val message: String? = null,
    @SerializedName("reset_time") val resetTime: String? = null,
    @SerializedName("reset_time_utc") val resetTimeUtc: String? = null,
)

data class OpenSubtitlesSearchResponseDto(
    @SerializedName("total_pages") val totalPages: Int? = null,
    @SerializedName("total_count") val totalCount: Int? = null,
    @SerializedName("page") val page: Int? = null,
    @SerializedName("data") val data: List<OpenSubtitlesSubtitleDto>? = emptyList(),
)

data class OpenSubtitlesLanguagesResponseDto(
    @SerializedName("data") val data: List<OpenSubtitlesLanguageDto>? = emptyList(),
)

data class OpenSubtitlesLanguageDto(
    @SerializedName("language_code") val languageCode: String? = null,
    @SerializedName("language_name") val languageName: String? = null,
)

data class OpenSubtitlesSubtitleDto(
    @SerializedName("id") val id: String? = null,
    @SerializedName("type") val type: String? = null,
    @SerializedName("attributes") val attributes: OpenSubtitlesSubtitleAttributesDto? = null,
)

data class OpenSubtitlesSubtitleAttributesDto(
    @SerializedName("subtitle_id") val subtitleId: String? = null,
    @SerializedName("language") val language: String? = null,
    @SerializedName("language_name") val languageName: String? = null,
    @SerializedName("release") val release: String? = null,
    @SerializedName("download_count") val downloadCount: Long? = null,
    @SerializedName("feature_details") val featureDetails: OpenSubtitlesFeatureDetailsDto? = null,
    @SerializedName("files") val files: List<OpenSubtitlesFileDto>? = emptyList(),
)

data class OpenSubtitlesFeatureDetailsDto(
    @SerializedName("title") val title: String? = null,
    @SerializedName("year") val year: Int? = null,
    @SerializedName("imdb_id") val imdbId: Long? = null,
    @SerializedName("tmdb_id") val tmdbId: Long? = null,
    @SerializedName("parent_imdb_id") val parentImdbId: Long? = null,
    @SerializedName("parent_tmdb_id") val parentTmdbId: Long? = null,
    @SerializedName("season_number") val seasonNumber: Int? = null,
    @SerializedName("episode_number") val episodeNumber: Int? = null,
)

data class OpenSubtitlesFileDto(
    @SerializedName("file_id") val fileId: Long? = null,
    @SerializedName("file_name") val fileName: String? = null,
    @SerializedName("cd_number") val cdNumber: Int? = null,
)
