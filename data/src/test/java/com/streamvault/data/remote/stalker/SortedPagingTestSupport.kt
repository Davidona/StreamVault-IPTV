package com.streamvault.data.remote.stalker

import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.doSuspendableAnswer
import org.mockito.kotlin.whenever

/** Interface default overloads are not executed by Mockito mocks. */
suspend fun StalkerApiService.delegateSortedPagingToBasicStubs() {
    whenever(getSeriesDetailsInCategory(any(), any(), any(), any()))
        .doSuspendableAnswer { call -> getSeriesDetails(call.getArgument(0), call.getArgument(1), call.getArgument(2)) }
    whenever(getVodStreamsPage(any(), any(), anyOrNull(), any(), anyOrNull(), anyOrNull()))
        .doSuspendableAnswer { call ->
            getVodStreamsPage(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3))
        }
    whenever(getSeriesPage(any(), any(), anyOrNull(), any(), anyOrNull()))
        .doSuspendableAnswer { call ->
            getSeriesPage(call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3))
        }
}
