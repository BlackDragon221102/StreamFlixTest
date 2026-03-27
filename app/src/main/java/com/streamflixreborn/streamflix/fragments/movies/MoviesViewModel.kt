package com.streamflixreborn.streamflix.fragments.movies

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.utils.CatalogSeedUtils
import com.streamflixreborn.streamflix.utils.PrefetchUtils
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.UiCacheStore
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class MoviesViewModel(private val database: AppDatabase) : ViewModel() {
    private val diskCacheKey = "mobile_movies_categories_v2"

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<Movie>()
                    database.movieDao().getByIds(movies.map { it.id })
                        .collect { emit(it) }
                }
                else -> emit(emptyList<Movie>())
            }
        }
    ) { state, moviesDb ->
        when (state) {
            is State.SuccessLoading -> {
                State.SuccessLoading(
                    categories = state.categories.map { category ->
                        category.copy(
                            list = category.list.map { item ->
                                when (item) {
                                    is Movie -> moviesDb.find { it.id == item.id }
                                        ?.let { dbMovie -> mergeMovieForList(item, dbMovie) }
                                        ?: item
                                    else -> item
                                }
                            }
                        ).also { copy ->
                            copy.itemSpacing = category.itemSpacing
                            runCatching { category.itemType }.getOrNull()?.let { copy.itemType = it }
                        }
                    }
                )
            }
            else -> state
        }
    }

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val categories: List<Category>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    private val cacheKey: String
        get() = UserPreferences.currentProvider?.name ?: "default"

    companion object {
        private data class CacheEntry(
            val categories: List<Category>,
            val loadedAtMillis: Long
        )

        private const val CACHE_TTL_MILLIS: Long = 180_000L
        private val cache = mutableMapOf<String, CacheEntry>()
    }

    init {
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                loadNetflixStyleCategories(forceRefresh = true)
            }
        }
        loadNetflixStyleCategories()
    }

    fun loadNetflixStyleCategories(forceRefresh: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        var cachedLoadedAt: Long? = null
        var cached = synchronized(cache) { cache[cacheKey] }
            ?.also { cachedLoadedAt = it.loadedAtMillis }
            ?.takeIf { now - it.loadedAtMillis < CACHE_TTL_MILLIS }
            ?.categories

        if (cached == null) {
            UiCacheStore.loadCategories(diskCacheKey)?.let { (loadedAt, categories) ->
                cachedLoadedAt = loadedAt
                cached = categories
                synchronized(cache) {
                    cache[cacheKey] = CacheEntry(
                        categories = categories,
                        loadedAtMillis = loadedAt
                    )
                }
            }
        }

        if (!forceRefresh && cached != null) {
            _state.emit(State.SuccessLoading(cached))
            viewModelScope.launch(Dispatchers.IO) {
                CatalogSeedUtils.seedFromCategories(database, cached, "seed_movies_${cacheKey}")
            }
            viewModelScope.launch(Dispatchers.IO) {
                PrefetchUtils.prefetchDetails(database, cached, "movies_${cacheKey}")
            }
            val isFresh = cachedLoadedAt?.let { now - it < CACHE_TTL_MILLIS } == true
            if (isFresh) return@launch
        }

        if (cached == null) {
            _state.emit(State.Loading)
        }
        try {
            val provider = UserPreferences.currentProvider!!
            val moviePool: List<Movie> = coroutineScope {
                listOf(1, 2)
                    .map { page ->
                        async { runCatching { provider.getMovies(page) }.getOrDefault(emptyList<Movie>()) }
                    }
                    .awaitAll()
                    .flatten()
                    .distinctBy { it.id }
            }

            if (moviePool.isEmpty()) {
                _state.emit(State.SuccessLoading(emptyList()))
                return@launch
            }

            val baseCategories = mutableListOf<Category>()
            baseCategories.add(Category(name = Category.FEATURED, list = moviePool.sortedByDescending { movie: Movie -> movie.rating ?: 0.0 }.take(10)))
            baseCategories.add(Category("Aggiunti di recente", moviePool.sortedByDescending { movie: Movie -> movie.released?.timeInMillis ?: 0L }.take(30)))
            baseCategories.add(Category("I più votati", moviePool.sortedByDescending { movie: Movie -> movie.rating ?: 0.0 }.drop(10).take(30)))
            baseCategories.add(Category("Da non perdere", moviePool.shuffled().take(30)))

            _state.emit(State.SuccessLoading(baseCategories))

            val genresCategory = runCatching {
                val allGenres = provider.search("", 1).filterIsInstance<Genre>()
                val isStreamingCommunity = provider.name.equals("StreamingCommunity", ignoreCase = true)
                val genreItems = allGenres
                    .filter { !it.id.contains("A-Z", ignoreCase = true) }
                    .filterNot { genre ->
                        genre.id.equals("Tutte le Serie TV", ignoreCase = true) ||
                            genre.name.contains("Tutte le Serie TV", ignoreCase = true)
                    }
                    .distinctBy { it.name.trim().lowercase() }
                    .sortedBy { it.name }
                    .map { genre ->
                        val isAllMoviesEntry =
                            genre.id.equals("Tutti i Film", ignoreCase = true) ||
                                genre.name.contains("Tutti i Film", ignoreCase = true)
                        genre.copy(
                            id = if (isStreamingCommunity && !isAllMoviesEntry) {
                                "Film: ${genre.name}"
                            } else {
                                genre.id
                            },
                            name = genre.name
                        ).also { it.itemType = com.streamflixreborn.streamflix.adapters.AppAdapter.Type.GENRE_MOBILE_ITEM }
                    }

                genreItems.takeIf { it.isNotEmpty() }?.let { items ->
                    Category("Generi", items).also { category ->
                        category.itemSpacing = 10
                    }
                }
            }.getOrNull()

            val finalCategories = (baseCategories + listOfNotNull(genresCategory))
                .filter { it.list.isNotEmpty() }

            synchronized(cache) {
                cache[cacheKey] = CacheEntry(
                    categories = finalCategories,
                    loadedAtMillis = System.currentTimeMillis()
                )
            }
            UiCacheStore.saveCategories(diskCacheKey, finalCategories)

            if (genresCategory != null) {
                _state.emit(State.SuccessLoading(finalCategories))
            }
            viewModelScope.launch(Dispatchers.IO) {
                CatalogSeedUtils.seedFromCategories(database, finalCategories, "seed_movies_${cacheKey}")
            }
            viewModelScope.launch(Dispatchers.IO) {
                PrefetchUtils.prefetchDetails(database, finalCategories, "movies_${cacheKey}")
            }
        } catch (e: Exception) {
            Log.e("MoviesVM", "Errore caricamento: ", e)
            _state.emit(State.FailedLoading(e))
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
}
