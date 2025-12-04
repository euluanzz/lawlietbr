package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.toScore

class UltraCine : MainAPI() {
    override var mainUrl = "https://ultracine.org"
    override var name = "UltraCine"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val hasDownloadSupport = true
    override val hasChromecastSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)
    override var lang = "pt-BR"

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/fantasia/" to "Fantasia",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + if (page > 1) "page/$page/" else ""
        val doc = app.get(url, timeout = 20).document
        val items = doc.select("div.aa-cn ul.post-lst li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.size >= 24)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("header h1.entry-title a") ?: return null
        val title = titleEl.text()
        val href = fixUrl(titleEl.attr("href"))
        val poster = selectFirst("img")?.attr("src")?.let { fixUrl(it) }
            ?.replace(Regex("/w\\d+/"), "/original/")

        val year = selectFirst("span.year")?.text()?.toIntOrNull()
        val quality = selectFirst("span.post-ql")?.text()?.let { getQualityFromString(it) }

        val type = if (href.contains("/serie/")) TvType.TvSeries else TvType.Movie

        return newSearchResponse(title, href, type) {
            this.posterUrl = poster
            this.year = year
            this.quality = quality
        }
    }

    // CORRIGIDO: busca funcional
    override suspend fun search(query: String): List<SearchResponse> {
        val url = "\( mainUrl/?s= \){query.trim().replace(" ", "+")}"
        val doc = app.get(url, timeout = 15).document
        return doc.select("div.aa-cn ul.post-lst li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val doc = app.get(url).document

        val title = doc.selectFirst("h1.entry-title")?.text() ?: return null
        val poster = doc.selectFirst("div.bghd img")?.attr("src")
            ?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
            ?.replace(Regex("/w\\d+/"), "/original/")

        val year = doc.selectFirst("span.year")?.text()?.toIntOrNull()
        val duration = doc.selectFirst("span.duration")?.text()
        val plot = doc.selectFirst("div.description p")?.ownText()
        val tags = doc.select("span.genres a").map { it.text() }
        val rating = doc.selectFirst("div.vote span.num")?.text()?.toDoubleOrNull()

        val actors = doc.select("ul.cast-lst a").mapNotNull {
            Actor(it.text(), it.attr("href"))
        }

        val trailer = doc.selectFirst("div.video iframe")?.attr("src")?.let { fixUrl(it) }

        val isSeries = url.contains("/serie/") || doc.select("div.seasons").isNotEmpty()

        if (isSeries) {
            val episodes = mutableListOf<Episode>()
            doc.select("li[data-episode-id]").forEach { el ->
                val epId = el.attr("data-episode-id").takeIf { it.isNotBlank() } ?: return@forEach
                val season = el.attr("data-season-number").toIntOrNull() ?: 1
                val epText = el.selectFirst("a")?.text() ?: "Episódio $epId"
                val episodeNum = epText.substringAfter(" ").substringBefore(" ").toIntOrNull() ?: 1

                episodes.add(Episode(
                    data = epId,
                    name = epText,
                    season = season,
                    episode = episodeNum
                ))
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.score = rating?.times(10)?.toInt()?.toScore()
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            val playerUrl = doc.selectFirst("iframe[src*='assistir'], iframe[data-lazy-src*='assistir']")
                ?.attr("src")?.takeIf { it.isNotBlank() }
                ?: doc.selectFirst("iframe[src*='assistir'], iframe[data-lazy-src*='assistir']")
                    ?.attr("data-lazy-src")

            return newMovieLoadResponse(title, url, TvType.Movie, playerUrl ?: url) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = parseDuration(duration)
                this.score = rating?.times(10)?.toInt()?.toScore()
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Caso 1: apenas o ID do episódio (ex: "123456")
        if (data.matches(Regex("^\\d+$"))) {
            val epUrl = "https://assistirseriesonline.icu/episodio/$data/"
            return extractFromPage(epUrl, subtitleCallback, callback)
        }

        // Caso 2: URL completa do player/iframe
        if (data.startsWith("http")) {
            return extractFromPage(data, subtitleCallback, callback)
        }

        return false
    }

    private suspend fun extractFromPage(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val res = app.get(url, referer = mainUrl, timeout = 20)
            if (!res.isSuccessful) return false

            val doc = res.document

            // 1. Botões embedplay (os mais usados agora)
            doc.select("button[data-source]").forEach { btn ->
                val source = btn.attr("data-source").takeIf { it.isNotBlank() } ?: return@forEach
                loadExtractor(source, url, subtitleCallback, callback)
            }

            // 2. iframe direto no player
            doc.select("#player iframe, div.player iframe, iframe[src*='play'], iframe[src*='embedplay']").forEach { iframe ->
                var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }.ifBlank { iframe.attr("data-lazy-src") }
                if (src.startsWith("//")) src = "https:$src"
                if (src.isNotBlank()) {
                    loadExtractor(src, url, subtitleCallback, callback)
                }
            }

            // 3. WebView fallback (quando tem proteção pesada)
            if (callback.invokeAll.isEmpty()) {
                WebViewResolver().resolveUsingWebView(res.text).forEach { link ->
                    callback(link)
                }
            }

            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun parseDuration(text: String?): Int? = text?.let {
        Regex("(\\d+)h.*?(\\d+)m").find(it)?.let { m ->
            m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
        } ?: Regex("(\\d+)m").find(it)?.groupValues?.get(1)?.toInt()
    }
}