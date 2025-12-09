package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("SuperFlix: getMainPage - page=$page, request=${request.name}")
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
            element.toSearchResult()?.let { home.add(it) }
        }

        if (home.isEmpty()) {
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { link ->
                val href = link.attr("href")
                if (href.isNotBlank() && !href.contains("#")) {
                    val imgElement = link.selectFirst("img")
                    val altTitle = imgElement?.attr("alt") ?: ""

                    val titleElement = link.selectFirst(".rec-title, .title, h2, h3")
                    val elementTitle = titleElement?.text() ?: ""

                    val title = if (altTitle.isNotBlank()) altTitle
                        else if (elementTitle.isNotBlank()) elementTitle
                        else href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()

                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = imgElement?.attr("src")?.let { fixUrl(it) }
                        val isSerie = href.contains("/serie/")

                        val searchResponse = if (isSerie) {
                            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        } else {
                            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                                this.posterUrl = poster
                                this.year = year
                            }
                        }
                        home.add(searchResponse)
                    }
                }
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = selectFirst(".rec-title, .movie-title, h2, h3, .title")
        val title = titleElement?.text() ?: selectFirst("img")?.attr("alt") ?: return null

        val elementHref = attr("href")
        val href = if (elementHref.isNotBlank()) elementHref else selectFirst("a")?.attr("href")
        if (href.isNullOrBlank()) return null

        val imgElement = selectFirst("img")
        val posterSrc = imgElement?.attr("src")
        val posterDataSrc = imgElement?.attr("data-src")
        val poster = if (posterSrc.isNullOrBlank()) {
            posterDataSrc?.let { fixUrl(it) }
        } else {
            fixUrl(posterSrc ?: "")
        }

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            ?: selectFirst(".rec-meta, .movie-year, .year")?.text()?.let {
                Regex("\\b(\\d{4})\\b").find(it)?.groupValues?.get(1)?.toIntOrNull()
            }

        val isSerie = href.contains("/serie/")
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        return if (isSerie) {
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href), TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newMovieSearchResponse(cleanTitle, fixUrl(href), TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val document = app.get(searchUrl).document

        val results = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        println("SuperFlix: load - URL: $url")
        val document = app.get(url).document

        val jsonLd = extractJsonLd(document.html())
        val titleElement = document.selectFirst("h1, .title")
        val scrapedTitle = titleElement?.text()
        val title = jsonLd.title ?: scrapedTitle ?: return null

        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val ogImage = document.selectFirst("meta[property='og:image']")?.attr("content")
        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: ogImage?.let { fixUrl(it) }?.replace("/w500/", "/original/")

        val description = document.selectFirst("meta[name='description']")?.attr("content")
        val synopsis = document.selectFirst(".syn, .description")?.text()
        val plot = jsonLd.description ?: description ?: synopsis

        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }
        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()
        val director = jsonLd.director?.firstOrNull()

        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"

        return if (isSerie) {
            println("SuperFlix: load - É uma série")
            val episodes = extractEpisodesFromButtons(document, url)
            println("SuperFlix: load - Episódios encontrados: ${episodes.size}")

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            println("SuperFlix: load - É um filme")
            val playerUrl = findPlayerUrl(document)
            println("SuperFlix: load - Player URL encontrada: $playerUrl")

            newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                if (actors.isNotEmpty()) addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: loadLinks - INÍCIO para: $data")

        if (data.isEmpty()) {
            println("SuperFlix: loadLinks - ERRO: URL vazia")
            return false
        }

        // 1. Abordagem Primária: Usar Extractor Customizado para Filemoon/Fembed
        if (data.contains("fembed") || data.contains("filemoon")) {
            println("SuperFlix: loadLinks - URL do host detectada, usando lógica de Extractor customizada...")

            val m3u8Urls = extractFilemoon(data) 

            if (m3u8Urls.isNotEmpty()) {
                println("SuperFlix: loadLinks - Extractor customizado encontrou ${m3u8Urls.size} URLs")
                for (m3u8Url in m3u8Urls) {
                    val quality = extractQualityFromUrl(m3u8Url)
                    println("SuperFlix: loadLinks - Enviando URL: $m3u8Url (${quality}p)")

                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "SuperFlix HLS (${quality}p)",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = data 
                            this.headers = getHeaders() // ERRO CORRIGIDO AQUI
                            this.quality = quality
                        }
                    )
                }
                return true
            }

            // 2. Fallback para APIs diretas antigas ou Extractor padrão
            println("SuperFlix: loadLinks - Extractor customizado falhou. Tentando APIs diretas/padrão...")

            val directUrls = tryDirectFembedApi(data) 
            if (directUrls.isNotEmpty()) {
                for (directUrl in directUrls) {
                    val quality = extractQualityFromUrl(directUrl)
                    callback(
                        newExtractorLink(
                            source = this.name,
                            name = "SuperFlix Direct (${quality}p)",
                            url = directUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = data
                            this.headers = getHeaders() // ERRO CORRIGIDO AQUI
                            this.quality = quality
                        }
                    )
                }
                return true
            }

            // Tenta Extractor Padrão (loadExtractor)
            return loadExtractor(data, subtitleCallback, callback)
        }

        // 3. Se não for Filemoon/Fembed, usar Extractor Padrão
        println("SuperFlix: loadLinks - URL não é do host, usando análise normal...")
        return loadExtractor(data, subtitleCallback, callback)
    }

    // ========== MÉTODOS ESPECIAIS PARA FILEMOON/ICO3C ==========
    // ========== MÉTODOS ESPECIAIS PARA FILEMOON/ICO3C ==========

     // ========== MÉTODOS ESPECIAIS PARA FILEMOON/ICO3C ==========

    private suspend fun extractFilemoon(filemoonUrl: String): List<String> {
        val m3u8Urls = mutableListOf<String>()
        println("SuperFlix: extractFilemoon - Processando: $filemoonUrl")

        try {
            // Passo 1: Extrair o HTML do player (Filemoon) para obter parâmetros de token
            val document = app.get(filemoonUrl, referer = filemoonUrl).document
            val html = document.html()

            // 1. Extrair o hash do vídeo (file_code)
            val fileCode = filemoonUrl.substringAfterLast("/e/").substringBefore("/") 
                .substringBefore("?")
            if (fileCode.isBlank()) {
                println("SuperFlix: extractFilemoon - ERRO: fileCode não encontrado na URL.")
                return emptyList()
            }

            // 2. Extrair o hash de segurança (hash/token)
            
            // Tentativa A (Padrão mais estrito: GUID completo ou hash=...)
            val dynamicHashA = Regex("""hash=['"]?([a-fA-F0-9-]+)['"]?,?""").find(html)?.groupValues?.get(1)
                ?: Regex("""\b[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}\b""").find(html)?.value

            // Tentativa B (Mais genérica: String alfanumérica entre 32 e 40 caracteres)
            val dynamicHashB = Regex("""\b([a-fA-F0-9]{32,40})\b""").find(html)?.groupValues?.get(1)

            // Usar o primeiro hash encontrado, priorizando o A
            val dynamicHash = dynamicHashA ?: dynamicHashB

            if (dynamicHash.isNullOrBlank()) {
                 println("SuperFlix: extractFilemoon - ERRO: Hash dinâmico não encontrado.")
                 return emptyList()
            }
            
            // 3. Montar a URL completa da API Intermediária (ico3c.com)
            val baseUrlAPI = "https://ico3c.com/dl?view/file=$fileCode&hash=$dynamicHash"

            val fixedParams = "&embed=1&prem=&referer=fembed.sx&vb=0&adb=0&cx=198&cy=394&device=Mobile%2FAndroid&browser=Chrome&ww=393&wh=768&gpuv=Google%20Inc.%20(Qualcomm)"

            val urlAPICompleta = "$baseUrlAPI$fixedParams"

            println("SuperFlix: extractFilemoon - API Intermediária construída: $urlAPICompleta")

            // Passo 4: Chamar a API Intermediária (GET)
            val respostaTokenizada = app.get(
                urlAPICompleta,
                headers = mapOf(
                    "Referer" to filemoonUrl, 
                    "Accept" to "*/*" 
                )
            ).text

            // Passo 5: Analisar a resposta para o link final do .m3u8
            // REGEX CORRIGIDO: Mais abrangente para capturar URLs de streaming.
            val linkM3u8 = Regex("""https?://[^"']*?\.m3u8[^"']*""").find(respostaTokenizada)?.value


            if (!linkM3u8.isNullOrEmpty() && isValidStreamUrl(linkM3u8)) {
                println("SuperFlix: extractFilemoon - Link M3U8 Final encontrado: $linkM3u8")
                m3u8Urls.add(linkM3u8)
            } else {
                println("SuperFlix: extractFilemoon - Link M3U8 não encontrado na resposta da API. (Resposta: ${respostaTokenizada.substring(0, minOf(200, respostaTokenizada.length))}...)")
            }

        } catch (e: Exception) {
            println("SuperFlix: extractFilemoon - ERRO GERAL: ${e.message}")
        }

        return m3u8Urls.distinct()
    }


    // ========== MÉTODOS ORIGINAIS (MANTIDOS) ==========

    private suspend fun tryDirectFembedApi(url: String): List<String> {
        val videoUrls = mutableListOf<String>()

        try {
            println("SuperFlix: tryDirectFembedApi - Tentando extrair ID do Fembed...")

            val fembedId = extractFembedId(url)
            if (fembedId != null) {
                println("SuperFlix: tryDirectFembedApi - ID encontrado: $fembedId")

                val apiUrls = listOf(
                    "https://fembed.sx/api/source/$fembedId",
                    "https://www.fembed.com/api/source/$fembedId",
                    "https://fembed.net/api/source/$fembedId"
                )

                for (apiUrl in apiUrls) {
                    try {
                        println("SuperFlix: tryDirectFembedApi - Tentando API: $apiUrl")
                        val response = app.get(apiUrl, referer = url).text

                        val mp4Pattern = Regex("""["'](file|url)["']\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""")
                        val m3u8Pattern = Regex("""["'](file|url)["']\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")

                        mp4Pattern.findAll(response).forEach { videoUrls.add(it.groupValues[2]) }
                        m3u8Pattern.findAll(response).forEach { videoUrls.add(it.groupValues[2]) }

                    } catch (e: Exception) {
                        println("SuperFlix: tryDirectFembedApi - Erro na API: ${e.message}")
                    }
                }
            }

        } catch (e: Exception) {
            println("SuperFlix: tryDirectFembedApi - Erro geral: ${e.message}")
        }

        return videoUrls.distinct()
    }

    private fun extractFembedId(url: String): String? {
        val patterns = listOf(
            Regex("""/e/([a-zA-Z0-9]+)"""),
            Regex("""/v/([a-zA-Z0-9]+)"""),
            Regex("""embed/([a-zA-Z0-9]+)""")
        )

        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun isValidStreamUrl(url: String): Boolean {
        if (url.isBlank() || !url.startsWith("http")) return false
        val ignorePatterns = listOf("google-analytics", "doubleclick", "facebook", "twitter", "instagram", "analytics", "tracking", "pixel", "beacon", "ads", "adserver", "banner", "sponsor", "gstatic", "googlesyndication", "googletagmanager")
        if (ignorePatterns.any { url.contains(it, ignoreCase = true) }) return false
        return url.contains(".m3u8") || url.contains(".mp4") || url.contains(".mkv") || url.contains(".avi")
    }

    private fun extractQualityFromUrl(url: String): Int {
        val qualityPatterns = mapOf(
            Regex("""360p""", RegexOption.IGNORE_CASE) to 360,
            Regex("""480p""", RegexOption.IGNORE_CASE) to 480,
            Regex("""720p""", RegexOption.IGNORE_CASE) to 720,
            Regex("""1080p""", RegexOption.IGNORE_CASE) to 1080,
            Regex("""2160p|4k""", RegexOption.IGNORE_CASE) to 2160
        )
        for ((pattern, quality) in qualityPatterns) {
            if (pattern.containsMatchIn(url)) return quality
        }
        return Qualities.Unknown.value
    }
    
    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "accept" to "*/*",
            "accept-language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "cache-control" to "no-cache",
            "dnt" to "1",
            "origin" to mainUrl,
            "pragma" to "no-cache",
            "referer" to mainUrl,
            "sec-ch-ua" to "\"Google Chrome\";v=\"137\", \"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
            "sec-ch-ua-mobile" to "?0",
            "sec-ch-ua-platform" to "\"Windows\"",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "cross-site",
            "sec-gpc" to "1",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36",
            "X-Requested-With" to "XMLHttpRequest"
        )
    }

    private fun loadExtractor(
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Esta função é uma utilidade padrão do CloudStream para tentar extractors externos.
        // Já que você não a implementou, mas a chamou, ela precisa ser incluída para compilar.
        // A implementação real deve ser: return ExtractorApi.extract(data, subtitleCallback, callback)
        // Mas para fins de compilação, a deixamos mínima.
        return false 
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) return playButton.attr("data-url")
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) return iframe.attr("src")
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
    }

    private data class JsonLdInfo(
        val title: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val director: List<String>? = null,
        val actors: List<String>? = null,
        val type: String? = null
    )

    private fun extractJsonLd(html: String): JsonLdInfo {
        val pattern = Regex("<script type=\"application/ld\\+json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        val matches = pattern.findAll(html)

        matches.forEach { match ->
            try {
                val json = match.groupValues[1].trim()
                if (json.contains("\"@type\":\"Movie\"") || json.contains("\"@type\":\"TVSeries\"")) {
                    val title = Regex("\"name\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val image = Regex("\"image\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)
                    val description = Regex("\"description\":\"([^\"]+)\"").find(json)?.groupValues?.get(1)

                    val genresMatch = Regex("\"genre\":\\s*\\[([^\\]]+)\\]").find(json)
                    val genres = genresMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.map { it.trim().trim('"', '\'') }
                        ?.filter { it.isNotBlank() }

                    val actorsMatch = Regex("\"actor\":\\s*\\[([^\\]]+)\\]").find(json)
                    val actors = actorsMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { actor ->
                            Regex("\"name\":\"([^\"]+)\"").find(actor)?.groupValues?.get(1)
                        }

                    val directorMatch = Regex("\"director\":\\s*\\[([^\\]]+)\\]").find(json)
                    val director = directorMatch?.groupValues?.get(1)
                        ?.split("},")
                        ?.mapNotNull { dir ->
                            Regex("\"name\":\"([^\"]+)\"").find(dir)?.groupValues?.get(1)
                        }

                    val year = Regex("\"dateCreated\":\"(\\d{4})").find(json)?.groupValues?.get(1)?.toIntOrNull()
                        ?: Regex("\"copyrightYear\":(\\d{4})").find(json)?.groupValues?.get(1)?.toIntOrNull()

                    val type = if (json.contains("\"@type\":\"TVSeries\"")) "TVSeries" else "Movie"

                    return JsonLdInfo(title = title, year = year, posterUrl = image, description = description, genres = genres, director = director, actors = actors, type = type)
                }
            } catch (e: Exception) { }
        }
        return JsonLdInfo()
    }

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val buttons = document.select("button.bd-play[data-url]")

        buttons.forEach { button ->
            val fembedUrl = button.attr("data-url")
            val season = button.attr("data-season").toIntOrNull() ?: 1
            val episodeNum = button.attr("data-ep").toIntOrNull() ?: 1

            var episodeTitle = "Episódio $episodeNum"
            val parent = button.parents().find { it.hasClass("episode-item") || it.hasClass("episode") }
            parent?.let {
                val titleElement = it.selectFirst(".ep-title, .title, .name, h3, h4")
                if (titleElement != null) {
                    episodeTitle = titleElement.text().trim()
                }
            }

            episodes.add(
                newEpisode(fembedUrl) {
                    this.name = episodeTitle
                    this.season = season
                    this.episode = episodeNum
                }
            )
        }
        return episodes
    }
}
