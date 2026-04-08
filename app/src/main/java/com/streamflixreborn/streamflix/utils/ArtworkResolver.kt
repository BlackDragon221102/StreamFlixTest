package com.streamflixreborn.streamflix.utils

object ArtworkResolver {

    data class Artwork(
        val poster: String?,
        val banner: String?,
        val heroPoster: String?,
        val hasTextlessHero: Boolean,
        val source: Source,
    )

    enum class Source {
        TMDB_IT,
        TMDB_EN,
        PROVIDER,
        MIXED,
        NONE,
    }

    private val tmdbCache = mutableMapOf<String, TmdbUtils.Artwork?>()

    private fun normalize(url: String?): String? = url?.trim()?.takeIf { it.isNotEmpty() }

    fun isTmdbImage(url: String?): Boolean {
        val normalized = normalize(url) ?: return false
        return normalized.contains("image.tmdb.org", ignoreCase = true)
    }

    fun choosePreferredImage(
        primary: String?,
        secondary: String?,
    ): String? {
        val first = normalize(primary)
        val second = normalize(secondary)

        return when {
            first != null && isTmdbImage(first) -> first
            second != null && isTmdbImage(second) -> second
            first != null -> first
            else -> second
        }
    }

    suspend fun resolve(
        showType: String,
        tmdbId: String?,
        title: String,
        year: Int?,
        providerPoster: String?,
        providerBanner: String?,
        allowSearchFallback: Boolean = true,
        requireTextlessHero: Boolean = false,
    ): Artwork? {
        val tmdbIt = resolveTmdbArtwork(
            showType = showType,
            tmdbId = tmdbId,
            title = title,
            year = year,
            language = "it",
            allowSearchFallback = allowSearchFallback,
        )
        val tmdbEn = resolveTmdbArtwork(
            showType = showType,
            tmdbId = tmdbId,
            title = title,
            year = year,
            language = "en",
            allowSearchFallback = allowSearchFallback,
        )

        val tmdbArtwork = mergeLocalizedTmdbArtwork(tmdbIt, tmdbEn)
        if (requireTextlessHero && (tmdbArtwork?.hasTextlessHero != true)) {
            return null
        }

        val poster = choosePreferredImage(tmdbArtwork?.poster, providerPoster)
        val banner = choosePreferredImage(
            tmdbArtwork?.heroPoster ?: tmdbArtwork?.banner,
            providerBanner ?: providerPoster,
        )

        val source = when {
            tmdbArtwork == null && poster == null && banner == null -> Source.NONE
            tmdbArtwork == null -> Source.PROVIDER
            (providerPoster != null || providerBanner != null) &&
                ((poster != null && !isTmdbImage(poster)) || (banner != null && !isTmdbImage(banner))) -> Source.MIXED
            tmdbIt != null -> Source.TMDB_IT
            tmdbEn != null -> Source.TMDB_EN
            else -> Source.MIXED
        }

        return Artwork(
            poster = poster,
            banner = banner,
            heroPoster = banner,
            hasTextlessHero = tmdbArtwork?.hasTextlessHero == true,
            source = source,
        )
    }

    private suspend fun resolveTmdbArtwork(
        showType: String,
        tmdbId: String?,
        title: String,
        year: Int?,
        language: String,
        allowSearchFallback: Boolean,
    ): TmdbUtils.Artwork? {
        val cacheKey = listOf(showType, tmdbId.orEmpty(), title.trim().lowercase(), year?.toString().orEmpty(), language)
            .joinToString(":")

        synchronized(tmdbCache) {
            if (tmdbCache.containsKey(cacheKey)) {
                return tmdbCache[cacheKey]
            }
        }

        val numericId = tmdbId?.toIntOrNull()
        val direct = when (showType) {
            "movie" -> numericId?.let { TmdbUtils.getMovieArtworkById(it, language = language) }
            else -> numericId?.let { TmdbUtils.getTvShowArtworkById(it, language = language) }
        }

        val result = if (shouldSearchFallback(direct, allowSearchFallback)) {
            mergeTmdbResults(
                direct,
                when (showType) {
                    "movie" -> resolveMovieSearchArtwork(title, year, language)
                    else -> resolveTvSearchArtwork(title, year, language)
                }
            )
        } else {
            direct
        }

        synchronized(tmdbCache) {
            tmdbCache[cacheKey] = result
        }
        return result
    }

    private fun shouldSearchFallback(
        direct: TmdbUtils.Artwork?,
        allowSearchFallback: Boolean,
    ): Boolean {
        if (!allowSearchFallback) return false
        return direct == null || direct.poster.isNullOrBlank() || direct.banner.isNullOrBlank() || !direct.hasTextlessHero
    }

    private suspend fun resolveMovieSearchArtwork(
        title: String,
        year: Int?,
        language: String,
    ): TmdbUtils.Artwork? {
        val tmdbMovie = TmdbUtils.getMovie(title, year = year, language = language)
        val enriched = tmdbMovie?.id?.toIntOrNull()?.let {
            TmdbUtils.getMovieArtworkById(it, language = language)
        }
        return mergeTmdbResults(
            enriched,
            tmdbMovie?.let { movie ->
                TmdbUtils.Artwork(
                    poster = movie.poster,
                    banner = movie.banner,
                    heroPoster = movie.banner,
                    hasTextlessHero = false,
                )
            }
        )
    }

    private suspend fun resolveTvSearchArtwork(
        title: String,
        year: Int?,
        language: String,
    ): TmdbUtils.Artwork? {
        val tmdbShow = TmdbUtils.getTvShow(title, year = year, language = language)
        val enriched = tmdbShow?.id?.toIntOrNull()?.let {
            TmdbUtils.getTvShowArtworkById(it, language = language)
        }
        return mergeTmdbResults(
            enriched,
            tmdbShow?.let { show ->
                TmdbUtils.Artwork(
                    poster = show.poster,
                    banner = show.banner,
                    heroPoster = show.banner,
                    hasTextlessHero = false,
                )
            }
        )
    }

    internal fun mergeLocalizedTmdbArtwork(
        italian: TmdbUtils.Artwork?,
        english: TmdbUtils.Artwork?,
    ): TmdbUtils.Artwork? = mergeTmdbResults(italian, english)

    private fun mergeTmdbResults(
        primary: TmdbUtils.Artwork?,
        fallback: TmdbUtils.Artwork?,
    ): TmdbUtils.Artwork? {
        if (primary == null) return fallback
        if (fallback == null) return primary

        return TmdbUtils.Artwork(
            poster = primary.poster ?: fallback.poster,
            banner = primary.banner ?: fallback.banner,
            heroPoster = when {
                primary.hasTextlessHero -> primary.heroPoster ?: primary.banner ?: fallback.heroPoster ?: fallback.banner
                fallback.hasTextlessHero -> fallback.heroPoster ?: primary.heroPoster ?: fallback.banner ?: primary.banner
                else -> primary.heroPoster ?: fallback.heroPoster ?: primary.banner ?: fallback.banner
            },
            hasTextlessHero = primary.hasTextlessHero || fallback.hasTextlessHero,
        )
    }
}
