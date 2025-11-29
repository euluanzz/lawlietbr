package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override var lang = "pt"           // pre-release atual exige String
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/filmes/page/" to "Filmes",
        "$mainUrl/series/page/" to "Séries",
        "$mainUrl/lancamentos/page/" to "Lançamentos"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else request.data.replace("/page/", "/page/$page")
        val doc = app.get(url).document
        val items = doc.select("article.item").mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items)
    }

    private fun org.jsoup.nodes.Element.toSearchResponse(): SearchResponse? {
        val title = selectFirst("h2")?.text() ?: selectFirst(".title")?.text() ?: return null
        val href = selectFirst("a")?.attr("href") ?: return null
        val poster = selectFirst("img")?.attr("src") ?: selectFirst("img")?.attr("data-src")
        return if (href.contains("/serie/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { posterUrl = poster }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { posterUrl = poster }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("article.item").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1")?.text() ?: ""
        val poster = doc.selectFirst(".poster img")?.attr("src") ?: doc.selectFirst(".poster img")?.attr("data-src")
        val plot = doc.selectFirst(".sinopsis")?.text() ?: doc.selectFirst(".description")?.text()
        val year = doc.selectFirst(".year")?.text()?.toIntOrNull()

        if (url.contains("/serie/")) {
            val episodes = doc.select(".episodios li").mapNotNull { ep ->
                val a = ep.selectFirst("a") ?: return@mapNotNull null
                val epUrl = a.attr("href")
                val epName = a.text()
                newEpisode(epUrl) { name = epName }
            }
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document

        // iframes
        doc.select("iframe").forEach {
            val src = it.attr("src")
            if (src.isNotBlank() && !src.contains("youtube")) {
                loadExtractor(src, data, subtitleCallback, callback)
            }
        }

        // players diretos (botões ou links)
        doc.select("player-option, .player, #player option").forEach {
            val playerUrl = it.attr("data-url") ?: it.attr("value") ?: return@forEach
            if (playerUrl.startsWith("http")) playerUrl else "$mainUrl$playerUrl"
            loadExtractor(playerUrl, data, subtitleCallback, callback)
        }

        return true
    }
}