package com.streamflixreborn.streamflix.repository

import android.util.Log
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.WatchItem
import com.streamflixreborn.streamflix.utils.CatalogRefreshUtils
import com.streamflixreborn.streamflix.utils.UiCacheStore
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.util.Calendar

class DefaultUserContentRepository(
    private val database: AppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : UserContentRepository {

    override val favoriteMovies: Flow<List<Movie>> = database.movieDao().getFavorites()
    override val favoriteTvShows: Flow<List<TvShow>> = database.tvShowDao().getFavorites()
    override val watchingMovies: Flow<List<Movie>> = database.movieDao().getWatchingMovies()
    override val watchingEpisodes: Flow<List<Episode>> = database.episodeDao().getWatchingEpisodes()

    override suspend fun toggleMovieFavorite(movie: Movie): Movie = withContext(ioDispatcher) {
        val persisted = database.movieDao().getById(movie.id)
            ?.copy()
            ?.mergeCatalogFrom(movie)
            ?.applyUserStateFrom(movie)
            ?: movie.copy()
        persisted.isFavorite = !persisted.isFavorite
        persisted.favoriteAddedAtUtcMillis = if (persisted.isFavorite) {
            System.currentTimeMillis()
        } else {
            null
        }
        Log.d("UserContentRepository", "Movie favorite toggled: id=${persisted.id}, favorite=${persisted.isFavorite}, favoriteAddedAtUtcMillis=${persisted.favoriteAddedAtUtcMillis}")
        database.movieDao().save(persisted)
        persisted
    }

    override suspend fun toggleTvShowFavorite(tvShow: TvShow): TvShow = withContext(ioDispatcher) {
        val persisted = database.tvShowDao().getById(tvShow.id)
            ?.copy()
            ?.mergeCatalogFrom(tvShow)
            ?.applyUserStateFrom(tvShow)
            ?: tvShow.copy()
        persisted.isFavorite = !persisted.isFavorite
        persisted.favoriteAddedAtUtcMillis = if (persisted.isFavorite) {
            System.currentTimeMillis()
        } else {
            null
        }
        Log.d("UserContentRepository", "TvShow favorite toggled: id=${persisted.id}, favorite=${persisted.isFavorite}, favoriteAddedAtUtcMillis=${persisted.favoriteAddedAtUtcMillis}")
        database.tvShowDao().save(persisted)
        persisted
    }

    override suspend fun setMovieWatched(movie: Movie, watched: Boolean): Movie = withContext(ioDispatcher) {
        val persisted = database.movieDao().getById(movie.id)
            ?.copy()
            ?.mergeCatalogFrom(movie)
            ?.applyUserStateFrom(movie)
            ?: movie.copy()
        persisted.isWatched = watched
        persisted.watchedDate = if (watched) Calendar.getInstance() else null
        if (watched) {
            persisted.watchHistory = null
        }
        database.movieDao().save(persisted)
        persisted
    }

    override suspend fun clearMovieProgress(movie: Movie): Movie = withContext(ioDispatcher) {
        val persisted = database.movieDao().getById(movie.id)
            ?.copy()
            ?.mergeCatalogFrom(movie)
            ?.applyUserStateFrom(movie)
            ?: movie.copy()
        persisted.watchHistory = null
        database.movieDao().save(persisted)
        persisted
    }

    override suspend fun saveMovieProgress(
        movie: Movie,
        history: WatchItem.WatchHistory?,
        watched: Boolean,
        watchedDate: Calendar?,
    ): Movie = withContext(ioDispatcher) {
        val persisted = database.movieDao().getById(movie.id)
            ?.copy()
            ?.mergeCatalogFrom(movie)
            ?.applyUserStateFrom(movie)
            ?: movie.copy()
        persisted.isWatched = watched
        persisted.watchedDate = watchedDate
        persisted.watchHistory = history
        database.movieDao().save(persisted)
        persisted
    }

    override suspend fun setEpisodeWatched(episode: Episode, watched: Boolean): Episode = withContext(ioDispatcher) {
        val persisted = database.episodeDao().getById(episode.id)
            ?.copy()
            ?.mergeCatalogFrom(episode)
            ?.applyUserStateFrom(episode)
            ?: episode.copy()
        persisted.isWatched = watched
        persisted.watchedDate = if (watched) Calendar.getInstance() else null
        if (watched) {
            persisted.watchHistory = null
        }
        database.episodeDao().save(persisted)
        persisted
    }

    override suspend fun clearEpisodeProgress(episode: Episode): Episode = withContext(ioDispatcher) {
        val persisted = database.episodeDao().getById(episode.id)
            ?.copy()
            ?.mergeCatalogFrom(episode)
            ?.applyUserStateFrom(episode)
            ?: episode.copy()
        persisted.watchHistory = null
        database.episodeDao().save(persisted)
        persisted
    }

    override suspend fun saveEpisodeProgress(
        episode: Episode,
        history: WatchItem.WatchHistory?,
        watched: Boolean,
        watchedDate: Calendar?,
    ): Episode = withContext(ioDispatcher) {
        val persisted = database.episodeDao().getById(episode.id)
            ?.copy()
            ?.mergeCatalogFrom(episode)
            ?.applyUserStateFrom(episode)
            ?: episode.copy()
        persisted.isWatched = watched
        persisted.watchedDate = watchedDate
        persisted.watchHistory = history
        database.episodeDao().save(persisted)
        persisted
    }

    override suspend fun markEpisodesUpTo(episode: Episode, watched: Boolean) = withContext(ioDispatcher) {
        val tvShowId = episode.tvShow?.id ?: return@withContext
        val now = Calendar.getInstance()
        database.episodeDao().getEpisodesByTvShowId(tvShowId).forEach { candidate ->
            if (candidate.number <= episode.number && candidate.isWatched != watched) {
                candidate.isWatched = watched
                candidate.watchedDate = if (watched) now else null
                if (watched) {
                    candidate.watchHistory = null
                }
                database.episodeDao().save(candidate)
            }
        }
    }

    override suspend fun setTvShowWatching(tvShow: TvShow, isWatching: Boolean): TvShow = withContext(ioDispatcher) {
        val persisted = database.tvShowDao().getById(tvShow.id)
            ?.copy()
            ?.mergeCatalogFrom(tvShow)
            ?.applyUserStateFrom(tvShow)
            ?: tvShow.copy()
        persisted.isWatching = isWatching
        database.tvShowDao().save(persisted)
        persisted
    }

    override suspend fun clearCatalogCache() = withContext(ioDispatcher) {
        UiCacheStore.clearCategories(
            CatalogRefreshUtils.HOME_CACHE_KEY,
            CatalogRefreshUtils.MOVIES_CACHE_KEY,
            CatalogRefreshUtils.TV_CACHE_KEY,
        )
        database.episodeDao().deleteCatalogOnlyEntries()
        database.movieDao().deleteCatalogOnlyEntries()
        database.tvShowDao().deleteCatalogOnlyEntries()
    }
}
