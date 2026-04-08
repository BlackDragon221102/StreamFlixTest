package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object CatalogSeedUtils {

    private const val COOLDOWN_MS = 30_000L
    private val lastSeedAt = mutableMapOf<String, Long>()

    suspend fun seedFromCategories(
        database: AppDatabase,
        categories: List<Category>,
        key: String,
        maxMovies: Int = 80,
        maxTvShows: Int = 80
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val canRun = synchronized(lastSeedAt) {
            val last = lastSeedAt[key] ?: 0L
            if (now - last < COOLDOWN_MS) {
                false
            } else {
                lastSeedAt[key] = now
                true
            }
        }
        if (!canRun) return@withContext

        val movies = categories
            .flatMap { it.list }
            .filterIsInstance<Movie>()
            .distinctBy { it.id }
            .take(maxMovies)

        movies.forEach { movie ->
            val local = database.movieDao().getById(movie.id)
            if (local == null) {
                database.movieDao().upsertCatalog(movie.copy())
            } else {
                val shouldUpdate =
                    ArtworkResolver.choosePreferredImage(movie.poster, local.poster) != local.poster ||
                    ArtworkResolver.choosePreferredImage(movie.banner, local.banner) != local.banner ||
                    (local.rating == null && movie.rating != null)

                if (shouldUpdate) {
                    val enrichedLocal = local.copy(
                        poster = ArtworkResolver.choosePreferredImage(movie.poster, local.poster),
                        banner = ArtworkResolver.choosePreferredImage(movie.banner, local.banner),
                        rating = local.rating ?: movie.rating,
                        title = local.title.ifBlank { movie.title }
                    )
                    database.movieDao().upsertCatalog(enrichedLocal)
                }
            }
        }

        val tvShows = categories
            .flatMap { it.list }
            .filterIsInstance<TvShow>()
            .distinctBy { it.id }
            .take(maxTvShows)

        tvShows.forEach { tvShow ->
            val local = database.tvShowDao().getById(tvShow.id)
            if (local == null) {
                database.tvShowDao().upsertCatalog(tvShow.copy())
            } else {
                val shouldUpdate =
                    ArtworkResolver.choosePreferredImage(tvShow.poster, local.poster) != local.poster ||
                    ArtworkResolver.choosePreferredImage(tvShow.banner, local.banner) != local.banner ||
                    (local.rating == null && tvShow.rating != null)

                if (shouldUpdate) {
                    val enrichedLocal = local.copy(
                        poster = ArtworkResolver.choosePreferredImage(tvShow.poster, local.poster),
                        banner = ArtworkResolver.choosePreferredImage(tvShow.banner, local.banner),
                        rating = local.rating ?: tvShow.rating,
                        title = local.title.ifBlank { tvShow.title }
                    )
                    database.tvShowDao().upsertCatalog(enrichedLocal)
                }
            }
        }
    }
}

