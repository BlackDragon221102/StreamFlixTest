package com.streamflixreborn.streamflix.repository

import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.WatchItem
import kotlinx.coroutines.flow.Flow
import java.util.Calendar

interface UserContentRepository {
    val favoriteMovies: Flow<List<Movie>>
    val favoriteTvShows: Flow<List<TvShow>>
    val watchingMovies: Flow<List<Movie>>
    val watchingEpisodes: Flow<List<Episode>>

    suspend fun toggleMovieFavorite(movie: Movie): Movie
    suspend fun toggleTvShowFavorite(tvShow: TvShow): TvShow

    suspend fun setMovieWatched(movie: Movie, watched: Boolean): Movie
    suspend fun clearMovieProgress(movie: Movie): Movie
    suspend fun saveMovieProgress(
        movie: Movie,
        history: WatchItem.WatchHistory?,
        watched: Boolean,
        watchedDate: Calendar?,
    ): Movie

    suspend fun setEpisodeWatched(episode: Episode, watched: Boolean): Episode
    suspend fun clearEpisodeProgress(episode: Episode): Episode
    suspend fun saveEpisodeProgress(
        episode: Episode,
        history: WatchItem.WatchHistory?,
        watched: Boolean,
        watchedDate: Calendar?,
    ): Episode

    suspend fun markEpisodesUpTo(episode: Episode, watched: Boolean)
    suspend fun setTvShowWatching(tvShow: TvShow, isWatching: Boolean): TvShow
    suspend fun clearCatalogCache()
}
