package com.CineAgora

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat

class CineAgora : MainAPI() {
    override var mainUrl = "https://cineagora.net"
    override var name = "CineAgora"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = false

    // Configuração TMDB
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private val TMDB_ACCESS_TOKEN = BuildConfig.TMDB_ACCESS_TOKEN

    companion object {
        // Seções que estão na página principal (com URLs específicas)
        private val HOME_SECTIONS = listOf(
            "ultimos-filmes" to "Últimos Filmes",
            "ultimas-series" to "Últimas Séries"
        )
        
        // Seções com URLs específicas
        private val SECTION_URLS = mapOf(
            // Links específicos para as seções da home
            "ultimos-filmes" to "https://cineagora.net/filmes-hd-online/",
            "ultimas-series" to "https://cineagora.net/series-online-hd-gratis/",
            // Outras seções
            "filmes-populares" to "https://cineagora.net/filmes-hd-online/filmes-populares-hd/",
            "series-populares" to "https://cineagora.net/series-online-hd-gratis/series-populares-hd/",
            "netflix" to "https://cineagora.net/netflix/",
            "paramount" to "https://cineagora.net/paramount/",
            "disney" to "https://cineagora.net/disney/",
            "apple" to "https://cineagora.net/apple/",
            "hbo" to "https://cineagora.net/hbo/",
            "acao" to "https://cineagora.net/filmes-hd-online/filmes-de-acao-hd/",
            "aventura" to "https://cineagora.net/filmes-hd-online/filmes-de-aventura-gratis/",
            "animacao" to "https://cineagora.net/filmes-hd-online/filmes-de-animacao-online/",
            "biograficos" to "https://cineagora.net/filmes-hd-online/assistir-filmes-biograficos/",
            "comedia" to "https://cineagora.net/filmes-hd-online/comedia-filmes-online/",
            "crime" to "https://cineagora.net/filmes-hd-online/crime-filmes-online/",
            "documentarios" to "https://cineagora.net/filmes-hd-online/documentarios-em-portugues/",
            "esporte" to "https://cineagora.net/filmes-hd-online/filmes-de-esporte-hd/",
            "drama" to "https://cineagora.net/filmes-hd-online/filmes-drama-online-hd/",
            "familia" to "https://cineagora.net/filmes-hd-online/filmes-familia-online/",
            "fantasia" to "https://cineagora.net/filmes-hd-online/filmes-fantasia-magia/",
            "historicos" to "https://cineagora.net/filmes-hd-online/filmes-historicos-hd/",
            "terror" to "https://cineagora.net/filmes-hd-online/filmes-terror-horror/",
            "musicais" to "https://cineagora.net/filmes-hd-online/filmes-musicais-online/",
            "misterio" to "https://cineagora.net/filmes-hd-online/filmes-misterio-suspense/",
            "romanticos" to "https://cineagora.net/filmes-hd-online/filmes-romanticos-online/",
            "suspense" to "https://cineagora.net/filmes-hd-online/filmes-suspense-hd/",
            "sci-fi" to "https://cineagora.net/filmes-hd-online/ficcao-cientifica-hd/",
            "tv" to "https://cineagora.net/filmes-hd-online/filmes-para-tv-hd/",
            "thriller" to "https://cineagora.net/filmes-hd-online/thriller-suspense-online/",
            "guerra" to "https://cineagora.net/filmes-hd-online/filmes-guerra-epicas/",
            "faroeste" to "https://cineagora.net/filmes-hd-online/filmes-faroeste-online/"
        )
    }

    override val mainPage = mainPageOf(
        *HOME_SECTIONS.map { (section, name) -> 
            "home_$section" to name 
        }.toTypedArray(),
        *SECTION_URLS.filterKeys { it !in HOME_SECTIONS.map { it.first } }
                     .map { (section, _) ->
                         "section_$section" to getSectionName(section)
                     }.toTypedArray()
    )

    private fun getSectionName(section: String): String {
        return when (section) {
            "ultimos-filmes" -> "Últimos Filmes"
            "ultimas-series" -> "Últimas Séries"
            "filmes-populares" -> "Filmes Populares"
            "series-populares" -> "Séries Populares"
            "netflix" -> "Netflix"
            "paramount" -> "Paramount+"
            "disney" -> "Disney+"
            "apple" -> "Apple TV+"
            "hbo" -> "HBO Max"
            "acao" -> "Ação"
            "aventura" -> "Aventura"
            "animacao" -> "Animação"
            "biograficos" -> "Biográficos"
            "comedia" -> "Comédia"
            "crime" -> "Crime"
            "documentarios" -> "Documentários"
            "esporte" -> "Esporte"
            "drama" -> "Drama"
            "familia" -> "Família"
            "fantasia" -> "Fantasia"
            "historicos" -> "Históricos"
            "terror" -> "Terror"
            "musicais" -> "Musicais"
            "misterio" -> "Mistério"
            "romanticos" -> "Românticos"
            "suspense" -> "Suspense"
            "sci-fi" -> "Sci-Fi"
            "tv" -> "TV"
            "thriller" -> "Thriller"
            "guerra" -> "Guerra"
            "faroeste" -> "Faroeste"
            else -> section.replace("-", " ").split(" ").joinToString(" ") { 
                it.replaceFirstChar { char -> char.uppercase() }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("[CineAgora] 📋 getMainPage chamado - Página: $page, Request: ${request.name}, Data: ${request.data}")
        
        val sectionId = request.data.removePrefix("home_").removePrefix("section_")
        println("[CineAgora] 📋 Section ID: $sectionId")
        
        // Usar URL específica para cada seção
        val baseUrl = SECTION_URLS[sectionId] ?: mainUrl
        println("[CineAgora] 📋 Base URL: $baseUrl")
        
        // Verificar se a página atual é maior que 1 para adicionar /page/N/
        val url = if (page > 1) {
            // Verificar se a URL base já tem uma barra no final
            val cleanUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
            "$cleanUrl/page/$page/"
        } else {
            baseUrl
        }
        
        println("[CineAgora] 📋 URL final: $url")
        
        val document = try {
            println("[CineAgora] 📋 Fazendo requisição para: $url")
            val response = app.get(url)
            println("[CineAgora] 📋 Status da resposta: ${response.code}")
            response.document
        } catch (e: Exception) {
            println("[CineAgora] ❌ Erro ao carregar página: ${e.message}")
            // Se falhar na paginação, pode ser que a seção não suporte
            if (page > 1) {
                // Retorna lista vazia se não houver mais páginas
                return newHomePageResponse(request.name, emptyList(), false)
            } else {
                throw e
            }
        }
        
        println("[CineAgora] 📋 Documento carregado, título: ${document.title()}")
        
        val items = extractSectionItems(document, sectionId)
        println("[CineAgora] 📋 ${items.size} itens extraídos")
        
        // Verificar se há botões de paginação para determinar se há mais páginas
        val hasNextPage = checkForNextPage(document, page)
        println("[CineAgora] 📋 Tem próxima página? $hasNextPage")
        
        return newHomePageResponse(request.name, items.distinctBy { it.url }, hasNextPage)
    }

    private fun checkForNextPage(document: org.jsoup.nodes.Document, currentPage: Int): Boolean {
        println("[CineAgora] 🔍 Verificando próxima página, página atual: $currentPage")
        
        // Verificar botões de paginação
        val pagination = document.select(".pagination, .nav-links, .page-numbers, a[href*='page/']")
        println("[CineAgora] 🔍 Elementos de paginação encontrados: ${pagination.size}")
        
        // Verificar se há algum link para a próxima página
        val nextPageLinks = pagination.filter { element ->
            val href = element.attr("href")
            val text = element.text().lowercase()
            val isNext = href.contains("/page/${currentPage + 1}/") || 
                        text.contains("próxima") || 
                        text.contains("next") ||
                        element.hasClass("next") ||
                        element.hasClass("next-page")
            
            if (isNext) {
                println("[CineAgora] 🔍 ✅ Encontrou link para próxima página: href='$href', text='${element.text()}'")
            }
            isNext
        }
        
        // Ou verificar se há número da próxima página
        val pageNumbers = document.select(".page-numbers, .page-number, [class*='page']")
            .filter { it.text().matches(Regex("\\d+")) }
            .mapNotNull { 
                val num = it.text().toIntOrNull()
                if (num != null) {
                    println("[CineAgora] 🔍 Número de página encontrado: $num")
                }
                num
            }
            .sorted()
        
        // Se houver número maior que a página atual
        if (pageNumbers.any { it > currentPage }) {
            println("[CineAgora] 🔍 ✅ Número maior que página atual encontrado")
            return true
        }
        
        val hasNext = nextPageLinks.isNotEmpty()
        println("[CineAgora] 🔍 Resultado final tem próxima página? $hasNext")
        return hasNext
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("[CineAgora] 🔍 search chamado com query: '$query'")
        
        if (query.isBlank()) {
            println("[CineAgora] 🔍 Query vazia, retornando lista vazia")
            return emptyList()
        }
        
        val searchUrl = mainUrl
        println("[CineAgora] 🔍 URL de busca: $searchUrl")
        
        try {
            println("[CineAgora] 🔍 Tentando busca POST...")
            val document = app.post(
                url = searchUrl,
                data = mapOf(
                    "do" to "search",
                    "subaction" to "search",
                    "story" to query
                ),
                referer = searchUrl,
                headers = mapOf(
                    "Content-Type" to "application/x-www-form-urlencoded",
                    "Origin" to mainUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
                )
            ).document
            
            val results = extractSearchResults(document)
            println("[CineAgora] 🔍 ✅ ${results.size} resultados encontrados via POST")
            return results
            
        } catch (e: Exception) {
            println("[CineAgora] 🔍 ❌ Erro na busca POST: ${e.message}")
            
            try {
                val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
                val fallbackUrl = "$mainUrl/?do=search&subaction=search&story=$encodedQuery"
                println("[CineAgora] 🔍 Tentando fallback GET: $fallbackUrl")
                
                val document = app.get(fallbackUrl).document
                val results = extractSearchResults(document)
                println("[CineAgora] 🔍 ✅ ${results.size} resultados encontrados via GET fallback")
                return results
            } catch (e2: Exception) {
                println("[CineAgora] 🔍 ❌ Erro no fallback GET: ${e2.message}")
                return emptyList()
            }
        }
    }

    private fun extractSearchResults(document: org.jsoup.nodes.Document): List<SearchResponse> {
        println("[CineAgora] 🔍 Extraindo resultados da busca...")
        
        val searchItems = document.select(".film-list .content .col-6.col-sm-4.col-md-3.col-lg-2 .item-relative > a.item")
        println("[CineAgora] 🔍 Seletores específicos encontrados: ${searchItems.size}")
        
        return if (searchItems.isNotEmpty()) {
            val results = searchItems.mapNotNull { it.toSearchResult() }
            println("[CineAgora] 🔍 ${results.size} resultados dos seletores específicos")
            results
        } else {
            val fallbackItems = document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
            println("[CineAgora] 🔍 Fallback seletores encontrados: ${fallbackItems.size}")
            val results = fallbackItems.mapNotNull { it.toSearchResult() }
            println("[CineAgora] 🔍 ${results.size} resultados do fallback")
            results
        }
    }

    private fun extractSectionItems(document: org.jsoup.nodes.Document, sectionId: String): List<SearchResponse> {
        println("[CineAgora] 📋 Extraindo itens da seção '$sectionId'...")
        
        val items = document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
        println("[CineAgora] 📋 Elementos encontrados: ${items.size}")
        
        val results = items.mapNotNull { it.toSearchResult() }
        println("[CineAgora] 📋 ${results.size} itens convertidos para SearchResponse")
        
        return results
    }

    private fun extractScoreAdvanced(element: Element): Pair<String?, String?> {
        println("[CineAgora] ⭐ Extraindo pontuação avançada...")
        
        val selectors = listOf(
            ".item-info-ust .rating" to "Seletor rating principal",
            ".rating" to "Seletor .rating",
            ".score" to "Seletor .score",
            ".item-info + div" to "Próximo ao item-info",
            ".item-footer span" to "No rodapé do item",
            "span:contains(★)" to "Span com estrela",
            "span:contains(/10)" to "Span com /10",
            "[class*='rating']" to "Classe contém 'rating'",
            "[class*='score']" to "Classe contém 'score'",
            ".item-info-ust div" to "Div dentro de item-info-ust",
            "small" to "Tag small",
            "b" to "Tag bold",
            "i" to "Tag italic"
        )

        for ((selector, description) in selectors) {
            val found = element.selectFirst(selector)?.text()?.trim()
            if (!found.isNullOrBlank() && isScoreLike(found)) {
                println("[CineAgora] ⭐ ✅ Pontuação encontrada com seletor '$description': '$found'")
                return found to selector
            }
        }

        element.parent()?.let { parent ->
            for ((selector, description) in selectors) {
                val found = parent.selectFirst(selector)?.text()?.trim()
                if (!found.isNullOrBlank() && isScoreLike(found)) {
                    println("[CineAgora] ⭐ ✅ Pontuação encontrada no parent com seletor '$description': '$found'")
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
                    println("[CineAgora] ⭐ ✅ Pontuação encontrada com regex: '$found'")
                    return found to "regex"
                }
            }
        }

        println("[CineAgora] ⭐ ❌ Nenhuma pontuação encontrada")
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

    private fun Element.toSearchResult(): SearchResponse? {
        println("[CineAgora] 🔄 Convertendo elemento para SearchResult...")
        
        val linkElement = this.selectFirst("a")
        val href = linkElement?.attr("href")?.takeIf { it.isNotBlank() }
        
        if (href == null) {
            println("[CineAgora] 🔄 ❌ Elemento sem href válido")
            return null
        }
        
        println("[CineAgora] 🔄 href encontrado: $href")
        
        val titleElement = selectFirst(".item-footer .title, .title, .poster-title, h3, h4")
        val title = titleElement?.text()?.trim()
        
        if (title == null) {
            println("[CineAgora] 🔄 ❌ Elemento sem título válido")
            return null
        }
        
        println("[CineAgora] 🔄 Título encontrado: $title")
        
        val year = selectFirst(".info span:first-child, .year, .date")?.text()?.toIntOrNull()
            ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        println("[CineAgora] 🔄 Ano extraído: $year")
        
        val cleanTitle = title
            .replace(Regex("\\(\\d{4}\\)"), "")
            .replace(Regex("\\d{4}$"), "")
            .trim()
        
        println("[CineAgora] 🔄 Título limpo: $cleanTitle")
        
        val imgElement = selectFirst("img.thumbnail, img.poster, img")
        val posterUrl = imgElement?.attr("src")?.let { fixUrl(it) }
        
        println("[CineAgora] 🔄 Poster URL: $posterUrl")
        
        val qualityBadge = select(".item-info, .quality, .badge").firstOrNull()?.selectFirst("div:first-child, span")?.text()?.trim()
        println("[CineAgora] 🔄 Quality badge: $qualityBadge")
        
        val languageBadge = select(".item-info, .language, .badge").firstOrNull()?.selectFirst("div:nth-child(2), .lang")?.text()?.trim()
        println("[CineAgora] 🔄 Language badge: $languageBadge")
        
        val scoreResult = extractScoreAdvanced(this)
        val scoreText = scoreResult.first
        val score = when {
            scoreText == null || scoreText == "N/A" -> null
            else -> scoreText.toFloatOrNull()?.let { Score.from10(it) }
        }
        
        println("[CineAgora] 🔄 Score: $score ($scoreText)")
        
        val lastEpisodeInfo = select(".item-info, .episode, .data").getOrNull(1)?.selectFirst("small, .last-ep")?.text()?.trim()
            ?: selectFirst(".data, .episode-info")?.text()?.trim()
        
        println("[CineAgora] 🔄 Last episode info: $lastEpisodeInfo")
        
        val isSerie = href.contains("/series-") || href.contains("/serie-") || href.contains("/tv-") || 
                      href.contains("/series-online") ||
                      lastEpisodeInfo?.contains(Regex("S\\d+.*E\\d+")) == true ||
                      title.contains(Regex("(?i)(temporada|episódio|season|episode)"))
        
        println("[CineAgora] 🔄 É série? $isSerie")
        
        val quality = when {
            qualityBadge?.contains("HD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("4K", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("FULLHD", ignoreCase = true) == true -> SearchQuality.HD
            qualityBadge?.contains("TS", ignoreCase = true) == true -> SearchQuality.Cam
            else -> null
        }
        
        println("[CineAgora] 🔄 Quality final: $quality")
        
        return if (isSerie) {
            println("[CineAgora] 🔄 ✅ Criando TvSeriesSearchResponse")
            newTvSeriesSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
                if (quality != null) {
                    this.quality = quality
                }
            }
        } else {
            println("[CineAgora] 🔄 ✅ Criando MovieSearchResponse")
            newMovieSearchResponse(cleanTitle, fixUrl(href)) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = score
                if (quality != null) {
                    this.quality = quality
                }
            }
        }
    }

    private fun extractBannerUrl(doc: org.jsoup.nodes.Document): String? {
        println("[CineAgora] 🖼️ Procurando banner...")
        
        val bannerSelectors = listOf(
            "meta[property='og:image']",
            "meta[name='twitter:image']",
            "picture img",
            "picture source[media='(max-width: 768px)']",
            "picture img[alt*='assistir'][title*='Assistir']",
            "picture img[loading='lazy']",
            ".cover-img",
            ".banner-img",
            "img.banner",
            ".hero img",
            ".featured-image img",
            ".post-thumbnail img",
            ".single-featured-image img",
            "[class*='banner'] img",
            "[class*='cover'] img",
            ".movie-banner",
            ".series-banner",
            ".post-content img",
            ".entry-content img",
            ".article-content img",
            "img[title*='Assistir']",
            "img[alt*='assistir']",
            "img[alt*='online']",
            "img[title*='online']"
        )
        
        for (selector in bannerSelectors) {
            val element = doc.selectFirst(selector)
            if (element != null) {
                val url = when {
                    selector.startsWith("meta[") -> element.attr("content")
                    selector.contains("source[") -> element.attr("srcset")
                    else -> element.attr("src")
                }
                
                if (url.isNotBlank()) {
                    val fixedUrl = fixUrl(url)
                    println("[CineAgora] 🖼️ ✅ Banner encontrado com seletor '$selector': $fixedUrl")
                    
                    if (selector.contains("source[") && url.contains(",")) {
                        val firstUrl = url.substringBefore(",").trim()
                        if (firstUrl.isNotBlank()) {
                            val fixedFirstUrl = fixUrl(firstUrl)
                            println("[CineAgora] 🖼️ ✅ Extraindo primeira URL do srcset: $fixedFirstUrl")
                            return fixedFirstUrl
                        }
                    }
                    
                    return fixedUrl
                }
            }
        }
        
        println("[CineAgora] 🖼️ ❌ Nenhum banner encontrado com seletores principais")
        
        val pictureElements = doc.select("picture")
        println("[CineAgora] 🖼️ Elementos picture encontrados: ${pictureElements.size}")
        
        for (picture in pictureElements) {
            val imgElement = picture.selectFirst("img")
            if (imgElement != null) {
                val src = imgElement.attr("src")
                if (src.isNotBlank()) {
                    val fixedUrl = fixUrl(src)
                    println("[CineAgora] 🖼️ ✅ Banner encontrado em <picture> <img>: $fixedUrl")
                    return fixedUrl
                }
            }
            
            val sourceElement = picture.selectFirst("source")
            if (sourceElement != null) {
                val srcset = sourceElement.attr("srcset")
                if (srcset.isNotBlank()) {
                    val firstUrl = srcset.split(",").firstOrNull()?.trim()?.substringBefore(" ")?.trim()
                    if (!firstUrl.isNullOrBlank()) {
                        val fixedUrl = fixUrl(firstUrl)
                        println("[CineAgora] 🖼️ ✅ Banner encontrado em <picture> <source>: $fixedUrl")
                        return fixedUrl
                    }
                }
            }
        }
        
        println("[CineAgora] 🖼️ Fallback: procurando imagens grandes...")
        val allImages = doc.select("img[src]")
        println("[CineAgora] 🖼️ Total de imagens: ${allImages.size}")
        
        val largeImages = allImages.filter { 
            val src = it.attr("src")
            val width = it.attr("width").toIntOrNull()
            val height = it.attr("height").toIntOrNull()
            
            src.contains("/uploads/posts/") ||
            src.contains(".webp") ||
            (width != null && height != null && width >= 600 && height >= 300) ||
            src.contains("banner") ||
            src.contains("cover") ||
            src.contains("featured")
        }
        
        println("[CineAgora] 🖼️ Imagens grandes encontradas: ${largeImages.size}")
        
        if (largeImages.isNotEmpty()) {
            val sortedImages = largeImages.sortedByDescending { 
                val width = it.attr("width").toIntOrNull() ?: 0
                val height = it.attr("height").toIntOrNull() ?: 0
                width * height
            }

            for (img in sortedImages.take(3)) {
                val src = img.attr("src")
                if (src.isNotBlank()) {
                    val fixedUrl = fixUrl(src)
                    println("[CineAgora] 🖼️ ✅ Banner encontrado (fallback): $fixedUrl")
                    return fixedUrl
                }
            }
        }
        
        println("[CineAgora] 🖼️ ❌ Nenhum banner encontrado")
        return null
    }

    private fun extractYear(doc: org.jsoup.nodes.Document): Int? {
        println("[CineAgora] 📅 Extraindo ano...")
        
        val yearFromSelector = doc.selectFirst(".year, .date, time")?.text()?.toIntOrNull()
        if (yearFromSelector != null) {
            println("[CineAgora] 📅 ✅ Ano encontrado em seletor: $yearFromSelector")
            return yearFromSelector
        }
        
        val h1Text = doc.selectFirst("h1")?.text() ?: ""
        val yearFromRegex = Regex("(\\d{4})").find(h1Text)?.groupValues?.get(1)?.toIntOrNull()
        if (yearFromRegex != null) {
            println("[CineAgora] 📅 ✅ Ano encontrado em regex no h1: $yearFromRegex")
            return yearFromRegex
        }
        
        println("[CineAgora] 📅 ❌ Nenhum ano encontrado")
        return null
    }

    private fun extractGenres(doc: org.jsoup.nodes.Document): List<String>? {
        println("[CineAgora] 🏷️ Extraindo gêneros...")
        
        val genres = doc.select(".genres a, .genre a, .category a, a[href*='genero'], a[href*='categoria']")
            .mapNotNull { it.text().trim() }
            .filter { it.isNotBlank() }
        
        if (genres.isNotEmpty()) {
            println("[CineAgora] 🏷️ ✅ Gêneros encontrados: $genres")
            return genres
        }
        
        println("[CineAgora] 🏷️ ❌ Nenhum gênero encontrado")
        return null
    }

    // =============================================
    // FUNÇÕES TMDB
    // =============================================
    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("[CineAgora] 🎬 TMDB - Buscando: '$query', Ano: $year, isTv: $isTv")
        
        return try {
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = if (isTv) {
                "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            } else {
                "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR$yearParam"
            }

            println("[CineAgora] 🎬 TMDB - URL da busca: $searchUrl")

            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val response = app.get(searchUrl, headers = headers, timeout = 10_000)
            println("[CineAgora] 🎬 TMDB - Status da resposta: ${response.code}")
            
            if (response.code != 200) {
                println("[CineAgora] 🎬 TMDB - ❌ Erro na resposta: ${response.code}")
                return null
            }

            val searchResult = response.parsedSafe<TMDBSearchResponse>()
            if (searchResult == null) {
                println("[CineAgora] 🎬 TMDB - ❌ Erro ao fazer parse da resposta")
                return null
            }

            println("[CineAgora] 🎬 TMDB - Resultados encontrados: ${searchResult.results.size}")
            
            val result = searchResult.results.firstOrNull()
            if (result == null) {
                println("[CineAgora] 🎬 TMDB - ❌ Nenhum resultado encontrado")
                return null
            }
            
            println("[CineAgora] 🎬 TMDB - Primeiro resultado: ID=${result.id}, Nome/Título=${result.name ?: result.title}")

            val details = getTMDBDetails(result.id, isTv)
            if (details == null) {
                println("[CineAgora] 🎬 TMDB - ❌ Não conseguiu obter detalhes")
                return null
            }

            // Extrair atores como List<Pair<Actor, String?>> para addActors
            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    val actorObj = Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                    
                    // Retornar Pair<Actor, String?> onde String é o papel/personagem
                    Pair(actorObj, actor.character)
                } else null
            }

            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)
            if (youtubeTrailer != null) {
                println("[CineAgora] 🎬 TMDB - Trailer encontrado: $youtubeTrailer")
            }

            val seasonsEpisodes = if (isTv) {
                println("[CineAgora] 🎬 TMDB - Buscando temporadas e episódios...")
                getTMDBAllSeasons(result.id)
            } else {
                emptyMap()
            }
            
            if (isTv) {
                println("[CineAgora] 🎬 TMDB - Temporadas encontradas: ${seasonsEpisodes.size}")
                seasonsEpisodes.forEach { (season, episodes) ->
                    println("[CineAgora] 🎬 TMDB - Temporada $season: ${episodes.size} episódios")
                }
            }

            val tmdbInfo = TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) {
                    result.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    result.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                actors = allActors,
                youtubeTrailer = youtubeTrailer,
                duration = if (!isTv) details.runtime else null,
                seasonsEpisodes = seasonsEpisodes,
                rating = details.vote_average?.takeIf { it > 0 }
            )
            
            println("[CineAgora] 🎬 TMDB - ✅ Informações obtidas com sucesso!")
            println("[CineAgora] 🎬 TMDB - Título: ${tmdbInfo.title}")
            println("[CineAgora] 🎬 TMDB - Ano: ${tmdbInfo.year}")
            println("[CineAgora] 🎬 TMDB - Rating: ${tmdbInfo.rating}")
            
            tmdbInfo
        } catch (e: Exception) {
            println("[CineAgora] 🎬 TMDB - ❌ Erro na busca: ${e.message}")
            println("[CineAgora] 🎬 TMDB - Stack trace: ${e.stackTraceToString()}")
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        println("[CineAgora] 🎬 TMDB - Buscando todas as temporadas para série ID: $seriesId")
        
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val seriesDetailsUrl = "https://api.themoviedb.org/3/tv/$seriesId?api_key=$TMDB_API_KEY&language=pt-BR"
            println("[CineAgora] 🎬 TMDB - URL detalhes da série: $seriesDetailsUrl")
            
            val seriesResponse = app.get(seriesDetailsUrl, headers = headers, timeout = 10_000)
            println("[CineAgora] 🎬 TMDB - Status resposta detalhes: ${seriesResponse.code}")

            if (seriesResponse.code != 200) {
                println("[CineAgora] 🎬 TMDB - ❌ Erro ao obter detalhes da série: ${seriesResponse.code}")
                return emptyMap()
            }

            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>()
            if (seriesDetails == null) {
                println("[CineAgora] 🎬 TMDB - ❌ Não conseguiu fazer parse dos detalhes da série")
                return emptyMap()
            }

            println("[CineAgora] 🎬 TMDB - Total de temporadas encontradas: ${seriesDetails.seasons.size}")

            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) {
                    val seasonNumber = season.season_number
                    println("[CineAgora] 🎬 TMDB - Processando temporada $seasonNumber...")

                    val seasonUrl = "https://api.themoviedb.org/3/tv/$seriesId/season/$seasonNumber?api_key=$TMDB_API_KEY&language=pt-BR"
                    val seasonResponse = app.get(seasonUrl, headers = headers, timeout = 10_000)

                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            seasonsEpisodes[seasonNumber] = episodes
                            println("[CineAgora] 🎬 TMDB - ✅ Temporada $seasonNumber: ${episodes.size} episódios")
                        }
                    } else {
                        println("[CineAgora] 🎬 TMDB - ❌ Erro ao obter temporada $seasonNumber: ${seasonResponse.code}")
                    }
                }
            }

            println("[CineAgora] 🎬 TMDB - Total de temporadas processadas: ${seasonsEpisodes.size}")
            seasonsEpisodes
        } catch (e: Exception) {
            println("[CineAgora] 🎬 TMDB - ❌ Erro ao obter temporadas: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        println("[CineAgora] 🎬 TMDB - Obtendo detalhes para ID: $id, isTv: $isTv")
        
        return try {
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )

            val url = if (isTv) {
                "https://api.themoviedb.org/3/tv/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            } else {
                "https://api.themoviedb.org/3/movie/$id?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            }

            println("[CineAgora] 🎬 TMDB - URL detalhes: $url")

            val response = app.get(url, headers = headers, timeout = 10_000)
            println("[CineAgora] 🎬 TMDB - Status resposta detalhes: ${response.code}")

            if (response.code != 200) {
                println("[CineAgora] 🎬 TMDB - ❌ Erro na resposta: ${response.code}")
                return null
            }
            
            val details = response.parsedSafe<TMDBDetailsResponse>()
            if (details == null) {
                println("[CineAgora] 🎬 TMDB - ❌ Não conseguiu fazer parse dos detalhes")
                return null
            }
            
            println("[CineAgora] 🎬 TMDB - ✅ Detalhes obtidos com sucesso")
            println("[CineAgora] 🎬 TMDB - Sinopse: ${details.overview?.take(50)}...")
            println("[CineAgora] 🎬 TMDB - Gêneros: ${details.genres?.size}")
            println("[CineAgora] 🎬 TMDB - Atores: ${details.credits?.cast?.size}")
            
            details
        } catch (e: Exception) {
            println("[CineAgora] 🎬 TMDB - ❌ Erro ao obter detalhes: ${e.message}")
            null
        }
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) {
            println("[CineAgora] 🎬 TMDB - Nenhum vídeo encontrado")
            return null
        }
        
        println("[CineAgora] 🎬 TMDB - Total de vídeos: ${videos.size}")
        
        val trailerInfo = videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true -> {
                    println("[CineAgora] 🎬 TMDB - ✅ Trailer oficial do YouTube encontrado")
                    Triple(video.key, 10, "YouTube Trailer Oficial")
                }
                video.site == "YouTube" && video.type == "Trailer" -> {
                    println("[CineAgora] 🎬 TMDB - ✅ Trailer do YouTube encontrado")
                    Triple(video.key, 9, "YouTube Trailer")
                }
                video.site == "YouTube" && video.type == "Teaser" && video.official == true -> {
                    println("[CineAgora] 🎬 TMDB - ✅ Teaser oficial do YouTube encontrado")
                    Triple(video.key, 8, "YouTube Teaser Oficial")
                }
                video.site == "YouTube" && video.type == "Teaser" -> {
                    println("[CineAgora] 🎬 TMDB - ✅ Teaser do YouTube encontrado")
                    Triple(video.key, 7, "YouTube Teaser")
                }
                else -> null
            }
        }
        ?.sortedByDescending { it.second }
        ?.firstOrNull()
        
        return trailerInfo?.let { (key, _, _) -> 
            val trailerUrl = "https://www.youtube.com/watch?v=$key"
            println("[CineAgora] 🎬 TMDB - URL do trailer: $trailerUrl")
            trailerUrl
        }
    }

    // =============================================
    // FUNÇÕES DE EXTRAÇÃO DE EPISÓDIOS
    // =============================================

    private suspend fun extractSeriesSlugFromPage(doc: org.jsoup.nodes.Document, baseUrl: String): String? {
        println("[CineAgora] 🔗 Extraindo seriesSlug da página: $baseUrl")
        
        // ESTRATÉGIA PRINCIPAL: Buscar elementos com data-link contendo /tv/
        val dataLinkElements = doc.select("[data-link*='/tv/']")
        println("[CineAgora] 🔗 Elementos com data-link contendo /tv/: ${dataLinkElements.size}")
        
        for ((index, element) in dataLinkElements.withIndex()) {
            val dataLink = element.attr("data-link")
            println("[CineAgora] 🔗 Elemento $index data-link: $dataLink")
            
            // Extrair slug do padrão /tv/{slug}
            val tvPattern = Regex("""/tv/([^/?]+)""")
            val tvMatch = tvPattern.find(dataLink)
            if (tvMatch != null) {
                val slug = tvMatch.groupValues[1]
                println("[CineAgora] 🔗 ✅ Slug encontrado em data-link: $slug")
                return slug
            }
        }
        
        // ESTRATÉGIA 2: Buscar botões ou spans com links que contenham /tv/
        val tvButtons = doc.select("""
            button[data-link*='/tv/'], 
            span[data-link*='/tv/'], 
            a[data-link*='/tv/'],
            div[data-link*='/tv/']
        """.trimIndent())
        println("[CineAgora] 🔗 Botões/spans com data-link /tv/: ${tvButtons.size}")
        
        for ((index, element) in tvButtons.withIndex()) {
            val dataLink = element.attr("data-link")
            println("[CineAgora] 🔗 Botão $index data-link: $dataLink")
            
            val tvPattern = Regex("""/tv/([^/?]+)""")
            val tvMatch = tvPattern.find(dataLink)
            if (tvMatch != null) {
                val slug = tvMatch.groupValues[1]
                println("[CineAgora] 🔗 ✅ Slug encontrado em botão data-link: $slug")
                return slug
            }
        }
        
        // ESTRATÉGIA 3: Buscar no texto do HTML
        println("[CineAgora] 🔗 Buscando 'watch.brplayer.cc/tv/' no HTML...")
        val html = doc.html()
        val brplayerRegex = Regex("""watch\.brplayer\.cc/tv/([^"'\s?&]+)""")
        val matches = brplayerRegex.findAll(html).toList()
        
        println("[CineAgora] 🔗 Encontrados ${matches.size} matches no HTML")
        matches.take(3).forEachIndexed { index, match ->
            val slug = match.groupValues[1]
            println("[CineAgora] 🔗 Match $index no HTML: $slug")
        }
        
        val firstMatch = matches.firstOrNull()
        if (firstMatch != null) {
            val slug = firstMatch.groupValues[1]
            println("[CineAgora] 🔗 ✅ Slug extraído do HTML: $slug")
            return slug
        }
        
        println("[CineAgora] 🔗 ❌ Não foi possível encontrar o seriesSlug")
        return null
    }

    private suspend fun getSeriesFromVideoSlug(videoSlug: String): String {
        try {
            val apiUrl = "https://watch.brplayer.cc/get_series_from_video.php?videoSlug=$videoSlug"
            println("[CineAgora] 🔗 Chamando API para converter videoSlug: $apiUrl")
            
            val response = app.get(apiUrl, timeout = 10)
            println("[CineAgora] 🔗 Status da API: ${response.code}")
            
            if (response.isSuccessful) {
                val seriesSlug = response.text.trim()
                println("[CineAgora] 🔗 Resposta da API: '$seriesSlug'")
                
                if (seriesSlug.isNotBlank() && seriesSlug != "null") {
                    println("[CineAgora] 🔗 ✅ Series slug obtido da API: $seriesSlug")
                    return seriesSlug
                } else {
                    println("[CineAgora] 🔗 ❌ Resposta da API vazia ou 'null'")
                }
            } else {
                println("[CineAgora] 🔗 ❌ API retornou erro: ${response.code}")
            }
        } catch (e: Exception) {
            println("[CineAgora] 🔗 ❌ Erro ao obter series slug: ${e.message}")
        }
        
        return ""
    }

    // FUNÇÃO PARA BUSCAR EPISÓDIOS DA API

    
    private suspend fun fetchEpisodesFromApi(seriesSlug: String): List<Episode> {
    val episodes = mutableListOf<Episode>()
    
    val apiUrl = "https://watch.brplayer.cc/fetch_series_data.php?seriesSlug=$seriesSlug"
    println("[CineAgora] 📺 Chamando API de episódios: $apiUrl")
    
    try {
        val headers = mapOf(
            "accept" to "application/json, text/javascript, */*; q=0.01",
            "accept-language" to "pt-BR",
            "referer" to "https://watch.brplayer.cc/tv/$seriesSlug",
            "sec-fetch-dest" to "empty",
            "sec-fetch-mode" to "cors",
            "sec-fetch-site" to "same-origin",
            "user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36",
            "x-requested-with" to "XMLHttpRequest"
        )
        
        val response = app.get(apiUrl, headers = headers, timeout = 30)
        println("[CineAgora] 📺 Status da API: ${response.code}")
        
        if (!response.isSuccessful) {
            println("[CineAgora] 📺 ❌ API retornou erro: ${response.code}")
            return episodes
        }
        
        val jsonText = response.text
        println("[CineAgora] 📺 Resposta da API recebida (${jsonText.length} caracteres)")
        
        if (jsonText.isEmpty() || jsonText == "null") {
            println("[CineAgora] 📺 ❌ API retornou resposta vazia")
            return episodes
        }
        
        println("[CineAgora] 📺 Primeiros 500 caracteres da resposta: ${jsonText.take(500)}...")
        
        val responseMap: Map<String, Any>? = AppUtils.parseJson(jsonText)
        
        if (responseMap == null) {
            println("[CineAgora] 📺 ❌ Erro ao fazer parse do JSON da API")
            return episodes
        }
        
        println("[CineAgora] 📺 Chaves do JSON: ${responseMap.keys}")
        
        // Estrutura correta: {"seasons":{"1":[...],"2":[...]}}
        val seasonsMap = responseMap["seasons"] as? Map<String, List<Map<String, Any>>>
        
        if (seasonsMap == null) {
            println("[CineAgora] 📺 ❌ Não encontrou 'seasons' no JSON")
            return episodes
        }
        
        println("[CineAgora] 📺 ✅ API carregada com sucesso. ${seasonsMap.size} temporada(s) encontrada(s)")
        
        // Processar cada temporada
        seasonsMap.forEach { (seasonStr, episodeList) ->
            val seasonNum = seasonStr.toIntOrNull() ?: 1
            println("[CineAgora] 📺 Processando temporada $seasonNum com ${episodeList.size} episódios")
            
            episodeList.forEach { epMap ->
                try {
                    val videoSlug = epMap["video_slug"] as? String
                    if (videoSlug == null) {
                        println("[CineAgora] 📺 ❌ Episódio sem video_slug: $epMap")
                        return@forEach
                    }
                    
                    val epNumberStr = epMap["episode_number"] as? String
                    val epTitleRaw = epMap["episode_title"] as? String
                    
                    // Determinar número do episódio
                    val epNumber = epNumberStr?.toIntOrNull() ?: 1
                    
                    // Limpar título do episódio
                    val episodeTitle = cleanEpisodeTitle(epTitleRaw, seasonNum, epNumber)
                    
                    // URL final do episódio
                    val episodeUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                    
                    episodes.add(
                        newEpisode(episodeUrl) {
                            name = episodeTitle
                            season = seasonNum
                            episode = epNumber
                            description = "Temporada $seasonNum • Episódio $epNumber"
                        }
                    )
                    
                    println("[CineAgora] 📺 ✅ Adicionado: Temporada $seasonNum, Episódio $epNumber - $episodeTitle")
                    
                } catch (e: Exception) {
                    println("[CineAgora] 📺 ❌ Erro ao extrair episódio da temporada $seasonStr: ${e.message}")
                }
            }
        }
        
        println("[CineAgora] 📺 ✅ Total de ${episodes.size} episódios criados a partir da API!")
        
    } catch (e: Exception) {
        println("[CineAgora] 📺 ❌ Erro na chamada à API: ${e.message}")
        println("[CineAgora] 📺 Stack trace: ${e.stackTraceToString()}")
    }
    
    return episodes
}

private fun cleanEpisodeTitle(rawTitle: String?, seasonNum: Int, episodeNum: Int): String {
    if (rawTitle.isNullOrBlank()) {
        return "Episódio $episodeNum"
    }
    
    val cleanTitle = rawTitle.trim()
    println("[CineAgora] 📺 Título original do episódio: '$cleanTitle'")
    
    // Tentar extrair um título mais limpo
    val patterns = listOf(
        // Remover padrões como "Stranger.Things.2016.S01E01.mkv"
        Regex("""S\d+E\d+\s*(.+?)(?:\.mkv|\.mp4|\.avi)?$""", RegexOption.IGNORE_CASE),
        // Remover informações técnicas
        Regex("""\.\s*(.+?)\s*(?:720p|1080p|HD|German|EAC3|NF|WEB|H264|MEGA)""", RegexOption.IGNORE_CASE),
        // Tentar pegar parte após o último ponto
        Regex("""[^.]+\.[^.]+\.[^.]+\.[^.]+\.[^.]+\s*(.+)$""")
    )
    
    for (pattern in patterns) {
        val match = pattern.find(cleanTitle)
        if (match != null && match.groupValues.size > 1) {
            val extracted = match.groupValues[1].trim()
            if (extracted.isNotBlank() && extracted.length > 2) {
                println("[CineAgora] 📺 Título extraído com padrão: '$extracted'")
                return extracted
            }
        }
    }
    
    // Se não conseguir extrair, usar título padrão
    return "Episódio $episodeNum"
}

    // FUNÇÃO PRINCIPAL PARA EXTRAIR EPISÓDIOS
    private suspend fun extractEpisodes(doc: org.jsoup.nodes.Document, baseUrl: String): List<Episode> {
        println("[CineAgora] 📺 ===========================================")
        println("[CineAgora] 📺 INICIANDO EXTRAÇÃO DE EPISÓDIOS para: $baseUrl")
        println("[CineAgora] 📺 ===========================================")
        
        // 1. Tentar extrair o seriesSlug da página
        val seriesSlug = extractSeriesSlugFromPage(doc, baseUrl)
        
        if (seriesSlug != null) {
            println("[CineAgora] 📺 ✅ Series Slug encontrado: $seriesSlug")
            
            // Buscar episódios da API
            val apiEpisodes = fetchEpisodesFromApi(seriesSlug)
            
            if (apiEpisodes.isNotEmpty()) {
                println("[CineAgora] 📺 ✅ ${apiEpisodes.size} episódios obtidos da API")
                
                // Ordenar por temporada e episódio
                val sortedEpisodes = apiEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))
                
                // Agrupar por temporada para debug
                val episodesBySeason = sortedEpisodes.groupBy { it.season ?: 1 }
                episodesBySeason.forEach { (season, eps) ->
                    println("[CineAgora] 📺 Temporada $season: ${eps.size} episódios")
                }
                
                return sortedEpisodes
            }
            
            println("[CineAgora] 📺 ❌ API não retornou episódios")
        } else {
            println("[CineAgora] 📺 ❌ Não encontrou seriesSlug")
        }
        
        // Fallback mínimo - criar um episódio básico
        val episodes = mutableListOf<Episode>()
        episodes.add(
            newEpisode(baseUrl) {
                name = "Episódio 1"
                season = 1
                episode = 1
            }
        )
        
        println("[CineAgora] 📺 ===========================================")
        println("[CineAgora] 📺 FINALIZADA EXTRAÇÃO DE EPISÓDIOS")
        println("[CineAgora] 📺 Total de episódios encontrados: ${episodes.size}")
        println("[CineAgora] 📺 ===========================================")
        
        return episodes
    }

    // =============================================
    // FUNÇÃO PARA ENRIQUECER EPISÓDIOS COM TMDB
    // =============================================
    private suspend fun enrichEpisodesWithTMDBInfo(
        episodes: List<Episode>,
        tmdbInfo: TMDBInfo?
    ): List<Episode> {
        println("[CineAgora] 🌟 Iniciando enriquecimento de episódios com TMDB")
        println("[CineAgora] 🌟 Episódios para enriquecer: ${episodes.size}")
        println("[CineAgora] 🌟 TMDB Info disponível? ${tmdbInfo != null}")
        
        if (tmdbInfo == null) {
            println("[CineAgora] 🌟 ❌ Sem informações do TMDB, retornando episódios originais")
            return episodes
        }
        
        if (tmdbInfo.seasonsEpisodes.isEmpty()) {
            println("[CineAgora] 🌟 ❌ TMDB não tem informações de temporadas/episódios")
            return episodes
        }
        
        println("[CineAgora] 🌟 TMDB tem ${tmdbInfo.seasonsEpisodes.size} temporadas")
        tmdbInfo.seasonsEpisodes.forEach { (season, eps) ->
            println("[CineAgora] 🌟 TMDB Temporada $season: ${eps.size} episódios")
        }
        
        val enrichedEpisodes = episodes.map { originalEpisode ->
            try {
                val season = originalEpisode.season ?: 1
                val episodeNum = originalEpisode.episode ?: 1
                
                println("[CineAgora] 🌟 Processando: Temporada $season, Episódio $episodeNum")
                
                // Buscar episódio correspondente no TMDB
                val tmdbEpisode = findTMDBEpisode(tmdbInfo, season, episodeNum)
                
                if (tmdbEpisode != null) {
                    println("[CineAgora] 🌟 ✅ Encontrou episódio no TMDB: ${tmdbEpisode.name}")
                    
                    // Construir nova descrição com sinopse do TMDB
                    val descriptionWithDuration = buildDescriptionWithDuration(
                        tmdbEpisode.overview,
                        tmdbEpisode.runtime
                    )
                    
                    // Criar episódio enriquecido
                    val enrichedEpisode = newEpisode(originalEpisode.data) {
                        this.name = tmdbEpisode.name
                        this.season = season
                        this.episode = episodeNum
                        this.posterUrl = tmdbEpisode.still_path?.let { "$tmdbImageUrl/w300$it" }
                        this.description = descriptionWithDuration
                        
                        // Adicionar data de lançamento se disponível
                        tmdbEpisode.air_date?.let { airDate ->
                            try {
                                val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                                val date = dateFormatter.parse(airDate)
                                this.date = date.time
                                println("[CineAgora] 🌟 ✅ Adicionada data: $airDate")
                            } catch (e: Exception) {
                                println("[CineAgora] 🌟 ❌ Erro ao parsear data: ${e.message}")
                            }
                        }
                    }
                    
                    println("[CineAgora] 🌟 ✅ Episódio enriquecido com sucesso")
                    enrichedEpisode
                } else {
                    println("[CineAgora] 🌟 ⚠️ Não encontrou episódio $episodeNum da temporada $season no TMDB")
                    originalEpisode
                }
            } catch (e: Exception) {
                println("[CineAgora] 🌟 ❌ Erro ao enriquecer episódio: ${e.message}")
                originalEpisode
            }
        }
        
        println("[CineAgora] 🌟 ✅ Enriquecimento concluído: ${enrichedEpisodes.size} episódios processados")
        return enrichedEpisodes
    }
    
    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        if (tmdbInfo == null) {
            println("[CineAgora] 🌟 ❌ TMDB Info é null")
            return null
        }

        println("[CineAgora] 🌟 Buscando episódio $episode da temporada $season no TMDB")
        
        val episodes = tmdbInfo.seasonsEpisodes[season]
        if (episodes == null) {
            println("[CineAgora] 🌟 ❌ Não encontrou temporada $season no TMDB")
            return null
        }

        val foundEpisode = episodes.find { it.episode_number == episode }
        if (foundEpisode != null) {
            println("[CineAgora] 🌟 ✅ Encontrou episódio no TMDB: ${foundEpisode.name}")
        } else {
            println("[CineAgora] 🌟 ❌ Não encontrou episódio $episode na temporada $season")
        }
        
        return foundEpisode
    }

    private fun buildDescriptionWithDuration(overview: String?, runtime: Int?): String? {
        val result = when {
            overview != null && runtime != null && runtime > 0 -> {
                "$overview\n\nDuração: $runtime min"
            }
            overview != null -> {
                overview
            }
            runtime != null && runtime > 0 -> {
                "Duração: $runtime min"
            }
            else -> null
        }
        
        println("[CineAgora] 🌟 Descrição construída: ${result?.take(50)}...")
        return result
    }

    // =============================================
    // FUNÇÃO LOAD PRINCIPAL
    // =============================================
override suspend fun load(url: String): LoadResponse? {
    println("[CineAgora] 🚀 ===========================================")
    println("[CineAgora] 🚀 INICIANDO LOAD PARA URL: $url")
    println("[CineAgora] 🚀 ===========================================")
    
    val doc = try {
        println("[CineAgora] 🚀 Fazendo requisição para a URL...")
        val response = app.get(url)
        println("[CineAgora] 🚀 Status da resposta: ${response.code}")
        println("[CineAgora] 🚀 Tamanho da resposta: ${response.text.length} caracteres")
        response.document
    } catch (e: Exception) {
        println("[CineAgora] 🚀 ❌ Erro ao carregar URL: ${e.message}")
        return null
    }
    
    println("[CineAgora] 🚀 Documento carregado. Título: ${doc.title()}")
    
    // 1. Extrair informações básicas
    println("[CineAgora] 🚀 Extraindo banner...")
    val bannerUrl = extractBannerUrl(doc)
    println("[CineAgora] 🚀 Banner URL: $bannerUrl")
    
    println("[CineAgora] 🚀 Extraindo poster...")
    val posterUrl = doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
        ?: doc.selectFirst("#info--box .cover-img")?.attr("src")?.let { fixUrl(it) }
        ?: bannerUrl
    
    println("[CineAgora] 🚀 Poster URL: $posterUrl")
    
    val title = doc.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
    val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
    
    println("[CineAgora] 🚀 Título original: $title")
    println("[CineAgora] 🚀 Título limpo: $cleanTitle")
    
    // 2. Extrair episódios (apenas para séries)
    println("[CineAgora] 🚀 ===========================================")
    println("[CineAgora] 🚀 INICIANDO EXTRAÇÃO DE EPISÓDIOS")
    println("[CineAgora] 🚀 ===========================================")
    
    val episodes = extractEpisodes(doc, url)
    
    println("[CineAgora] 🚀 ===========================================")
    println("[CineAgora] 🚀 EXTRAÇÃO DE EPISÓDIOS CONCLUÍDA")
    println("[CineAgora] 🚀 Total de episódios extraídos: ${episodes.size}")
    println("[CineAgora] 🚀 ===========================================")
    
    // 3. DETERMINAR SE É SÉRIE OU FILME
    val isSerie = url.contains("/series-") || url.contains("/serie-") || url.contains("/tv-") || 
                 url.contains("/series-online") ||
                 doc.select(".player-controls, #episodeDropdown, .seasons").isNotEmpty() ||
                 episodes.size > 1
    
    println("[CineAgora] 🚀 É série? $isSerie (${episodes.size} episódios)")
    
    // 4. INFORMAÇÕES ADICIONAIS DO SITE
    val yearFromSite = extractYear(doc)
    val plotFromSite = doc.selectFirst(".info-description, .description, .sinopse, .plot")?.text()?.trim()
    val genresFromSite = extractGenres(doc)
    
    println("[CineAgora] 🚀 Ano do site: $yearFromSite")
    println("[CineAgora] 🚀 Plot do site: ${plotFromSite?.take(50)}...")
    println("[CineAgora] 🚀 Gêneros do site: $genresFromSite")
    
    // 5. Buscar informações do TMDB (PARA SÉRIES E FILMES!)
    println("[CineAgora] 🚀 Buscando informações no TMDB...")
    val tmdbInfo = searchOnTMDB(cleanTitle, yearFromSite, isSerie)
    
    if (tmdbInfo == null) {
        println("[CineAgora] 🚀 ❌ Não encontrou informações no TMDB")
    } else {
        println("[CineAgora] 🚀 ✅ Informações do TMDB encontradas!")
        println("[CineAgora] 🚀 TMDB - Título: ${tmdbInfo.title}")
        println("[CineAgora] 🚀 TMDB - Ano: ${tmdbInfo.year}")
        println("[CineAgora] 🚀 TMDB - Rating: ${tmdbInfo.rating}")
    }
    
    // 6. Enriquecer episódios com metadados do TMDB (apenas se for série)
    val enrichedEpisodes = if (isSerie && tmdbInfo != null) {
        println("[CineAgora] 🚀 Enriquecendo episódios com TMDB...")
        enrichEpisodesWithTMDBInfo(episodes, tmdbInfo)
    } else {
        println("[CineAgora] 🚀 Mantendo episódios originais")
        episodes
    }
    
    println("[CineAgora] 🚀 Episódios enriquecidos: ${enrichedEpisodes.size}")
    
    // 7. Recomendações do site
    val recommendations = extractRecommendationsFromSite(doc)
    println("[CineAgora] 🚀 Recomendações encontradas: ${recommendations.size}")
    
    // 8. Encontrar URL do player
    val playerUrl = if (!isSerie) {
        println("[CineAgora] 🚀 É filme, buscando URL do player...")
        findPlayerUrl(doc) ?: url
    } else {
        println("[CineAgora] 🚀 É série, usando URL original")
        url
    }
    
    println("[CineAgora] 🚀 URL final do player: $playerUrl")
    
    // 9. Criar resposta com base nas informações
    println("[CineAgora] 🚀 ===========================================")
    println("[CineAgora] 🚀 CRIANDO RESPOSTA FINAL")
    println("[CineAgora] 🚀 ===========================================")
    
    return if (isSerie) {
        println("[CineAgora] 🚀 Criando resposta para série...")
        createSeriesLoadResponse(tmdbInfo, url, enrichedEpisodes, recommendations, plotFromSite, genresFromSite, bannerUrl, posterUrl, yearFromSite)
    } else {
        println("[CineAgora] 🚀 Criando resposta para filme...")
        createMovieLoadResponse(tmdbInfo, playerUrl, recommendations, plotFromSite, genresFromSite, bannerUrl, posterUrl, yearFromSite)
    }
}
        
        
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        println("[CineAgora] 🎬 Procurando URL do player...")
        
        // Procura por iframes do brplayer
        val iframe = document.selectFirst("iframe[src*='watch.brplayer.cc']")
        if (iframe != null) {
            val src = iframe.attr("src")
            println("[CineAgora] 🎬 Iframe encontrado: $src")
            
            // Extrair videoSlug do iframe
            val watchPattern = Regex("""/watch/([^/?]+)""")
            val watchMatch = watchPattern.find(src)
            if (watchMatch != null) {
                val videoSlug = watchMatch.groupValues[1]
                val playerUrl = "https://watch.brplayer.cc/watch/$videoSlug"
                println("[CineAgora] 🎬 ✅ URL do player encontrada: $playerUrl")
                return playerUrl
            }
        }
        
        println("[CineAgora] 🎬 ❌ Nenhum player encontrado")
        return null
    }

    private fun extractRecommendationsFromSite(document: org.jsoup.nodes.Document): List<SearchResponse> {
        println("[CineAgora] 🤝 Extraindo recomendações...")
        
        val recommendations = document.select(".item, .item-relative .item, .poster, .movie-item, .serie-item")
            .mapNotNull { element ->
                element.toSearchResult()
            }
            .take(10)
        
        println("[CineAgora] 🤝 ${recommendations.size} recomendações encontradas")
        return recommendations
    }

    // =============================================
    // FUNÇÕES PARA CRIAR LOAD RESPONSE COM TMDB
    // =============================================
    private suspend fun createSeriesLoadResponse(
        tmdbInfo: TMDBInfo?,
        url: String,
        episodes: List<Episode>,
        siteRecommendations: List<SearchResponse>,
        plotFromSite: String?,
        genresFromSite: List<String>?,
        bannerUrlFromSite: String?,
        posterUrlFromSite: String?,
        yearFromSite: Int?
    ): LoadResponse {
        println("[CineAgora] 📺 Criando SeriesLoadResponse...")
        
        // Informações do TMDB ou do site
        val title = tmdbInfo?.title ?: app.get(url).document.selectFirst("h1.title, h1, .title, h2")?.text()?.trim() ?: "Título não encontrado"
        val year = tmdbInfo?.year ?: yearFromSite
        val plot = tmdbInfo?.overview ?: plotFromSite
        val posterUrl = tmdbInfo?.posterUrl ?: posterUrlFromSite
        val backdropUrl = tmdbInfo?.backdropUrl ?: bannerUrlFromSite
        val genres = tmdbInfo?.genres ?: genresFromSite
        val rating = tmdbInfo?.rating?.let { Score.from10(it) }
        
        println("[CineAgora] 📺 Dados finais:")
        println("[CineAgora] 📺 - Título: $title")
        println("[CineAgora] 📺 - Ano: $year")
        println("[CineAgora] 📺 - Episódios: ${episodes.size}")
        println("[CineAgora] 📺 - Rating: $rating")
        println("[CineAgora] 📺 - Recomendações: ${siteRecommendations.size}")
        
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.backgroundPosterUrl = backdropUrl
            this.year = year
            this.plot = plot
            this.tags = genres
            this.score = rating
            this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
            
            // Adicionar atores do TMDB com suas vozes
            tmdbInfo?.actors?.let { actors ->
                println("[CineAgora] 📺 Adicionando ${actors.size} atores...")
                addActors(actors)
            }
            
            // Adicionar trailer do TMDB se disponível
            tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
                println("[CineAgora] 📺 Adicionando trailer: $trailerUrl")
                addTrailer(trailerUrl)
            }
        }
    }

    private suspend fun createMovieLoadResponse(
    tmdbInfo: TMDBInfo?,
    playerUrl: String,
    siteRecommendations: List<SearchResponse>,
    plotFromSite: String?,
    genresFromSite: List<String>?,
    bannerUrlFromSite: String?,
    posterUrlFromSite: String?,
    yearFromSite: Int?
): LoadResponse {
    println("[CineAgora] 🎬 Criando MovieLoadResponse...")
    
    // Para filmes, não precisamos fazer nova requisição para pegar o título
    // Podemos usar o título extraído anteriormente ou do TMDB
    
    val title = tmdbInfo?.title ?: "Título não encontrado"
    val year = tmdbInfo?.year ?: yearFromSite
    val plot = tmdbInfo?.overview ?: plotFromSite
    val posterUrl = tmdbInfo?.posterUrl ?: posterUrlFromSite
    val backdropUrl = tmdbInfo?.backdropUrl ?: bannerUrlFromSite
    val genres = tmdbInfo?.genres ?: genresFromSite
    val duration = tmdbInfo?.duration
    val rating = tmdbInfo?.rating?.let { Score.from10(it) }
    
    println("[CineAgora] 🎬 Dados finais:")
    println("[CineAgora] 🎬 - Título: $title")
    println("[CineAgora] 🎬 - Ano: $year")
    println("[CineAgora] 🎬 - Duração: $duration")
    println("[CineAgora] 🎬 - Rating: $rating")
    println("[CineAgora] 🎬 - Gêneros: $genres")
    
    return newMovieLoadResponse(title, playerUrl, TvType.Movie, playerUrl) {
        this.posterUrl = posterUrl
        this.backgroundPosterUrl = backdropUrl
        this.year = year
        this.plot = plot
        this.tags = genres
        this.duration = duration
        this.score = rating
        this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
        
        // Adicionar atores do TMDB com suas vozes
        tmdbInfo?.actors?.let { actors ->
            println("[CineAgora] 🎬 Adicionando ${actors.size} atores...")
            addActors(actors)
        }
        
        // Adicionar trailer do TMDB se disponível
        tmdbInfo?.youtubeTrailer?.let { trailerUrl ->
            println("[CineAgora] 🎬 Adicionando trailer: $trailerUrl")
            addTrailer(trailerUrl)
        }
    }
}

    // =============================================
    // FUNÇÃO LOADLINKS COM EXTRACTOR
    // =============================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[CineAgora] 🔗 ===========================================")
        println("[CineAgora] 🔗 LOADLINKS chamado")
        println("[CineAgora] 🔗 Data: ${data.take(100)}...")
        println("[CineAgora] 🔗 isCasting: $isCasting")
        println("[CineAgora] 🔗 ===========================================")
        
        if (data.contains("youtube.com") || data.contains("youtu.be")) {
            println("[CineAgora] 🔗 ❌ URL do YouTube, ignorando...")
            return false
        }
        
        println("[CineAgora] 🔗 Chamando CineAgoraExtractor...")
        // Usar o extractor CineAgoraExtractor
        return CineAgoraExtractor.extractVideoLinks(data, name, callback)
    }

    // =============================================
    // CLASSES PARA TMDB
    // =============================================
    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<Pair<Actor, String?>>?,
        val youtubeTrailer: String?,
        val duration: Int?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap(),
        val rating: Double? = null
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

    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?,
        @JsonProperty("vote_average") val vote_average: Double?
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
}