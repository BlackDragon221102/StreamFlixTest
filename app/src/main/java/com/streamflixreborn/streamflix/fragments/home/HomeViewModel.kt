package com.streamflixreborn.streamflix.fragments.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.CatalogSeedUtils
import com.streamflixreborn.streamflix.utils.PrefetchUtils
import com.streamflixreborn.streamflix.utils.UiCacheStore
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class HomeViewModel(database: AppDatabase) : ViewModel() {
    private val diskCacheKey = "mobile_home_categories_v1"
    private val db = database
    private var cachedCategories: List<Category>? = null
    private var lastHomeLoadMillis: Long = 0L
    private val homeCacheTtlMillis: Long = 120_000L
    private val cacheKey: String
        get() = UserPreferences.currentProvider?.name ?: "default"

    companion object {
        private data class HomeCacheEntry(
            val categories: List<Category>,
            val loadedAtMillis: Long
        )

        private val homeCache = mutableMapOf<String, HomeCacheEntry>()
    }

    private val _state = MutableStateFlow(initialState())

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        combine(
            database.movieDao().getWatchingMovies(),
            database.episodeDao().getWatchingEpisodes(),
            database.episodeDao().getNextEpisodesToWatch(),
        ) { watchingMovies, watchingEpisodes, watchNextEpisodes ->
            watchingMovies + watchingEpisodes.onEach { episode ->
                episode.tvShow = episode.tvShow?.let { database.tvShowDao().getById(it.id) }
                episode.season = episode.season?.let { database.seasonDao().getById(it.id) }
            } + watchNextEpisodes.onEach { episode ->
                episode.tvShow = episode.tvShow?.let { database.tvShowDao().getById(it.id) }
                episode.season = episode.season?.let { database.seasonDao().getById(it.id) }
            }
        },
        database.movieDao().getFavorites(),
        database.tvShowDao().getFavorites(),
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
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<TvShow>()
                    database.tvShowDao().getByIds(tvShows.map { it.id })
                        .collect { emit(it) }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, continueWatching, favoritesMovies, favoriteTvShows, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val categories = listOfNotNull(
                    state.categories
                        .find { it.name == Category.FEATURED }
                        ?.let { category ->
                            category.copy(
                                list = category.list.map { item ->
                                    when (item) {
                                        is Movie -> moviesDb.find { it.id == item.id }
                                            ?.let { mergeMovieForList(item, it) }
                                            ?: item
                                        is TvShow -> tvShowsDb.find { it.id == item.id }
                                            ?.let { mergeTvShowForList(item, it) }
                                            ?: item
                                        else -> item
                                    }
                                }
                            )
                        },

                    Category(
                        name = Category.CONTINUE_WATCHING,
                        list = continueWatching
                            .sortedByDescending {
                                it.watchHistory?.lastEngagementTimeUtcMillis
                                    ?: it.watchedDate?.timeInMillis
                            }
                            .distinctBy {
                                when (it) {
                                    is Episode -> it.tvShow?.id
                                    else -> false
                                }
                            },
                    ),

                    Category(
                        name = Category.FAVORITE_MOVIES,
                        list = favoritesMovies
                            .reversed(),
                    ),

                    Category(
                        name = Category.FAVORITE_TV_SHOWS,
                        list = favoriteTvShows
                            .reversed(),
                    ),
                ) + state.categories
                    .filter { it.name != Category.FEATURED }
                    .map { category ->
                        category.copy(
                            list = category.list.map { item ->
                                when (item) {
                                    is Movie -> moviesDb.find { it.id == item.id }
                                        ?.let { mergeMovieForList(item, it) }
                                        ?: item
                                    is TvShow -> tvShowsDb.find { it.id == item.id }
                                        ?.let { mergeTvShowForList(item, it) }
                                        ?: item
                                    else -> item
                                }
                            }
                        )
                    }

                State.SuccessLoading(categories)
            }
            else -> state
        }
    }

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val categories: List<Category>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    private fun initialState(): State {
        val sharedCached = synchronized(homeCache) { homeCache[cacheKey] }
        if (sharedCached != null) {
            cachedCategories = sharedCached.categories
            lastHomeLoadMillis = sharedCached.loadedAtMillis
            return State.SuccessLoading(sharedCached.categories)
        }

        UiCacheStore.loadCategories(diskCacheKey)?.let { (loadedAt, categories) ->
            cachedCategories = categories
            lastHomeLoadMillis = loadedAt
            synchronized(homeCache) {
                homeCache[cacheKey] = HomeCacheEntry(
                    categories = categories,
                    loadedAtMillis = loadedAt
                )
            }
            return State.SuccessLoading(categories)
        }

        return State.Loading
    }

    fun hasCachedHome(): Boolean = cachedCategories != null

    fun isCachedHomeFresh(): Boolean {
        if (cachedCategories == null || lastHomeLoadMillis <= 0L) return false
        return (System.currentTimeMillis() - lastHomeLoadMillis) < homeCacheTtlMillis
    }

    fun getHome(forceRefresh: Boolean = false) = viewModelScope.launch(Dispatchers.IO) {
        val sharedCached = synchronized(homeCache) { homeCache[cacheKey] }
        if (cachedCategories == null && sharedCached != null) {
            cachedCategories = sharedCached.categories
            lastHomeLoadMillis = sharedCached.loadedAtMillis
        } else if (cachedCategories == null) {
            UiCacheStore.loadCategories(diskCacheKey)?.let { (loadedAt, categories) ->
                cachedCategories = categories
                lastHomeLoadMillis = loadedAt
                synchronized(homeCache) {
                    homeCache[cacheKey] = HomeCacheEntry(
                        categories = categories,
                        loadedAtMillis = loadedAt
                    )
                }
            }
        }

        val cached = cachedCategories
        val cacheIsFresh = (System.currentTimeMillis() - lastHomeLoadMillis) < homeCacheTtlMillis
        if (!forceRefresh && cached != null) {
            _state.emit(State.SuccessLoading(cached))
            viewModelScope.launch(Dispatchers.IO) {
                CatalogSeedUtils.seedFromCategories(db, cached, "seed_home_${cacheKey}")
            }
            viewModelScope.launch(Dispatchers.IO) {
                PrefetchUtils.prefetchDetails(db, cached, "home_${cacheKey}")
            }
            if (cacheIsFresh) return@launch
        } else {
            _state.emit(State.Loading)
        }

        try {
            val provider = UserPreferences.currentProvider!!
            val categories = provider.getHome().toMutableList()

            val hasEnoughRows = categories
                .filter { it.name != Category.FEATURED }
                .count { it.list.size >= 8 } >= 4

            if (!hasEnoughRows) {
                val (moviePool, tvPool) = coroutineScope {
                    val moviesDeferred = async {
                        runCatching { provider.getMovies(1) }.getOrDefault(emptyList<Movie>())
                    }
                    val tvDeferred = async {
                        runCatching { provider.getTvShows(1) }.getOrDefault(emptyList<TvShow>())
                    }
                    Pair(
                        moviesDeferred.await().distinctBy { it.id },
                        tvDeferred.await().distinctBy { it.id }
                    )
                }

                fun addIfMissing(name: String, items: List<AppAdapter.Item>) {
                    if (items.size < 8) return
                    if (categories.none { it.name.equals(name, ignoreCase = true) }) {
                        categories.add(Category(name, items))
                    }
                }

                addIfMissing("Film popolari", moviePool.sortedByDescending { movie: Movie -> movie.rating ?: 0.0 }.take(25))
                addIfMissing("Film aggiunti di recente", moviePool.sortedByDescending { movie: Movie -> movie.released?.timeInMillis ?: 0L }.take(25))
                addIfMissing("Serie popolari", tvPool.sortedByDescending { tvShow: TvShow -> tvShow.rating ?: 0.0 }.take(25))
                addIfMissing("Serie aggiunte di recente", tvPool.sortedByDescending { tvShow: TvShow -> tvShow.released?.timeInMillis ?: 0L }.take(25))
                addIfMissing("Scelti per te", (moviePool.shuffled().take(12) + tvPool.shuffled().take(12)))
            }

            cachedCategories = categories
            lastHomeLoadMillis = System.currentTimeMillis()
            synchronized(homeCache) {
                homeCache[cacheKey] = HomeCacheEntry(
                    categories = categories,
                    loadedAtMillis = lastHomeLoadMillis
                )
            }
            UiCacheStore.saveCategories(diskCacheKey, categories, lastHomeLoadMillis)
            _state.emit(State.SuccessLoading(categories))
            viewModelScope.launch(Dispatchers.IO) {
                CatalogSeedUtils.seedFromCategories(db, categories, "seed_home_${cacheKey}")
            }
            viewModelScope.launch(Dispatchers.IO) {
                PrefetchUtils.prefetchDetails(db, categories, "home_${cacheKey}")
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "getHome: ", e)
            if (cached != null) {
                _state.emit(State.SuccessLoading(cached))
            } else {
                _state.emit(State.FailedLoading(e))
            }
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
