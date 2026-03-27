package com.streamflixreborn.streamflix.utils

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow

object UiCacheStore {

    private val gson = Gson()

    private data class ShowSnapshot(
        val type: String,
        val id: String,
        val title: String,
        val released: String?,
        val rating: Double?,
        val poster: String?,
        val banner: String?
    )

    private data class CategorySnapshot(
        val name: String,
        val shows: List<ShowSnapshot>
    )

    private data class Payload(
        val loadedAtMillis: Long,
        val categories: List<CategorySnapshot>
    )

    private data class Restored(
        val loadedAtMillis: Long,
        val categories: List<Category>
    )

    private fun toPayload(categories: List<Category>, loadedAtMillis: Long): Payload {
        val categorySnapshots = categories.map { category ->
            val shows = category.list.mapNotNull { item ->
                when (item) {
                    is Movie -> ShowSnapshot(
                        type = "movie",
                        id = item.id,
                        title = item.title,
                        released = item.released?.format("yyyy-MM-dd"),
                        rating = item.rating,
                        poster = item.poster,
                        banner = item.banner
                    )
                    is TvShow -> ShowSnapshot(
                        type = "tv",
                        id = item.id,
                        title = item.title,
                        released = item.released?.format("yyyy-MM-dd"),
                        rating = item.rating,
                        poster = item.poster,
                        banner = item.banner
                    )
                    is Genre -> ShowSnapshot(
                        type = "genre",
                        id = item.id,
                        title = item.name,
                        released = null,
                        rating = null,
                        poster = null,
                        banner = null
                    )
                    else -> null
                }
            }
            CategorySnapshot(name = category.name, shows = shows)
        }
        return Payload(
            loadedAtMillis = loadedAtMillis,
            categories = categorySnapshots
        )
    }

    private fun fromPayload(payload: Payload): Restored {
        val categories = payload.categories.map { category ->
            Category(
                name = category.name,
                list = category.shows.map { show ->
                    when (show.type) {
                        "movie" -> Movie(
                            id = show.id,
                            title = show.title,
                            released = show.released,
                            rating = show.rating,
                            poster = show.poster,
                            banner = show.banner
                        )
                        "genre" -> Genre(
                            id = show.id,
                            name = show.title
                        )
                        else -> TvShow(
                            id = show.id,
                            title = show.title,
                            released = show.released,
                            rating = show.rating,
                            poster = show.poster,
                            banner = show.banner
                        )
                    }
                }
            )
        }
        return Restored(
            loadedAtMillis = payload.loadedAtMillis,
            categories = categories
        )
    }

    fun saveCategories(cacheKey: String, categories: List<Category>, loadedAtMillis: Long = System.currentTimeMillis()) {
        val provider = UserPreferences.currentProvider ?: return
        val payload = toPayload(categories, loadedAtMillis)
        UserPreferences.setProviderCache(provider, cacheKey, gson.toJson(payload))
    }

    fun loadCategories(cacheKey: String): Pair<Long, List<Category>>? {
        val provider = UserPreferences.currentProvider ?: return null
        val json = UserPreferences.getProviderCache(provider, cacheKey)
        if (json.isBlank()) return null
        return runCatching {
            val type = object : TypeToken<Payload>() {}.type
            val payload = gson.fromJson<Payload>(json, type) ?: return null
            val restored = fromPayload(payload)
            restored.loadedAtMillis to restored.categories
        }.getOrNull()
    }
}
