package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    // Headers completos
    private val defaultHeaders = mapOf(
        "Referer" to mainUrl,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36"
    )
    
    // Helper: Extrai a URL de embed do Fembed
    private fun getFembedUrl(element: Element): String? {
        val iframeSrc = element.selectFirst("iframe#player")?.attr("src")
        if (!iframeSrc.isNullOrEmpty() && iframeSrc.contains("fembed")) {
            return iframeSrc
        }
        val dataUrl = element.selectFirst("button[data-url]")?.attr("data-url")
        if (!dataUrl.isNullOrEmpty() && dataUrl.contains("fembed")) {
            return dataUrl
        }
        return null
    }

    override val mainPage = listOf(
        MainPageData("Lançamentos", "$mainUrl/lancamentos"),
        MainPageData("Últimos Filmes", "$mainUrl/filmes"),
        MainPageData("Últimas Séries", "$mainUrl/series"),
        MainPageData("Últimos Animes", "$mainUrl/animes")
    )

    // =======================================================
    // 1. FUNÇÃO AUXILIAR (TO SEARCH RESULT)
    // =======================================================
    private fun Element.toSearchResult(): SearchResponse? {
        // Extrai título e href do elemento principal (a.card)
        val title = this.attr("title").takeIf { it.isNotBlank() } 
            ?: this.selectFirst(".card-title")?.text()?.trim() ?: return null
            
        val href = fixUrl(this.attr("href")).takeIf { it.isNotBlank() } 
            ?: fixUrl(this.selectFirst("a")?.attr("href") ?: return null)

        // Extrai Poster (Robusta: data-src para lazy load, ou src)
        val posterUrl = this.selectFirst("img")?.attr("data-src")
            .takeIf { it?.isNotEmpty() == true } 
            ?: this.selectFirst("img")?.attr("src")
            ?.let { fixUrl(it) }

        // Determina o tipo de conteúdo
        val type = if (href.contains("/filme/")) TvType.Movie else TvType.TvSeries

        // Extrai o ano do título (se existir)
        val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()
        val cleanTitle = title.substringBeforeLast("(").trim().ifEmpty { title }

        // Usa os construtores específicos para evitar o erro 'Unresolved reference'
        return if (type == TvType.Movie) {
            newMovieSearchResponse(cleanTitle, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(cleanTitle, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    // =======================================================
    // 2. MAIN PAGE
    // =======================================================
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val type = request.data.substringAfterLast("/")
            if (type.contains("genero")) {
                val genre = request.data.substringAfterLast("genero/").substringBefore("/")
                "$mainUrl/genero/$genre/page/$page"
            } else {
                "$mainUrl/$type/page/$page"
            }
        }
        
        val response = app.get(url, headers = defaultHeaders)
        val document = response.document

        // Mapeia e chama a função auxiliar (ESTILO ULTRACINE)
        val list = document.select("a.card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }
    
    // ---

    // =======================================================
    // 3. SEARCH
    // =======================================================
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=$query"

        val response = app.get(url, headers = defaultHeaders)
        val document = response.document 

        // Mapeia e chama a função auxiliar (ESTILO ULTRACINE)
        val results = document.select("a.card, div.card").mapNotNull { it.toSearchResult() }

        if (results.isEmpty()) {
            val errorHtml = document.html().take(150)
            throw ErrorLoadingException("ERRO BUSCA: Nenhum resultado. HTML Recebido (150 chars): $errorHtml")
        }

        return results
    }

    // ---

    // =======================================================
    // 4. LOAD
    // =======================================================
    override suspend fun load(url: String): LoadResponse {
        val response = app.get(url, headers = defaultHeaders) 
        val document = response.document

        val isMovie = url.contains("/filme/")

        // 1. TÍTULO
        val dynamicTitle = document.selectFirst(".title")?.text()?.trim()
        val title: String

        if (dynamicTitle.isNullOrEmpty()) {
            val fullTitle = document.selectFirst("title")?.text()?.trim()
                ?: throw ErrorLoadingException("Não foi possível extrair a tag <title>.")

            title = fullTitle.substringAfter("Assistir").substringBefore("Grátis").trim()
                .ifEmpty { fullTitle.substringBefore("Grátis").trim() } 
        } else {
            title = dynamicTitle
        }

        // 2. POSTER e SINOPSE
        val posterUrl = document.selectFirst(".poster img")?.attr("src")?.let { fixUrl(it) }
            ?: document.selectFirst(".poster")?.attr("src")?.let { fixUrl(it) }

        val plot = document.selectFirst(".syn")?.text()?.trim()
            ?: "Sinopse não encontrada."

        // 3. TAGS/GÊNEROS
        val tags = document.select("a.chip").map { it.text().trim() }.filter { it.isNotEmpty() }

        // 4. ELENCO (ATORES): Usando a Estratégia de Exclusão
        val allDivLinks = document.select("div a").map { it.text().trim() }
        val chipTexts = tags.toSet() 

        val actors = allDivLinks
            .filter { linkText -> linkText !in chipTexts }
            .filter { linkText -> !linkText.contains("Assista sem anúncios") }
            .filter { it.isNotEmpty() && it.length > 2 }
            .distinct() 
            .take(15) 
            .toList()

        // Outros campos
        val year = title.substringAfterLast("(").substringBeforeLast(")").toIntOrNull()

        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (isMovie) {
            val embedUrl = getFembedUrl(document)
            newMovieLoadResponse(title, url, type, embedUrl) {
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                addActors(actors)
            }
        } else {
            val seasons = document.select("div#season-tabs button").mapIndexed { index, element ->
                val seasonName = element.text().trim()
                newEpisode(url) {
                    name = seasonName
                    season = index + 1
                    episode = 1 
                    data = url 
                }
            }
            newTvSeriesLoadResponse(title, url, type, seasons) { 
                this.posterUrl = posterUrl
                this.plot = plot
                this.tags = tags
                this.year = year
                addActors(actors)
            }
        }
    }

    // ---

    // =======================================================
    // 5. LOAD LINKS
    // =======================================================
    override suspend fun loadLinks(
        data: String,
        isMovie: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (isMovie) {
            return loadExtractor(data, data, subtitleCallback, callback)
        } else {
            val response = app.get(data, headers = defaultHeaders) 
            val document = response.document

            val episodeButtons = document.select("button[data-url*=\"fembed\"]")

            for (button in episodeButtons) {
                val embedUrl = button.attr("data-url")
                if (!embedUrl.isNullOrEmpty()) {
                    loadExtractor(embedUrl, mainUrl, subtitleCallback, callback) 
                }
            }
            return true
        }
    }
}
