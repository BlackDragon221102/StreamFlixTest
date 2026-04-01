package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

object CatalogRefreshUtils {
    const val HOME_CACHE_KEY = "mobile_home_categories_v1"
    const val MOVIES_CACHE_KEY = "mobile_movies_categories_v2"
    const val TV_CACHE_KEY = "mobile_tv_categories_v2"

    private const val BACKGROUND_REFRESH_TTL_MILLIS = 10 * 60 * 1000L

    fun shouldRefreshInBackground(nowMillis: Long = System.currentTimeMillis()): Boolean {
        return listOf(HOME_CACHE_KEY, MOVIES_CACHE_KEY, TV_CACHE_KEY).any { cacheKey ->
            val loadedAt = UiCacheStore.loadCategories(cacheKey)?.first ?: return@any true
            nowMillis - loadedAt >= BACKGROUND_REFRESH_TTL_MILLIS
        }
    }

    suspend fun refreshMobileCatalogCaches() {
        val provider = UserPreferences.currentProvider ?: return

        UiCacheStore.saveCategories(HOME_CACHE_KEY, buildHomeCategories(provider))

        if (Provider.supportsMovies(provider)) {
            UiCacheStore.saveCategories(MOVIES_CACHE_KEY, buildMovieCategories(provider))
        }

        if (Provider.supportsTvShows(provider)) {
            UiCacheStore.saveCategories(TV_CACHE_KEY, buildTvShowCategories(provider))
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
                    tvDeferred.await().distinctBy { it.id }
                )
            }

            fun addIfMissing(name: String, items: List<AppAdapter.Item>) {
                if (items.size < 8) return
                if (categories.none { it.name.equals(name, ignoreCase = true) }) {
                    categories.add(Category(name, items))
                }
            }

            addIfMissing(
                "Film popolari",
                moviePool.sortedByDescending { movie -> movie.rating ?: 0.0 }.take(25)
            )
            addIfMissing(
                "Film aggiunti di recente",
                moviePool.sortedByDescending { movie -> movie.released?.timeInMillis ?: 0L }.take(25)
            )
            addIfMissing(
                "Serie popolari",
                tvPool.sortedByDescending { tvShow -> tvShow.rating ?: 0.0 }.take(25)
            )
            addIfMissing(
                "Serie aggiunte di recente",
                tvPool.sortedByDescending { tvShow -> tvShow.released?.timeInMillis ?: 0L }.take(25)
            )
            addIfMissing(
                "Scelti per te",
                moviePool.shuffled().take(12) + tvPool.shuffled().take(12)
            )
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
        baseCategories.add(
            Category(
                name = Category.FEATURED,
                list = moviePool.sortedByDescending { movie -> movie.rating ?: 0.0 }.take(10)
            )
        )
        baseCategories.add(
            Category(
                "Aggiunti di recente",
                moviePool.sortedByDescending { movie -> movie.released?.timeInMillis ?: 0L }.take(30)
            )
        )
        baseCategories.add(
            Category(
                "I più votati",
                moviePool.sortedByDescending { movie -> movie.rating ?: 0.0 }.drop(10).take(30)
            )
        )
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
            }
        )

        return (baseCategories + listOfNotNull(genresCategory))
            .filter { it.list.isNotEmpty() }
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
        baseCategories.add(
            Category(
                name = Category.FEATURED,
                list = tvPool.sortedByDescending { tvShow -> tvShow.rating ?: 0.0 }.take(10)
            )
        )
        baseCategories.add(
            Category(
                "Aggiunte di recente",
                tvPool.sortedByDescending { tvShow -> tvShow.released?.timeInMillis ?: 0L }.take(30)
            )
        )
        baseCategories.add(
            Category(
                "Serie più votate",
                tvPool.sortedByDescending { tvShow -> tvShow.rating ?: 0.0 }.drop(10).take(30)
            )
        )
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
            }
        )

        return (baseCategories + listOfNotNull(genresCategory))
            .filter { it.list.isNotEmpty() }
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
                    name = genre.name
                ).also { it.itemType = AppAdapter.Type.GENRE_MOBILE_ITEM }
            }

        return genreItems.takeIf { it.isNotEmpty() }?.let { items ->
            Category(title, items).also { category ->
                category.itemSpacing = 10
            }
        }
    }
}
