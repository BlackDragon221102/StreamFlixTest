package com.streamflixreborn.streamflix.fragments.tv_shows

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.TvShow
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

class TvShowsViewModel(private val database: AppDatabase) : ViewModel() {
    private val diskCacheKey = "mobile_tv_categories_v2"

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: Flow<State> = combine(
        _state,
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
        }
    ) { state, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                State.SuccessLoading(
                    categories = state.categories.map { category ->
                        category.copy(
                            list = category.list.map { item ->
                                when (item) {
                                    is TvShow -> tvShowsDb.find { it.id == item.id }
                                        ?.let { dbShow -> mergeTvShowForList(item, dbShow) }
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
                CatalogSeedUtils.seedFromCategories(database, cached, "seed_tv_${cacheKey}")
            }
            viewModelScope.launch(Dispatchers.IO) {
                PrefetchUtils.prefetchDetails(database, cached, "tv_${cacheKey}")
            }
            val isFresh = cachedLoadedAt?.let { now - it < CACHE_TTL_MILLIS } == true
            if (isFresh) return@launch
        }

        if (cached == null) {
            _state.emit(State.Loading)
        }
        try {
            val provider = UserPreferences.currentProvider!!
            val tvPool: List<TvShow> = coroutineScope {
                listOf(1, 2)
                    .map { page ->
                        async { runCatching { provider.getTvShows(page) }.getOrDefault(emptyList<TvShow>()) }
                    }
                    .awaitAll()
                    .flatten()
                    .distinctBy { it.id }
            }

            if (tvPool.isEmpty()) {
                _state.emit(State.SuccessLoading(emptyList()))
                return@launch
            }

            val baseCategories = mutableListOf<Category>()
            baseCategories.add(Category(name = Category.FEATURED, list = tvPool.sortedByDescending { tvShow: TvShow -> tvShow.rating ?: 0.0 }.take(10)))
            baseCategories.add(Category("Aggiunte di recente", tvPool.sortedByDescending { tvShow: TvShow -> tvShow.released?.timeInMillis ?: 0L }.take(30)))
            baseCategories.add(Category("Serie più votate", tvPool.sortedByDescending { tvShow: TvShow -> tvShow.rating ?: 0.0 }.drop(10).take(30)))
            baseCategories.add(Category("Binge-worthy", tvPool.shuffled().take(30)))

            _state.emit(State.SuccessLoading(baseCategories))

            val genresCategory = runCatching {
                val allGenres = provider.search("", 1).filterIsInstance<Genre>()
                val isStreamingCommunity = provider.name.equals("StreamingCommunity", ignoreCase = true)
                val genreItems = allGenres
                    .filter { !it.id.contains("A-Z", ignoreCase = true) }
                    .filterNot { genre ->
                        genre.id.equals("Tutti i Film", ignoreCase = true) ||
                            genre.name.contains("Tutti i Film", ignoreCase = true)
                    }
                    .distinctBy { it.name.trim().lowercase() }
                    .sortedBy { it.name }
                    .map { genre ->
                        val isAllTvEntry =
                            genre.id.equals("Tutte le Serie TV", ignoreCase = true) ||
                                genre.name.contains("Tutte le Serie TV", ignoreCase = true)
                        genre.copy(
                            id = if (isStreamingCommunity && !isAllTvEntry) {
                                "Serie TV: ${genre.name}"
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
                CatalogSeedUtils.seedFromCategories(database, finalCategories, "seed_tv_${cacheKey}")
            }
            viewModelScope.launch(Dispatchers.IO) {
                PrefetchUtils.prefetchDetails(database, finalCategories, "tv_${cacheKey}")
            }
        } catch (e: Exception) {
            Log.e("TvShowsVM", "Errore caricamento: ", e)
            _state.emit(State.FailedLoading(e))
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
