package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import org.jsoup.nodes.Element
import kotlin.math.roundToInt   // ← ESSA LINHA FALTAVA

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/documentario/" to "Documentário",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/familia/" to "Família",
        "$mainUrl/category/fantasia/" to "Fantasia",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/guerra/" to "Guerra",
        "$mainUrl/category/kids/" to "Kids",
        "$mainUrl/category/misterio/" to "Misterio",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val document = app.get(url).document
        val home = document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("header.entry-header h1.entry-title")?.text() ?: return null
        val href = this.selectFirst("a.lnk-blk")?.attr("href") ?: return null
        val posterUrl = this.selectFirst("div.post-thumbnail figure img")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let {
                val full = if (it.startsWith("//")) "https:$it" else it
                full.replace("/w500/", "/original/")
            }
        }
        val year = this.selectFirst("span.year")?.text()?.toIntOrNull()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.year = year
            this.quality = getQualityFromString(this@toSearchResult.selectFirst("span.post-ql")?.text())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.aa-cn div#movies-a ul.post-lst li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("aside.fg1 header.entry-header h1.entry-title")?.text() ?: return newMovieLoadResponse("Erro", url, TvType.Movie, url)
        val poster = document.selectFirst("div.bghd img.TPostBg")?.let { img ->
            val src = img.attr("src").takeIf { it.isNotBlank() } ?: img.attr("data-src")
            src?.let {
                val full = if (it.startsWith("//")) "https:$it" else it
                full.replace("/w1280/", "/original/")
            }
        }

        val year = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.year")?.ownText()?.toIntOrNull()
        val duration = document.selectFirst("aside.fg1 header.entry-header div.entry-meta span.duration")?.ownText()
        val rating = document.selectFirst("div.vote-cn span.vote span.num")?.text()?.toDoubleOrNull()
        val plot = document.selectFirst("aside.fg1 div.description p")?.text()
        val genres = document.select("aside.fg1 header.entry-header div.entry-meta span.genres a").map { it.text() }
        val actors = document.select("aside.fg1 ul.cast-lst a").map { Actor(it.text(), it.attr("href")) }
        val trailerUrl = document.selectFirst("div.mdl-cn div.video iframe")?.attr("src")

        val iframeUrl = document.selectFirst("iframe[src*='assistirseriesonline'], iframe[data-src*='assistirseriesonline']")
            ?.let { it.attr("src").ifBlank { it.attr("data-src") } }

        return if (url.contains("/serie/")) {
            val episodes = if (iframeUrl != null) parseSeriesEpisodes(iframeUrl) else emptyList()
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = rating?.let { (it * 10).toInt() }
                this.tags = genres
                if (actors.isNotEmpty()) addActors(actors)
                trailerUrl?.let { addTrailer(it) }
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, iframeUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = rating?.let { (it * 10).toInt() }
                this.tags = genres
                this.recommendations = null
                this.duration = parseDuration(duration)
                if (actors.isNotEmpty()) addActors(actors)
                trailerUrl?.let { addTrailer(it) }
            }
        }
    }

    private suspend fun parseSeriesEpisodes(iframeUrl: String): List<Episode> {
        val doc = app.get(iframeUrl).document
        val episodes = mutableListOf<Episode>()

        doc.select("header.header ul.header-navigation li").forEach { seasonTab ->
            val season = seasonTab.attr("data-season-number").toIntOrNull() ?: return@forEach
            val seasonId = seasonTab.attr("data-season-id")

            doc.select("li[data-season-id='$seasonId']").forEach { ep ->
                val epId = ep.attr("data-episode-id")
                val epTitle = ep.selectFirst("a")?.text() ?: return@forEach
                val epNumber = epTitle.substringBefore(" - ").toIntOrNull() ?: 1
                val cleanTitle = if (epTitle.contains(" - ")) epTitle.substringAfter(" - ") else epTitle

                episodes.add(newEpisode(epId) {
                    this.name = cleanTitle
                    this.season = season
                    this.episode = epNumber
                })
            }
        }
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        val url = if (data.matches(Regex("^\\d+$"))) "https://assistirseriesonline.icu/episodio/$data" else data
        val doc = app.get(url).document

        doc.select("button[data-source*='embedplay.upns.pro'], button[data-source*='embedplay.upn.one']").forEach {
            val link = it.attr("data-source")
            if (link.isNotBlank()) loadExtractor(link, url, subtitleCallback, callback)
        }

        doc.select("div.play-overlay div#player iframe").forEach {
            val src = it.attr("src")
            if (src.isNotBlank()) loadExtractor(src, url, subtitleCallback, callback)
        }

        return true
    }

    private fun parseDuration(duration: String?): Int? {
        if (duration == null) return null
        val regex = Regex("(\\d+)h\\s*(\\d+)m")
        val match = regex.find(duration)
        return if (match != null) {
            val h = match.groupValues[1].toInt()
            val m = match.groupValues[2].toInt()
            h * 60 + m
        } else {
            duration.filter { it.isDigit() }.toIntOrNull()
        }
    }
}