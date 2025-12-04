package com.UltraCine

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element

class UltraCine : MainAPI() {
    override var name = "UltraCine"
    override var mainUrl = "https://ultracine.org"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/category/lancamentos/" to "Lançamentos",
        "$mainUrl/category/acao/" to "Ação",
        "$mainUrl/category/animacao/" to "Animação",
        "$mainUrl/category/comedia/" to "Comédia",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/terror/" to "Terror",
        "$mainUrl/category/ficcao-cientifica/" to "Ficção Científica",
        "$mainUrl/category/thriller/" to "Thriller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) request.data else "${request.data}page/$page/"
        val doc = app.get(url).document
        val items = doc.select("ul.post-lst li").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a.lnk-blk") ?: return null
        val title = a.selectFirst("h2")?.text() ?: a.attr("title")
        val href = fixUrl(a.attr("href"))
        val poster = selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("data-src")

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = poster?.replace("/w500/", "/original/")
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/?s=$query").document
        return doc.select("ul.post-lst li").mapNotNull { it.toSearchResult() }
    }

    override suspend fun loadLinks(
    data: String,
    isCasting: Boolean,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
): Boolean {
    val playerUrl = data.trim()

    try {
        val response = app.get(playerUrl, referer = "https://ultracine.top/")
        if (!response.isSuccessful) return false

        val script = response.text

        // Extrai o .m3u8 do script (padrão do ultracine)
        val m3u8Url = Regex("""["']([^"']*\.m3u8[^"']*)["']""").find(script)?.groupValues?.get(1)
            ?: Regex("""file:\s*["']([^"']*\.m3u8[^"']*)["']""").find(script)?.groupValues?.get(1)
            ?: return false

        callback.invoke(
            ExtractorLink(
                source = name,
                name = "$name - HD",
                url = m3u8Url,
                referer = playerUrl,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = mapOf("Origin" to "https://ultracine.top")
            )
        )
        return true
    } catch (e: Exception) {
        e.printStackTrace()
        return false
   
      }
 
   }

 }