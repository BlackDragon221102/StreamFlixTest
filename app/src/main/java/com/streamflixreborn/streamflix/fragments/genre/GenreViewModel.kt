package com.streamflixreborn.streamflix.fragments.genre

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.StreamingCommunityProvider
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
class GenreViewModel(private val id: String, database: AppDatabase) : ViewModel() {

    enum class SortMode(val label: String, val apiValue: String) {
        RELEASE_DATE("Data di uscita", "release_date"),
        UPDATED_DATE("Data di aggiornamento", "updated_at"),
        ADDED_DATE("Data di aggiunta", "created_at"),
        RATING("Valutazione", "score"),
        VIEWS("Views", "views"),
        NAME("Nome", "name")
    }

    private val _sourceState = MutableStateFlow<State>(State.Loading)
    private val _sortMode = MutableStateFlow(SortMode.RELEASE_DATE)
    val sortMode: StateFlow<SortMode> = _sortMode.asStateFlow()
    
    init {
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getGenre(id)
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _sourceState,
        _sourceState.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.genre.shows
                        .filterIsInstance<Movie>()
                    database.movieDao().getByIds(movies.map { it.id })
                        .collect { emit(it) }
                }
                else -> emit(emptyList<Movie>())
            }
        },
        _sourceState.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.genre.shows
                        .filterIsInstance<TvShow>()
                    database.tvShowDao().getByIds(tvShows.map { it.id })
                        .collect { emit(it) }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val mergedShows = state.genre.shows.map { item ->
                    when (item) {
                        is Movie -> moviesDb.find { it.id == item.id }
                            ?.let { mergeMovieForList(item, it) }
                            ?: item
                        is TvShow -> tvShowsDb.find { it.id == item.id }
                            ?.let { mergeTvShowForList(item, it) }
                            ?: item
                    }
                }

                State.SuccessLoading(
                    genre = state.genre.copy(shows = mergedShows),
                    hasMore = state.hasMore
                )
            }
            else -> state
        }
    }

    private var page = 1

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val genre: Genre, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getGenre(id)
    }

    fun setSortMode(mode: SortMode) {
        if (_sortMode.value == mode) return
        _sortMode.value = mode
        getGenre(id)
    }

    fun getGenre(id: String) = viewModelScope.launch(Dispatchers.IO) {
        _sourceState.emit(State.Loading)

        try {
            val genre = loadGenre(id, page = 1)

            page = 1

            _sourceState.emit(State.SuccessLoading(genre, true))
        } catch (e: Exception) {
            Log.e("GenreViewModel", "getGenre: ", e)
            _sourceState.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreGenreShows() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _sourceState.first()
        if (currentState is State.SuccessLoading) {
            _sourceState.emit(State.LoadingMore)

            try {
                val genre = loadGenre(id, page = page + 1)

                page += 1

                _sourceState.emit(
                    State.SuccessLoading(
                        genre = Genre(
                            id = genre.id,
                            name = genre.name,

                            shows = currentState.genre.shows + genre.shows,
                        ),
                        hasMore = genre.shows.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("GenreViewModel", "loadMoreGenreShows: ", e)
                _sourceState.emit(State.FailedLoading(e))
            }
        }
    }

    private suspend fun loadGenre(id: String, page: Int): Genre {
        val provider = UserPreferences.currentProvider
            ?: error("Provider non disponibile")

        return if (provider is StreamingCommunityProvider) {
            provider.getGenre(id, page, _sortMode.value.apiValue)
        } else {
            provider.getGenre(id, page)
        }
    }

    private fun mergeMovieForList(item: Movie, dbMovie: Movie): Movie {
        return item.copy(
            poster = dbMovie.poster ?: item.poster,
            isFavorite = dbMovie.isFavorite
        ).also { merged ->
            merged.isWatched = dbMovie.isWatched
            merged.watchedDate = dbMovie.watchedDate
            merged.watchHistory = dbMovie.watchHistory
            runCatching { item.itemType }.getOrNull()?.let { merged.itemType = it }
        }
    }

    private fun mergeTvShowForList(item: TvShow, dbShow: TvShow): TvShow {
        return item.copy(
            poster = dbShow.poster ?: item.poster,
            isFavorite = dbShow.isFavorite
        ).also { merged ->
            merged.isWatching = dbShow.isWatching
            runCatching { item.itemType }.getOrNull()?.let { merged.itemType = it }
        }
    }
}
