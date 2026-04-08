package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.TmdbUtils

internal class StreamingCommunityDetailsCoordinator {

    suspend fun resolveMovieTmdb(
        show: StreamingCommunityShow,
        language: String,
    ): Movie? {
        val direct = show.tmdbId?.toIntOrNull()?.let {
            TmdbUtils.getMovieById(it, language = language)
        }
        if (direct != null && !direct.poster.isNullOrEmpty() && !direct.banner.isNullOrEmpty()) {
            return direct
        }

        val fallback = TmdbUtils.getMovie(
            title = show.name,
            year = show.lastAirDate?.substringBefore("-")?.toIntOrNull(),
            language = language,
        )

        return direct ?: fallback
    }

    suspend fun resolveTvTmdb(
        show: StreamingCommunityShow,
        language: String,
    ): TvShow? {
        val direct = show.tmdbId?.toIntOrNull()?.let {
            TmdbUtils.getTvShowById(it, language = language)
        }
        if (direct != null && !direct.poster.isNullOrEmpty() && !direct.banner.isNullOrEmpty()) {
            return direct
        }

        val fallback = TmdbUtils.getTvShow(
            title = show.name,
            year = show.lastAirDate?.substringBefore("-")?.toIntOrNull(),
            language = language,
        )

        return direct ?: fallback
    }
}
