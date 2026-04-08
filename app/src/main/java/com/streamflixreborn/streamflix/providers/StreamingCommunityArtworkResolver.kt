package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.utils.ArtworkResolver

internal class StreamingCommunityArtworkResolver(
    private val imageLinkResolver: (String?) -> String?,
) {

    suspend fun resolve(
        show: StreamingCommunityShow,
        allowSearchFallback: Boolean = false,
        heroOnlyTextless: Boolean = false,
    ): ArtworkResolver.Artwork? {
        val providerBanner = imageLinkResolver(
            show.images.findLast { it.type == "background" || it.type == "backdrop" }?.filename
                ?: show.images.findLast { it.type == "cover" }?.filename
                ?: show.images.find { it.type == "cover" }?.filename
        )
        val providerPoster = imageLinkResolver(
            show.images.findLast { it.type == "poster" }?.filename
                ?: show.images.find { it.type == "poster" }?.filename
        )

        return ArtworkResolver.resolve(
            showType = show.type,
            tmdbId = show.tmdbId,
            title = show.name,
            year = show.lastAirDate?.substringBefore("-")?.toIntOrNull(),
            providerPoster = providerPoster,
            providerBanner = providerBanner,
            allowSearchFallback = allowSearchFallback,
            requireTextlessHero = heroOnlyTextless,
        )
    }
}
