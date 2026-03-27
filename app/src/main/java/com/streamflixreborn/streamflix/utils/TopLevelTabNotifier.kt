package com.streamflixreborn.streamflix.utils

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object TopLevelTabNotifier {
    data class ScrollToTopRequest(
        val destinationId: Int,
        val animate: Boolean = false,
        val requestId: Long = System.nanoTime(),
    )

    private val _scrollToTopFlow = MutableSharedFlow<ScrollToTopRequest>(
        replay = 1,
        extraBufferCapacity = 1,
    )
    val scrollToTopFlow: SharedFlow<ScrollToTopRequest> = _scrollToTopFlow.asSharedFlow()

    fun requestScrollToTop(destinationId: Int, animate: Boolean = false) {
        _scrollToTopFlow.tryEmit(
            ScrollToTopRequest(
                destinationId = destinationId,
                animate = animate,
            )
        )
    }
}
