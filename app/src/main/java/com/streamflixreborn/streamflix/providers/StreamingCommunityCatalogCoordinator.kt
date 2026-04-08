package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre

internal data class StreamingCommunityGenreRequest(
    val displayName: String,
    val actualType: String?,
    val actualGenreId: String?,
)

internal class StreamingCommunityCatalogCoordinator {

    suspend fun buildHomeCategories(
        response: StreamingCommunityHomeRes,
        mapShows: suspend (
            shows: List<StreamingCommunityShow>,
            heroOnlyTextless: Boolean,
            allowSearchFallback: Boolean,
            searchFallbackLimit: Int,
        ) -> List<com.streamflixreborn.streamflix.models.Show>,
    ): List<Category> {
        val sliders = response.props.sliders.orEmpty()
        val categories = mutableListOf<Category>()

        val heroSlider = sliders.find { it.name == "hero" } ?: sliders.firstOrNull()
        if (heroSlider != null) {
            val textlessHeroShows = mapShows(
                heroSlider.titles,
                true,
                true,
                Int.MAX_VALUE,
            ).take(10)

            val featuredShows = if (textlessHeroShows.isNotEmpty()) {
                textlessHeroShows
            } else {
                mapShows(
                    heroSlider.titles,
                    false,
                    true,
                    Int.MAX_VALUE,
                ).take(10)
            }

            if (featuredShows.isNotEmpty()) {
                categories.add(Category(name = Category.FEATURED, list = featuredShows))
            }
        }

        val processedSliderNames = mutableSetOf<String>()
        if (heroSlider != null) processedSliderNames.add(heroSlider.name)

        val propsMapping = listOf(
            "I titoli del momento" to (response.props.trendingTitles ?: response.props.trending),
            "Film aggiunti di recente" to response.props.latestMovies,
            "Serie TV aggiunte di recente" to response.props.latestTvShows,
            "Top 10 titoli di oggi" to (response.props.top10Titles ?: response.props.top10),
            "In arrivo" to (response.props.upcomingTitles ?: response.props.upcoming),
        )

        propsMapping.forEach { (name, list) ->
            if (!list.isNullOrEmpty()) {
                categories.add(
                    Category(
                        name,
                        mapShows(
                            list,
                            false,
                            true,
                            40,
                        ),
                    ),
                )
            }
        }

        sliders.forEach { slider ->
            if (!processedSliderNames.contains(slider.name) && slider.titles.isNotEmpty()) {
                val italianName = when {
                    slider.name.contains("trending", true) -> "I titoli del momento"
                    slider.name.contains("latest-movies", true) -> "Film aggiunti di recente"
                    slider.name.contains("latest-tv-shows", true) -> "Serie TV aggiunte di recente"
                    slider.name.contains("top-10", true) -> "Top 10 titoli di oggi"
                    slider.name.contains("upcoming", true) -> "In arrivo"
                    slider.name.contains("new-releases", true) -> "Nuove uscite"
                    else -> slider.label ?: slider.name
                }

                if (categories.none { it.name.equals(italianName, ignoreCase = true) }) {
                    categories.add(
                        Category(
                            italianName,
                            mapShows(
                                slider.titles,
                                false,
                                true,
                                40,
                            ),
                        ),
                    )
                }
            }
        }

        return categories
            .filter { it.list.isNotEmpty() }
            .distinctBy { it.name.lowercase().trim() }
    }

    fun buildSearchGenres(response: StreamingCommunityHomeRes): List<Genre> {
        val dynamicGenres = response.props.genres
            .map { Genre(id = it.id, name = it.name) }
            .sortedBy { it.name }
            .toMutableList()

        dynamicGenres.add(0, Genre(id = "Tutti i Film", name = "\uD83C\uDF7F Tutti i Film"))
        dynamicGenres.add(1, Genre(id = "Tutte le Serie TV", name = "\uD83D\uDCFA Tutte le Serie TV"))
        return dynamicGenres
    }

    fun resolveGenreRequest(
        id: String,
        availableGenres: List<Genre>,
    ): StreamingCommunityGenreRequest {
        var actualType: String? = null
        var actualGenreId: String? = null
        var displayName = id

        when {
            id == "Tutti i Film" -> actualType = "movie"
            id == "Tutte le Serie TV" -> actualType = "tv"
            id.startsWith("Film: ") -> {
                actualType = "movie"
                val genreName = id.removePrefix("Film: ")
                displayName = genreName
                actualGenreId = availableGenres.find { it.name.equals(genreName, ignoreCase = true) }?.id
            }
            id.startsWith("Serie TV: ") -> {
                actualType = "tv"
                val genreName = id.removePrefix("Serie TV: ")
                displayName = genreName
                actualGenreId = availableGenres.find { it.name.equals(genreName, ignoreCase = true) }?.id
            }
            else -> {
                actualGenreId = availableGenres.find { it.name.equals(id, ignoreCase = true) }?.id ?: id
            }
        }

        return StreamingCommunityGenreRequest(
            displayName = displayName,
            actualType = actualType,
            actualGenreId = actualGenreId,
        )
    }

    fun buildGenre(
        id: String,
        name: String,
        shows: List<com.streamflixreborn.streamflix.models.Show>,
    ): Genre = Genre(
        id = id,
        name = name,
        shows = shows,
    )
}
