package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import java.net.URLEncoder
import java.util.Base64

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
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
                    val title = link.selectFirst("img")?.attr("alt")
                        ?: link.selectFirst(".rec-title, .title, h2, h3)")?.text()
                        ?: href.substringAfterLast("/").replace("-", " ").replace(Regex("\\d{4}$"), "").trim()

                    if (title.isNotBlank()) {
                        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
                        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
                        val poster = link.selectFirst("img")?.attr("src")?.let { fixUrl(it) }
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
        val title = selectFirst(".rec-title, .movie-title, h2, h3, .title")?.text()
            ?: selectFirst("img")?.attr("alt")
            ?: return null

        val href = attr("href") ?: selectFirst("a")?.attr("href") ?: return null

        val poster = selectFirst("img")?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { fixUrl(it) }
            ?: selectFirst("img")?.attr("data-src")?.let { fixUrl(it) }

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
        val document = app.get("$mainUrl/?s=$encodedQuery").document

        val results = mutableListOf<SearchResponse>()

        document.select("div.recs-grid a.rec-card, a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()

        val jsonLd = extractJsonLd(html)

        val title = jsonLd.title ?: document.selectFirst("h1, .title")?.text() ?: return null
        val year = jsonLd.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        val poster = jsonLd.posterUrl?.replace("/w500/", "/original/")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")

        val plot = jsonLd.description ?: document.selectFirst("meta[name='description']")?.attr("content")
            ?: document.selectFirst(".syn, .description")?.text()

        val tags = jsonLd.genres ?: document.select("a.chip, .chip").map { it.text() }

        val actors = jsonLd.actors?.map { Actor(it, "") } ?: emptyList()

        val director = jsonLd.director?.firstOrNull()

        val isSerie = url.contains("/serie/") || jsonLd.type == "TVSeries"

        return if (isSerie) {
            val episodes = extractEpisodesFromButtons(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        } else {
            val videoData = findFembedUrl(document) ?: ""

            newMovieLoadResponse(title, url, TvType.Movie, videoData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                addActors(actors)
            }
        }
    }

    private fun extractEpisodesFromButtons(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()

        document.select("button.bd-play[data-url]").forEach { button ->
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

    private fun findFembedUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='fembed']")
        if (iframe != null) {
            return iframe.attr("src")
        }

        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) {
            return playButton.attr("data-url")
        }

        val anyButton = document.selectFirst("button[data-url*='fembed']")
        if (anyButton != null) {
            return anyButton.attr("data-url")
        }

        val html = document.html()
        val patterns = listOf(
            Regex("""https?://[^"'\s]*fembed[^"'\s]*/e/\w+"""),
            Regex("""data-url=["'](https?://[^"']*fembed[^"']+)["']"""),
            Regex("""src\s*[:=]\s*["'](https?://[^"']*fembed[^"']+)["']""")
        )

        patterns.forEach { pattern ->
            pattern.find(html)?.let { match ->
                val url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                if (url.isNotBlank()) return url
            }
        }

        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Carregando links de: $data")
        
        // Extrair ID corretamente
        val videoId = extractVideoId(data)
        if (videoId.isEmpty()) {
            println("SuperFlix: ERRO - Não consegui extrair ID do vídeo")
            return false
        }
        
        println("SuperFlix: ID do vídeo extraído: $videoId")
        println("SuperFlix: URL completa: $data")
        
        return try {
            extractFembedVideo(videoId, data, callback)
        } catch (e: Exception) {
            println("SuperFlix: ERRO - ${e.message}")
            e.printStackTrace()
            false
        }
    }

    private fun extractVideoId(data: String): String {
        // Extrair ID de diferentes formatos de URL
        val patterns = listOf(
            Regex("""/e/(\d+)"""),                   // https://fembed.sx/e/85517
            Regex("""/e/(\d+)/\d+-\d+"""),           // https://fembed.sx/e/85517/1-1
            Regex("""/v/([a-zA-Z0-9]+)"""),          // https://fembed.sx/v/abc123
            Regex("""\?.*[&?]id=([^&]+)""")          // ?id=12345
        )
        
        for (pattern in patterns) {
            val match = pattern.find(data)
            if (match != null && match.groupValues.size > 1) {
                val id = match.groupValues[1]
                println("SuperFlix: ID extraído via padrão '$pattern': $id")
                return id
            }
        }
        
        // Fallback: pegar último segmento antes de parâmetros
        val simpleId = data.substringAfterLast("/").substringBefore("?").substringBefore("-")
        println("SuperFlix: ID fallback: $simpleId")
        return simpleId
    }

    private suspend fun extractFembedVideo(
        videoId: String,
        originalUrl: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("SuperFlix: Extraindo vídeo do Fembed ID: $videoId")
        
        // URL 1: Direto da API do Fembed
        val apiUrl = "https://fembed.sx/api/source/$videoId"
        
        println("SuperFlix: API URL: $apiUrl")
        
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://fembed.sx/",
            "Origin" to "https://fembed.sx",
            "X-Requested-With" to "XMLHttpRequest",
            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8"
        )
        
        // PASSO 1: Tentar método direto da API
        println("SuperFlix: === PASSO 1: Método direto da API ===")
        
        try {
            val response = app.post(apiUrl, headers = headers, data = emptyMap())
            println("SuperFlix: Status code: ${response.code}")
            
            if (response.isSuccessful) {
                val responseText = response.text
                println("SuperFlix: Resposta direta (${responseText.length} chars)")
                println("SuperFlix: Primeiros 300 chars: ${responseText.take(300)}")
                
                // Tentar parsear como JSON
                val json = response.parsedSafe<Map<String, Any>>()
                if (json != null) {
                    println("SuperFlix: JSON parseado com ${json.size} chaves: ${json.keys}")
                    
                    // Verificar se tem dados
                    if (json.containsKey("data")) {
                        val data = json["data"]
                        if (data is List<*>) {
                            println("SuperFlix: Encontrados ${data.size} links")
                            
                            // Processar cada link
                            for (item in data) {
                                if (item is Map<*, *>) {
                                    val file = item["file"] as? String
                                    val label = item["label"] as? String
                                    
                                    if (file != null && file.isNotBlank()) {
                                        println("SuperFlix: Link encontrado: $label -> ${file.take(100)}...")
                                        
                                        if (testUrl(file)) {
                                            println("SuperFlix: ✅ URL funciona: ${file.take(100)}...")
                                            addVideoLink(file, label ?: "Desconhecido", "https://fembed.sx/", callback)
                                            return true
                                        }
                                    }
                                }
                            }
                        }
                    }
                    
                    // Verificar se tem success e é falso
                    if (json.containsKey("success") && json["success"] == false) {
                        println("SuperFlix: API retornou success=false")
                    }
                } else {
                    println("SuperFlix: Não consegui parsear JSON")
                }
            } else {
                println("SuperFlix: API falhou com código ${response.code}")
                println("SuperFlix: Mensagem: ${response.text}")
            }
        } catch (e: Exception) {
            println("SuperFlix: Erro método direto: ${e.message}")
        }
        
        // PASSO 2: Tentar método alternativo com player
        println("SuperFlix: === PASSO 2: Método player alternativo ===")
        
        val playerUrl = "https://fembed.sx/api.php"
        val playerData = mapOf(
            "s" to videoId,
            "c" to "",
            "key" to Base64.getEncoder().encodeToString("0".toByteArray()),
            "lang" to "DUB"
        )
        
        try {
            val response = app.post(playerUrl, headers = headers, data = playerData)
            println("SuperFlix: Status player: ${response.code}")
            
            if (response.isSuccessful) {
                val responseText = response.text
                println("SuperFlix: Resposta player (${responseText.length} chars)")
                println("SuperFlix: Primeiros 500 chars: ${responseText.take(500)}")
                
                // Procurar por URLs m3u8 na resposta
                val m3u8Url = findRealM3u8Url(responseText)
                if (m3u8Url != null && testUrl(m3u8Url)) {
                    println("SuperFlix: ✅ URL encontrada via player: $m3u8Url")
                    addVideoLink(m3u8Url, "Dublado", "https://fembed.sx/", callback)
                    return true
                }
                
                // Verificar se tem iframe e extrair conteúdo
                val iframeSrc = extractIframeSrc(responseText)
                if (iframeSrc != null) {
                    println("SuperFlix: Iframe encontrado: $iframeSrc")
                    
                    // Acessar conteúdo do iframe
                    val iframeResponse = app.get(iframeSrc, headers = headers)
                    if (iframeResponse.isSuccessful) {
                        val iframeContent = iframeResponse.text
                        println("SuperFlix: Conteúdo iframe (${iframeContent.length} chars)")
                        
                        val m3u8FromIframe = findRealM3u8Url(iframeContent)
                        if (m3u8FromIframe != null && testUrl(m3u8FromIframe)) {
                            println("SuperFlix: ✅ URL encontrada no iframe: $m3u8FromIframe")
                            addVideoLink(m3u8FromIframe, "Dublado", "https://fembed.sx/", callback)
                            return true
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: Erro método player: ${e.message}")
        }
        
        // PASSO 3: Tentar Legendado
        println("SuperFlix: === PASSO 3: Tentando Legendado ===")
        
        val playerDataSub = mapOf(
            "s" to videoId,
            "c" to "",
            "key" to Base64.getEncoder().encodeToString("0".toByteArray()),
            "lang" to "SUB"
        )
        
        try {
            val response = app.post(playerUrl, headers = headers, data = playerDataSub)
            println("SuperFlix: Status legendado: ${response.code}")
            
            if (response.isSuccessful) {
                val responseText = response.text
                val m3u8Url = findRealM3u8Url(responseText)
                if (m3u8Url != null && testUrl(m3u8Url)) {
                    println("SuperFlix: ✅ URL legendada encontrada: $m3u8Url")
                    addVideoLink(m3u8Url, "Legendado", "https://fembed.sx/", callback)
                    return true
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: Erro legendado: ${e.message}")
        }
        
        // PASSO 4: Tentar acesso à página e extrair player via JavaScript
        println("SuperFlix: === PASSO 4: Extrair da página HTML ===")
        
        val pageUrl = "https://fembed.sx/e/$videoId"
        println("SuperFlix: Acessando página: $pageUrl")
        
        try {
            val pageHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
                "Accept-Language" to "pt-BR,pt;q=0.9,en-US;q=0.8,en;q=0.7",
                "Referer" to "https://superflix21.lol/"
            )
            
            val pageResponse = app.get(pageUrl, headers = pageHeaders)
            if (pageResponse.isSuccessful) {
                val pageHtml = pageResponse.text
                println("SuperFlix: Página obtida (${pageHtml.length} chars)")
                
                // Procurar por URLs m3u8 diretamente no HTML
                val m3u8Url = findRealM3u8Url(pageHtml)
                if (m3u8Url != null && testUrl(m3u8Url)) {
                    println("SuperFlix: ✅ URL encontrada no HTML: $m3u8Url")
                    addVideoLink(m3u8Url, "HTML", "https://fembed.sx/", callback)
                    return true
                }
                
                // Procurar por script com dados
                val scriptPattern = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                val scripts = scriptPattern.findAll(pageHtml)
                
                for (match in scripts) {
                    val script = match.groupValues[1]
                    if (script.contains("sources") || script.contains("file") || script.contains("m3u8")) {
                        println("SuperFlix: Script potencial encontrado")
                        
                        // Extrair qualquer coisa que pareça URL
                        val urlPattern = Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""")
                        val urls = urlPattern.findAll(script)
                        
                        for (urlMatch in urls) {
                            val url = urlMatch.value
                            println("SuperFlix: URL encontrada no script: $url")
                            
                            if (testUrl(url)) {
                                println("SuperFlix: ✅ URL do script funciona: $url")
                                addVideoLink(url, "Script", "https://fembed.sx/", callback)
                                return true
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("SuperFlix: Erro ao acessar página: ${e.message}")
        }
        
        // PASSO 5: Tentar URLs padrão conhecidas
        println("SuperFlix: === PASSO 5: URLs padrão conhecidas ===")
        
        // Domínios conhecidos do Fembed
        val defaultDomains = listOf(
            "https://be6721.rcr72.waw04.i8yz83pn.com",
            "https://be6721.rcr72.waw04.cdn123.com",
            "https://be6721.rcr72.waw04.streamsb.net",
            "https://fcdn.stream",
            "https://embedsb.com",
            "https://sbembed.com"
        )
        
        // Padrões de URL conhecidos
        val urlPatterns = listOf(
            "/hls2/03/10382/{hash}_h/master.m3u8",
            "/hls2/{hash}/master.m3u8",
            "/hls/{videoId}/master.m3u8",
            "/v/{videoId}.m3u8",
            "/api/source/{videoId}"
        )
        
        // Parâmetros conhecidos
        val params = "?t=0oy5UBzU4ee3_2k_o0hqL6xJb_0x8YqlZR4n6PvCU&s=1765199884&e=10800&f=51912396&srv=1060&asn=52601&sp=4000&p=GET"
        
        for (domain in defaultDomains) {
            for (pattern in urlPatterns) {
                val testUrl = if (pattern.contains("{hash}")) {
                    val hash = generateHashFromId(videoId)
                    "$domain${pattern.replace("{hash}", hash)}$params"
                } else if (pattern.contains("{videoId}")) {
                    "$domain${pattern.replace("{videoId}", videoId)}"
                } else {
                    "$domain$pattern"
                }
                
                println("SuperFlix: Testando URL padrão: ${testUrl.take(100)}...")
                if (testUrl(testUrl)) {
                    println("SuperFlix: ✅ URL padrão funciona!")
                    addVideoLink(testUrl, "Padrão", domain, callback)
                    return true
                }
            }
        }
        
        // PASSO 6: Último recurso - usar API pública conhecida
        println("SuperFlix: === PASSO 6: API pública ===")
        
        val publicApis = listOf(
            "https://fembed-hd.com/api/source/$videoId",
            "https://www.fembed.com/api/source/$videoId",
            "https://fembed.net/api/source/$videoId"
        )
        
        for (api in publicApis) {
            try {
                println("SuperFlix: Tentando API pública: $api")
                val response = app.post(api, headers = headers, data = emptyMap())
                
                if (response.isSuccessful) {
                    val json = response.parsedSafe<Map<String, Any>>()
                    if (json != null && json.containsKey("data")) {
                        val data = json["data"] as? List<Map<String, String>>
                        if (data != null) {
                            for (item in data) {
                                val file = item["file"]
                                val label = item["label"] ?: "Desconhecido"
                                
                                if (file != null && testUrl(file)) {
                                    println("SuperFlix: ✅ URL da API pública funciona!")
                                    addVideoLink(file, label, api, callback)
                                    return true
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("SuperFlix: Erro API pública $api: ${e.message}")
            }
        }
        
        println("SuperFlix: ❌❌❌ NENHUM LINK FUNCIONA ❌❌❌")
        return false
    }

    private fun extractIframeSrc(html: String): String? {
        val pattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
        val match = pattern.find(html)
        return match?.groupValues?.get(1)
    }

    private fun findRealM3u8Url(text: String): String? {
        // Procurar por padrões de URL m3u8
        val patterns = listOf(
            Regex("""https?://[^"'\s]+\.m3u8\?[^"'\s]*"""),
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""(https?://[a-z0-9]+\.rcr\d+\.[a-z]+\d+\.[a-z0-9]+\.com/[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""file["'\s]*:["'\s]*(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""src["'\s]*:["'\s]*(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""url["'\s]*:["'\s]*(https?://[^"'\s]+\.m3u8[^"'\s]*)"""),
            Regex("""sources["'\s]*:["'\s]*\[[^\]]*"([^"]+\.m3u8[^"]*)"[^\]]*\]""")
        )
        
        for (pattern in patterns) {
            val matches = pattern.findAll(text)
            matches.forEach { match ->
                var url = if (match.groupValues.size > 1) match.groupValues[1] else match.value
                url = url.trim()
                
                if (url.isNotBlank() && url.contains(".m3u8")) {
                    println("SuperFlix: URL encontrada via padrão: ${url.take(100)}...")
                    return url
                }
            }
        }
        
        return null
    }

    private suspend fun testUrl(url: String): Boolean {
        println("SuperFlix: Testando URL: ${url.take(100)}...")
        
        return try {
            val testHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                "Accept" to "*/*",
                "Referer" to "https://fembed.sx/"
            )
            
            val response = app.get(url, headers = testHeaders, allowRedirects = true, timeout = 10)
            println("SuperFlix: Teste HTTP: ${response.code}")
            
            if (response.isSuccessful) {
                val content = response.text
                // Verificar se é um m3u8 válido
                if (content.contains("#EXTM3U")) {
                    println("SuperFlix: ✅ M3U8 válido (tem EXTM3U)")
                    return true
                } else if (url.contains(".m3u8") && content.isNotBlank()) {
                    // Mesmo sem EXTM3U, se a URL termina em .m3u8 e retorna algo, pode ser válido
                    println("SuperFlix: ⚠️ URL .m3u8 retornou conteúdo mas sem EXTM3U")
                    return true
                }
            }
            false
        } catch (e: Exception) {
            println("SuperFlix: ❌ Erro ao testar URL: ${e.message}")
            false
        }
    }

    private suspend fun addVideoLink(
        url: String,
        language: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        println("SuperFlix: Adicionando link: $language")
        println("SuperFlix: URL final: ${url.take(100)}...")
        
        // Determinar qualidade
        val quality = determineQualityFromUrl(url)
        println("SuperFlix: Qualidade detectada: $quality")
        
        val link = newExtractorLink(
            source = this.name,
            name = "$name ($language)",
            url = url
        )
        
        callback.invoke(link)
        
        println("SuperFlix: ✅ Link adicionado com sucesso!")
    }

    private fun determineQualityFromUrl(url: String): Int {
        return when {
            url.contains("1080") || url.contains("fullhd") || url.contains("fhd") -> Qualities.P1080.value
            url.contains("720") || url.contains("hd") -> Qualities.P720.value
            url.contains("480") || url.contains("sd") -> Qualities.P480.value
            url.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun generateHashFromId(videoId: String): String {
        // Gerar hash determinístico baseado no ID
        val hashInt = videoId.hashCode() and 0xfffffff
        return hashInt.toString(16).padStart(12, '0').take(12)
    }

    private data class JsonLdInfo(
        val title: String? = null,
        val year: Int? = null,
        val posterUrl: String? = null,
        val description: String? = null,
        val genres: List<String>? = null,
        val director: List<String>? = null,
        val actors: List<String>? = null,
        val tmdbId: String? = null,
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

                    val sameAsMatch = Regex("\"sameAs\":\\s*\\[([^\\]]+)\\]").find(json)
                    val tmdbId = sameAsMatch?.groupValues?.get(1)
                        ?.split(",")
                        ?.find { it.contains("themoviedb.org") }
                        ?.substringAfterLast("/")
                        ?.trim(' ', '"', '\'')

                    val type = if (json.contains("\"@type\":\"Movie\"")) "Movie" else "TVSeries"

                    return JsonLdInfo(
                        title = title,
                        year = null,
                        posterUrl = image,
                        description = description,
                        genres = genres,
                        director = director,
                        actors = actors,
                        tmdbId = tmdbId,
                        type = type
                    )
                }
            } catch (e: Exception) {
                // Continua
            }
        }

        return JsonLdInfo()
    }
}