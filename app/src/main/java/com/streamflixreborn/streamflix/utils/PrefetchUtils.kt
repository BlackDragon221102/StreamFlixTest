package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object PrefetchUtils {

    private const val PREFETCH_COOLDOWN_MS = 90_000L
    private const val MAX_MOVIES = 1
    private const val MAX_TV_SHOWS = 1
    private const val NETWORK_TIMEOUT_MS = 6_000L

    private val semaphore = Semaphore(1)
    private val prefetchTimestamps = mutableMapOf<String, Long>()

    suspend fun prefetchDetails(
        database: AppDatabase,
        categories: List<Category>,
        key: String
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val shouldRun = synchronized(prefetchTimestamps) {
            val last = prefetchTimestamps[key] ?: 0L
            if (now - last < PREFETCH_COOLDOWN_MS) {
                false
            } else {
                prefetchTimestamps[key] = now
                true
            }
        }
        if (!shouldRun) return@withContext

        semaphore.withPermit {
            val provider = UserPreferences.currentProvider ?: return@withPermit

            val movies = categories
                .flatMap { it.list }
                .filterIsInstance<Movie>()
                .distinctBy { it.id }
                .take(MAX_MOVIES)

            for (movie in movies) {
                val inDb = database.movieDao().getById(movie.id)
                if (inDb?.overview.isNullOrBlank()) {
                    val fetched = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                        runCatching { provider.getMovie(movie.id) }.getOrNull()
                    }
                    fetched?.also { fullMovie ->
                        inDb?.let { fullMovie.applyUserStateFrom(it) }
                        database.movieDao().upsertCatalog(fullMovie)
                    }
                }
            }

            val tvShows = categories
                .flatMap { it.list }
                .filterIsInstance<TvShow>()
                .distinctBy { it.id }
                .take(MAX_TV_SHOWS)

            for (tvShow in tvShows) {
                val inDb = database.tvShowDao().getById(tvShow.id)
                if (inDb?.overview.isNullOrBlank()) {
                    val fetched = withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
                        runCatching { provider.getTvShow(tvShow.id) }.getOrNull()
                    }
                    fetched?.also { fullTvShow ->
                        inDb?.let { fullTvShow.applyUserStateFrom(it) }
                        database.tvShowDao().upsertCatalog(fullTvShow)

                        val tvCopy = fullTvShow.copy()
                        fullTvShow.seasons.forEach { season ->
                            season.tvShow = tvCopy
                        }
                        database.seasonDao().insertAll(fullTvShow.seasons)
                    }
                }
            }
        }
    }
}

