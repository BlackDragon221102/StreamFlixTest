
package com.streamflixreborn.streamflix.repository

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.StreamingCommunityProvider
import com.streamflixreborn.streamflix.utils.ArtworkResolver
import com.streamflixreborn.streamflix.utils.CatalogRefreshUtils
import com.streamflixreborn.streamflix.utils.CatalogSeedUtils
import com.streamflixreborn.streamflix.utils.PrefetchUtils
import com.streamflixreborn.streamflix.utils.UiCacheStore
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.combine
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

class DefaultCatalogRepository(
    private val database: AppDatabase,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CatalogRepository {

    private val homeDiskCacheKey = CatalogRefreshUtils.HOME_CACHE_KEY
    private val moviesDiskCacheKey = CatalogRefreshUtils.MOVIES_CACHE_KEY
    private val tvShowsDiskCacheKey = CatalogRefreshUtils.TV_CACHE_KEY
    private val cacheTtlMillis: Long = 180_000L
    private val homeCacheTtlMillis: Long = 120_000L

    private val rawHomeState = MutableStateFlow(initialHomeState())
    private val rawMoviesState = MutableStateFlow(initialMoviesState())
    private val rawTvShowsState = MutableStateFlow(initialTvShowsState())
    private val rawGenreState = MutableStateFlow<GenrePageState>(GenrePageState.Idle)
    private val _genreSortMode = MutableStateFlow(GenreSortMode.RELEASE_DATE)

    private var cachedCategories: List<Category>? = null
    private var lastHomeLoadMillis: Long = 0L
    private var cachedMovieCategories: List<Category>? = null
    private var lastMoviesLoadMillis: Long = 0L
    private var cachedTvShowCategories: List<Category>? = null
    private var lastTvShowsLoadMillis: Long = 0L

    private val cacheKey: String
        get() = UserPreferences.currentProvider?.name ?: "default"

    companion object {
        private data class HomeCacheEntry(
            val categories: List<Category>,
            val loadedAtMillis: Long,
        )

        private data class CategoryCacheEntry(
            val categories: List<Category>,
            val loadedAtMillis: Long,
        )

        private data class GenreCacheEntry(
            val genre: Genre,
            val page: Int,
            val hasMore: Boolean,
            val loadedAtMillis: Long,
        )

        private val homeCache = mutableMapOf<String, HomeCacheEntry>()
        private val moviesCache = mutableMapOf<String, CategoryCacheEntry>()
        private val tvShowsCache = mutableMapOf<String, CategoryCacheEntry>()
        private val genreCache = mutableMapOf<String, GenreCacheEntry>()
    }

    private sealed interface GenrePageState {
        data object Idle : GenrePageState
        data object Loading : GenrePageState
        data class Success(
            val genre: Genre,
            val page: Int,
            val hasMore: Boolean,
            val isLoadingMore: Boolean,
        ) : GenrePageState

        data class Error(
            val error: Exception,
            val cachedGenre: Genre?,
            val page: Int,
            val hasMore: Boolean,
        ) : GenrePageState
    }

    override val genreSortMode: StateFlow<GenreSortMode> = _genreSortMode.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val homeState: Flow<HomeCatalogState> = combine(
        rawHomeState,
        combine(
            database.movieDao().getWatchingMovies(),
            database.episodeDao().getWatchingEpisodes(),
            database.episodeDao().getNextEpisodesToWatch(),
        ) { watchingMovies, watchingEpisodes, nextEpisodes ->
            watchingMovies + watchingEpisodes.onEach { episode ->
                episode.tvShow = episode.tvShow?.let { database.tvShowDao().getById(it.id) }
                episode.season = episode.season?.let { database.seasonDao().getById(it.id) }
            } + nextEpisodes.onEach { episode ->
                episode.tvShow = episode.tvShow?.let { database.tvShowDao().getById(it.id) }
                episode.season = episode.season?.let { database.seasonDao().getById(it.id) }
            }
        },
        database.movieDao().getFavorites(),
        database.tvShowDao().getFavorites(),
        rawHomeState.transformLatest { state ->
            when (state) {
                is HomeCatalogState.Success -> {
                    val movies = state.categories.flatMap { it.list }.filterIsInstance<Movie>()
                    database.movieDao().getByIds(movies.map { it.id }).collect { emit(it) }
                }
                else -> emit(emptyList<Movie>())
            }
        },
        rawHomeState.transformLatest { state ->
            when (state) {
                is HomeCatalogState.Success -> {
                    val shows = state.categories.flatMap { it.list }.filterIsInstance<TvShow>()
                    database.tvShowDao().getByIds(shows.map { it.id }).collect { emit(it) }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, continueWatching, favoriteMovies, favoriteTvShows, moviesDb, tvShowsDb ->
        when (state) {
            is HomeCatalogState.Success -> {
                val categories = listOfNotNull(
                    state.categories.find { it.name == Category.FEATURED }?.let { category ->
                        category.copy(
                            list = category.list.map { item ->
                                when (item) {
                                    is Movie -> moviesDb.find { it.id == item.id }?.let { mergeMovieForList(item, it) } ?: item
                                    is TvShow -> tvShowsDb.find { it.id == item.id }?.let { mergeTvShowForList(item, it) } ?: item
                                    else -> item
                                }
                            },
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
                                    is Episode -> "episode:${it.tvShow?.id ?: it.id}"
                                    is Movie -> "movie:${it.id}"
                                    else -> "item:${it.hashCode()}"
                                }
                            },
                    ),
                    Category(
                        name = Category.FAVORITE_MOVIES,
                        list = favoriteMovies.sortedByDescending { it.favoriteAddedAtUtcMillis ?: 0L },
                    ),
                    Category(
                        name = Category.FAVORITE_TV_SHOWS,
                        list = favoriteTvShows.sortedByDescending { it.favoriteAddedAtUtcMillis ?: 0L },
                    ),
                ) + state.categories
                    .filter { it.name != Category.FEATURED }
                    .map { category ->
                        category.copy(
                            list = category.list.map { item ->
                                when (item) {
                                    is Movie -> moviesDb.find { it.id == item.id }?.let { mergeMovieForList(item, it) } ?: item
                                    is TvShow -> tvShowsDb.find { it.id == item.id }?.let { mergeTvShowForList(item, it) } ?: item
                                    else -> item
                                }
                            },
                        )
                    }

                HomeCatalogState.Success(categories)
            }
            else -> state
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val moviesState: Flow<MoviesCatalogState> = combine(
        rawMoviesState,
        rawMoviesState.transformLatest { state ->
            when (state) {
                is MoviesCatalogState.Success -> {
                    val movies = state.categories.flatMap { it.list }.filterIsInstance<Movie>()
                    database.movieDao().getByIds(movies.map { it.id }).collect { emit(it) }
                }
                else -> emit(emptyList<Movie>())
            }
        },
    ) { state, moviesDb ->
        when (state) {
            is MoviesCatalogState.Success -> MoviesCatalogState.Success(
                state.categories.map { category ->
                    category.copy(
                        list = category.list.map { item ->
                            when (item) {
                                is Movie -> moviesDb.find { it.id == item.id }?.let { mergeMovieForList(item, it) } ?: item
                                else -> item
                            }
                        },
                    ).also { copy ->
                        copy.itemSpacing = category.itemSpacing
                        runCatching { category.itemType }.getOrNull()?.let { copy.itemType = it }
                    }
                }
            )
            else -> state
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val tvShowsState: Flow<TvShowsCatalogState> = combine(
        rawTvShowsState,
        rawTvShowsState.transformLatest { state ->
            when (state) {
                is TvShowsCatalogState.Success -> {
                    val tvShows = state.categories.flatMap { it.list }.filterIsInstance<TvShow>()
                    database.tvShowDao().getByIds(tvShows.map { it.id }).collect { emit(it) }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, tvShowsDb ->
        when (state) {
            is TvShowsCatalogState.Success -> TvShowsCatalogState.Success(
                state.categories.map { category ->
                    category.copy(
                        list = category.list.map { item ->
                            when (item) {
                                is TvShow -> tvShowsDb.find { it.id == item.id }?.let { mergeTvShowForList(item, it) } ?: item
                                else -> item
                            }
                        },
                    ).also { copy ->
                        copy.itemSpacing = category.itemSpacing
                        runCatching { category.itemType }.getOrNull()?.let { copy.itemType = it }
                    }
                }
            )
            else -> state
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override val genreState: Flow<GenreCatalogState> = combine(
        rawGenreState,
        genreSortMode,
        rawGenreState.transformLatest { state ->
            when (state) {
                is GenrePageState.Success -> {
                    val movies = state.genre.shows.filterIsInstance<Movie>()
                    database.movieDao().getByIds(movies.map { it.id }).collect { emit(it) }
                }
                is GenrePageState.Error -> {
                    val movies = state.cachedGenre?.shows.orEmpty().filterIsInstance<Movie>()
                    database.movieDao().getByIds(movies.map { it.id }).collect { emit(it) }
                }
                else -> emit(emptyList<Movie>())
            }
        },
        rawGenreState.transformLatest { state ->
            when (state) {
                is GenrePageState.Success -> {
                    val tvShows = state.genre.shows.filterIsInstance<TvShow>()
                    database.tvShowDao().getByIds(tvShows.map { it.id }).collect { emit(it) }
                }
                is GenrePageState.Error -> {
                    val tvShows = state.cachedGenre?.shows.orEmpty().filterIsInstance<TvShow>()
                    database.tvShowDao().getByIds(tvShows.map { it.id }).collect { emit(it) }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { rawState, sortMode, moviesDb, tvShowsDb ->
        when (rawState) {
            GenrePageState.Idle,
            GenrePageState.Loading -> GenreCatalogState.Loading
            is GenrePageState.Success -> GenreCatalogState.Success(
                genre = rawState.genre.copy(shows = rawState.genre.shows.map { item ->
                    when (item) {
                        is Movie -> moviesDb.find { it.id == item.id }?.let { mergeMovieForList(item, it) } ?: item
                        is TvShow -> tvShowsDb.find { it.id == item.id }?.let { mergeTvShowForList(item, it) } ?: item
                    }
                }),
                hasMore = rawState.hasMore,
                sortMode = sortMode,
                isLoadingMore = rawState.isLoadingMore,
            )
            is GenrePageState.Error -> GenreCatalogState.Error(
                error = rawState.error,
                cachedGenre = rawState.cachedGenre?.copy(shows = rawState.cachedGenre.shows.map { item ->
                    when (item) {
                        is Movie -> moviesDb.find { it.id == item.id }?.let { mergeMovieForList(item, it) } ?: item
                        is TvShow -> tvShowsDb.find { it.id == item.id }?.let { mergeTvShowForList(item, it) } ?: item
                    }
                }),
                hasMore = rawState.hasMore,
                sortMode = sortMode,
            )
        }
    }

    override fun hasCachedHome(): Boolean = cachedCategories != null

    override fun isCachedHomeFresh(): Boolean {
        if (cachedCategories == null || lastHomeLoadMillis <= 0L) return false
        return (System.currentTimeMillis() - lastHomeLoadMillis) < homeCacheTtlMillis
    }

    override suspend fun refreshHome(
        forceRefresh: Boolean,
        silentRefresh: Boolean,
    ) = withContext(ioDispatcher) {
        hydrateHomeCacheFromMemoryOrDisk()

        val cached = cachedCategories
        val cacheIsFresh = (System.currentTimeMillis() - lastHomeLoadMillis) < homeCacheTtlMillis
        val canUseSilentRefresh = silentRefresh && cached != null

        if (!forceRefresh && cached != null) {
            rawHomeState.emit(HomeCatalogState.Success(cached))
            CatalogSeedUtils.seedFromCategories(database, cached, "seed_home_${cacheKey}")
            PrefetchUtils.prefetchDetails(database, cached, "home_${cacheKey}")
            if (cacheIsFresh) return@withContext
        } else {
            rawHomeState.emit(HomeCatalogState.Loading)
        }

        try {
            val provider = UserPreferences.currentProvider ?: error("Provider non disponibile")
            val categories = buildHomeCategories(provider)

            cachedCategories = categories
            lastHomeLoadMillis = System.currentTimeMillis()
            synchronized(homeCache) {
                homeCache[cacheKey] = HomeCacheEntry(categories, lastHomeLoadMillis)
            }
            UiCacheStore.saveCategories(homeDiskCacheKey, categories, lastHomeLoadMillis)
            if (!canUseSilentRefresh) {
                rawHomeState.emit(HomeCatalogState.Success(categories))
            }
            CatalogSeedUtils.seedFromCategories(database, categories, "seed_home_${cacheKey}")
            PrefetchUtils.prefetchDetails(database, categories, "home_${cacheKey}")
        } catch (e: Exception) {
            if (cached != null) {
                rawHomeState.emit(HomeCatalogState.Success(cached))
            } else {
                rawHomeState.emit(HomeCatalogState.Error(e))
            }
        }
    }

    override suspend fun refreshMovies(
        forceRefresh: Boolean,
        silentRefresh: Boolean,
    ) = withContext(ioDispatcher) {
        hydrateMoviesCacheFromMemoryOrDisk()

        val now = System.currentTimeMillis()
        val cached = cachedMovieCategories
        val canUseSilentRefresh = silentRefresh && cached != null
        val cacheIsFresh = cached != null && (now - lastMoviesLoadMillis) < cacheTtlMillis

        if (!forceRefresh && cached != null) {
            rawMoviesState.emit(MoviesCatalogState.Success(cached))
            CatalogSeedUtils.seedFromCategories(database, cached, "seed_movies_${cacheKey}")
            PrefetchUtils.prefetchDetails(database, cached, "movies_${cacheKey}")
            if (cacheIsFresh) return@withContext
        } else if (cached == null) {
            rawMoviesState.emit(MoviesCatalogState.Loading)
        }

        try {
            val categories = buildMovieCategories(UserPreferences.currentProvider ?: error("Provider non disponibile"))

            cachedMovieCategories = categories
            lastMoviesLoadMillis = System.currentTimeMillis()
            synchronized(moviesCache) {
                moviesCache[cacheKey] = CategoryCacheEntry(categories, lastMoviesLoadMillis)
            }
            UiCacheStore.saveCategories(moviesDiskCacheKey, categories, lastMoviesLoadMillis)
            if (!canUseSilentRefresh) {
                rawMoviesState.emit(MoviesCatalogState.Success(categories))
            }
            CatalogSeedUtils.seedFromCategories(database, categories, "seed_movies_${cacheKey}")
            PrefetchUtils.prefetchDetails(database, categories, "movies_${cacheKey}")
        } catch (e: Exception) {
            if (cached != null) {
                rawMoviesState.emit(MoviesCatalogState.Success(cached))
            } else {
                rawMoviesState.emit(MoviesCatalogState.Error(e))
            }
        }
    }

    override suspend fun refreshTvShows(
        forceRefresh: Boolean,
        silentRefresh: Boolean,
    ) = withContext(ioDispatcher) {
        hydrateTvShowsCacheFromMemoryOrDisk()

        val now = System.currentTimeMillis()
        val cached = cachedTvShowCategories
        val canUseSilentRefresh = silentRefresh && cached != null
        val cacheIsFresh = cached != null && (now - lastTvShowsLoadMillis) < cacheTtlMillis

        if (!forceRefresh && cached != null) {
            rawTvShowsState.emit(TvShowsCatalogState.Success(cached))
            CatalogSeedUtils.seedFromCategories(database, cached, "seed_tv_${cacheKey}")
            PrefetchUtils.prefetchDetails(database, cached, "tv_${cacheKey}")
            if (cacheIsFresh) return@withContext
        } else if (cached == null) {
            rawTvShowsState.emit(TvShowsCatalogState.Loading)
        }

        try {
            val categories = buildTvShowCategories(UserPreferences.currentProvider ?: error("Provider non disponibile"))

            cachedTvShowCategories = categories
            lastTvShowsLoadMillis = System.currentTimeMillis()
            synchronized(tvShowsCache) {
                tvShowsCache[cacheKey] = CategoryCacheEntry(categories, lastTvShowsLoadMillis)
            }
            UiCacheStore.saveCategories(tvShowsDiskCacheKey, categories, lastTvShowsLoadMillis)
            if (!canUseSilentRefresh) {
                rawTvShowsState.emit(TvShowsCatalogState.Success(categories))
            }
            CatalogSeedUtils.seedFromCategories(database, categories, "seed_tv_${cacheKey}")
            PrefetchUtils.prefetchDetails(database, categories, "tv_${cacheKey}")
        } catch (e: Exception) {
            if (cached != null) {
                rawTvShowsState.emit(TvShowsCatalogState.Success(cached))
            } else {
                rawTvShowsState.emit(TvShowsCatalogState.Error(e))
            }
        }
    }

    override fun setGenreSortMode(mode: GenreSortMode) {
        if (_genreSortMode.value == mode) return
        _genreSortMode.value = mode
    }

    override fun showCachedGenre(genreId: String): Boolean {
        val cacheEntry = synchronized(genreCache) { genreCache[genreCacheKey(genreId, genreSortMode.value)] } ?: return false
        rawGenreState.value = GenrePageState.Success(
            genre = cacheEntry.genre,
            page = cacheEntry.page,
            hasMore = cacheEntry.hasMore,
            isLoadingMore = false,
        )
        return true
    }

    override suspend fun refreshGenre(
        genreId: String,
        forceRefresh: Boolean,
    ) = withContext(ioDispatcher) {
        val cacheEntry = synchronized(genreCache) { genreCache[genreCacheKey(genreId, genreSortMode.value)] }
        val now = System.currentTimeMillis()
        val isFresh = cacheEntry != null && (now - cacheEntry.loadedAtMillis) < cacheTtlMillis

        if (!forceRefresh && cacheEntry != null) {
            rawGenreState.emit(
                GenrePageState.Success(
                    genre = cacheEntry.genre,
                    page = cacheEntry.page,
                    hasMore = cacheEntry.hasMore,
                    isLoadingMore = false,
                )
            )
            if (isFresh) return@withContext
        } else {
            rawGenreState.emit(GenrePageState.Loading)
        }

        try {
            val genre = loadGenrePage(genreId, page = 1, sortMode = genreSortMode.value)
            val hasMore = genre.shows.isNotEmpty()
            synchronized(genreCache) {
                genreCache[genreCacheKey(genreId, genreSortMode.value)] = GenreCacheEntry(
                    genre = genre,
                    page = 1,
                    hasMore = hasMore,
                    loadedAtMillis = System.currentTimeMillis(),
                )
            }
            rawGenreState.emit(
                GenrePageState.Success(
                    genre = genre,
                    page = 1,
                    hasMore = hasMore,
                    isLoadingMore = false,
                )
            )
        } catch (e: Exception) {
            if (cacheEntry != null) {
                rawGenreState.emit(
                    GenrePageState.Success(
                        genre = cacheEntry.genre,
                        page = cacheEntry.page,
                        hasMore = cacheEntry.hasMore,
                        isLoadingMore = false,
                    )
                )
            } else {
                rawGenreState.emit(
                    GenrePageState.Error(
                        error = e,
                        cachedGenre = null,
                        page = 0,
                        hasMore = false,
                    )
                )
            }
        }
    }

    override suspend fun loadMoreGenre(genreId: String) = withContext(ioDispatcher) {
        val currentState = rawGenreState.value as? GenrePageState.Success ?: return@withContext
        if (currentState.isLoadingMore || !currentState.hasMore) return@withContext

        rawGenreState.emit(currentState.copy(isLoadingMore = true))

        try {
            val nextPage = currentState.page + 1
            val loadedGenre = loadGenrePage(genreId, page = nextPage, sortMode = genreSortMode.value)
            val mergedGenre = Genre(
                id = currentState.genre.id,
                name = currentState.genre.name,
                shows = currentState.genre.shows + loadedGenre.shows,
            )
            val hasMore = loadedGenre.shows.isNotEmpty()
            synchronized(genreCache) {
                genreCache[genreCacheKey(genreId, genreSortMode.value)] = GenreCacheEntry(
                    genre = mergedGenre,
                    page = nextPage,
                    hasMore = hasMore,
                    loadedAtMillis = System.currentTimeMillis(),
                )
            }
            rawGenreState.emit(
                GenrePageState.Success(
                    genre = mergedGenre,
                    page = nextPage,
                    hasMore = hasMore,
                    isLoadingMore = false,
                )
            )
        } catch (e: Exception) {
            rawGenreState.emit(
                GenrePageState.Error(
                    error = e,
                    cachedGenre = currentState.genre,
                    page = currentState.page,
                    hasMore = currentState.hasMore,
                )
            )
            rawGenreState.emit(currentState.copy(isLoadingMore = false))
        }
    }

    private fun initialHomeState(): HomeCatalogState {
        val sharedCached = synchronized(homeCache) { homeCache[cacheKey] }
        if (sharedCached != null) {
            cachedCategories = sharedCached.categories
            lastHomeLoadMillis = sharedCached.loadedAtMillis
            return HomeCatalogState.Success(sharedCached.categories)
        }

        UiCacheStore.loadCategories(homeDiskCacheKey)?.let { (loadedAt, categories) ->
            cachedCategories = categories
            lastHomeLoadMillis = loadedAt
            synchronized(homeCache) {
                homeCache[cacheKey] = HomeCacheEntry(categories, loadedAt)
            }
            return HomeCatalogState.Success(categories)
        }

        return HomeCatalogState.Loading
    }

    private fun initialMoviesState(): MoviesCatalogState {
        val sharedCached = synchronized(moviesCache) { moviesCache[cacheKey] }
        if (sharedCached != null) {
            cachedMovieCategories = sharedCached.categories
            lastMoviesLoadMillis = sharedCached.loadedAtMillis
            return MoviesCatalogState.Success(sharedCached.categories)
        }

        UiCacheStore.loadCategories(moviesDiskCacheKey)?.let { (loadedAt, categories) ->
            cachedMovieCategories = categories
            lastMoviesLoadMillis = loadedAt
            synchronized(moviesCache) {
                moviesCache[cacheKey] = CategoryCacheEntry(categories, loadedAt)
            }
            return MoviesCatalogState.Success(categories)
        }

        return MoviesCatalogState.Loading
    }

    private fun initialTvShowsState(): TvShowsCatalogState {
        val sharedCached = synchronized(tvShowsCache) { tvShowsCache[cacheKey] }
        if (sharedCached != null) {
            cachedTvShowCategories = sharedCached.categories
            lastTvShowsLoadMillis = sharedCached.loadedAtMillis
            return TvShowsCatalogState.Success(sharedCached.categories)
        }

        UiCacheStore.loadCategories(tvShowsDiskCacheKey)?.let { (loadedAt, categories) ->
            cachedTvShowCategories = categories
            lastTvShowsLoadMillis = loadedAt
            synchronized(tvShowsCache) {
                tvShowsCache[cacheKey] = CategoryCacheEntry(categories, loadedAt)
            }
            return TvShowsCatalogState.Success(categories)
        }

        return TvShowsCatalogState.Loading
    }

    private fun hydrateHomeCacheFromMemoryOrDisk() {
        val sharedCached = synchronized(homeCache) { homeCache[cacheKey] }
        if (cachedCategories == null && sharedCached != null) {
            cachedCategories = sharedCached.categories
            lastHomeLoadMillis = sharedCached.loadedAtMillis
            return
        }

        if (cachedCategories == null) {
            UiCacheStore.loadCategories(homeDiskCacheKey)?.let { (loadedAt, categories) ->
                cachedCategories = categories
                lastHomeLoadMillis = loadedAt
                synchronized(homeCache) {
                    homeCache[cacheKey] = HomeCacheEntry(categories, loadedAt)
                }
            }
        }
    }

    private fun hydrateMoviesCacheFromMemoryOrDisk() {
        val sharedCached = synchronized(moviesCache) { moviesCache[cacheKey] }
        if (cachedMovieCategories == null && sharedCached != null) {
            cachedMovieCategories = sharedCached.categories
            lastMoviesLoadMillis = sharedCached.loadedAtMillis
            return
        }

        if (cachedMovieCategories == null) {
            UiCacheStore.loadCategories(moviesDiskCacheKey)?.let { (loadedAt, categories) ->
                cachedMovieCategories = categories
                lastMoviesLoadMillis = loadedAt
                synchronized(moviesCache) {
                    moviesCache[cacheKey] = CategoryCacheEntry(categories, loadedAt)
                }
            }
        }
    }

    private fun hydrateTvShowsCacheFromMemoryOrDisk() {
        val sharedCached = synchronized(tvShowsCache) { tvShowsCache[cacheKey] }
        if (cachedTvShowCategories == null && sharedCached != null) {
            cachedTvShowCategories = sharedCached.categories
            lastTvShowsLoadMillis = sharedCached.loadedAtMillis
            return
        }

        if (cachedTvShowCategories == null) {
            UiCacheStore.loadCategories(tvShowsDiskCacheKey)?.let { (loadedAt, categories) ->
                cachedTvShowCategories = categories
                lastTvShowsLoadMillis = loadedAt
                synchronized(tvShowsCache) {
                    tvShowsCache[cacheKey] = CategoryCacheEntry(categories, loadedAt)
                }
            }
        }
    }

    private suspend fun buildHomeCategories(provider: Provider): List<Category> {
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
                    tvDeferred.await().distinctBy { it.id },
                )
            }

            fun addIfMissing(name: String, items: List<AppAdapter.Item>) {
                if (items.size < 8) return
                if (categories.none { it.name.equals(name, ignoreCase = true) }) {
                    categories.add(Category(name, items))
                }
            }

            addIfMissing("Film popolari", moviePool.sortedByDescending { it.rating ?: 0.0 }.take(25))
            addIfMissing("Film aggiunti di recente", moviePool.sortedByDescending { it.released?.timeInMillis ?: 0L }.take(25))
            addIfMissing("Serie popolari", tvPool.sortedByDescending { it.rating ?: 0.0 }.take(25))
            addIfMissing("Serie aggiunte di recente", tvPool.sortedByDescending { it.released?.timeInMillis ?: 0L }.take(25))
            addIfMissing("Scelti per te", moviePool.shuffled().take(12) + tvPool.shuffled().take(12))
        }

        return categories
    }

    private suspend fun buildMovieCategories(provider: Provider): List<Category> {
        val moviePool = coroutineScope {
            listOf(1, 2)
                .map { page ->
                    async { runCatching { provider.getMovies(page) }.getOrDefault(emptyList<Movie>()) }
                }
                .awaitAll()
                .flatten()
                .distinctBy { it.id }
        }

        if (moviePool.isEmpty()) return emptyList()

        val baseCategories = mutableListOf<Category>()
        baseCategories.add(Category(name = Category.FEATURED, list = moviePool.sortedByDescending { it.rating ?: 0.0 }.take(10)))
        baseCategories.add(Category("Aggiunti di recente", moviePool.sortedByDescending { it.released?.timeInMillis ?: 0L }.take(30)))
        baseCategories.add(Category("I più votati", moviePool.sortedByDescending { it.rating ?: 0.0 }.drop(10).take(30)))
        baseCategories.add(Category("Da non perdere", moviePool.shuffled().take(30)))

        val genresCategory = buildGenresCategory(
            provider = provider,
            title = "Generi",
            filterOut = { genre ->
                genre.id.equals("Tutte le Serie TV", ignoreCase = true) ||
                    genre.name.contains("Tutte le Serie TV", ignoreCase = true)
            },
            remapStreamingCommunityId = { genre ->
                val isAllMoviesEntry =
                    genre.id.equals("Tutti i Film", ignoreCase = true) ||
                        genre.name.contains("Tutti i Film", ignoreCase = true)
                if (!isAllMoviesEntry) "Film: ${genre.name}" else genre.id
            },
        )

        return (baseCategories + listOfNotNull(genresCategory)).filter { it.list.isNotEmpty() }
    }

    private suspend fun buildTvShowCategories(provider: Provider): List<Category> {
        val tvPool = coroutineScope {
            listOf(1, 2)
                .map { page ->
                    async { runCatching { provider.getTvShows(page) }.getOrDefault(emptyList<TvShow>()) }
                }
                .awaitAll()
                .flatten()
                .distinctBy { it.id }
        }

        if (tvPool.isEmpty()) return emptyList()

        val baseCategories = mutableListOf<Category>()
        baseCategories.add(Category(name = Category.FEATURED, list = tvPool.sortedByDescending { it.rating ?: 0.0 }.take(10)))
        baseCategories.add(Category("Aggiunte di recente", tvPool.sortedByDescending { it.released?.timeInMillis ?: 0L }.take(30)))
        baseCategories.add(Category("Serie più votate", tvPool.sortedByDescending { it.rating ?: 0.0 }.drop(10).take(30)))
        baseCategories.add(Category("Binge-worthy", tvPool.shuffled().take(30)))

        val genresCategory = buildGenresCategory(
            provider = provider,
            title = "Generi",
            filterOut = { genre ->
                genre.id.equals("Tutti i Film", ignoreCase = true) ||
                    genre.name.contains("Tutti i Film", ignoreCase = true)
            },
            remapStreamingCommunityId = { genre ->
                val isAllTvEntry =
                    genre.id.equals("Tutte le Serie TV", ignoreCase = true) ||
                        genre.name.contains("Tutte le Serie TV", ignoreCase = true)
                if (!isAllTvEntry) "Serie TV: ${genre.name}" else genre.id
            },
        )

        return (baseCategories + listOfNotNull(genresCategory)).filter { it.list.isNotEmpty() }
    }

    private suspend fun buildGenresCategory(
        provider: Provider,
        title: String,
        filterOut: (Genre) -> Boolean,
        remapStreamingCommunityId: (Genre) -> String,
    ): Category? {
        val allGenres = provider.search("", 1).filterIsInstance<Genre>()
        val isStreamingCommunity = provider.name.equals("StreamingCommunity", ignoreCase = true)
        val genreItems = allGenres
            .filter { !it.id.contains("A-Z", ignoreCase = true) }
            .filterNot(filterOut)
            .distinctBy { it.name.trim().lowercase() }
            .sortedBy { it.name }
            .map { genre ->
                genre.copy(
                    id = if (isStreamingCommunity) remapStreamingCommunityId(genre) else genre.id,
                    name = genre.name,
                ).also { it.itemType = AppAdapter.Type.GENRE_MOBILE_ITEM }
            }

        return genreItems.takeIf { it.isNotEmpty() }?.let { items ->
            Category(title, items).also { category ->
                category.itemSpacing = 10
            }
        }
    }

    private suspend fun loadGenrePage(
        genreId: String,
        page: Int,
        sortMode: GenreSortMode,
    ): Genre {
        val provider = UserPreferences.currentProvider ?: error("Provider non disponibile")
        return if (provider is StreamingCommunityProvider) {
            provider.getGenre(genreId, page, sortMode.apiValue)
        } else {
            provider.getGenre(genreId, page)
        }
    }

    private fun genreCacheKey(genreId: String, sortMode: GenreSortMode): String =
        "$cacheKey|$genreId|${sortMode.name}"

    private fun mergeMovieForList(item: Movie, dbMovie: Movie): Movie {
        return item.copy(
            poster = ArtworkResolver.choosePreferredImage(item.poster, dbMovie.poster),
            banner = ArtworkResolver.choosePreferredImage(item.banner, dbMovie.banner),
            isFavorite = dbMovie.isFavorite,
        ).also { merged ->
            merged.isWatched = dbMovie.isWatched
            merged.watchedDate = dbMovie.watchedDate
            merged.watchHistory = dbMovie.watchHistory
            runCatching { item.itemType }.getOrNull()?.let { merged.itemType = it }
        }
    }

    private fun mergeTvShowForList(item: TvShow, dbShow: TvShow): TvShow {
        return item.copy(
            poster = ArtworkResolver.choosePreferredImage(item.poster, dbShow.poster),
            banner = ArtworkResolver.choosePreferredImage(item.banner, dbShow.banner),
            isFavorite = dbShow.isFavorite,
        ).also { merged ->
            merged.isWatching = dbShow.isWatching
            runCatching { item.itemType }.getOrNull()?.let { merged.itemType = it }
        }
    }
}
