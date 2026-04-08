package com.streamflixreborn.streamflix.providers

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.TmdbUtils
import org.json.JSONObject
import java.lang.reflect.Type

internal class StreamingCommunityMapper {
    private val gson = Gson()
    private val showListType: Type = object : TypeToken<List<StreamingCommunityShow>>() {}.type

    private fun preferNonBlank(vararg candidates: String?): String? =
        candidates.firstOrNull { !it.isNullOrBlank() }

    fun parseTitlesFromInertiaJson(
        json: JSONObject,
        onVersionResolved: (String) -> Unit = {},
    ): List<StreamingCommunityShow> {
        val props = json.optJSONObject("props") ?: return emptyList()
        if (props.has("titles") && props.optJSONArray("titles") != null) {
            val jsonArray = props.optJSONArray("titles")
            return gson.fromJson<List<StreamingCommunityShow>>(jsonArray?.toString() ?: "[]", showListType)
                ?: emptyList()
        }

        val res: StreamingCommunityArchiveRes? = try {
            gson.fromJson(json.toString(), StreamingCommunityArchiveRes::class.java)
        } catch (_: Exception) {
            null
        }
        res?.version?.let(onVersionResolved)
        return res?.props?.let { p ->
            p.archive?.data ?: p.titles?.data ?: p.movies?.data ?: p.tv?.data ?: p.tvShows?.data
        } ?: emptyList()
    }

    fun mapCatalogShow(
        show: StreamingCommunityShow,
        poster: String?,
        banner: String?,
    ): com.streamflixreborn.streamflix.models.Show {
        return if (show.type == "movie") {
            Movie(
                id = "${show.id}-${show.slug}",
                title = show.name,
                released = show.lastAirDate,
                rating = show.score,
                poster = poster,
                banner = banner
            )
        } else {
            TvShow(
                id = "${show.id}-${show.slug}",
                title = show.name,
                released = show.lastAirDate,
                rating = show.score,
                poster = poster,
                banner = banner
            )
        }
    }

    fun mapMovieDetails(
        id: String,
        title: StreamingCommunityShow,
        tmdbMovie: Movie?,
        providerPoster: String?,
        providerBanner: String?,
        recommendations: List<com.streamflixreborn.streamflix.models.Show>,
    ): Movie {
        return Movie(
            id = id,
            title = tmdbMovie?.title ?: title.name,
            overview = preferNonBlank(tmdbMovie?.overview, title.plot),
            released = title.lastAirDate,
            rating = title.score,
            quality = title.quality,
            runtime = title.runtime,
            poster = tmdbMovie?.poster ?: providerPoster,
            banner = tmdbMovie?.banner ?: tmdbMovie?.poster ?: providerBanner,
            genres = title.genres?.map { Genre(id = it.id, name = it.name) } ?: emptyList(),
            cast = title.actors?.map { actor ->
                val tmdbPerson = tmdbMovie?.cast?.find { p -> p.name.equals(actor.name, ignoreCase = true) }
                People(id = actor.name, name = actor.name, image = tmdbPerson?.image)
            } ?: emptyList(),
            trailer = title.trailers?.find { !it.youtubeId.isNullOrBlank() }?.youtubeId?.let { yid ->
                "https://youtube.com/watch?v=$yid"
            },
            recommendations = recommendations
        )
    }

    fun mapTvShowDetails(
        id: String,
        title: StreamingCommunityShow,
        tmdbShow: TvShow?,
        providerPoster: String?,
        providerBanner: String?,
        recommendations: List<com.streamflixreborn.streamflix.models.Show>,
    ): TvShow {
        val providerSeasons = title.seasons.orEmpty()
        return TvShow(
            id = id,
            title = tmdbShow?.title ?: title.name,
            overview = preferNonBlank(tmdbShow?.overview, title.plot),
            released = title.lastAirDate,
            rating = title.score,
            quality = title.quality,
            runtime = title.runtime,
            poster = tmdbShow?.poster ?: providerPoster,
            banner = tmdbShow?.banner ?: tmdbShow?.poster ?: providerBanner,
            genres = title.genres?.map { Genre(id = it.id, name = it.name) } ?: emptyList(),
            cast = title.actors?.map { actor ->
                val tmdbPerson = tmdbShow?.cast?.find { p -> p.name.equals(actor.name, ignoreCase = true) }
                People(id = actor.name, name = actor.name, image = tmdbPerson?.image)
            } ?: emptyList(),
            trailer = title.trailers?.find { !it.youtubeId.isNullOrBlank() }?.youtubeId?.let { yid ->
                "https://youtube.com/watch?v=$yid"
            },
            recommendations = recommendations,
            seasons = providerSeasons.mapIndexed { index, s ->
                val seasonNumber = s.number.toIntOrNull() ?: (index + 1)
                Season(
                    id = "$id/season-${s.number}",
                    number = seasonNumber,
                    title = s.name,
                    poster = tmdbShow?.seasons?.find { ts -> ts.number == seasonNumber }?.poster
                )
            }
        )
    }

    fun mapSeasonEpisodes(
        seasonId: String,
        episodes: List<StreamingCommunitySeasonEpisode>,
        imageLinkResolver: (String?) -> String?,
    ): List<Episode> {
        return episodes.mapIndexed { index, episode ->
            Episode(
                id = "${seasonId.substringBefore("-")}?episode_id=${episode.id}",
                number = episode.number.toIntOrNull() ?: (index + 1),
                title = episode.name,
                poster = imageLinkResolver(episode.images.findLast { it.type == "cover" }?.filename),
                overview = episode.plot
            )
        }
    }
}
