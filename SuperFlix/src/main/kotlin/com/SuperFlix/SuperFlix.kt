package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import java.net.URLEncoder
import com.fasterxml.jackson.annotation.JsonProperty

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // API do TMDB (para enriquecer dados quando JSON-LD não tiver tudo)
    private val tmdbApiKey = "f9a1e262f2251496b1efa1cd5759680a"
    private val tmdbBaseUrl = "https://api.themoviedb.org/3"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    override val mainPage = mainPageOf(
        "$mainUrl/filmes" to "Filmes",
        "$mainUrl/series" to "Séries",
        "$mainUrl/lancamentos" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "?page=$page" else ""
        val document = app.get(url).document

        val home = mutableListOf<SearchResponse>()

        document.select("a.card").forEach { element ->
            element.toSearchResult()?.let { home.add(it) }
        }

        // Fallback para outros seletores
        if (home.isEmpty()) {
            document.select("div.recs-grid a.rec-card, .movie-card, article, .item").forEach { element ->
                element.toSearchResult()?.let { home.add(it) }
            }
        }

        return newHomePageResponse(request.name, home.distinctBy { it.url })
    }

    private fun Element.toSearchResult(): SearchResponse? {
        try {
            val url = this.attr("href") ?: return null
            val titleElement = this.selectFirst(".card-title, .rec-title, .movie-title, h2, h3, .title")
            val title = titleElement?.text()?.trim() ?: return null

            val image = this.selectFirst(".card-img, img")?.attr("src")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }

            // Determinar se é Filme ou Série
            val badge = this.selectFirst(".badge-kind")?.text()?.lowercase()
            val isSerie = when {
                badge?.contains("série") == true -> true
                badge?.contains("anime") == true -> true
                badge?.contains("filme") == true -> false
                url.contains("/serie/") -> true
                else -> false
            }

            // Extrair ano do título (ex: "Amy (2015)")
            val yearMatch = Regex("\\((\\d{4})\\)").find(title)
            val year = yearMatch?.groupValues?.get(1)?.toIntOrNull()

            // Limpar título (remover ano)
            val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

            return if (isSerie) {
                newTvSeriesSearchResponse(cleanTitle, fixUrl(url)) {
                    this.posterUrl = image
                    this.year = year
                }
            } else {
                newMovieSearchResponse(cleanTitle, fixUrl(url)) {
                    this.posterUrl = image
                    this.year = year
                }
            }
        } catch (e: Exception) {
            return null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/buscar?q=$encodedQuery"
        val document = app.get(searchUrl).document

        val results = mutableListOf<SearchResponse>()

        // Usar o seletor correto baseado na estrutura real
        document.select("div.grid a.card").forEach { element ->
            element.toSearchResult()?.let { results.add(it) }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()

        // 1. PRIMEIRO: Extrair dados do JSON-LD do site (já funciona!)
        val jsonLdInfo = extractJsonLd(html)
        
        // 2. Se o JSON-LD não tiver título, usar fallback
        val title = jsonLdInfo.title ?: document.selectFirst("h1, .title")?.text() ?: return null
        
        // 3. Extrair ano
        val year = jsonLdInfo.year ?: Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        
        // 4. Poster - preferir JSON-LD, depois meta tag
        val poster = jsonLdInfo.posterUrl?.replace("/w500/", "/original/")
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?.replace("/w500/", "/original/")
        
        // 5. Plot/descrição
        val plot = jsonLdInfo.description 
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")
            ?: document.selectFirst(".description, .syn")?.text()
        
        // 6. Tags/gêneros - do JSON-LD ou do site
        val tags = jsonLdInfo.genres 
            ?: document.select("a[href*='/categoria/'], a.chip, .chip").map { it.text() }
        
        // 7. Atores - do JSON-LD
        val jsonLdActors = jsonLdInfo.actors?.map { Actor(it, "") } ?: emptyList()
        
        // 8. Diretor - do JSON-LD
        val director = jsonLdInfo.director?.firstOrNull()
        
        // 9. Determinar se é série
        val isSerie = url.contains("/serie/") || jsonLdInfo.type == "TVSeries"
        
        // 10. Se o JSON-LD não tiver atores ou dados completos, buscar do TMDB
        val tmdbActors = if (jsonLdActors.isEmpty() && jsonLdInfo.tmdbId != null) {
            fetchTMDBActors(jsonLdInfo.tmdbId, isSerie)
        } else {
            emptyList()
        }
        
        // 11. Trailer do TMDB se tiver ID
        val youtubeTrailer = if (jsonLdInfo.tmdbId != null) {
            fetchTMDBTrailer(jsonLdInfo.tmdbId, isSerie)
        } else {
            null
        }
        
        // Combinar atores do JSON-LD e TMDB
        val allActors = (jsonLdActors + tmdbActors).distinctBy { it.name }
        
        // 12. Criar resposta
        return if (isSerie) {
            val episodes = extractEpisodesFromButtons(document, url)

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                
                // Adicionar atores
                if (allActors.isNotEmpty()) addActors(allActors)
                
                // Adicionar diretor como ator especial
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                
                // Adicionar trailer se tiver
                youtubeTrailer?.let { addTrailer(it) }
            }
        } else {
            val videoData = findFembedUrl(document) ?: ""

            newMovieLoadResponse(title, url, TvType.Movie, videoData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = if (tags.isNotEmpty()) tags else null
                
                // Adicionar atores
                if (allActors.isNotEmpty()) addActors(allActors)
                
                // Adicionar diretor como ator especial
                if (director != null) addActors(listOf(Actor(director, "Diretor")))
                
                // Adicionar trailer se tiver
                youtubeTrailer?.let { addTrailer(it) }
            }
        }
    }

    // =========================================================================
    // FUNÇÕES AUXILIARES DO JSON-LD (QUE JÁ FUNCIONAM)
    // =========================================================================
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

    // =========================================================================
    // FUNÇÕES DO TMDB (PARA COMPLEMENTAR DADOS)
    // =========================================================================
    private suspend fun fetchTMDBActors(tmdbId: String, isTv: Boolean): List<Actor> {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$tmdbBaseUrl/$type/$tmdbId/credits?api_key=$tmdbApiKey&language=pt-BR"
            
            val response = app.get(url, timeout = 5_000)
            val credits = response.parsedSafe<TMDBCreditsResponse>()
            
            credits?.cast?.take(10)?.map { actor ->
                val profileUrl = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                Actor(actor.name, profileUrl)
            } ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchTMDBTrailer(tmdbId: String, isTv: Boolean): String? {
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$tmdbBaseUrl/$type/$tmdbId/videos?api_key=$tmdbApiKey&language=pt-BR"
            
            val response = app.get(url, timeout = 5_000)
            val videos = response.parsedSafe<TMDBVideosResponse>()
            
            videos?.results?.find { it.site == "YouTube" && it.type == "Trailer" }?.key
        } catch (e: Exception) {
            null
        }
    }

    // =========================================================================
    // FUNÇÕES RESTANTES (MANTIDAS)
    // =========================================================================
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
        if (iframe != null) return iframe.attr("src")

        val playButton = document.selectFirst("button.bd-play[data-url]")
        if (playButton != null) return playButton.attr("data-url")

        return null
    }

    // =========================================================================
    // CLASSES PARA O TMDB
    // =========================================================================
    private data class TMDBCreditsResponse(
        @JsonProperty("cast") val cast: List<TMDBCast>?
    )

    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("profile_path") val profile_path: String?
    )

    private data class TMDBVideosResponse(
        @JsonProperty("results") val results: List<TMDBVideo>?
    )

    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String
    )

    // =========================================================================
    // LOAD LINKS (MANTIDO)
    // =========================================================================
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Usar o extrator do Fembed que já funciona
        return loadExtractor(data, mainUrl, subtitleCallback, callback)
    }
}