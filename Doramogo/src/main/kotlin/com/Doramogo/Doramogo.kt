package com.Doramogo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer

@CloudstreamPlugin
class DoramogoPlugin: Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Doramogo())
    }
}

class Doramogo : MainAPI() {
    override var mainUrl = "https://www.doramogo.net"
    override var name = "Doramogo"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)
    override val usesWebView = false

    // === TMDB CONFIGURAÇÃO ===
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private val TMDB_ACCESS_TOKEN = BuildConfig.TMDB_ACCESS_TOKEN

    // Países de doramas (códigos ISO 3166-1)
    private val doramaCountries = setOf("KR", "JP", "CN", "TW", "TH", "PH", "ID", "VN", "MY", "SG")
    
    // Idiomas de doramas (códigos ISO 639-1)
    private val doramaLanguages = setOf("ko", "ja", "zh", "th", "tl", "id", "vi")
    
    // Gêneros relacionados a doramas
    private val doramaGenres = setOf(
        "Drama", "Romance", "Melodrama", "Family", "Comedy", 
        "Fantasy", "Mystery", "Thriller", "Action"
    )
    
    // Gêneros para excluir (COM ANIMAÇÃO ADICIONADA)
    private val excludeGenres = setOf(
        "Reality", "Talk Show", "News", "Documentary", "Game Show", "Animation"
    )
    
    // Gêneros que recebem penalidade (ANIMAÇÃO - forte negativo)
    private val penaltyGenres = mapOf(
        "Animation" to -5,      // Animação é NÃO é dorama
        "Anime" to -5,          // Anime é NÃO é dorama
        "Reality" to -3,
        "Talk Show" to -3,
        "Game Show" to -3
    )

    override val mainPage = mainPageOf(
        "$mainUrl/episodios" to "Episódios Recentes",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=DUB" to "Doramas Dublados",
        "$mainUrl/dorama?slug=&status=&ano=&classificacao_idade=&idiomar=LEG" to "Doramas Legendados",
        "$mainUrl/genero/dorama-acao" to "Ação",
        "$mainUrl/genero/dorama-aventura" to "Aventura",
        "$mainUrl/genero/dorama-comedia" to "Comédia",
        "$mainUrl/genero/dorama-crime" to "Crime",
        "$mainUrl/genero/dorama-drama" to "Drama",
        "$mainUrl/genero/dorama-familia" to "Família",
        "$mainUrl/genero/dorama-fantasia" to "Fantasia",
        "$mainUrl/genero/dorama-ficcao-cientifica" to "Ficção Científica",
        "$mainUrl/genero/dorama-misterio" to "Mistério",
        "$mainUrl/genero/dorama-reality" to "Reality Shows",
        "$mainUrl/genero/dorama-sci-fi" to "Sci-Fi",
        "$mainUrl/filmes" to "Filmes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            when {
                request.data.contains("/dorama?") || request.data.contains("/filmes") -> 
                    "${request.data}&pagina=$page"
                request.data.contains("/genero/") -> 
                    "${request.data}/pagina/$page"
                request.data.contains("/episodios") -> 
                    "${request.data}?pagina=$page"
                else -> "${request.data}?page=$page"
            }
        } else {
            request.data
        }

        val document = app.get(url).document
        val items = ArrayList<SearchResponse>()

        val isEpisodesPage = request.data.contains("/episodios") || request.name.contains("Episódios")

        if (isEpisodesPage) {
            document.select(".episode-card").forEach { card ->
                try {
                    // Extrair o link principal
                    val aTag = card.selectFirst("a") ?: return@forEach
                    val href = aTag.attr("href")
                    
                    // Extrair nome do dorama (pode estar dentro do h3 > a ou direto no a)
                    val titleElement = card.selectFirst("h3 a") ?: aTag
                    val doramaName = titleElement.text().trim()
                    if (doramaName.isBlank()) return@forEach
                    
                    // EXTRAIR NÚMERO DO EPISÓDIO DO ELEMENTO <p> CORRETAMENTE
                    // Baseado no HTML que você mostrou:
                    // <p class="text-xs text-gray-600 mb-3 line-clamp-2" id="font-size-episodios-mobile">Episódio 03</p>
                    val episodeTextElement = card.selectFirst("p#font-size-episodios-mobile")
                        ?: card.selectFirst("p.text-xs.text-gray-600")
                        ?: card.selectFirst("p:contains(Episódio)")
                        ?: card.selectFirst("p:contains(EP)")
                    
                    val episodeText = episodeTextElement?.text()?.trim() ?: "Episódio 01"
                    
                    // DEBUG
                    println("DORAMOGO DEBUG - Card encontrado:")
                    println("  Dorama: $doramaName")
                    println("  Episódio Texto: $episodeText")
                    println("  URL: $href")
                    
                    // Extrair número do episódio
                    val episodeNumber = extractEpisodeNumberFromText(episodeText)
                        ?: extractEpisodeNumberFromUrl(href)
                        ?: 1
                    
                    println("  Número extraído: $episodeNumber")
                    
                    // Extrair imagem/poster
                    val imgElement = card.selectFirst("img")
                    val posterUrl = when {
                        imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                        imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                        else -> null
                    }
                    
                    // Verificar se é DUB ou LEG
                    val isDub = href.contains("/dub/") || request.data.contains("idiomar=DUB") || 
                               doramaName.contains("Dublado", ignoreCase = true)
                    val isLeg = href.contains("/leg/") || request.data.contains("idiomar=LEG") || 
                               doramaName.contains("Legendado", ignoreCase = true)
                    
                    val audioType = when {
                        isDub -> "DUB"
                        isLeg -> "LEG"
                        else -> ""
                    }
                    
                    // Formatar título final: "Dorama - EP X DUB/LEG"
                    val finalTitle = if (audioType.isNotEmpty()) {
                        "$doramaName - EP $episodeNumber $audioType"
                    } else {
                        "$doramaName - EP $episodeNumber"
                    }
                    
                    println("  Título final: $finalTitle")
                    println("  ---")
                    
                    items.add(newTvSeriesSearchResponse(finalTitle, fixUrl(href), TvType.TvSeries) {
                        this.posterUrl = posterUrl
                    })
                } catch (e: Exception) {
                    println("DORAMOGO ERROR - Erro ao processar card: ${e.message}")
                }
            }
        } else {
            // Para outras páginas (doramas, filmes, gêneros)
            document.select(".episode-card").forEach { card ->
                try {
                    val aTag = card.selectFirst("a") ?: return@forEach
                    val titleElement = card.selectFirst("h3")
                    var title = titleElement?.text()?.trim() 
                        ?: aTag.attr("title")?.trim()
                        ?: return@forEach
                    
                    title = cleanTitle(title)
                    
                    val href = aTag.attr("href")
                    
                    val imgElement = card.selectFirst("img")
                    val posterUrl = when {
                        imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                        imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                        else -> null
                    }
                    
                    val isMovie = href.contains("/filmes/") || request.name.contains("Filmes")
                    val type = if (isMovie) TvType.Movie else TvType.TvSeries
                    
                    if (type == TvType.Movie) {
                        items.add(newMovieSearchResponse(title, fixUrl(href), type) {
                            this.posterUrl = posterUrl
                        })
                    } else {
                        items.add(newTvSeriesSearchResponse(title, fixUrl(href), type) { 
                            this.posterUrl = posterUrl
                        })
                    }
                } catch (e: Exception) {
                    // Ignorar erros
                }
            }
        }

        val hasNextPage = document.select("""a[href*="pagina/"], a[href*="?page="], 
            .pagination a, .next-btn, a:contains(PRÓXIMA)""").isNotEmpty()
        
        val homePageList = HomePageList(
            request.name,
            items.distinctBy { it.url },
            isHorizontalImages = isEpisodesPage
        )
        
        return newHomePageResponse(listOf(homePageList), hasNextPage)
    }

    // Função para extrair número do episódio do texto
    private fun extractEpisodeNumberFromText(text: String): Int? {
        println("DEBUG - Extraindo número de: '$text'")
        
        val patterns = listOf(
            Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Ep\.\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""E(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\b0*(\d+)\b""") // Apenas números (remove zeros à esquerda)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(text)
            if (match != null) {
                val number = match.groupValues[1].toIntOrNull()
                println("DEBUG - Padrão encontrado: $pattern, Número: $number")
                return number
            }
        }
        
        println("DEBUG - Nenhum padrão encontrado")
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val allResults = mutableListOf<SearchResponse>()
        var currentPage = 1
        var hasMorePages = true
        val maxPages = 3
        
        try {
            while (hasMorePages && currentPage <= maxPages) {
                val pageResults = searchPage(query, currentPage)
                
                if (pageResults.isNotEmpty()) {
                    allResults.addAll(pageResults)
                    hasMorePages = checkIfHasNextPage(query, currentPage)
                    currentPage++
                } else {
                    hasMorePages = false
                }
            }
        } catch (e: Exception) {
        }
        
        return allResults.distinctBy { it.url }
    }

    private suspend fun searchPage(query: String, page: Int): List<SearchResponse> {
        val searchUrl = buildSearchUrl(query, page)
        
        val document = try {
            app.get(searchUrl).document
        } catch (e: Exception) {
            return emptyList()
        }
        
        val results = document.select(".doramogo-search-result-card").mapNotNull { card ->
            try {
                processSearchResultCard(card)
            } catch (e: Exception) {
                null
            }
        }
        
        return results
    }

    private fun buildSearchUrl(query: String, page: Int): String {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        
        return if (page == 1) {
            "$mainUrl/search/?q=$encodedQuery"
        } else {
            "$mainUrl/search/$encodedQuery/pagina/$page"
        }
    }

    private fun processSearchResultCard(card: Element): SearchResponse? {
        val linkElement = card.selectFirst("a[href^='/series/'], a[href^='/filmes/']") 
            ?: card.selectFirst(".doramogo-search-result-image-container a")
            ?: return null
        
        val href = linkElement.attr("href")
        if (href.isBlank() || href == "#") return null
        
        val titleElement = card.selectFirst("#doramogo-search-result-title a") 
            ?: card.selectFirst("h3 a") 
            ?: linkElement
        
        var title = titleElement.text().trim()
        if (title.isBlank()) {
            val imgElement = card.selectFirst("img")
            title = imgElement?.attr("title")?.trim() 
                ?: imgElement?.attr("alt")?.trim() 
                ?: return null
        }
        
        title = cleanTitle(title)
        
        val imgElement = card.selectFirst("img")
        val posterUrl = when {
            imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
            imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
            else -> null
        }
        
        val type = when {
            href.contains("/filmes/") -> TvType.Movie
            else -> TvType.TvSeries
        }
        
        val year = extractYearFromUrl(href)
        
        if (type == TvType.Movie) {
            return newMovieSearchResponse(title, fixUrl(href), type) { 
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            return newTvSeriesSearchResponse(title, fixUrl(href), type) { 
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    private suspend fun checkIfHasNextPage(query: String, currentPage: Int): Boolean {
        return try {
            val currentPageUrl = buildSearchUrl(query, currentPage)
            val document = app.get(currentPageUrl).document
            
            val hasPaginationElements = document.select(""".pagination a, 
                a[href*="/pagina/"], 
                a:contains(Próxima), 
                a:contains(Next), 
                .next-btn, 
                .next-page""").isNotEmpty()
            
            val nextPageLink = document.select("a[href*='/pagina/${currentPage + 1}']").first()
            
            if (hasPaginationElements || nextPageLink != null) {
                true
            } else {
                val nextPageUrl = buildSearchUrl(query, currentPage + 1)
                val testResponse = app.get(nextPageUrl, allowRedirects = false)
                
                if (testResponse.code == 200) {
                    val nextDoc = app.get(nextPageUrl).document
                    val nextPageResults = nextDoc.select(".doramogo-search-result-card").size
                    
                    nextPageResults > 0
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        
        val fullTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")
            ?: return null
        
        var title = cleanTitle(fullTitle)
        
        // Extrair número do episódio do título da página (se existir)
        val episodeNumberFromTitle = extractEpisodeNumberFromTitle(fullTitle)
        
        // Se for uma página de episódio específico, criar título base
        val isEpisodePage = episodeNumberFromTitle != null && (
            url.contains("/episodio-") || 
            url.contains("/ep-") || 
            url.contains("/temporada-")
        )
        
        if (isEpisodePage) {
            // Remover "Episódio X" do título para pegar o nome do dorama
            title = title.replace(Regex("\\s*-\\s*Epis[oó]dio\\s*\\d+.*$"), "").trim()
        }
        
        val description = document.selectFirst("#sinopse-text")?.text()?.trim()
            ?: document.selectFirst("#synopsis p")?.text()?.trim()
            ?: document.selectFirst(".synopsis-text")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: ""
        
        // === BUSCAR NO TMDB COM DETECÇÃO INTELIGENTE ===
        val isMovie = url.contains("/filmes/")
        
        // Primeiro, tentar buscar como dorama (série)
        val tmdbInfo = if (!isMovie) {
            searchOnTMDBAsDorama(title, url)
        } else {
            searchOnTMDB(title, null, true)
        }
        
        // Poster - TMDB PRIMEIRO
        val poster = tmdbInfo?.posterUrl ?: 
            document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: document.selectFirst("#w-55")?.attr("src")?.let { fixUrl(it) }
        
        // Background - APENAS TMDB
        val backdropUrl = tmdbInfo?.backdropUrl
        
        val infoMap = extractInfoMap(document)
        
        // Ano - TMDB PRIMEIRO
        val year = tmdbInfo?.year
            ?: infoMap["ano"]?.toIntOrNull()
            ?: extractYearFromUrl(url)
            ?: title.findYear()
        
        // Gêneros - TMDB PRIMEIRO
        val tmdbGenres = tmdbInfo?.genres?.map { it.name }
        val mainTags = document.select(".gens a").map { it.text().trim() }
        
        val allTags = (tmdbGenres ?: mainTags).distinct()
        
        // Duração - TMDB PRIMEIRO
        val duration = tmdbInfo?.duration ?: infoMap["duração"]?.parseDuration()
        
        val type = if (isMovie) TvType.Movie else TvType.TvSeries
        
        val recommendations = extractRecommendationsFromSite(document)
        
        // === ATORES E TRAILER DO TMDB ===
        val tmdbActors = tmdbInfo?.actors
        val tmdbTrailer = tmdbInfo?.youtubeTrailer
        
        if (type == TvType.TvSeries) {
            val episodes = mutableListOf<Episode>()
            
            // TENTAR EXTRAIR EPISÓDIOS DA PÁGINA
            document.select(".dorama-one-season-block").forEach { seasonBlock ->
                val seasonTitle = seasonBlock.selectFirst(".dorama-one-season-title")?.text()?.trim() ?: "1° Temporada"
                val seasonNumber = extractSeasonNumber(seasonTitle)
                
                seasonBlock.select(".dorama-one-episode-item").forEach { episodeItem ->
                    val episodeUrl = episodeItem.attr("href")?.let { fixUrl(it) } ?: return@forEach
                    val episodeTitle = episodeItem.selectFirst(".episode-title")?.text()?.trim() ?: "Episódio"
                    
                    val episodeNumber = extractEpisodeNumberFromEpisodeItem(episodeItem)
                    
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    })
                }
            }
            
            // SE NÃO ENCONTROU EPISÓDIOS ESTRUTURADOS
            if (episodes.isEmpty()) {
                document.select(".dorama-one-episode-item").forEach { episodeItem ->
                    val episodeUrl = episodeItem.attr("href")?.let { fixUrl(it) } ?: return@forEach
                    val episodeTitle = episodeItem.selectFirst(".episode-title")?.text()?.trim() ?: "Episódio"
                    
                    val episodeNumber = extractEpisodeNumberFromEpisodeItem(episodeItem)
                    val seasonNumber = extractSeasonNumberFromUrl(episodeUrl) ?: 1
                    
                    episodes.add(newEpisode(episodeUrl) {
                        this.name = episodeTitle
                        this.season = seasonNumber
                        this.episode = episodeNumber
                    })
                }
            }
            
            // SE AINDA NÃO TEM EPISÓDIOS, CRIAR UM COM BASE NA URL E TÍTULO
            if (episodes.isEmpty()) {
                // Extrair número do episódio da URL ou título
                val episodeNum = episodeNumberFromTitle 
                    ?: extractEpisodeNumberFromUrl(url) 
                    ?: 1
                
                // Extrair temporada da URL
                val seasonNum = extractSeasonNumberFromUrl(url) ?: 1
                
                // Criar um episódio com a própria URL
                episodes.add(newEpisode(url) {
                    this.name = "Episódio $episodeNum"
                    this.season = seasonNum
                    this.episode = episodeNum
                    
                    // Se tiver descrição, adicionar
                    if (description.isNotBlank()) {
                        this.description = description
                    }
                })
            }
            
            return newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdropUrl
                this.year = year
                this.plot = description
                this.tags = allTags
                this.recommendations = recommendations
                
                tmdbActors?.let { actors ->
                    addActors(actors)
                }
                
                tmdbTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        } else {
            // Para filmes
            return newMovieLoadResponse(title, url, type, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdropUrl
                this.year = year
                this.plot = description
                this.tags = allTags
                this.duration = duration
                this.recommendations = recommendations
                
                tmdbActors?.let { actors ->
                    addActors(actors)
                }
                
                tmdbTrailer?.let { trailerUrl ->
                    addTrailer(trailerUrl)
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var linksFound = false
        
        val document = app.get(data).document
        
        val urlParts = data.split("/")
        val slug = urlParts.getOrNull(urlParts.indexOf("series") + 1) 
            ?: urlParts.getOrNull(urlParts.indexOf("filmes") + 1)
            ?: return false
        
        val temporada = extractSeasonNumberFromUrl(data) ?: 1
        val episodio = extractEpisodeNumberFromUrl(data) ?: 1
        
        val isFilme = data.contains("/filmes/")
        val tipo = if (isFilme) "filmes" else "doramas"
        
        val streamPath = if (isFilme) {
            val pt = slug.first().uppercase()
            "$pt/$slug/stream/stream.m3u8?nocache=${System.currentTimeMillis()}"
        } else {
            val pt = slug.first().uppercase()
            val tempNum = temporada.toString().padStart(2, '0')
            val epNum = episodio.toString().padStart(2, '0')
            "$pt/$slug/$tempNum-temporada/$epNum/stream.m3u8?nocache=${System.currentTimeMillis()}"
        }
        
        val PRIMARY_URL = "https://proxy-us-east1-outbound-series.xreadycf.site"
        val FALLBACK_URL = "https://proxy-us-east1-forks-doramas.xreadycf.site"
        
        val primaryStreamUrl = "$PRIMARY_URL/$streamPath"
        val fallbackStreamUrl = "$FALLBACK_URL/$streamPath"
        
        val headers = mapOf(
            "accept" to "*/*",
            "accept-language" to "pt-BR",
            "origin" to "https://www.doramogo.net",
            "priority" to "u=1, i",
            "referer" to "https://www.doramogo.net/",
            "sec-ch-ua" to "\"Chromium\";v=\"127\", \"Not)A;Brand\";v=\"99\", \"Microsoft Edge Simulate\";v=\"127\", \"Lemur\";v=\"127\"",
            "sec-ch-ua-mobile" to "?1",
            "sec-ch-ua-platform" to "\"Android\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "cross-site",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
        )
        
        suspend fun tryAddLink(url: String, name: String): Boolean {
            return try {
                val testResponse = app.get(
                    url,
                    headers = headers,
                    allowRedirects = true,
                    timeout = 15
                )
                
                if (testResponse.code in 200..299) {
                    callback(newExtractorLink(name, "Doramogo", url, ExtractorLinkType.M3U8) {
                        referer = mainUrl
                        quality = Qualities.P720.value
                        this.headers = headers
                    })
                    true
                } else {
                    false
                }
            } catch (e: Exception) {
                false
            }
        }
        
        if (tryAddLink(primaryStreamUrl, "Doramogo")) {
            linksFound = true
        }
        
        if (!linksFound) {
            if (tryAddLink(fallbackStreamUrl, "Doramogo")) {
                linksFound = true
            }
        }
        
        if (!linksFound) {
            callback(newExtractorLink(name, "Doramogo", primaryStreamUrl, ExtractorLinkType.M3U8) {
                referer = mainUrl
                quality = Qualities.P720.value
                this.headers = headers
            })
            
            callback(newExtractorLink(name, "Doramogo", fallbackStreamUrl, ExtractorLinkType.M3U8) {
                referer = mainUrl
                quality = Qualities.P720.value
                this.headers = headers
            })
            
            linksFound = true
        }
        
        if (!linksFound) {
            val scriptContent = document.select("script").find { 
                it.html().contains("construirStreamPath") 
            }?.html()
            
            if (!scriptContent.isNullOrBlank()) {
                val urls = extractUrlsFromJavaScript(scriptContent)
                urls.forEachIndexed { index, url ->
                    if (url.contains(".m3u8") && !url.contains("jwplatform.com")) {
                        callback(newExtractorLink(name, "Doramogo", url, ExtractorLinkType.M3U8) {
                            referer = mainUrl
                            quality = Qualities.P720.value
                            this.headers = headers
                        })
                        linksFound = true
                    }
                }
            }
        }
        
        return linksFound
    }
    
    // === FUNÇÕES AUXILIARES ===
    
    private fun cleanTitle(title: String): String {
        return title.replace(Regex("\\s*\\(Legendado\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*\\(Dublado\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*-\\s*(Dublado|Legendado|Online|e|Dublado e Legendado).*"), "")
            .replace(Regex("\\s*\\(.*\\)"), "")
            .replace(Regex("\\(\\d{4}\\)"), "")
            .trim()
    }
    
    // Função para extrair número do episódio do título (EP X)
    private fun extractEpisodeNumberFromTitle(title: String): Int? {
        val patterns = listOf(
            Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Episode\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Ep\.\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""- (\d+)(?:\s*DUB|\s*LEG)?$""", RegexOption.IGNORE_CASE), // Para "Dorama - 03 DUB"
            Regex("""\((\d+)\)""") // Para "Dorama (03)"
        )
        
        for (pattern in patterns) {
            val match = pattern.find(title)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        
        return null
    }
    
    private fun extractYearFromUrl(url: String): Int? {
        val pattern = Regex("""/(?:series|filmes)/[^/]+-(\d{4})/""")
        val match = pattern.find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractEpisodeNumberFromUrl(url: String): Int? {
        val patterns = listOf(
            Regex("""episodio-(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""ep-(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""ep(\d+)""", RegexOption.IGNORE_CASE)
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        
        return null
    }
    
    private fun extractUrlsFromJavaScript(script: String): List<String> {
        val urls = mutableListOf<String>()
        
        val patterns = listOf(
            Regex("""(https?://[^"' >]+\.m3u8[^"' >]*)"""),
            Regex("""PRIMARY_URL\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""FALLBACK_URL\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""url\s*=\s*['"]([^'"]+)['"]"""),
            Regex("""file\s*:\s*['"]([^'"]+\.m3u8[^'"]*)['"]""")
        )
        
        patterns.forEach { pattern ->
            val matches = pattern.findAll(script)
            matches.forEach { match ->
                val url = match.groupValues[1]
                if (url.isNotBlank() && (url.contains("http") || url.contains("//"))) {
                    val fullUrl = if (url.startsWith("//")) "https:$url" else url
                    urls.add(fullUrl)
                }
            }
        }
        
        return urls.distinct()
    }
    
    private fun extractInfoMap(document: Element): Map<String, String> {
        val infoMap = mutableMapOf<String, String>()
        
        document.selectFirst(".detail p.text-white")?.text()?.trim()?.let { detailText ->
            val yearMatch = Regex("""\s*/\s*(\d{4})\s*/\s*""").find(detailText)
            yearMatch?.groupValues?.get(1)?.let { year ->
                infoMap["ano"] = year
            }
            
            val epMatch = Regex("""(\d+)\s*Episodes?""").find(detailText)
            epMatch?.groupValues?.get(1)?.let { eps ->
                infoMap["episódios"] = eps
            }
        }
        
        document.select(".casts div").forEach { div ->
            val text = div.text()
            if (text.contains(":")) {
                val parts = text.split(":", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim().lowercase().removePrefix("b").removeSuffix(":")
                    val value = parts[1].trim()
                    
                    val normalizedKey = when (key) {
                        "status" -> "status"
                        "estúdio", "estudio", "studio" -> "estúdio"
                        "áudio", "audio" -> "áudio"
                        "duração", "duracao", "duration" -> "duração"
                        else -> key
                    }
                    
                    infoMap[normalizedKey] = value
                }
            }
        }
        
        return infoMap
    }
    
    private fun extractRecommendationsFromSite(document: Element): List<SearchResponse> {
        val recommendations = mutableListOf<SearchResponse>()
        
        val selectors = listOf(
            ".cover .thumbnail a", 
            ".grid .cover a", 
            ".rec-card a", 
            ".related-content a",
            "a[href*='/series/']", 
            "a[href*='/filmes/']"
        )
        
        for (selector in selectors) {
            document.select(selector).forEach { element ->
                try {
                    val href = element.attr("href")?.takeIf { it.isNotBlank() } ?: return@forEach
                    if (href == "#" || href.contains("javascript:")) return@forEach
                    
                    if (!href.contains(mainUrl) && !href.startsWith("/")) return@forEach
                    
                    val imgElement = element.selectFirst("img")
                    val title = imgElement?.attr("alt")?.takeIf { it.isNotBlank() }
                        ?: imgElement?.attr("title")?.takeIf { it.isNotBlank() }
                        ?: element.attr("title")?.takeIf { it.isNotBlank() }
                        ?: return@forEach
                    
                    if (title.equals(document.selectFirst("h1")?.text()?.trim(), ignoreCase = true)) {
                        return@forEach
                    }
                    
                    val poster = when {
                        imgElement?.hasAttr("data-src") == true -> fixUrl(imgElement.attr("data-src"))
                        imgElement?.hasAttr("src") == true -> fixUrl(imgElement.attr("src"))
                        else -> null
                    }
                    
                    val cleanTitle = cleanTitle(title)
                    val year = extractYearFromUrl(href)
                    
                    val type = when {
                        href.contains("/filmes/") -> TvType.Movie
                        else -> TvType.TvSeries
                    }
                    
                    if (type == TvType.Movie) {
                        recommendations.add(newMovieSearchResponse(cleanTitle, fixUrl(href), type) {
                            this.posterUrl = poster
                            this.year = year
                        })
                    } else {
                        recommendations.add(newTvSeriesSearchResponse(cleanTitle, fixUrl(href), type) {
                            this.posterUrl = poster
                            this.year = year
                        })
                    }
                    
                    if (recommendations.size >= 10) return recommendations
                } catch (e: Exception) {
                }
            }
            
            if (recommendations.isNotEmpty()) break
        }
        
        return recommendations.distinctBy { it.url }.take(10)
    }
    
    // === FUNÇÕES INTELIGENTES DO TMDB PARA DORAMAS ===
    
    private suspend fun searchOnTMDBAsDorama(query: String, url: String): TMDBInfo? {
        val searchResults = searchTMDBWithStrategy(query, url.contains("/filmes/"))
        return searchResults.firstOrNull()
    }
    
    private suspend fun searchTMDBWithStrategy(query: String, isMovie: Boolean): List<TMDBInfo> {
        val results = mutableListOf<TMDBInfo>()
        
        if (!isMovie) {
            val tvResults = searchAndFilterDoramas(query)
            results.addAll(tvResults)
        } else {
            val movieResult = searchOnTMDB(query, null, true)
            movieResult?.let { results.add(it) }
        }
        
        return results
    }
    
    private suspend fun searchAndFilterDoramas(query: String): List<TMDBInfo> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        
        // Buscar séries em português (para nomes romanizados)
        val searchUrl = "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR&include_adult=false"
        
        val headers = mapOf(
            "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
            "accept" to "application/json"
        )
        
        return try {
            val response = app.get(searchUrl, headers = headers, timeout = 10_000)
            if (response.code != 200) return emptyList()
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return emptyList()
            
            val scoredResults = mutableListOf<ScoredDorama>()
            
            for (result in searchResult.results.take(10)) {
                val score = calculateDoramaScore(result.id)
                if (score.totalScore >= 4) {
                    val details = getTMDBDetailsForDorama(result.id)
                    if (details != null) {
                        val tmdbInfo = createTMDBInfoFromDetails(result, details, false)
                        tmdbInfo?.let {
                            scoredResults.add(ScoredDorama(it, score))
                        }
                    }
                }
            }
            
            scoredResults
                .sortedByDescending { it.score.totalScore }
                .map { it.info }
                .take(3)
        } catch (e: Exception) {
            emptyList()
        }
    }
    
    private suspend fun calculateDoramaScore(tvId: Int): DoramaScore {
        var totalScore = 0
        val details = getTMDBDetailsWithExtra(tvId)
        
        if (details == null) return DoramaScore(0, emptyMap())
        
        val scoreDetails = mutableMapOf<String, Int>()
        
        // 1. País de origem (MAIS IMPORTANTE) → +3 pontos
        val hasAsianCountry = details.production_countries?.any { country ->
            doramaCountries.contains(country.iso_3166_1)
        } == true
        
        if (hasAsianCountry) {
            totalScore += 3
            scoreDetails["País Asiático"] = 3
        }
        
        // 2. Idioma original → +2 pontos
        val isAsianLanguage = details.original_language?.let { lang ->
            doramaLanguages.contains(lang)
        } == true
        
        if (isAsianLanguage) {
            totalScore += 2
            scoreDetails["Idioma Asiático"] = 2
        }
        
        // 3. Gêneros → pontos positivos e negativos
        details.genres?.forEach { genre ->
            val genreName = genre.name
            
            // PONTOS NEGATIVOS FORTES PARA ANIMAÇÃO
            if (penaltyGenres.containsKey(genreName)) {
                val penalty = penaltyGenres[genreName] ?: 0
                totalScore += penalty
                scoreDetails["Gênero $genreName (penalidade)"] = penalty
            }
            // Pontos positivos para gêneros de dorama
            else if (doramaGenres.contains(genreName)) {
                val points = if (genreName == "Drama" || genreName == "Romance") 2 else 1
                totalScore += points
                scoreDetails["Gênero $genreName"] = points
            }
        }
        
        // 4. Estrutura de episódios → até +2 pontos
        val hasDoramaStructure = details.number_of_seasons?.let { seasons ->
            details.number_of_episodes?.let { episodes ->
                seasons <= 2 && episodes in 8..32
            }
        } == true
        
        if (hasDoramaStructure) {
            totalScore += 2
            scoreDetails["Estrutura de Dorama"] = 2
        }
        
        // 5. Palavras-chave → +1 ponto (bônus)
        val hasDoramaKeywords = details.keywords?.keywords?.any { keyword ->
            keyword.name.contains("drama", ignoreCase = true) ||
            keyword.name.contains("korean", ignoreCase = true) ||
            keyword.name.contains("japanese", ignoreCase = true) ||
            keyword.name.contains("asia", ignoreCase = true) ||
            keyword.name.contains("dorama", ignoreCase = true)
        } == true
        
        if (hasDoramaKeywords) {
            totalScore += 1
            scoreDetails["Palavras-chave Relacionadas"] = 1
        }
        
        // 6. Penalidade por "Anime" no título ou keywords
        val title = details.title ?: details.name ?: ""
        val isAnime = title.contains("Anime", ignoreCase = true) ||
                     details.keywords?.keywords?.any { 
                         it.name.contains("anime", ignoreCase = true) || 
                         it.name.contains("animation", ignoreCase = true)
                     } == true
        
        if (isAnime) {
            totalScore -= 3
            scoreDetails["É Anime (penalidade extra)"] = -3
        }
        
        return DoramaScore(totalScore, scoreDetails)
    }
    
    private suspend fun getTMDBDetailsForDorama(tvId: Int): TMDBDetailsResponse? {
        // Buscar detalhes em português para nomes romanizados
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val url = "https://api.themoviedb.org/3/tv/$tvId?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            
            if (response.code != 200) return null
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun getTMDBDetailsWithExtra(tvId: Int): TMDBTVDetailsWithExtra? {
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val url = "https://api.themoviedb.org/3/tv/$tvId?api_key=$TMDB_API_KEY&language=en-US&append_to_response=keywords"
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            
            if (response.code != 200) return null
            response.parsedSafe<TMDBTVDetailsWithExtra>()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun createTMDBInfoFromDetails(
        result: TMDBResult,
        details: TMDBDetailsResponse,
        isMovie: Boolean
    ): TMDBInfo? {
        // Buscar atores romanizados (usar inglês ou português)
        val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
            if (actor.name.isNotBlank()) {
                Actor(
                    name = actor.name, // Já vem romanizado quando buscamos em pt-BR
                    image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                )
            } else null
        }
        
        val youtubeTrailer = getHighQualityTrailer(details.videos?.results)
        
        return TMDBInfo(
            id = result.id,
            title = if (isMovie) result.title else result.name,
            year = if (isMovie) {
                result.release_date?.substring(0, 4)?.toIntOrNull()
            } else {
                result.first_air_date?.substring(0, 4)?.toIntOrNull()
            },
            posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
            backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
            overview = details.overview,
            genres = details.genres,
            actors = allActors,
            youtubeTrailer = youtubeTrailer,
            duration = if (isMovie) details.runtime else null
        )
    }
    
    // Função de busca TMDB original (modificada para nomes romanizados)
    private suspend fun searchOnTMDB(query: String, year: Int?, isMovie: Boolean): TMDBInfo? {
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""
            
            // Usar pt-BR para nomes romanizados
            val searchUrl = if (isMovie) {
                "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            } else {
                "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            }
            
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val response = app.get(searchUrl, headers = headers, timeout = 10_000)
            if (response.code != 200) return null
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null
            
            // Buscar detalhes em pt-BR para nomes romanizados
            val details = if (isMovie) {
                getMovieDetailsRomanized(result.id)
            } else {
                getTVDetailsRomanized(result.id)
            }
            
            details ?: return null
            
            createTMDBInfoFromDetails(result, details, isMovie)
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun getMovieDetailsRomanized(movieId: Int): TMDBDetailsResponse? {
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val url = "https://api.themoviedb.org/3/movie/$movieId?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            
            if (response.code != 200) return null
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }
    
    private suspend fun getTVDetailsRomanized(tvId: Int): TMDBDetailsResponse? {
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val url = "https://api.themoviedb.org/3/tv/$tvId?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            
            if (response.code != 200) return null
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }
    
    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) return null
        
        return videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true ->
                    Triple(video.key, 10, "YouTube Trailer Oficial")
                video.site == "YouTube" && video.type == "Trailer" ->
                    Triple(video.key, 9, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" && video.official == true ->
                    Triple(video.key, 8, "YouTube Teaser Oficial")
                video.site == "YouTube" && video.type == "Teaser" ->
                    Triple(video.key, 7, "YouTube Teaser")
                else -> null
            }
        }
        ?.sortedByDescending { it.second }
        ?.firstOrNull()
        ?.let { (key, _, _) -> "https://www.youtube.com/watch?v=$key" }
    }
    
    // === CLASSES DO TMDB ===
    
    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<TMDBGenre>?,
        val actors: List<Actor>?,
        val youtubeTrailer: String?,
        val duration: Int?
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
        @JsonProperty("videos") val videos: TMDBVideos?
    )
    
    private data class TMDBTVDetailsWithExtra(
        @JsonProperty("title") val title: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("original_language") val original_language: String?,
        @JsonProperty("production_countries") val production_countries: List<TMDBProductionCountry>?,
        @JsonProperty("number_of_seasons") val number_of_seasons: Int?,
        @JsonProperty("number_of_episodes") val number_of_episodes: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("keywords") val keywords: TMDBKeywords?,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
    )
    
    private data class TMDBProductionCountry(
        @JsonProperty("iso_3166_1") val iso_3166_1: String,
        @JsonProperty("name") val name: String
    )
    
    private data class TMDBKeywords(
        @JsonProperty("results") val keywords: List<TMDBKeyword>
    )
    
    private data class TMDBKeyword(
        @JsonProperty("name") val name: String
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
        @JsonProperty("profile_path") val profile_path: String?
    )
    
    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )
    
    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
    
    // Classes para o sistema de pontuação
    private data class DoramaScore(
        val totalScore: Int,
        val details: Map<String, Int>
    )
    
    private data class ScoredDorama(
        val info: TMDBInfo,
        val score: DoramaScore
    )
    
    // === FUNÇÕES AUXILIARES FINAIS ===
    
    private fun extractEpisodeNumberFromEpisodeItem(episodeItem: Element): Int {
        val episodeNumberSpan = episodeItem.selectFirst(".dorama-one-episode-number")
        episodeNumberSpan?.text()?.let { spanText ->
            val match = Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE).find(spanText)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 1
            }
        }
        
        val episodeTitle = episodeItem.selectFirst(".episode-title")?.text() ?: ""
        val pattern = Regex("""Episódio\s*(\d+)|Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(episodeTitle)
        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: 1
    }
    
    private fun extractSeasonNumber(seasonTitle: String): Int {
        val pattern = Regex("""(\d+)°\s*Temporada|Temporada\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(seasonTitle)
        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: 1
    }
    
    private fun extractSeasonNumberFromUrl(url: String): Int? {
        val pattern = Regex("""temporada[_-](\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun String.findYear(): Int? {
        val pattern = Regex("""\b(19\d{2}|20\d{2})\b""")
        return pattern.find(this)?.value?.toIntOrNull()
    }
    
    private fun String?.parseDuration(): Int? {
        if (this.isNullOrBlank()) return null
        val pattern = Regex("""(\d+)\s*(min|minutes|minutos)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(this)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
}
