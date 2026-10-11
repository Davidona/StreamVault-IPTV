package com.streamvault.domain.usecase

import com.streamvault.domain.model.ExternalSubtitleLanguage
import com.streamvault.domain.model.Result
import com.streamvault.domain.repository.ExternalSubtitleRepository
import javax.inject.Inject

class GetExternalSubtitleLanguages @Inject constructor(
    private val repository: ExternalSubtitleRepository,
) {
    suspend operator fun invoke(): Result<List<ExternalSubtitleLanguage>> = repository.languages()
}
