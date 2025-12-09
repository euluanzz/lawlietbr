package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override val usesWebView = true

    // =========================================================================
    // PÁGINA PRINCIPAL - REORDENADA
    // =========================================================================
    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries"
    )

    // =========================================================================
    // PÁGINA PRINCIPAL
    // =========================================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = mutableListOf<SearchResponse>()

        // Correção: Use como função de extensão
        document.select("a.card, div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
            element.toSearchResult()?.let { home.add(it) }
        }

        // Fallback
        if (home.isEmpty()) {
            document.select("a[href*='/filme/'], a[href*='/serie/']").forEach { element ->
                element.toSearchResult()?.let { home.add(it) }
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    // =========================================================================
    // FUNÇÃO AUXILIAR: CONVERTER ELEMENTO PARA SEARCHRESPONSE
    // =========================================================================
    private fun Element.toSearchResult(): SearchResponse? {
        // 1. Título vem do atributo "title"
        val title = this.attr("title") ?: return null

        // 2. Link
        val href = this.attr("href") ?: return null

        // 3. Imagem do poster
        val img = this.selectFirst("img.card-img, img")
        val poster = img?.attr("src")?.let { fixUrl(it) }

        // 4. Extrai ano do título (ex: "Amy (2015)")
        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()

        // 5. Limpa o título
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

        // 6. Verifica se é série
        val isSerie = href.contains("/serie/") || 
                      this.selectFirst("span.badge-kind")?.text()?.contains("SÉRIE", true) == true

        // 7. Cria o resultado
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

    // =========================================================================
    // PESQUISA
    // =========================================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document

        // Seletor CORRETO para SuperFlix
        return document.select("a.card").mapNotNull {
            it.toSearchResult()
        }
    }

    // =========================================================================
    // CARREGAR DETALHES DO CONTEÚDO
    // =========================================================================
    override suspend fun load(url: String): LoadResponse? {
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
            val episodes = extractEpisodesFromButtons(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                if (actors.isNotEmpty()) addActors(actors)
            }
        } else {
            val playerUrl = findPlayerUrl(document)

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

    // =========================================================================
    // EXTRATÇÃO DE LINKS
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return SuperFlixExtractor.extractVideoLinks(data, mainUrl, name, callback)
    }

    // =========================================================================
    // FUNÇÕES AUXILIARES
    // =========================================================================
    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) return playButton.attr("data-url")
        val iframe = document.selectFirst("iframe[src*='fembed'], iframe[src*='filemoon'], iframe[src*='player'], iframe[src*='embed']")
        if (iframe != null) return iframe.attr("src")
        val videoLink = document.selectFirst("a[href*='.m3u8'], a[href*='.mp4'], a[href*='watch']")
        return videoLink?.attr("href")
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

    // =========================================================================
    // JSON-LD PARSER
    // =========================================================================
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

                    return JsonLdInfo(
                        title = title,
                        year = year,
                        posterUrl = image,
                        description = description,
                        genres = genres,
                        director = director,
                        actors = actors,
                        type = type
                    )
                }
            } catch (e: Exception) {
                // Ignorar JSON-LD malformado
            }
        }
        return JsonLdInfo()
    }
}