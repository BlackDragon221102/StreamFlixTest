package com.streamflixreborn.streamflix.providers

import android.util.Log
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
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import com.google.gson.Gson
import android.os.Looper
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.jsoup.Jsoup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class StreamingCommunityProvider private constructor(
    private val _language: String?,
    private val serviceFactory: StreamingCommunityServiceFactoryContract = StreamingCommunityServiceFactory,
    private val mapper: StreamingCommunityMapper = StreamingCommunityMapper(),
    private val catalogCoordinator: StreamingCommunityCatalogCoordinator = StreamingCommunityCatalogCoordinator(),
    private val detailsCoordinator: StreamingCommunityDetailsCoordinator = StreamingCommunityDetailsCoordinator(),
    artworkResolver: StreamingCommunityArtworkResolver? = null,
) : Provider {

    constructor(_language: String? = null) : this(
        _language = _language,
        serviceFactory = StreamingCommunityServiceFactory,
        mapper = StreamingCommunityMapper(),
        catalogCoordinator = StreamingCommunityCatalogCoordinator(),
        detailsCoordinator = StreamingCommunityDetailsCoordinator(),
        artworkResolver = null
    )

    private val mutex = Mutex()
    private val totalCounts = mutableMapOf<String, Int>()

    private var cachedGenres: List<Genre>? = null
    private val artworkSemaphore = Semaphore(permits = 6)
    private val artworkResolver = artworkResolver ?: StreamingCommunityArtworkResolver(
        imageLinkResolver = { filename -> getImageLink(filename) }
    )

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
                cachedGenres = null
                totalCounts.clear()
                version = null
                _service = null
                _serviceDomain = null
                _serviceLanguage = null
            }
        }

    override val name: String
        get() = if (language == "it") "StreamingCommunity" else "StreamingCommunity (EN)"

    override val logo: String
        get() = "https://${domain.ifBlank { DEFAULT_DOMAIN }}/apple-touch-icon.png"

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
                _service = serviceFactory.build(finalBase, currentLang, { domain }, { nd ->
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
            cachedGenres = null
            totalCounts.clear()
            _service = serviceFactory.build(finalBase, language, { domain }, { nd ->
                _domain = nd
                UserPreferences.streamingcommunityDomain = nd
            }, LANG)
        }
    }

    private fun resolveFinalBaseUrl(startBaseUrl: String): String {
        if (Looper.myLooper() == Looper.getMainLooper()) return startBaseUrl

        return try {
            serviceFactory.resolveFinalBaseUrl(startBaseUrl, language)
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
                _service = serviceFactory.buildUnsafe(finalBase, language, LANG)
            }
            block(getService())
        }
    }

    private var version: String? = null

    private fun currentVersionOrNull(): String? = version?.takeIf { it.isNotBlank() }

    private fun updateVersion(candidate: String?) {
        candidate
            ?.takeIf { it.isNotBlank() }
            ?.let { version = it }
    }

    private suspend fun ensureVersion(): String {
        currentVersionOrNull()?.let { return it }

        return mutex.withLock {
            currentVersionOrNull()?.let { return@withLock it }

            runCatching {
                val document = withSslFallback { it.getHome() }
                InertiaUtils.getVersion(document)
            }.getOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.also { version = it }
                ?: "1"
        }
    }

    private fun getImageLink(filename: String?): String? {
        if (filename.isNullOrEmpty()) return null
        return "https://cdn.$domain/images/$filename"
    }

    private suspend fun mapShowsFast(
        shows: List<StreamingCommunityShow>,
        heroOnlyTextless: Boolean = false,
        allowSearchFallback: Boolean = false,
        searchFallbackLimit: Int = Int.MAX_VALUE,
    ): List<com.streamflixreborn.streamflix.models.Show> = coroutineScope {
        shows.mapIndexed { index, title ->
            async {
                artworkSemaphore.withPermit {
                    val shouldAllowSearchFallback = allowSearchFallback && index < searchFallbackLimit
                    val resolvedArtwork = artworkResolver.resolve(
                        show = title,
                        allowSearchFallback = shouldAllowSearchFallback,
                        heroOnlyTextless = heroOnlyTextless
                    ) ?: return@async null
                    mapper.mapCatalogShow(
                        show = title,
                        poster = resolvedArtwork.poster,
                        banner = resolvedArtwork.banner
                    )
                }
            }
        }.awaitAll().filterNotNull()
    }

    override suspend fun getHome(): List<Category> {
        val res: StreamingCommunityHomeRes = try {
            if (currentVersionOrNull() == null) {
                val json = InertiaUtils.parseInertiaData(withSslFallback { it.getHome() })
                Gson().fromJson(json.toString(), StreamingCommunityHomeRes::class.java).also {
                    updateVersion(it.version)
                }
            } else {
                try {
                    val resolvedVersion = currentVersionOrNull() ?: "1"
                    withSslFallback { it.getHome(version = resolvedVersion) }.also { fetched ->
                        updateVersion(fetched.version)
                    }
                } catch (e: Exception) {
                    val json = InertiaUtils.parseInertiaData(withSslFallback { it.getHome() })
                    Gson().fromJson(json.toString(), StreamingCommunityHomeRes::class.java).also {
                        updateVersion(it.version)
                    }
                }
            }
        } catch (e: Exception) { throw e }

        return catalogCoordinator.buildHomeCategories(res) { shows, heroOnlyTextless, allowSearchFallback, searchFallbackLimit ->
            mapShowsFast(
                shows = shows,
                heroOnlyTextless = heroOnlyTextless,
                allowSearchFallback = allowSearchFallback,
                searchFallbackLimit = searchFallbackLimit,
            )
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) {
            if (cachedGenres != null) return cachedGenres!!
            val currentVersion = ensureVersion()
            val res = try {
                withSslFallback { it.getHome(version = currentVersion) }
            } catch (e: Exception) {
                val json = InertiaUtils.parseInertiaData(withSslFallback { it.getHome() })
                Gson().fromJson(json.toString(), StreamingCommunityHomeRes::class.java)
            }
            if (version != res.version) version = res.version

            // Creiamo la lista dei generi e rinominiamo i cubi magici per evitare incomprensioni
            cachedGenres = catalogCoordinator.buildSearchGenres(res)
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

    private fun getTitlesFromInertiaJson(json: JSONObject): List<StreamingCommunityShow> =
        mapper.parseTitlesFromInertiaJson(json) { parsedVersion -> updateVersion(parsedVersion) }

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
            val currentVersion = ensureVersion()
            try {
                withSslFallback { it.getDetails(id, version = currentVersion, language = LANG) }.also { updateVersion(it.version) }
            } catch (e: Exception) {
                val doc = serviceFactory.fetchDocumentWithRedirectsAndSslFallback("https://$domain/$LANG/titles/$id", "https://$domain/", language)
                Gson().fromJson(InertiaUtils.parseInertiaData(doc).toString(), StreamingCommunityHomeRes::class.java).also { updateVersion(it.version) }
            }
        }

        val res = resDeferred.await()
        val title = res.props.title
        val detailArtwork = artworkResolver.resolve(
            show = title,
            allowSearchFallback = true
        )
        val tmdbMovie = detailsCoordinator.resolveMovieTmdb(title, language)

        return@coroutineScope mapper.mapMovieDetails(
            id = id,
            title = title,
            tmdbMovie = tmdbMovie,
            providerPoster = detailArtwork?.poster ?: getImageLink(title.images.findLast { it.type == "poster" }?.filename),
            providerBanner = detailArtwork?.banner ?: getImageLink(
                title.images.findLast { it.type == "background" || it.type == "backdrop" }?.filename
                    ?: title.images.findLast { it.type == "cover" }?.filename
            ),
            recommendations = mapShowsFast(
                res.props.sliders?.find { it.titles.isNotEmpty() }?.titles ?: listOf(),
                allowSearchFallback = true,
                searchFallbackLimit = 20
            )
        )
    }

    override suspend fun getTvShow(id: String): TvShow = coroutineScope {
        val resDeferred = async {
            val currentVersion = ensureVersion()
            try {
                withSslFallback { it.getDetails(id, version = currentVersion, language = LANG) }.also { updateVersion(it.version) }
            } catch (e: Exception) {
                val doc = serviceFactory.fetchDocumentWithRedirectsAndSslFallback("https://$domain/$LANG/titles/$id", "https://$domain/", language)
                Gson().fromJson(InertiaUtils.parseInertiaData(doc).toString(), StreamingCommunityHomeRes::class.java).also { updateVersion(it.version) }
            }
        }

        val res = resDeferred.await()
        val title = res.props.title
        val detailArtwork = artworkResolver.resolve(
            show = title,
            allowSearchFallback = true
        )
        val tmdbShow = detailsCoordinator.resolveTvTmdb(title, language)

        return@coroutineScope mapper.mapTvShowDetails(
            id = id,
            title = title,
            tmdbShow = tmdbShow,
            providerPoster = detailArtwork?.poster ?: getImageLink(title.images.findLast { it.type == "poster" }?.filename),
            providerBanner = detailArtwork?.banner ?: getImageLink(
                title.images.findLast { it.type == "background" || it.type == "backdrop" }?.filename
                    ?: title.images.findLast { it.type == "cover" }?.filename
            ),
            recommendations = mapShowsFast(
                res.props.sliders?.find { it.titles.isNotEmpty() }?.titles ?: listOf(),
                allowSearchFallback = true,
                searchFallbackLimit = 20
            )
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val currentVersion = ensureVersion()
        val res: StreamingCommunitySeasonRes = try {
            withSslFallback { it.getSeasonDetails(seasonId, version = currentVersion, language = LANG) }
        } catch (e: Exception) {
            val doc = serviceFactory.fetchDocumentWithRedirectsAndSslFallback("https://$domain/$LANG/titles/$seasonId", "https://$domain/", language)
            Gson().fromJson(InertiaUtils.parseInertiaData(doc).toString(), StreamingCommunitySeasonRes::class.java)
        }
        return mapper.mapSeasonEpisodes(
            seasonId = seasonId,
            episodes = res.props.loadedSeason.episodes,
            imageLinkResolver = { filename -> getImageLink(filename) }
        )
    }

    // NIENTE PIÙ ORDINAMENTO ALFABETICO FAKE: I risultati arrivano puliti dal server e sono ordinati per ultimi usciti
    override suspend fun getGenre(id: String, page: Int): Genre =
        getGenre(id = id, page = page, sort = "release_date")

    suspend fun getGenre(id: String, page: Int, sort: String?): Genre {
        val offset = (page - 1) * 60
        val genreRequest = catalogCoordinator.resolveGenreRequest(
            id = id,
            availableGenres = search("", 1).filterIsInstance<Genre>(),
        )

        totalCounts[id]?.let { total ->
            if (offset >= total) {
                return catalogCoordinator.buildGenre(
                    id = id,
                    name = genreRequest.displayName,
                    shows = emptyList(),
                )
            }
        }

        val shows = try {
            if (page == 1) {
                val json = InertiaUtils.parseInertiaData(
                    withSslFallback {
                        it.getArchiveHtml(
                            genreId = genreRequest.actualGenreId,
                            type = genreRequest.actualType,
                            sort = sort
                        )
                    }
                )
                val props = json.optJSONObject("props")
                if (props != null) {
                    val total = props.optInt("totalCount", 0)
                    if (total > 0) totalCounts[id] = total
                }
                getTitlesFromInertiaJson(json)
            } else {
                withSslFallback {
                    it.getArchiveApi(
                        lang = language,
                        offset = offset,
                        genreId = genreRequest.actualGenreId,
                        type = genreRequest.actualType,
                        sort = sort
                    )
                }.titles
            }
        } catch (e: Exception) { listOf() }

        return catalogCoordinator.buildGenre(
            id = id,
            name = genreRequest.displayName,
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
        val document = serviceFactory.fetchDocumentWithRedirectsAndSslFallback(iframeUrl, base, language)
        val src = document.selectFirst("iframe")?.attr("src") ?: ""
        if (src.isEmpty()) return listOf()
        return listOf(Video.Server(id = id, name = "Vixcloud", src = src))
    }

    override suspend fun getVideo(server: Video.Server): Video {
        return VixcloudExtractor(language).extract(server.src)
    }

}
