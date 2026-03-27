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
                database.movieDao().insert(movie.copy())
            } else {
                val shouldUpdate =
                    (local.poster.isNullOrBlank() && !movie.poster.isNullOrBlank()) ||
                    (local.banner.isNullOrBlank() && !movie.banner.isNullOrBlank()) ||
                    (local.rating == null && movie.rating != null)

                if (shouldUpdate) {
                    val enrichedLocal = local.copy(
                        poster = local.poster ?: movie.poster,
                        banner = local.banner ?: movie.banner,
                        rating = local.rating ?: movie.rating,
                        title = local.title.ifBlank { movie.title }
                    )
                    database.movieDao().insert(enrichedLocal)
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
                database.tvShowDao().insert(tvShow.copy())
            } else {
                val shouldUpdate =
                    (local.poster.isNullOrBlank() && !tvShow.poster.isNullOrBlank()) ||
                    (local.banner.isNullOrBlank() && !tvShow.banner.isNullOrBlank()) ||
                    (local.rating == null && tvShow.rating != null)

                if (shouldUpdate) {
                    val enrichedLocal = local.copy(
                        poster = local.poster ?: tvShow.poster,
                        banner = local.banner ?: tvShow.banner,
                        rating = local.rating ?: tvShow.rating,
                        title = local.title.ifBlank { tvShow.title }
                    )
                    database.tvShowDao().insert(enrichedLocal)
                }
            }
        }
    }
}
