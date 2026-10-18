package com.streamvault.domain.usecase

import com.streamvault.domain.model.ExternalSubtitle
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ExternalSubtitleRepository
import java.io.File
import javax.inject.Inject

class DownloadExternalSubtitle @Inject constructor(
    private val repository: ExternalSubtitleRepository,
) {
    suspend operator fun invoke(subtitle: ExternalSubtitle): Result<File> =
        repository.downloadToCache(subtitle)
}
