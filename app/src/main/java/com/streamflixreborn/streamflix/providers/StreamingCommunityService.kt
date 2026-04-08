package com.streamflixreborn.streamflix.providers

import com.google.gson.annotations.SerializedName
import org.jsoup.nodes.Document
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

internal interface StreamingCommunityService {
    @GET("./")
    suspend fun getHome(): Document

    @GET("./")
    suspend fun getHome(
        @Header("x-inertia") xInertia: String = "true",
        @Header("x-inertia-version") version: String,
        @Header("X-Requested-With") xRequestedWith: String = "XMLHttpRequest"
    ): StreamingCommunityHomeRes

    @GET("archive?type=movie")
    suspend fun getMoviesHtml(): Document

    @GET("archive?type=tv")
    suspend fun getTvShowsHtml(): Document

    @GET("/api/search")
    suspend fun search(
        @Query("q", encoded = true) keyword: String,
        @Query("offset") offset: Int = 0,
        @Query("lang") language: String
    ): StreamingCommunitySearchRes

    @GET("/api/archive")
    suspend fun getArchiveApi(
        @Query("lang") lang: String,
        @Query("offset") offset: Int,
        @Query("genre[]") genreId: String? = null,
        @Query("type") type: String? = null,
        @Query("sort") sort: String? = null
    ): StreamingCommunityApiArchiveRes

    @GET("archive")
    suspend fun getArchiveHtml(
        @Query("genre[]") genreId: String? = null,
        @Query("type") type: String? = null,
        @Query("sort") sort: String? = null
    ): Document

    @GET("titles/{id}")
    suspend fun getDetails(
        @Path("id") id: String,
        @Header("x-inertia") xInertia: String = "true",
        @Header("x-inertia-version") version: String,
        @Query("lang") language: String,
        @Header("X-Requested-With") xRequestedWith: String = "XMLHttpRequest"
    ): StreamingCommunityHomeRes

    @GET("titles/{id}/")
    suspend fun getSeasonDetails(
        @Path("id") id: String,
        @Header("x-inertia") xInertia: String = "true",
        @Header("x-inertia-version") version: String,
        @Query("lang") language: String,
        @Header("X-Requested-With") xRequestedWith: String = "XMLHttpRequest"
    ): StreamingCommunitySeasonRes
}

internal data class StreamingCommunityImage(
    val filename: String,
    val type: String,
    val locale: String? = null
)

internal data class StreamingCommunityGenreDto(val id: String, val name: String)
internal data class StreamingCommunityActor(val id: String, val name: String)
internal data class StreamingCommunityTrailer(@SerializedName("youtube_id") val youtubeId: String?)
internal data class StreamingCommunitySeasonDto(val number: String, val name: String?)

internal data class StreamingCommunityShow(
    val id: String,
    val name: String,
    val type: String,
    @SerializedName("tmdb_id") val tmdbId: String?,
    val score: Double,
    @SerializedName("last_air_date") val lastAirDate: String?,
    val images: List<StreamingCommunityImage>,
    val slug: String,
    val plot: String?,
    val genres: List<StreamingCommunityGenreDto>?,
    @SerializedName("main_actors") val actors: List<StreamingCommunityActor>?,
    val trailers: List<StreamingCommunityTrailer>?,
    val seasons: List<StreamingCommunitySeasonDto>?,
    val quality: String?,
    val runtime: Int?
)

internal data class StreamingCommunitySlider(
    val label: String?,
    val name: String,
    val titles: List<StreamingCommunityShow>
)

internal data class StreamingCommunityArchivePage(
    val data: List<StreamingCommunityShow>?,
    @SerializedName("current_page") val currentPage: Int?,
    @SerializedName("last_page") val lastPage: Int?
)

internal data class StreamingCommunityProps(
    val genres: List<StreamingCommunityGenreDto>,
    val sliders: List<StreamingCommunitySlider>?,
    val archive: StreamingCommunityArchivePage?,
    val titles: StreamingCommunityArchivePage?,
    val movies: StreamingCommunityArchivePage?,
    val tv: StreamingCommunityArchivePage?,
    @SerializedName("tv_shows") val tvShows: StreamingCommunityArchivePage?,
    @SerializedName("latest_movies") val latestMovies: List<StreamingCommunityShow>?,
    @SerializedName("latest_tv_shows") val latestTvShows: List<StreamingCommunityShow>?,
    @SerializedName("trending_titles") val trendingTitles: List<StreamingCommunityShow>?,
    @SerializedName("trending") val trending: List<StreamingCommunityShow>?,
    @SerializedName("top_10_titles") val top10Titles: List<StreamingCommunityShow>?,
    @SerializedName("top_10") val top10: List<StreamingCommunityShow>?,
    @SerializedName("upcoming_titles") val upcomingTitles: List<StreamingCommunityShow>?,
    @SerializedName("upcoming") val upcoming: List<StreamingCommunityShow>?,
    val title: StreamingCommunityShow
)

internal data class StreamingCommunityHomeRes(
    val version: String,
    val props: StreamingCommunityProps
)

internal data class StreamingCommunitySearchRes(
    val data: List<StreamingCommunityShow>,
    @SerializedName("current_page") val currentPage: Int?,
    @SerializedName("last_page") val lastPage: Int?
)

internal data class StreamingCommunitySeasonEpisode(
    val id: String,
    val images: List<StreamingCommunityImage>,
    val name: String,
    val number: String,
    val plot: String? = null
)

internal data class StreamingCommunitySeasonDetails(
    val episodes: List<StreamingCommunitySeasonEpisode>
)

internal data class StreamingCommunitySeasonProps(
    val loadedSeason: StreamingCommunitySeasonDetails
)

internal data class StreamingCommunitySeasonRes(
    val version: String,
    val props: StreamingCommunitySeasonProps
)

internal data class StreamingCommunityApiArchiveRes(
    val titles: List<StreamingCommunityShow>
)

internal data class StreamingCommunityArchiveProps(
    val archive: StreamingCommunityArchivePage?,
    val titles: StreamingCommunityArchivePage?,
    val movies: StreamingCommunityArchivePage?,
    val tv: StreamingCommunityArchivePage?,
    @SerializedName("tv_shows") val tvShows: StreamingCommunityArchivePage?
)

internal data class StreamingCommunityArchiveRes(
    val version: String,
    val props: StreamingCommunityArchiveProps?
)
