package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // SUA API KEY DO TMDB (funcional!)
    private val tmdbApiKey = "f9a1e262f2251496b1efa1cd5759680a"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    // =========================================================================
    // PÁGINA PRINCIPAL
    // =========================================================================
    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    // =========================================================================
    // PÁGINA PRINCIPAL
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = document.select("a.card, div.recs-grid a.rec-card").mapNotNull { element ->
            element.toSearchResult()
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    // =========================================================================
    // FUNÇÃO AUXILIAR
    // =========================================================================
    private fun Element.toSearchResult(): SearchResponse? {
        val title = attr("title") ?: selectFirst("img")?.attr("alt") ?: return null
        val href = attr("href") ?: return null

        val localPoster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Verifica tipo de conteúdo
        val badge = selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
        val isAnime = badge.contains("anime") || href.contains("/anime/") || 
                      title.contains("(Anime)", ignoreCase = true)
        val isSerie = badge.contains("série") || badge.contains("serie") || 
                     href.contains("/serie/") || 
                     (!isAnime && (badge.contains("tv") || href.contains("/tv/")))
        val isMovie = !isSerie && !isAnime

        return when {
            isAnime -> {
                newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            isSerie -> {
                newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
            else -> {
                newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                    this.posterUrl = localPoster
                    this.year = year
                }
            }
        }
    }

    // =========================================================================
    // BUSCA CORRIGIDA
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/buscar?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(searchUrl).document

        return document.select(".grid .card, a.card").mapNotNull { card ->
            try {
                val title = card.attr("title") ?: card.selectFirst("img")?.attr("alt") ?: return@mapNotNull null
                val href = card.attr("href") ?: return@mapNotNull null

                val poster = card.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
                val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

                val badge = card.selectFirst(".badge-kind")?.text()?.lowercase() ?: ""
                val isAnime = badge.contains("anime") || href.contains("/anime/") || 
                             title.contains("(Anime)", ignoreCase = true)
                val isSerie = badge.contains("série") || badge.contains("serie") || 
                             href.contains("/serie/") || 
                             (!isAnime && (badge.contains("tv") || href.contains("/tv/")))

                when {
                    isAnime -> newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.Anime) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    isSerie -> newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = poster
                        this.year = year
                    }
                    else -> newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                        this.posterUrl = poster
                        this.year = year
                    }
                }
            } catch (e: Exception) {
                println("❌ Erro ao processar card de busca: ${e.message}")
                null
            }
        }
    }

    // =========================================================================
    // CARREGAR DETALHES (COM TMDB INTEGRADO)
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        // 1. Extrai info básica
        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        
        // Determina tipo de conteúdo
        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") || 
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)

        println("🎬 SuperFlix: Carregando '$cleanTitle' (Tipo: ${when {
            isAnime -> "Anime"
            isSerie -> "Série"
            else -> "Filme"
        }}, Ano: $year)")

        // 2. Tenta buscar no TMDB
        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        // 3. Se encontrou no TMDB, usa dados enriquecidos
        return if (tmdbInfo != null) {
            println("✅ SuperFlix: Dados do TMDB encontrados para '$cleanTitle'")
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie)
        } else {
            println("⚠️ SuperFlix: Usando dados do site para '$cleanTitle'")
            // 4. Fallback para dados do site
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie)
        }
    }

 // =========================================================================
// BUSCA NO TMDB (API DIRETA) - MELHORADA COM SUPORTE PARA JAPONÊS
// =========================================================================
private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
    return try {
        val type = if (isTv) "tv" else "movie"
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val yearParam = year?.let { "&year=$it" } ?: ""

        val searchUrl = "$tmdbBaseUrl/search/$type?" +
                       "api_key=$tmdbApiKey" +
                       "&language=pt-BR" +
                       "&query=$encodedQuery" +
                       yearParam +
                       "&page=1"

        println("🔍 TMDB: Buscando '$query' ($type)")
        val response = app.get(searchUrl, timeout = 10_000)
        val searchResult = response.parsedSafe<TMDBSearchResponse>()

        val result = searchResult?.results?.firstOrNull()
        if (result == null) {
            println("❌ TMDB: Nenhum resultado para '$query'")
            return null
        }

        println("✅ TMDB: Encontrado '${if (isTv) result.name else result.title}' (ID: ${result.id})")

        // Verifica se é anime pelo título ou tipo
        val isAnime = query.contains("(Anime)", ignoreCase = true) || 
                     type == "tv" && (query.contains("anime", ignoreCase = true) || 
                     result.name?.contains("Anime") == true || 
                     result.title?.contains("Anime") == true)

        // Busca detalhes completos em português primeiro
        val details = getTMDBDetails(result.id, isTv, false)

        // Para animes, tenta buscar também em japonês para nomes originais
        val

    // =========================================================================
    // DETALHES DO TMDB COM TODOS OS ATORES
    // =========================================================================
    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$tmdbBaseUrl/$type/$id?" +
                     "api_key=$tmdbApiKey" +
                     "&language=pt-BR" +
                     "&append_to_response=credits,videos,recommendations"

            app.get(url, timeout = 10_000).parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ TMDB: Erro nos detalhes - ${e.message}")
            null
        }
    }

    // =========================================================================
    // BUSCA TODAS AS TEMPORADAS E EPISÓDIOS DO TMDB
    // =========================================================================
    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        return try {
            // Primeiro busca os detalhes da série para obter número de temporadas
            val seriesDetailsUrl = "$tmdbBaseUrl/tv/$seriesId?" +
                                  "api_key=$tmdbApiKey" +
                                  "&language=pt-BR"
            
            val seriesResponse = app.get(seriesDetailsUrl, timeout = 10_000)
            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>()
            
            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()
            
            // Busca episódios de cada temporada
            seriesDetails?.seasons?.forEach { season ->
                if (season.season_number > 0) { // Ignora temporada 0 (especiais)
                    val seasonNumber = season.season_number
                    val seasonData = getTMDBSeasonDetails(seriesId, seasonNumber)
                    seasonData?.episodes?.let { episodes ->
                        seasonsEpisodes[seasonNumber] = episodes
                    }
                }
            }
            
            seasonsEpisodes
        } catch (e: Exception) {
            println("❌ TMDB: Erro ao buscar temporadas - ${e.message}")
            emptyMap()
        }
    }

    // =========================================================================
    // DETALHES DA TEMPORADA DO TMDB
    // =========================================================================
    private suspend fun getTMDBSeasonDetails(seriesId: Int, seasonNumber: Int): TMDBSeasonResponse? {
        return try {
            val url = "$tmdbBaseUrl/tv/$seriesId/season/$seasonNumber?" +
                     "api_key=$tmdbApiKey" +
                     "&language=pt-BR"
            
            app.get(url, timeout = 10_000).parsedSafe<TMDBSeasonResponse>()
        } catch (e: Exception) {
            println("❌ TMDB: Erro na temporada $seasonNumber - ${e.message}")
            null
        }
    }

    // =========================================================================
    // CRIAR RESPOSTA COM TMDB
    // =========================================================================
    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        isAnime: Boolean,
        isSerie: Boolean
    ): LoadResponse {
        return if (isAnime || isSerie) {
            // Para séries/animes: extrai episódios com informações do TMDB
            val episodes = extractEpisodesWithTMDBInfo(
                document = document,
                url = url,
                tmdbInfo = tmdbInfo,
                isAnime = isAnime
            )

            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            newTvSeriesLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = type,
                episodes = episodes
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres

                // Adiciona TODOS os atores do TMDB
                tmdbInfo.actors?.let { actors ->
                    // Ordena por ordem de importância (como aparece no TMDB)
                    addActors(actors)
                }
                
                // Adiciona trailer
                tmdbInfo.youtubeTrailer?.let { trailerKey ->
                    val trailerUrl = "https://www.youtube.com/watch?v=$trailerKey"
                    addTrailer(trailerUrl)
                }

                // Recomendações
                this.recommendations = tmdbInfo.recommendations?.map { rec ->
                    if (rec.isMovie) {
                        newMovieSearchResponse(rec.title ?: "", "", TvType.Movie) {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.year
                        }
                    } else {
                        newTvSeriesSearchResponse(rec.title ?: "", "", TvType.TvSeries) {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.year
                        }
                    }
                }
            }
        } else {
            // Para filmes
            val playerUrl = findPlayerUrl(document)

            newMovieLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = TvType.Movie,
                dataUrl = playerUrl ?: url
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres
                this.duration = tmdbInfo.duration

                // Adiciona TODOS os atores do TMDB
                tmdbInfo.actors?.let { actors ->
                    addActors(actors)
                }
                
                // Adiciona trailer
                tmdbInfo.youtubeTrailer?.let { trailerKey ->
                    val trailerUrl = "https://www.youtube.com/watch?v=$trailerKey"
                    addTrailer(trailerUrl)
                }

                // Recomendações
                this.recommendations = tmdbInfo.recommendations?.map { rec ->
                    if (rec.isMovie) {
                        newMovieSearchResponse(rec.title ?: "", "", TvType.Movie) {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.year
                        }
                    } else {
                        newTvSeriesSearchResponse(rec.title ?: "", "", TvType.TvSeries) {
                            this.posterUrl = rec.posterUrl
                            this.year = rec.year
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // EXTRATIR EPISÓDIOS COM INFORMAÇÕES DO TMDB - CORRIGIDA
    // =========================================================================
    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        // Extrai episódios do site
        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item, .episode-link")
        
        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEachIndexed
                    
                    // Tenta extrair número do episódio
                    val epNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1
                    
                    // Busca informações do TMDB para este episódio
                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)
                    
                    // Cria o episódio
                    val episode = createEpisode(
                        dataUrl = dataUrl,
                        seasonNumber = seasonNumber,
                        episodeNumber = epNumber,
                        element = element,
                        tmdbEpisode = tmdbEpisode,
                        isAnime = isAnime
                    )
                    
                    episodes.add(episode)
                } catch (e: Exception) {
                    println("❌ Erro ao processar episódio ${index + 1}: ${e.message}")
                }
            }
        } else {
            // Fallback: procura por qualquer elemento que possa ser um episódio
            document.select("[class*='episode'], [class*='episodio']").forEach { element ->
                try {
                    val link = element.selectFirst("a[href*='episode'], a[href*='episodio'], button[data-url]")
                    val dataUrl = link?.attr("data-url") ?: link?.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEach
                    
                    val epNumber = extractEpisodeNumber(element, episodes.size + 1)
                    val seasonNumber = 1
                    
                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)
                    
                    val episode = createEpisode(
                        dataUrl = dataUrl,
                        seasonNumber = seasonNumber,
                        episodeNumber = epNumber,
                        element = element,
                        tmdbEpisode = tmdbEpisode,
                        isAnime = isAnime
                    )
                    
                    episodes.add(episode)
                } catch (e: Exception) {
                    // Ignora erros no fallback
                }
            }
        }
        
        return episodes
    }

    // =========================================================================
    // FUNÇÕES AUXILIARES PARA EPISÓDIOS
    // =========================================================================
    
    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }
    
    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        return tmdbInfo?.seasonsEpisodes?.get(season)?.find { it.episode_number == episode }
    }
    
    private fun createEpisode(
        dataUrl: String,
        seasonNumber: Int,
        episodeNumber: Int,
        element: Element,
        tmdbEpisode: TMDBEpisode?,
        isAnime: Boolean
    ): Episode {
        return newEpisode(fixUrl(dataUrl)) {
            // Nome do episódio (prioriza TMDB, depois site, depois padrão)
            this.name = tmdbEpisode?.name ?:
                       element.selectFirst(".ep-title, .title, .episode-title, h3, h4")?.text()?.trim() ?:
                       "Episódio $episodeNumber"
            
            this.season = seasonNumber
            this.episode = episodeNumber
            
            // Thumbnail do episódio
            this.posterUrl = tmdbEpisode?.still_path?.let { "$tmdbImageUrl/w300$it" } ?:
                            element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            
            // Descrição/sinopse do episódio
            val descriptionBuilder = StringBuilder()
            
            tmdbEpisode?.overview?.let { overview ->
                descriptionBuilder.append(overview)
            }
            
            // Adiciona data de lançamento
            tmdbEpisode?.air_date?.let { airDate ->
                try {
                    val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                    val date = dateFormatter.parse(airDate)
                    this.date = date.time
                    
                    val displayDate = SimpleDateFormat("dd/MM/yyyy").format(date)
                    if (descriptionBuilder.isNotEmpty()) descriptionBuilder.append("\n\n")
                    descriptionBuilder.append("📅 Lançado em: $displayDate")
                } catch (e: Exception) {
                    if (descriptionBuilder.isNotEmpty()) descriptionBuilder.append("\n\n")
                    descriptionBuilder.append("📅 Lançado em: $airDate")
                }
            }
            
            // Adiciona duração para animes
            if (isAnime) {
                val duration = tmdbEpisode?.runtime ?: 24
                if (descriptionBuilder.isNotEmpty()) descriptionBuilder.append("\n")
                descriptionBuilder.append("⏱️ Duração: ${duration}min")
            } else if (tmdbEpisode?.runtime != null && tmdbEpisode.runtime > 0) {
                if (descriptionBuilder.isNotEmpty()) descriptionBuilder.append("\n")
                descriptionBuilder.append("⏱️ Duração: ${tmdbEpisode.runtime}min")
            }
            
            this.description = descriptionBuilder.toString().takeIf { it.isNotEmpty() }
        }
    }

    // =========================================================================
    // FALLBACK: DADOS DO SITE
    // =========================================================================
    private suspend fun createLoadResponseFromSite(
        document: org.jsoup.nodes.Document,
        url: String,
        title: String,
        year: Int?,
        isAnime: Boolean,
        isSerie: Boolean
    ): LoadResponse {
        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = ogImage?.let { fixUrl(it) }

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description, .sinopse, .plot")?.text()
        val plot = description ?: synopsis

        val tags = document.select("a.chip, .chip, .genre, .tags").map { it.text() }
            .takeIf { it.isNotEmpty() }?.toList()

        return if (isAnime || isSerie) {
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            val episodes = extractEpisodesWithTMDBInfo(document, url, null, isAnime)
            
            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        } else {
            val playerUrl = findPlayerUrl(document)
            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
            }
        }
    }

    // =========================================================================
    // CLASSES DE DADOS PARA TMDB (ATUALIZADAS)
    // =========================================================================
    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<Actor>?,
        val youtubeTrailer: String?,
        val duration: Int?,
        val recommendations: List<TMDBRecommendation>?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap()
    )

    private data class TMDBRecommendation(
        val title: String?,
        val posterUrl: String?,
        val year: Int?,
        val isMovie: Boolean
    )

    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?,
        @JsonProperty("recommendations") val recommendations: TMDBRecommendationsResponse?
    )

    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    private data class TMDBSeasonInfo(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )

    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    private data class TMDBCast(
    @JsonProperty("name") val name: String,
    @JsonProperty("character") val character: String?,
    @JsonProperty("profile_path") val profile_path: String?,
    @JsonProperty("order") val order: Int?
)

    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String
    )

    private data class TMDBRecommendationsResponse(
        @JsonProperty("results") val results: List<TMDBRecommendationResult>
    )

    private data class TMDBRecommendationResult(
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val poster_path: String?,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null
    )

    // =========================================================================
    // FUNÇÕES RESTANTES
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) return playButton.attr("data-url")
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) return iframe.attr("src")
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
    }
}