package com.streamflixreborn.streamflix.repository

import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

enum class GenreSortMode(val label: String, val apiValue: String) {
    RELEASE_DATE("Data di uscita", "release_date"),
    UPDATED_DATE("Data di aggiornamento", "updated_at"),
    ADDED_DATE("Data di aggiunta", "created_at"),
    RATING("Valutazione", "score"),
    VIEWS("Views", "views"),
    NAME("Nome", "name"),
}

interface CatalogRepository {
    val homeState: Flow<HomeCatalogState>
    val moviesState: Flow<MoviesCatalogState>
    val tvShowsState: Flow<TvShowsCatalogState>
    val genreState: Flow<GenreCatalogState>
    val genreSortMode: StateFlow<GenreSortMode>

    fun hasCachedHome(): Boolean

    fun isCachedHomeFresh(): Boolean

    suspend fun refreshHome(
        forceRefresh: Boolean = false,
        silentRefresh: Boolean = false,
    )

    suspend fun refreshMovies(
        forceRefresh: Boolean = false,
        silentRefresh: Boolean = false,
    )

    suspend fun refreshTvShows(
        forceRefresh: Boolean = false,
        silentRefresh: Boolean = false,
    )

    fun setGenreSortMode(mode: GenreSortMode)

    fun showCachedGenre(genreId: String): Boolean

    suspend fun refreshGenre(
        genreId: String,
        forceRefresh: Boolean = false,
    )

    suspend fun loadMoreGenre(genreId: String)
}

sealed interface HomeCatalogState {
    data object Loading : HomeCatalogState
    data class Success(val categories: List<Category>) : HomeCatalogState
    data class Error(val error: Exception) : HomeCatalogState
}

sealed interface MoviesCatalogState {
    data object Loading : MoviesCatalogState
    data class Success(val categories: List<Category>) : MoviesCatalogState
    data class Error(val error: Exception) : MoviesCatalogState
}

sealed interface TvShowsCatalogState {
    data object Loading : TvShowsCatalogState
    data class Success(val categories: List<Category>) : TvShowsCatalogState
    data class Error(val error: Exception) : TvShowsCatalogState
}

sealed interface GenreCatalogState {
    data object Loading : GenreCatalogState
    data class Success(
        val genre: Genre,
        val hasMore: Boolean,
        val sortMode: GenreSortMode,
        val isLoadingMore: Boolean = false,
    ) : GenreCatalogState

    data class Error(
        val error: Exception,
        val cachedGenre: Genre? = null,
        val hasMore: Boolean = false,
        val sortMode: GenreSortMode,
    ) : GenreCatalogState
}
