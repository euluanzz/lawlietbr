package com.AnimeFire

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element

class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.io"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        
        private val loadingMutex = Mutex()
        
        private val FIXED_CATEGORIES = listOf(
            "/em-lancamento" to "Lançamentos",
            "/top-animes" to "Top Animes",
            "/lista-de-animes-dublados" to "Animes Dublados",
            "/lista-de-animes-legendados" to "Animes Legendados"
        )
        
        private val ALL_RANDOM_CATEGORIES = listOf(
            "/animes-atualizados" to "Atualizados",
            "/lista-de-filmes-legendados" to "Filmes Legendados",
            "/lista-de-filmes-dublados" to "Filmes Dublados",
            "/genero/acao" to "Ação",
            "/genero/aventura" to "Aventura",
            "/genero/comedia" to "Comédia",
            "/genero/drama" to "Drama",
            "/genero/fantasia" to "Fantasia",
            "/genero/romance" to "Romance",
            "/genero/shounen" to "Shounen",
            "/genero/seinen" to "Seinen",
            "/genero/esporte" to "Esporte",
            "/genero/misterio" to "Mistério",
            "/genero/artes-marciais" to "Artes Marciais",
            "/genero/demonios" to "Demônios",
            "/genero/ecchi" to "Ecchi",
            "/genero/ficcao-cientifica" to "Ficção Científica",
            "/genero/harem" to "Harém",
            "/genero/horror" to "Horror",
            "/genero/magia" to "Magia",
            "/genero/mecha" to "Mecha",
            "/genero/militar" to "Militar",
            "/genero/psicologico" to "Psicológico",
            "/genero/slice-of-life" to "Slice of Life",
            "/genero/sobrenatural" to "Sobrenatural",
            "/genero/superpoder" to "Superpoder",
            "/genero/vampiros" to "Vampiros",
            "/genero/vida-escolar" to "Vida Escolar"
        )
        
        private var cachedRandomTabs: List<Pair<String, String>>? = null
        private var cacheTime: Long = 0
        private const val CACHE_DURATION = 300000L
        
        fun getCombinedTabs(): List<Pair<String, String>> {
            val currentTime = System.currentTimeMillis()
            
            if (cachedRandomTabs != null && (currentTime - cacheTime) < CACHE_DURATION) {
                return FIXED_CATEGORIES + cachedRandomTabs!!
            }
            
            val randomTabs = ALL_RANDOM_CATEGORIES
                .shuffled()
                .take(8)
                .distinctBy { it.first }
            
            cachedRandomTabs = randomTabs
            cacheTime = currentTime
            
            return FIXED_CATEGORIES + randomTabs
        }
    }

    override val mainPage = mainPageOf(
        *getCombinedTabs().map { (path, name) -> 
            "$mainUrl$path" to name 
        }.toTypedArray()
    )

    private fun extractPoster(element: Element): String? {
        return try {
            val metaImage = element.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            if (metaImage != null) return metaImage
            
            val dataSrc = element.selectFirst("img[data-src]")?.attr("data-src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            if (dataSrc != null) return dataSrc
            
            val srcImage = element.selectFirst("img[src]")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            if (srcImage != null) return srcImage
            
            val style = element.attr("style")
            if (style.contains("background-image")) {
                val bgRegex = Regex("""url\(['"]?([^'"()]+)['"]?\)""")
                val match = bgRegex.find(style)
                match?.groupValues?.get(1)?.let { bgUrl ->
                    return fixUrl(bgUrl)
                }
            }
            
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractDetailPoster(document: org.jsoup.nodes.Document): String? {
        return try {
            val metaImage = document.selectFirst("meta[property='og:image']")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
            if (metaImage != null) return metaImage
            
            val posterSelectors = listOf(
                ".sub_animepage_img img",
                ".poster img", 
                ".anime-poster img",
                ".cover img",
                "img.poster"
            )
            
            for (selector in posterSelectors) {
                document.selectFirst(selector)?.let { img ->
                    val src = when {
                        img.hasAttr("src") -> img.attr("src")
                        img.hasAttr("data-src") -> img.attr("data-src")
                        else -> null
                    }
                    
                    src?.takeIf { it.isNotBlank() }?.let { 
                        return fixUrl(it)
                    }
                }
            }
            
            document.selectFirst("img[src*='/animes/'], img[src*='/img/']")
                ?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { return fixUrl(it) }
            
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val href = attr("href") ?: return null
        if (href.isBlank() || (!href.contains("/animes/") && !href.contains("/filmes/"))) return null
        
        val titleElement = selectFirst("h3.animeTitle, .animeTitle, h3, .card-title") ?: return null
        val rawTitle = titleElement.text().trim()
        
        val titleAttr = attr("title")?.trim() ?: ""
        val combinedTitle = if (titleAttr.isNotBlank() && titleAttr.length > 3) titleAttr else rawTitle
        
        if (combinedTitle.isBlank()) return null
        
        val scoreResult = extractScoreAdvanced(this)
        val scoreText = scoreResult.first
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        val hasExplicitDub = combinedTitle.contains("dublado", ignoreCase = true)
        val hasExplicitLeg = combinedTitle.contains("legendado", ignoreCase = true)
        
        val finalHasDub: Boolean
        val finalHasLeg: Boolean
        
        when {
            hasExplicitDub && !hasExplicitLeg -> {
                finalHasDub = true
                finalHasLeg = false
            }
            !hasExplicitDub && hasExplicitLeg -> {
                finalHasDub = false
                finalHasLeg = true
            }
            hasExplicitDub && hasExplicitLeg -> {
                finalHasDub = true
                finalHasLeg = true
            }
            else -> {
                finalHasDub = false
                finalHasLeg = true
            }
        }
        
        val cleanName = extractAnimeName(combinedTitle, selectFirst(".numEp")?.text())
        val isMovie = href.contains("/filmes/") || combinedTitle.contains("filme", ignoreCase = true)
        val sitePoster = extractPoster(this)

        return newAnimeSearchResponse(cleanName, fixUrl(href)) {
            this.posterUrl = sitePoster
            this.type = if (isMovie) TvType.Movie else TvType.Anime
            this.score = score
            
            if (finalHasDub || finalHasLeg) {
                addDubStatus(dubExist = finalHasDub, subExist = finalHasLeg)
            }
        }
    }

    private fun extractScoreAdvanced(element: Element): Pair<String?, String?> {
        val selectors = listOf(
            ".horaUltimosEps" to "Seletor padrão .horaUltimosEps",
            ".rating" to "Seletor .rating",
            ".score" to "Seletor .score",
            ".numEp + span" to "Próximo ao .numEp",
            ".episodes + span" to "Próximo a .episodes",
            ".card-footer span" to "No rodapé do card",
            "span:contains(★)" to "Span com estrela",
            "span:contains(/10)" to "Span com /10",
            "[class*='rating']" to "Classe contém 'rating'",
            "[class*='score']" to "Classe contém 'score'",
            "small" to "Tag small",
            "b" to "Tag bold"
        )

        for ((selector, _) in selectors) {
            val found = element.selectFirst(selector)?.text()?.trim()
            if (!found.isNullOrBlank() && isScoreLike(found)) {
                return found to selector
            }
        }

        element.parent()?.let { parent ->
            for ((selector, _) in selectors) {
                val found = parent.selectFirst(selector)?.text()?.trim()
                if (!found.isNullOrBlank() && isScoreLike(found)) {
                    return found to "parent.$selector"
                }
            }
        }

        val html = element.outerHtml()
        val scoreRegexes = listOf(
            Regex("""(\d+\.\d+|\d+)\s*(?:★|/10|pontos)"""),
            Regex("""class="[^"]*(?:rating|score)[^"]*">([^<]+)""")
        )

        for (regex in scoreRegexes) {
            val match = regex.find(html)
            if (match != null) {
                val found = match.groupValues[1].trim()
                if (isScoreLike(found)) {
                    return found to "regex"
                }
            }
        }

        return null to null
    }

    private fun isScoreLike(text: String): Boolean {
        return when {
            text.equals("N/A", ignoreCase = true) -> true
            text.matches(Regex("""^\d+(\.\d+)?$""")) -> true
            text.matches(Regex("""^\d+(\.\d+)?/10$""")) -> true
            text.contains("★") -> true
            text.contains("pontos", ignoreCase = true) -> true
            else -> false
        }
    }

    private fun extractAnimeName(fullText: String, episodeText: String?): String {
        var cleanName = fullText
        
        episodeText?.let {
            cleanName = cleanName.replace(it, "").trim()
        }
        
        val patterns = listOf(
            Regex("(?i)\\(dublado\\)"),
            Regex("(?i)\\(legendado\\)"),
            Regex("(?i)todos os episódios"),
            Regex("\\s*-\\s*$"),
            Regex("\\(\\d{4}\\)")
        )
        
        patterns.forEach { pattern ->
            cleanName = cleanName.replace(pattern, "")
        }
        
        return cleanName.trim().replace(Regex("\\s+"), " ")
    }

    private suspend fun detectHasNextPage(document: org.jsoup.nodes.Document, currentPageNum: Int): Boolean {
        return try {
            val hasElements = document.select("article, .card, .anime-item").isNotEmpty()
            if (!hasElements) return false
            
            val hasPagination = document.select(".pagination, .page-numbers, .paginacao").isNotEmpty()
            val hasNextLink = document.select("a:contains(Próxima), a:contains(›), a:contains(>), a[href*='/${currentPageNum + 1}']").isNotEmpty()
            
            hasElements && (hasPagination || hasNextLink)
        } catch (e: Exception) {
            false
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return loadingMutex.withLock {
            try {
                val isFixedTab = FIXED_CATEGORIES.any { it.second == request.name }
                val basePath = request.data.removePrefix(mainUrl)
                val sitePageNumber = page + 1
                
                val pageUrl = if (sitePageNumber == 1) {
                    "$mainUrl$basePath"
                } else {
                    "$mainUrl$basePath/$sitePageNumber"
                }
                
                val document = app.get(pageUrl, timeout = 25).document
                val elements = document.select("""
                    article a,
                    .card a,
                    .anime-item a,
                    a[href*='/animes/'],
                    a[href*='/filmes/']
                """).take(50)
                
                val homeItems = mutableListOf<SearchResponse>()
                
                elements.forEach { element ->
                    try {
                        val item = element.toSearchResponse()
                        if (item != null) {
                            homeItems.add(item)
                        }
                    } catch (e: Exception) {
                    }
                }
                
                val hasNextPage = detectHasNextPage(document, sitePageNumber)
                
                newHomePageResponse(
                    request.name,
                    homeItems.distinctBy { it.url },
                    hasNext = hasNextPage
                )
                
            } catch (e: Exception) {
                newHomePageResponse(request.name, emptyList(), false)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val searchUrl = "$mainUrl$SEARCH_PATH/${query.trim().replace(" ", "-").lowercase()}"
        
        return try {
            val document = app.get(searchUrl, timeout = 15).document
            
            document.select("a[href*='/animes/'], a[href*='/filmes/']")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
                .take(30)
                
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractTextAfterLabel(document: org.jsoup.nodes.Document, label: String): String? {
        return document.select("div.animeInfo:contains($label) span.spanAnimeInfo")
            .firstOrNull()?.text()?.trim()
    }

    private fun extractYear(text: String?): Int? {
        if (text.isNullOrBlank()) return null
        val yearRegex = Regex("""(\d{4})""")
        val match = yearRegex.find(text)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    override suspend fun load(url: String): LoadResponse {
        return try {
            val document = app.get(url, timeout = 35).document
            
            val title = document.selectFirst("h1.quicksand400")?.text()?.trim() 
                ?: document.selectFirst("h1.animeTitle, h1")?.text()?.trim() 
                ?: "Sem Título"
            
            val poster = extractDetailPoster(document)
            val background = poster
            
            val synopsis = document.selectFirst("div.divSinopse span.spanAnimeInfo")
                ?.text()
                ?.trim()
                ?: document.selectFirst("div.divSinopse")
                ?.text()
                ?.replace("Sinopse:", "")
                ?.trim()
                ?: document.selectFirst("p.sinopse, .description")
                ?.text()
                ?.trim()
                ?: "Sinopse não disponível."
            
            val yearText = extractTextAfterLabel(document, "Ano:")
            val year = extractYear(yearText)
            
            val statusElement = document.selectFirst("div.animeInfo:contains(Status do Anime:) span.spanAnimeInfo")
            val statusText = statusElement?.text()?.trim() ?: "Desconhecido"
            val showStatus = getStatus(statusText)
            
            val genres = mutableListOf<String>()
            document.select("div.animeInfo a[href*='/genero/']").forEach { element ->
                element.text().trim().takeIf { it.isNotBlank() }?.let { 
                    genres.add(it) 
                }
            }
            
            if (genres.isEmpty()) {
                document.select("div.animeInfo:contains(Gênero:) span.spanAnimeInfo")
                    .firstOrNull()
                    ?.text()
                    ?.trim()
                    ?.split(",", ";")
                    ?.forEach { genre ->
                        genre.trim().takeIf { it.isNotBlank() }?.let { 
                            genres.add(it) 
                        }
                    }
            }
            
            val audioText = extractTextAfterLabel(document, "Áudio:")
            val hasDub = audioText?.contains("dublado", ignoreCase = true) ?: false
            val hasSub = audioText?.contains("legendado", ignoreCase = true) ?: true
            
            val studio = extractTextAfterLabel(document, "Estúdios:")
            
            val scoreText = document.selectFirst("#anime_score")?.text()?.trim()
            val score = scoreText?.toFloatOrNull()?.let { Score.from10(it) }
            
            val isMovie = url.contains("/filmes/") || 
                         title.contains("filme", ignoreCase = true)
            
            val episodes = extractAllEpisodesFuncional(document, url)
            
            val response = newAnimeLoadResponse(
                title, 
                url, 
                if (isMovie) TvType.Movie else TvType.Anime
            ) {
                this.posterUrl = poster
                this.backgroundPosterUrl = background
                this.year = year
                this.plot = synopsis
                this.tags = genres
                this.score = score
                
                studio?.let { 
                    try {
                        val studioField = this::class.members.find { it.name == "studio" }
                        studioField?.call(this, it)
                    } catch (e: Exception) {
                    }
                }
                
                if (episodes.isNotEmpty()) {
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }
            
            response
            
        } catch (e: Exception) {
            newAnimeLoadResponse("Erro ao carregar", url, TvType.Anime) {
                this.plot = "Não foi possível carregar esta página."
            }
        }
    }

    private fun extractAllEpisodesFuncional(document: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val animeTitle = document.selectFirst("h1.quicksand400")?.text()?.trim() ?: ""
        
        val selectors = listOf(
            "div.div_video_list a.lEp.epT",
            "a.lEp.epT, a.lEp, .divListaEps a, [href*='/video/'], [href*='/episodio/']"
        )
        
        var episodeElements: org.jsoup.select.Elements? = null
        
        for (selector in selectors) {
            val elements = document.select(selector)
            if (elements.isNotEmpty()) {
                episodeElements = elements
                break
            }
        }
        
        if (episodeElements == null || episodeElements.isEmpty()) {
            val allLinks = document.select("a[href]")
            val filteredElements = mutableListOf<org.jsoup.nodes.Element>()
            
            allLinks.forEach { element ->
                val href = element.attr("href")
                if (href.matches(Regex(".*/animes/[^/]+/\\d+/?$"))) {
                    filteredElements.add(element)
                }
            }
            
            episodeElements = org.jsoup.select.Elements(filteredElements)
        }
        
        if (episodeElements == null || episodeElements.isEmpty()) {
            return emptyList()
        }
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val href = element.attr("href")
                var text = element.text().trim()
                
                if (href.isBlank() || text.isBlank()) {
                    return@forEachIndexed
                }
                
                if (animeTitle.isNotBlank()) {
                    text = text.replace(animeTitle, "").trim()
                    text = text.replace(Regex("^\\s*-\\s*"), "").trim()
                }
                
                if (text.length > 30) {
                    val epMatch = Regex("""Epis[oó]dio\s*\d+""", RegexOption.IGNORE_CASE).find(text)
                    if (epMatch != null) {
                        text = epMatch.value
                    }
                }
                
                val epNum = extractEpisodeNumberFuncional(text, href) ?: (index + 1)
                if (!text.contains(Regex("""Epis[oó]dio""", RegexOption.IGNORE_CASE))) {
                    text = "Episódio $epNum"
                }
                
                val episodeNumber = epNum
                
                val fixedHref = when {
                    href.startsWith("//") -> "https:$href"
                    href.startsWith("/") -> "$mainUrl$href"
                    !href.startsWith("http") -> "$mainUrl/$href"
                    else -> href
                }
                
                val episode = newEpisode(fixedHref) {
                    this.name = text
                    this.episode = episodeNumber
                    this.season = 1
                }
                
                episodes.add(episode)
                
            } catch (e: Exception) {
            }
        }
        
        return episodes.sortedBy { it.episode }
    }

    private fun extractEpisodeNumberFuncional(text: String, href: String = ""): Int? {
        val textPatterns = listOf(
            Regex("""Epis[oó]dio\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""Ep\.?\s*(\d+)""", RegexOption.IGNORE_CASE),
            Regex("""\b(\d+)$""")
        )
        
        for (pattern in textPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                return match.groupValues[1].toIntOrNull()
            }
        }
        
        val urlPattern = Regex("""/animes/[^/]+/(\d+)$""")
        val urlMatch = urlPattern.find(href)
        if (urlMatch != null) {
            return urlMatch.groupValues[1].toIntOrNull()
        }
        
        return null
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            AnimeFireVideoExtractor.extractVideoLinks(data, mainUrl, name, callback)
        } catch (e: Exception) {
            false
        }
    }
}
