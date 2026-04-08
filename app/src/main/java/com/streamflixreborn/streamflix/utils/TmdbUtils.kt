package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500

object TmdbUtils {

    data class Artwork(
        val poster: String?,
        val banner: String?,
        val heroPoster: String?,
        val hasTextlessHero: Boolean,
    )

    private fun pickTextlessImagePathWithFallback(
        defaultPath: String?,
        images: List<TMDb3.Images.FileImage>?,
        language: String?,
    ): String? {
        if (images.isNullOrEmpty()) return defaultPath

        val textless = images
            .asSequence()
            .filter { it.iso639 == null }
            .sortedByDescending { it.voteAverage ?: 0f }
            .map { it.filePath }
            .firstOrNull()

        return textless
            ?: TMDb3.pickImagePathWithFallback(defaultPath, images, language)
            ?: defaultPath
    }

    private fun pickTextlessImagePath(
        images: List<TMDb3.Images.FileImage>?,
    ): String? {
        if (images.isNullOrEmpty()) return null
        return images
            .asSequence()
            .filter { it.iso639 == null }
            .sortedByDescending { it.voteAverage ?: 0f }
            .map { it.filePath }
            .firstOrNull()
    }

    suspend fun getMovieArtworkById(id: Int, language: String? = null): Artwork? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val details = TMDb3.Movies.details(
                movieId = id,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Movie.IMAGES,
                ),
                language = language,
                includeImageLanguage = TMDb3.italianIncludeImageLanguage(language),
            )

            val localizedPoster = TMDb3.pickImagePathWithFallback(
                defaultPath = details.posterPath,
                images = details.images?.posters,
                language = language
            )?.original

            val localizedBanner = TMDb3.pickImagePathWithFallback(
                defaultPath = details.backdropPath,
                images = details.images?.backdrops,
                language = language
            )?.original

            val textlessHeroPoster = pickTextlessImagePathWithFallback(
                defaultPath = details.posterPath,
                images = details.images?.posters,
                language = language
            )?.original

            val textlessBackdrop = pickTextlessImagePath(
                images = details.images?.backdrops
            )?.original

            Artwork(
                poster = localizedPoster,
                banner = localizedBanner,
                heroPoster = textlessBackdrop ?: textlessHeroPoster ?: localizedBanner,
                hasTextlessHero = (textlessBackdrop != null || textlessHeroPoster != null),
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getTvShowArtworkById(id: Int, language: String? = null): Artwork? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val details = TMDb3.TvSeries.details(
                seriesId = id,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Tv.IMAGES,
                ),
                language = language,
                includeImageLanguage = TMDb3.italianIncludeImageLanguage(language),
            )

            val localizedPoster = TMDb3.pickImagePathWithFallback(
                defaultPath = details.posterPath,
                images = details.images?.posters,
                language = language
            )?.original

            val localizedBanner = TMDb3.pickImagePathWithFallback(
                defaultPath = details.backdropPath,
                images = details.images?.backdrops,
                language = language
            )?.original

            val textlessHeroPoster = pickTextlessImagePathWithFallback(
                defaultPath = details.posterPath,
                images = details.images?.posters,
                language = language
            )?.original

            val textlessBackdrop = pickTextlessImagePath(
                images = details.images?.backdrops
            )?.original

            Artwork(
                poster = localizedPoster,
                banner = localizedBanner,
                heroPoster = textlessBackdrop ?: textlessHeroPoster ?: localizedBanner,
                hasTextlessHero = (textlessBackdrop != null || textlessHeroPoster != null),
            )
        } catch (_: Exception) {
            null
        }
    }

    suspend fun getMovie(title: String, year: Int? = null, language: String? = null): Movie? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val results = TMDb3.Search.multi(title, language = language).results.filterIsInstance<TMDb3.Movie>()
            val movie = results.find {
                it.title.equals(title, ignoreCase = true) && (year == null || it.releaseDate?.contains(year.toString()) == true)
            } ?: results.firstOrNull() ?: return null

            val details = TMDb3.Movies.details(
                movieId = movie.id,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Movie.CREDITS,
                    TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                    TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
                    TMDb3.Params.AppendToResponse.Movie.IMAGES,
                ),
                language = language,
                includeImageLanguage = TMDb3.italianIncludeImageLanguage(language),
            )

            Movie(
                id = details.id.toString(),
                title = details.title,
                overview = details.overview,
                released = details.releaseDate,
                runtime = details.runtime,
                trailer = details.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = details.voteAverage.toDouble(),
                poster = TMDb3.pickImagePathWithFallback(
                    defaultPath = details.posterPath,
                    images = details.images?.posters,
                    language = language
                )?.original,
                banner = TMDb3.pickImagePathWithFallback(
                    defaultPath = details.backdropPath,
                    images = details.images?.backdrops,
                    language = language
                )?.original,
                imdbId = details.externalIds?.imdbId,
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast?.map { People(it.id.toString(), it.name, it.profilePath?.w500) } ?: listOf(),
            )
        } catch (_: Exception) { null }
    }

    suspend fun getTvShow(title: String, year: Int? = null, language: String? = null): TvShow? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val results = TMDb3.Search.multi(title, language = language).results.filterIsInstance<TMDb3.Tv>()
            val tv = results.find {
                it.name.equals(title, ignoreCase = true) && (year == null || it.firstAirDate?.contains(year.toString()) == true)
            } ?: results.firstOrNull() ?: return null

            val details = TMDb3.TvSeries.details(
                seriesId = tv.id,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Tv.CREDITS,
                    TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                    TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
                    TMDb3.Params.AppendToResponse.Tv.IMAGES,
                ),
                language = language,
                includeImageLanguage = TMDb3.italianIncludeImageLanguage(language),
            )

            TvShow(
                id = details.id.toString(),
                title = details.name,
                overview = details.overview,
                released = details.firstAirDate,
                trailer = details.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = details.voteAverage.toDouble(),
                poster = TMDb3.pickImagePathWithFallback(
                    defaultPath = details.posterPath,
                    images = details.images?.posters,
                    language = language
                )?.original,
                banner = TMDb3.pickImagePathWithFallback(
                    defaultPath = details.backdropPath,
                    images = details.images?.backdrops,
                    language = language
                )?.original,
                imdbId = details.externalIds?.imdbId,
                seasons = details.seasons.map {
                    Season(
                        id = "${details.id}-${it.seasonNumber}",
                        number = it.seasonNumber,
                        title = it.name,
                        poster = it.posterPath?.w500,
                    )
                },
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast?.map { People(it.id.toString(), it.name, it.profilePath?.w500) } ?: listOf(),
            )
        } catch (_: Exception) { null }
    }

    suspend fun getEpisodesBySeason(tvShowId: String, seasonNumber: Int, language: String? = null): List<Episode> {
        if (!UserPreferences.enableTmdb) return listOf()
        return try {
            TMDb3.TvSeasons.details(
                seriesId = tvShowId.toInt(),
                seasonNumber = seasonNumber,
                language = language
            ).episodes?.map {
                Episode(
                    id = it.id.toString(),
                    number = it.episodeNumber,
                    title = it.name ?: "",
                    released = it.airDate,
                    poster = it.stillPath?.w500,
                    overview = it.overview,
                )
            } ?: listOf()
        } catch (_: Exception) { listOf() }
    }

    suspend fun getMovieById(id: Int, language: String? = null): Movie? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val details = TMDb3.Movies.details(
                movieId = id,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Movie.CREDITS,
                    TMDb3.Params.AppendToResponse.Movie.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Movie.VIDEOS,
                    TMDb3.Params.AppendToResponse.Movie.EXTERNAL_IDS,
                    TMDb3.Params.AppendToResponse.Movie.IMAGES,
                ),
                language = language,
                includeImageLanguage = TMDb3.italianIncludeImageLanguage(language),
            )

            Movie(
                id = details.id.toString(),
                title = details.title,
                overview = details.overview,
                released = details.releaseDate,
                runtime = details.runtime,
                trailer = details.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = details.voteAverage.toDouble(),
                poster = TMDb3.pickImagePathWithFallback(
                    defaultPath = details.posterPath,
                    images = details.images?.posters,
                    language = language
                )?.original,
                banner = TMDb3.pickImagePathWithFallback(
                    defaultPath = details.backdropPath,
                    images = details.images?.backdrops,
                    language = language
                )?.original,
                imdbId = details.externalIds?.imdbId,
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast?.map { People(it.id.toString(), it.name, it.profilePath?.w500) } ?: listOf(),
            )
        } catch (_: Exception) { null }
    }

    suspend fun getTvShowById(id: Int, language: String? = null): TvShow? {
        if (!UserPreferences.enableTmdb) return null
        return try {
            val details = TMDb3.TvSeries.details(
                seriesId = id,
                appendToResponse = listOf(
                    TMDb3.Params.AppendToResponse.Tv.CREDITS,
                    TMDb3.Params.AppendToResponse.Tv.RECOMMENDATIONS,
                    TMDb3.Params.AppendToResponse.Tv.VIDEOS,
                    TMDb3.Params.AppendToResponse.Tv.EXTERNAL_IDS,
                    TMDb3.Params.AppendToResponse.Tv.IMAGES,
                ),
                language = language,
                includeImageLanguage = TMDb3.italianIncludeImageLanguage(language),
            )

            TvShow(
                id = details.id.toString(),
                title = details.name,
                overview = details.overview,
                released = details.firstAirDate,
                trailer = details.videos?.results
                    ?.sortedBy { it.publishedAt ?: "" }
                    ?.firstOrNull { it.site == TMDb3.Video.VideoSite.YOUTUBE }
                    ?.let { "https://www.youtube.com/watch?v=${it.key}" },
                rating = details.voteAverage.toDouble(),
                poster = TMDb3.pickImagePathWithFallback(
                    defaultPath = details.posterPath,
                    images = details.images?.posters,
                    language = language
                )?.original,
                banner = TMDb3.pickImagePathWithFallback(
                    defaultPath = details.backdropPath,
                    images = details.images?.backdrops,
                    language = language
                )?.original,
                imdbId = details.externalIds?.imdbId,
                seasons = details.seasons.map {
                    Season(
                        id = "${details.id}-${it.seasonNumber}",
                        number = it.seasonNumber,
                        title = it.name,
                        poster = it.posterPath?.w500,
                    )
                },
                genres = details.genres.map { Genre(it.id.toString(), it.name) },
                cast = details.credits?.cast?.map { People(it.id.toString(), it.name, it.profilePath?.w500) } ?: listOf(),
            )
        } catch (_: Exception) { null }
    }
}
