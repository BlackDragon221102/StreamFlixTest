package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.google.gson.annotations.SerializedName
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.VixcloudExtractor
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.InertiaUtils
import com.streamflixreborn.streamflix.utils.NetworkClient
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import org.json.JSONObject
import com.google.gson.Gson
import org.jsoup.nodes.Document
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query
import android.os.Looper
import com.google.gson.reflect.TypeToken
import java.lang.reflect.Type
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StreamingCommunityProvider(private val _language: String? = null) : Provider {

    private val mutex = Mutex()
    private val totalCounts = mutableMapOf<String, Int>()

    private var cachedGenres: List<Genre>? = null
    private val tmdbArtworkCache = mutableMapOf<String, TmdbUtils.Artwork?>()
    private val artworkSemaphore = Semaphore(permits = 6)

    override val language: String
        get() = _language ?: UserPreferences.currentLanguage ?: "it"

    private val LANG: String
        get() = if (language == "en") "en" else "it"

    private val TAG: String
        get() = "SCProvider[$LANG]"

    private val DEFAULT_DOMAIN: String = "streamingunity.buzz"
    override val baseUrl = DEFAULT_DOMAIN
    private var _domain: String? = null
    private var domain: String
        get() {
            if (!_domain.isNullOrEmpty()) return _domain!!
            val storedDomain = UserPreferences.streamingcommunityDomain
            _domain = if (storedDomain.isNullOrEmpty()) DEFAULT_DOMAIN else storedDomain
            return _domain!!
        }
        set(value) {
            if (value != domain) {
                UserPreferences.clearProviderCache(name)
                _domain = value
                UserPreferences.streamingcommunityDomain = value
                runBlocking { rebuildService(value) }
            }
        }

    override val name: String
        get() = if (language == "it") "StreamingCommunity" else "StreamingCommunity (EN)"

    override val logo get() = if (domain == DEFAULT_DOMAIN) {
        try {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                "https://$DEFAULT_DOMAIN/apple-touch-icon.png"
            } else {
                val resolvedBase = resolveFinalBaseUrl("https://$DEFAULT_DOMAIN/")
                val host = resolvedBase.substringAfter("https://").substringBefore("/")
                if (host.isNotEmpty() && host != domain) {
                    runBlocking { rebuildService(host) }
                    "https://$host/apple-touch-icon.png"
                } else if (host.isNotEmpty() && host == domain) {
                    "https://$domain/apple-touch-icon.png"
                } else {
                    "https://$DEFAULT_DOMAIN/apple-touch-icon.png"
                }
            }
        } catch (_: Exception) {
            "https://$DEFAULT_DOMAIN/apple-touch-icon.png"
        }
    } else {
        "https://$domain/apple-touch-icon.png"
    }

    private val MAX_SEARCH_RESULTS = 60

    private var _service: StreamingCommunityService? = null
    private var _serviceLanguage: String? = null
    private var _serviceDomain: String? = null

    private suspend fun fetchDomainFromTelegraph(): String? = withContext(Dispatchers.IO) {
        try {
            val telegraphUrl = "https://telegra.ph/Link-Aggiornato-StreamingCommunity-09-29"
            val req = okhttp3.Request.Builder().url(telegraphUrl).get().build()

            NetworkClient.default.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val html = resp.body?.string() ?: ""
                    val doc = Jsoup.parse(html)
                    val link = doc.select("a[href*=streamingcommunity]").first()?.attr("href")

                    if (link != null) {
                        val host = link.substringAfter("://").substringBefore("/").replace("www.", "")
                        if (host.isNotEmpty()) return@withContext host
                    }
                }
            }
        } catch (e: Exception) {}
        return@withContext null
    }

    private suspend fun getService(): StreamingCommunityService {
        return mutex.withLock {
            val currentLang = language

            if (_service == null) {
                val freshDomain = fetchDomainFromTelegraph()
                if (freshDomain != null) {
                    _domain = freshDomain
                    UserPreferences.streamingcommunityDomain = freshDomain
                }
            }

            val currentDom = domain
            if (_service == null || _serviceLanguage != currentLang || _serviceDomain != currentDom) {
                val finalBase = resolveFinalBaseUrl("https://$currentDom/")
                val host = finalBase.substringAfter("https://").substringBefore("/")

                _serviceLanguage = currentLang
                _serviceDomain = host
                _domain = host
                _service = StreamingCommunityService.build(finalBase, currentLang, { domain }, { nd ->
                    _domain = nd
                    UserPreferences.streamingcommunityDomain = nd
                }, LANG)
            }
            _service!!
        }
    }

    suspend fun rebuildService(newDomain: String = domain) {
        mutex.withLock {
            val freshDomain = fetchDomainFromTelegraph() ?: newDomain
            val finalBase = resolveFinalBaseUrl("https://$freshDomain/")
            val host = finalBase.substringAfter("https://").substringBefore("/")

            _domain = host
            UserPreferences.streamingcommunityDomain = host
            _serviceLanguage = language
            _serviceDomain = host
            _service = StreamingCommunityService.build(finalBase, language, { domain }, { nd ->
                _domain = nd
                UserPreferences.streamingcommunityDomain = nd
            }, LANG)
        }
    }

    private fun resolveFinalBaseUrl(startBaseUrl: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return startBaseUrl

        return try {
            val client = NetworkClient.default.newBuilder()
                .followRedirects(true)
                .followSslRedirects(true)
                .addInterceptor(RefererInterceptor(startBaseUrl))
                .addInterceptor(UserAgentInterceptor(StreamingCommunityService.USER_AGENT, { language }))
                .build()

            val req = okhttp3.Request.Builder().url(startBaseUrl).get().build()

            client.newCall(req).execute().use { resp ->
                val finalUri = resp.request.url
                finalUri.scheme + "://" + finalUri.host + "/"
            }
        } catch (e: Exception) {
            startBaseUrl
        }
    }

    private suspend fun <T> withSslFallback(block: suspend (StreamingCommunityService) -> T): T {
        return try {
            block(getService())
        } catch (e: Exception) {
            val isSsl = e is javax.net.ssl.SSLHandshakeException || e is java.security.cert.CertPathValidatorException
            if (!isSsl) throw e

            mutex.withLock {
                val finalBase = resolveFinalBaseUrl("https://$domain/")
                val host = finalBase.substringAfter("https://").substringBefore("/")
                _domain = host
                _serviceDomain = host
                _service = StreamingCommunityService.buildUnsafe(finalBase, language, LANG)
            }
            block(getService())
        }
    }

    private var version: String = ""
        get() {
            if (field != "") return field
            synchronized(this) {
                if (field != "") return field
                val document = runBlocking { withSslFallback { it.getHome() } }
                field = InertiaUtils.getVersion(document)
                return field
            }
        }

    private fun getImageLink(filename: String?): String? {
        if (filename.isNullOrEmpty()) return null
        return "https://cdn.$domain/images/$filename"
    }

    private suspend fun getTmdbArtwork(
        show: StreamingCommunityService.Show,
        allowSearchFallback: Boolean = false
    ): TmdbUtils.Artwork? {
        val numericId = show.tmdbId?.toIntOrNull()
        if (numericId == null && !allowSearchFallback) return null

        val releaseYear = show.lastAirDate?.substringBefore("-")?.toIntOrNull()
        val normalizedTitle = show.name.trim().lowercase()
        val key = when {
            numericId != null -> "${show.type}:id:$numericId:$language"
            allowSearchFallback -> "${show.type}:search:$normalizedTitle:$releaseYear:$language"
            else -> "${show.type}:noid:$normalizedTitle:$releaseYear:$language"
        }

        synchronized(tmdbArtworkCache) {
            tmdbArtworkCache[key]?.let { return it }
        }

        val artwork = when (show.type) {
            "movie" -> {
                if (numericId != null) {
                    TmdbUtils.getMovieArtworkById(numericId, language = language)
                } else if (allowSearchFallback) {
                    val tmdbMovie = TmdbUtils.getMovie(show.name, year = releaseYear, language = language)
                    val enriched = tmdbMovie?.id?.toIntOrNull()?.let { TmdbUtils.getMovieArtworkById(it, language = language) }
                    enriched ?: tmdbMovie?.let { movie ->
                        TmdbUtils.Artwork(
                            poster = movie.poster,
                            banner = movie.banner,
                            heroPoster = movie.banner,
                            hasTextlessHero = false
                        )
                    }
                } else {
                    null
                }
            }

            else -> {
                if (numericId != null) {
                    TmdbUtils.getTvShowArtworkById(numericId, language = language)
                } else if (allowSearchFallback) {
                    val tmdbTv = TmdbUtils.getTvShow(show.name, year = releaseYear, language = language)
                    val enriched = tmdbTv?.id?.toIntOrNull()?.let { TmdbUtils.getTvShowArtworkById(it, language = language) }
                    enriched ?: tmdbTv?.let { tvShow ->
                        TmdbUtils.Artwork(
                            poster = tvShow.poster,
                            banner = tvShow.banner,
                            heroPoster = tvShow.banner,
                            hasTextlessHero = false
                        )
                    }
                } else {
                    null
                }
            }
        }

        synchronized(tmdbArtworkCache) {
            if (artwork != null) {
                tmdbArtworkCache[key] = artwork
            }
        }
        return artwork
    }

    private suspend fun mapShowsFast(
        shows: List<StreamingCommunityService.Show>,
        heroOnlyTextless: Boolean = false,
        allowSearchFallback: Boolean = false,
        searchFallbackLimit: Int = Int.MAX_VALUE,
    ): List<com.streamflixreborn.streamflix.models.Show> = coroutineScope {
        shows.mapIndexed { index, title ->
            async {
                artworkSemaphore.withPermit {
                    val providerBanner = getImageLink(
                        title.images.findLast { img -> img.type == "background" || img.type == "backdrop" }?.filename
                            ?: title.images.findLast { img -> img.type == "cover" }?.filename
                            ?: title.images.find { img -> img.type == "cover" }?.filename
                    )
                    val providerPoster = getImageLink(
                        title.images.findLast { img -> img.type == "poster" }?.filename
                            ?: title.images.find { img -> img.type == "poster" }?.filename
                    )

                val shouldAllowSearchFallback = allowSearchFallback && index < searchFallbackLimit
                val tmdbArtwork = getTmdbArtwork(title, allowSearchFallback = shouldAllowSearchFallback)
                if (heroOnlyTextless && (tmdbArtwork?.hasTextlessHero != true)) {
                    return@async null
                }
                val finalPoster = tmdbArtwork?.poster ?: providerPoster
                val finalBanner = tmdbArtwork?.heroPoster ?: tmdbArtwork?.banner ?: providerBanner ?: finalPoster

                    if (title.type == "movie") {
                        Movie(
                            id = title.id + "-" + title.slug,
                            title = title.name,
                            released = title.lastAirDate,
                            rating = title.score,
                            poster = finalPoster,
                            banner = finalBanner
                        )
                    } else {
                        TvShow(
                            id = title.id + "-" + title.slug,
                            title = title.name,
                            released = title.lastAirDate,
                            rating = title.score,
                            poster = finalPoster,
                            banner = finalBanner
                        )
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    override suspend fun getHome(): List<Category> {
        val res: StreamingCommunityService.HomeRes = try {
            if (version.isEmpty()) {
                val json = InertiaUtils.parseInertiaData(withSslFallback { it.getHome() })
                Gson().fromJson(json.toString(), StreamingCommunityService.HomeRes::class.java).also {
                    if (version != it.version) version = it.version
                }
            } else {
                try {
                    withSslFallback { it.getHome(version = version) }.also { fetched ->
                        if (version != fetched.version) version = fetched.version
                    }
                } catch (e: Exception) {
                    val json = InertiaUtils.parseInertiaData(withSslFallback { it.getHome() })
                    Gson().fromJson(json.toString(), StreamingCommunityService.HomeRes::class.java).also {
                        if (version != it.version) version = it.version
                    }
                }
            }
        } catch (e: Exception) { throw e }

        val sliders = res.props.sliders ?: listOf()
        val categories = mutableListOf<Category>()

        val heroSlider = sliders.find { it.name == "hero" } ?: sliders.firstOrNull()
        if (heroSlider != null) {
            val textlessHeroShows = mapShowsFast(
                heroSlider.titles,
                heroOnlyTextless = true,
                allowSearchFallback = true
            ).take(10)
            if (textlessHeroShows.isNotEmpty()) {
                categories.add(Category(name = Category.FEATURED, list = textlessHeroShows))
            }
        }

        val processedSliderNames = mutableSetOf<String>()
        if (heroSlider != null) processedSliderNames.add(heroSlider.name)

        val propsMapping = listOf(
            "I titoli del momento" to (res.props.trendingTitles ?: res.props.trending),
            "Film aggiunti di recente" to res.props.latestMovies,
            "Serie TV aggiunte di recente" to res.props.latestTvShows,
            "Top 10 titoli di oggi" to (res.props.top10Titles ?: res.props.top10),
            "In arrivo" to (res.props.upcomingTitles ?: res.props.upcoming)
        )
        propsMapping.forEach { (name, list) ->
            if (list != null && list.isNotEmpty()) {
                categories.add(
                    Category(
                        name,
                        mapShowsFast(
                            list,
                            allowSearchFallback = true,
                            searchFallbackLimit = 40
                        )
                    )
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
                            mapShowsFast(
                                slider.titles,
                                allowSearchFallback = true,
                                searchFallbackLimit = 40
                            )
                        )
                    )
                }
            }
        }

        return categories.filter { it.list.isNotEmpty() }.distinctBy { it.name.lowercase().trim() }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            if (cachedGenres != null) return cachedGenres!!
            val res = try {
                withSslFallback { it.getHome(version = version) }
            } catch (e: Exception) {
                val json = InertiaUtils.parseInertiaData(withSslFallback { it.getHome() })
                Gson().fromJson(json.toString(), StreamingCommunityService.HomeRes::class.java)
            }
            if (version != res.version) version = res.version

            // Creiamo la lista dei generi e rinominiamo i cubi magici per evitare incomprensioni
            val dynamicGenres = res.props.genres.map { Genre(id = it.id, name = it.name) }.sortedBy { it.name }.toMutableList()
            dynamicGenres.add(0, Genre(id = "Tutti i Film", name = "🍿 Tutti i Film"))
            dynamicGenres.add(1, Genre(id = "Tutte le Serie TV", name = "📺 Tutte le Serie TV"))

            cachedGenres = dynamicGenres
            return cachedGenres!!
        }
        val res = withSslFallback { it.search(query, (page - 1) * MAX_SEARCH_RESULTS, LANG) }
        if (res.currentPage == null || res.lastPage == null || res.currentPage > res.lastPage) return listOf()
        return mapShowsFast(
            res.data.distinctBy { it.id },
            allowSearchFallback = true,
            searchFallbackLimit = 30
        )
    }

    private fun getTitlesFromInertiaJson(json: JSONObject): List<StreamingCommunityService.Show> {
        val gson = Gson()
        val showListType: Type = object : TypeToken<List<StreamingCommunityService.Show>>() {}.type
        val props = json.optJSONObject("props") ?: return listOf()
        if (props.has("titles") && props.optJSONArray("titles") != null) {
            val jsonArray = props.optJSONArray("titles")
            return gson.fromJson<List<StreamingCommunityService.Show>>(jsonArray?.toString() ?: "[]", showListType) ?: listOf()
        }
        val res: StreamingCommunityService.ArchiveRes? = try { gson.fromJson(json.toString(), StreamingCommunityService.ArchiveRes::class.java) } catch (e: Exception) { null }
        res?.version?.let { version = it }
        return res?.props?.let { p -> p.archive?.data ?: p.titles?.data ?: p.movies?.data ?: p.tv?.data ?: p.tvShows?.data } ?: listOf()
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        val offset = (page - 1) * 60
        val shows = try {
            if (page == 1) {
                getTitlesFromInertiaJson(InertiaUtils.parseInertiaData(withSslFallback { it.getMoviesHtml() }))
            } else {
                withSslFallback { it.getArchiveApi(lang = language, offset = offset, type = "movie") }.titles
            }
        } catch (e: Exception) { listOf() }
        return mapShowsFast(
            shows.distinctBy { it.id },
            allowSearchFallback = true,
            searchFallbackLimit = 45
        ).filterIsInstance<Movie>()
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        val offset = (page - 1) * 60
        val shows = try {
            if (page == 1) {
                getTitlesFromInertiaJson(InertiaUtils.parseInertiaData(withSslFallback { it.getTvShowsHtml() }))
            } else {
                withSslFallback { it.getArchiveApi(lang = language, offset = offset, type = "tv") }.titles
            }
        } catch (e: Exception) { listOf() }
        return mapShowsFast(
            shows.distinctBy { it.id },
            allowSearchFallback = true,
            searchFallbackLimit = 45
        ).filterIsInstance<TvShow>()
    }

    override suspend fun getMovie(id: String): Movie = coroutineScope {
        val resDeferred = async {
            try {
                withSslFallback { it.getDetails(id, version = version, language = LANG) }.also { if (version != it.version) version = it.version }
            } catch (e: Exception) {
                val doc = StreamingCommunityService.fetchDocumentWithRedirectsAndSslFallback("https://$domain/$LANG/titles/$id", "https://$domain/", language)
                Gson().fromJson(InertiaUtils.parseInertiaData(doc).toString(), StreamingCommunityService.HomeRes::class.java).also { if (version != it.version) version = it.version }
            }
        }

        val res = resDeferred.await()
        val title = res.props.title
        val tmdbMovie = title.tmdbId?.toIntOrNull()?.let { TmdbUtils.getMovieById(it, language = language) }

        return@coroutineScope Movie(
            id = id, title = tmdbMovie?.title ?: title.name, overview = tmdbMovie?.overview ?: title.plot, released = title.lastAirDate, rating = title.score, quality = title.quality, runtime = title.runtime,
            poster = tmdbMovie?.poster ?: getImageLink(title.images.findLast { it.type == "poster" }?.filename),
            banner = tmdbMovie?.banner
                ?: tmdbMovie?.poster
                ?: getImageLink(title.images.findLast { it.type == "background" || it.type == "backdrop" }?.filename ?: title.images.findLast { it.type == "cover" }?.filename),
            genres = title.genres?.map { Genre(id = it.id, name = it.name) } ?: listOf(),
            cast = title.actors?.map { actor ->
                val tmdbPerson = tmdbMovie?.cast?.find { p -> p.name.equals(actor.name, ignoreCase = true) }
                People(id = actor.name, name = actor.name, image = tmdbPerson?.image)
            } ?: listOf(),
            trailer = title.trailers?.find { t -> t.youtubeId != "" }?.youtubeId?.let { yid -> "https://youtube.com/watch?v=$yid" },
            recommendations = mapShowsFast(
                res.props.sliders?.find { it.titles.isNotEmpty() }?.titles ?: listOf(),
                allowSearchFallback = true,
                searchFallbackLimit = 20
            )
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val resDeferred = async {
            try {
                withSslFallback { it.getDetails(id, version = version, language = LANG) }.also { if (version != it.version) version = it.version }
            } catch (e: Exception) {
                val doc = StreamingCommunityService.fetchDocumentWithRedirectsAndSslFallback("https://$domain/$LANG/titles/$id", "https://$domain/", language)
                Gson().fromJson(InertiaUtils.parseInertiaData(doc).toString(), StreamingCommunityService.HomeRes::class.java).also { if (version != it.version) version = it.version }
            }
        }

        val res = resDeferred.await()
        val title = res.props.title
        val tmdbShow = title.tmdbId?.toIntOrNull()?.let { TmdbUtils.getTvShowById(it, language = language) }

        return@coroutineScope TvShow(id = id, title = tmdbShow?.title ?: title.name, overview = tmdbShow?.overview ?: title.plot, released = title.lastAirDate, rating = title.score, quality = title.quality,
            poster = tmdbShow?.poster ?: getImageLink(title.images.findLast { it.type == "poster" }?.filename),
            banner = tmdbShow?.banner
                ?: tmdbShow?.poster
                ?: getImageLink(title.images.findLast { it.type == "background" || it.type == "backdrop" }?.filename ?: title.images.findLast { it.type == "cover" }?.filename),
            genres = title.genres?.map { Genre(id = it.id, name = it.name) } ?: listOf(),
            cast = title.actors?.map { actor ->
                val tmdbPerson = tmdbShow?.cast?.find { p -> p.name.equals(actor.name, ignoreCase = true) }
                People(id = actor.name, name = actor.name, image = tmdbPerson?.image)
            } ?: listOf(),
            trailer = title.trailers?.find { t -> t.youtubeId != "" }?.youtubeId?.let { yid -> "https://youtube.com/watch?v=$yid" },
            recommendations = mapShowsFast(
                res.props.sliders?.find { it.titles.isNotEmpty() }?.titles ?: listOf(),
                allowSearchFallback = true,
                searchFallbackLimit = 20
            ),
            seasons = title.seasons?.map { s ->
                val seasonNumber = s.number.toIntOrNull() ?: (title.seasons.indexOf(s) + 1)
                Season(id = "$id/season-${s.number}", number = seasonNumber, title = s.name, poster = tmdbShow?.seasons?.find { ts -> ts.number == seasonNumber }?.poster)
            } ?: listOf())
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val res: StreamingCommunityService.SeasonRes = try {
            withSslFallback { it.getSeasonDetails(seasonId, version = version, language = LANG) }
        } catch (e: Exception) {
            val doc = StreamingCommunityService.fetchDocumentWithRedirectsAndSslFallback("https://$domain/$LANG/titles/$seasonId", "https://$domain/", language)
            Gson().fromJson(InertiaUtils.parseInertiaData(doc).toString(), StreamingCommunityService.SeasonRes::class.java)
        }
        return res.props.loadedSeason.episodes.map {
            Episode(id = "${seasonId.substringBefore("-")}?episode_id=${it.id}", number = it.number.toIntOrNull() ?: (res.props.loadedSeason.episodes.indexOf(it) + 1), title = it.name, poster = getImageLink(it.images.findLast { img -> img.type == "cover" }?.filename), overview = it.plot)
        }
    }

    // NIENTE PIÙ ORDINAMENTO ALFABETICO FAKE: I risultati arrivano puliti dal server e sono ordinati per ultimi usciti
    override suspend fun getGenre(id: String, page: Int): Genre {
        val offset = (page - 1) * 60
        var actualType: String? = null
        var actualGenreId: String? = null
        var displayName = id

        when {
            id == "Tutti i Film" -> { actualType = "movie" }
            id == "Tutte le Serie TV" -> { actualType = "tv" }
            id.startsWith("Film: ") -> {
                actualType = "movie"
                val genreName = id.removePrefix("Film: ")
                displayName = genreName
                val genres = search("", 1).filterIsInstance<Genre>()
                actualGenreId = genres.find { it.name.equals(genreName, ignoreCase = true) }?.id
            }
            id.startsWith("Serie TV: ") -> {
                actualType = "tv"
                val genreName = id.removePrefix("Serie TV: ")
                displayName = genreName
                val genres = search("", 1).filterIsInstance<Genre>()
                actualGenreId = genres.find { it.name.equals(genreName, ignoreCase = true) }?.id
            }
            else -> {
                val genres = search("", 1).filterIsInstance<Genre>()
                actualGenreId = genres.find { it.name.equals(id, ignoreCase = true) }?.id ?: id
            }
        }

        totalCounts[id]?.let { total -> if (offset >= total) return Genre(id = id, name = displayName, shows = emptyList()) }

        val shows = try {
            if (page == 1) {
                val json = InertiaUtils.parseInertiaData(withSslFallback { it.getArchiveHtml(genreId = actualGenreId, type = actualType) })
                val props = json.optJSONObject("props")
                if (props != null) {
                    val total = props.optInt("totalCount", 0)
                    if (total > 0) totalCounts[id] = total
                }
                getTitlesFromInertiaJson(json)
            } else {
                withSslFallback { it.getArchiveApi(lang = language, offset = offset, genreId = actualGenreId, type = actualType) }.titles
            }
        } catch (e: Exception) { listOf() }

        return Genre(
            id = id,
            name = displayName,
            shows = mapShowsFast(
                shows.distinctBy { it.id },
                allowSearchFallback = true,
                searchFallbackLimit = 35
            )
        )
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val res = withSslFallback { it.search(id, (page - 1) * MAX_SEARCH_RESULTS, LANG) }
        if (res.currentPage == null || res.lastPage == null || res.currentPage > res.lastPage) return People(id = id, name = id)
        return People(
            id = id,
            name = id,
            filmography = mapShowsFast(
                res.data.distinctBy { it.id },
                allowSearchFallback = true,
                searchFallbackLimit = 30
            )
        )
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val base = "https://$domain/"
        val iframeUrl = when (videoType) {
            is Video.Type.Movie -> base + "$LANG/iframe/" + id.substringBefore("-") + "?language=$LANG"
            is Video.Type.Episode -> base + "$LANG/iframe/" + id.substringBefore("?") + "?episode_id=" + id.substringAfter("=") + "&next_episode=1" + "&language=$LANG"
        }
        val document = StreamingCommunityService.fetchDocumentWithRedirectsAndSslFallback(iframeUrl, base, language)
        val src = document.selectFirst("iframe")?.attr("src") ?: ""
        if (src.isEmpty()) return listOf()
        return listOf(Video.Server(id = id, name = "Vixcloud", src = src))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return VixcloudExtractor(language).extract(server.src)
    }

    private class UserAgentInterceptor(private val userAgent: String, private val languageProvider: () -> String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val language = languageProvider()
            val requestBuilder = chain.request().newBuilder()
                .header("User-Agent", userAgent)
                .header("Accept-Language", if (language == "en") "en-US,en;q=0.9" else "it-IT,it;q=0.9")
                .header("Cookie", "language=$language")
            return chain.proceed(requestBuilder.build())
        }
    }

    private class RefererInterceptor(private val referer: String) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request().newBuilder().header("Referer", referer).build())
    }

    private class RedirectInterceptor(private val domainProvider: () -> String, private val onDomainChanged: (String) -> Unit) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var request = chain.request()
            var response = chain.proceed(request)
            val visited = mutableSetOf<String>()
            val currentDomain = domainProvider()
            while (response.isRedirect) {
                val location = response.header("Location") ?: break
                val newUrl = if (location.startsWith("http")) location else request.url.resolve(location)?.toString() ?: break
                if (!visited.add(newUrl)) break
                val host = newUrl.substringAfter("https://").substringBefore("/")
                if (host.isNotEmpty() && host != currentDomain && !host.contains("streamingcommunityz.green")) onDomainChanged(host)
                response.close()
                request = request.newBuilder().url(newUrl).build()
                response = chain.proceed(request)
            }
            return response
        }
    }

    private class RateLimitInterceptor(
        private val minIntervalMs: Long = 350L,
    ) : Interceptor {
        companion object {
            private val lock = Any()
            private var lastRequestAt: Long = 0L
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            synchronized(lock) {
                val now = System.currentTimeMillis()
                val waitMs = minIntervalMs - (now - lastRequestAt)
                if (waitMs > 0) {
                    Thread.sleep(waitMs)
                }
                lastRequestAt = System.currentTimeMillis()
            }
            return chain.proceed(chain.request())
        }
    }

    private interface StreamingCommunityService {
        companion object {
            const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

            fun build(baseUrl: String, language: String, domainProvider: () -> String, onDomainChanged: (String) -> Unit, lang: String): StreamingCommunityService {
                val client = NetworkClient.default.newBuilder()
                    .addInterceptor(RateLimitInterceptor())
                    .addInterceptor(RefererInterceptor(baseUrl))
                    .addInterceptor(UserAgentInterceptor(USER_AGENT, { language }))
                    .addInterceptor(RedirectInterceptor(domainProvider, onDomainChanged))
                    .build()

                return Retrofit.Builder()
                    .baseUrl("$baseUrl$lang/")
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                    .create(StreamingCommunityService::class.java)
            }

            fun buildUnsafe(baseUrl: String, language: String, lang: String): StreamingCommunityService {
                val client = NetworkClient.trustAll.newBuilder()
                    .addInterceptor(RateLimitInterceptor())
                    .addInterceptor(RefererInterceptor(baseUrl))
                    .addInterceptor(UserAgentInterceptor(USER_AGENT, { language }))
                    .build()

                return Retrofit.Builder()
                    .baseUrl("$baseUrl$lang/")
                    .addConverterFactory(JsoupConverterFactory.create())
                    .addConverterFactory(GsonConverterFactory.create())
                    .client(client)
                    .build()
                    .create(StreamingCommunityService::class.java)
            }

            fun fetchDocumentWithRedirectsAndSslFallback(url: String, referer: String, language: String): Document {
                val client = NetworkClient.default.newBuilder()
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .addInterceptor(RefererInterceptor(referer))
                    .addInterceptor(UserAgentInterceptor(USER_AGENT, { language }))
                    .build()

                return try {
                    client.newCall(okhttp3.Request.Builder().url(url).header("X-Requested-With", "XMLHttpRequest").get().build()).execute().use { resp ->
                        Jsoup.parse(resp.body?.string() ?: "")
                    }
                } catch (e: Exception) { Jsoup.parse("") }
            }
        }

        @GET("./") suspend fun getHome(): Document
        @GET("./") suspend fun getHome(@Header("x-inertia") xInertia: String = "true", @Header("x-inertia-version") version: String, @Header("X-Requested-With") xRequestedWith: String = "XMLHttpRequest"): HomeRes
        @GET("archive?type=movie") suspend fun getMoviesHtml(): Document
        @GET("archive?type=tv") suspend fun getTvShowsHtml(): Document
        @GET("/api/search") suspend fun search(@Query("q", encoded = true) keyword: String, @Query("offset") offset: Int = 0, @Query("lang") language: String): SearchRes
        @GET("/api/archive") suspend fun getArchiveApi(@Query("lang") lang: String, @Query("offset") offset: Int, @Query("genre[]") genreId: String? = null, @Query("type") type: String? = null): ApiArchiveRes
        @GET("archive") suspend fun getArchiveHtml(@Query("genre[]") genreId: String? = null, @Query("type") type: String? = null): Document
        @GET("titles/{id}") suspend fun getDetails(@Path("id") id: String, @Header("x-inertia") xInertia: String = "true", @Header("x-inertia-version") version: String, @Query("lang") language: String, @Header("X-Requested-With") xRequestedWith: String = "XMLHttpRequest"): HomeRes
        @GET("titles/{id}/") suspend fun getSeasonDetails(@Path("id") id: String, @Header("x-inertia") xInertia: String = "true", @Header("x-inertia-version") version: String, @Query("lang") language: String, @Header("X-Requested-With") xRequestedWith: String = "XMLHttpRequest"): SeasonRes

        data class Image(val filename: String, val type: String, val locale: String? = null)
        data class Genre(val id: String, val name: String)
        data class Actor(val id: String, val name: String)
        data class Trailer(@SerializedName("youtube_id") val youtubeId: String?)
        data class Season(val number: String, val name: String?)
        data class Show(val id: String, val name: String, val type: String, @SerializedName("tmdb_id") val tmdbId: String?, val score: Double, @SerializedName("last_air_date") val lastAirDate: String?, val images: List<Image>, val slug: String, val plot: String?, val genres: List<Genre>?, @SerializedName("main_actors") val actors: List<Actor>?, val trailers: List<Trailer>?, val seasons: List<Season>?, val quality: String?, val runtime: Int?)
        data class Slider(val label: String?, val name: String, val titles: List<Show>)
        data class Props(
            val genres: List<Genre>,
            val sliders: List<Slider>?,
            val archive: ArchivePage?,
            val titles: ArchivePage?,
            val movies: ArchivePage?,
            val tv: ArchivePage?,
            @SerializedName("tv_shows") val tvShows: ArchivePage?,
            @SerializedName("latest_movies") val latestMovies: List<Show>?,
            @SerializedName("latest_tv_shows") val latestTvShows: List<Show>?,
            @SerializedName("trending_titles") val trendingTitles: List<Show>?,
            @SerializedName("trending") val trending: List<Show>?,
            @SerializedName("top_10_titles") val top10Titles: List<Show>?,
            @SerializedName("top_10") val top10: List<Show>?,
            @SerializedName("upcoming_titles") val upcomingTitles: List<Show>?,
            @SerializedName("upcoming") val upcoming: List<Show>?,
            val title: Show
        )
        data class HomeRes(val version: String, val props: Props)
        data class SearchRes(val data: List<Show>, @SerializedName("current_page") val currentPage: Int?, @SerializedName("last_page") val lastPage: Int?)
        data class SeasonPropsEpisodes(val id: String, val images: List<Image>, val name: String, val number: String, val plot: String? = null)
        data class SeasonPropsDetails(val episodes: List<SeasonPropsEpisodes>)
        data class SeasonProps(val loadedSeason: SeasonPropsDetails)
        data class SeasonRes(val version: String, val props: SeasonProps)
        data class ArchivePage(val data: List<Show>?, @SerializedName("current_page") val currentPage: Int?, @SerializedName("last_page") val lastPage: Int?)
        data class ArchiveProps(val archive: ArchivePage?, val titles: ArchivePage?, val movies: ArchivePage?, val tv: ArchivePage?, @SerializedName("tv_shows") val tvShows: ArchivePage?)
        data class ArchiveRes(val version: String, val props: ArchiveProps?)
        data class ApiArchiveRes(val titles: List<Show>)
    }
}
