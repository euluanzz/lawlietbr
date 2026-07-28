package com.reidosembeds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import org.json.JSONObject
import org.json.JSONArray
import java.net.URI

@CloudstreamPlugin
class ReiDosEmbedsProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosEmbeds())
    }
}

private data class TempAgendaEvent(
    val title: String,
    val url: String,
    val posterUrl: String,
    val isLive: Boolean,
    val timeStr: String,
    val sortKey: String,
    val statusBadge: String
)

class ReiDosEmbeds : MainAPI() {
    override var name = "Rei dos Embeds"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val baseUrl = "https://reidosembeds.online"

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Origin" to baseUrl,
        "Referer" to "$baseUrl/"
    )

    companion object {
        private var cachedChannelCategories: List<HomePageList>? = null
        private var lastChannelsFetch: Long = 0L
        private const val CHANNELS_CACHE_MS = 24 * 60 * 60 * 1000L // 24 horas

        private var cachedAgenda: HomePageList? = null
        private var lastAgendaFetch: Long = 0L
        private const val AGENDA_CACHE_MS = 15 * 60 * 1000L // 15 minutos
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homeCategories = mutableListOf<HomePageList>()
        val currentTime = System.currentTimeMillis()

        // ==========================================
        // 1. LÓGICA DA AGENDA 
        // ==========================================
        try {
            if (cachedAgenda == null || (currentTime - lastAgendaFetch) > AGENDA_CACHE_MS) {
                val tempAgendaEvents = mutableListOf<TempAgendaEvent>()
                var currentPage = 1
                var keepFetching = true
                
                while (keepFetching && currentPage <= 10) {
                    val pageUrl = "$baseUrl/agenda?status=all&page=$currentPage"
                    val agendaDoc = app.get(pageUrl, headers = defaultHeaders, cacheTime = 15).document
                    val eventCards = agendaDoc.select("article[data-event-card]")
                    
                    if (eventCards.isEmpty()) break
                    
                    var foundValidEventOnPage = false
                    
                    for (card in eventCards) {
                        val statusBadge = card.select("div.absolute.right-2.top-2").text().trim()
                        
                        if (statusBadge.equals("AO VIVO", ignoreCase = true) || statusBadge.equals("EM BREVE", ignoreCase = true)) {
                            foundValidEventOnPage = true
                            
                            val rawTitle = card.select("h3").text().trim()
                            val posterUrl = card.select("img").firstOrNull()?.attr("src") ?: ""
                            val mainUrl = card.select("div.flex.gap-2 a[href^=http]").firstOrNull()?.attr("href") ?: ""
                            
                            var eventDate = ""
                            var eventTime = ""
                            
                            val timeElements = card.select("span.inline-flex.items-center")
                            for (el in timeElements) {
                                val text = el.text().trim()
                                val timeMatch = Regex("\\b\\d{2}:\\d{2}\\b").find(text)
                                if (timeMatch != null) eventTime = timeMatch.value
                                
                                val dateMatch = Regex("\\b\\d{2}/\\d{2}/\\d{4}\\b").find(text)
                                if (dateMatch != null) {
                                    val parts = dateMatch.value.split("/")
                                    if (parts.size == 3) eventDate = "${parts[2]}${parts[1]}${parts[0]}" 
                                }
                            }
                            
                            val sortKey = if (eventDate.isNotEmpty() && eventTime.isNotEmpty()) {
                                "${eventDate}${eventTime}"
                            } else if (eventTime.isNotEmpty()) {
                                "99999999${eventTime}"
                            } else {
                                "9999999999:99"
                            }
                            
                            val isLive = statusBadge.equals("AO VIVO", ignoreCase = true)
                            // URL PAYLOAD: Embutindo as imagens na URL para a tela de Play resgatar
                            val payloadUrl = "$mainUrl||$posterUrl||$posterUrl"
                            if (mainUrl.isNotEmpty()) tempAgendaEvents.add(TempAgendaEvent(rawTitle, payloadUrl, posterUrl, isLive, eventTime, sortKey, statusBadge))
                        }
                    }
                    if (!foundValidEventOnPage) keepFetching = false else currentPage++
                }

                if (tempAgendaEvents.isNotEmpty()) {
                    tempAgendaEvents.sortWith(compareBy({ !it.isLive }, { it.sortKey }))
                    val agendaEvents = tempAgendaEvents.map { event ->
                        val prefix = if (event.timeStr.isNotEmpty()) "[${event.statusBadge} - ${event.timeStr}]" else "[${event.statusBadge}]"
                        val fullTitle = "$prefix ${event.title}"
                        newLiveSearchResponse(fullTitle, event.url, TvType.Live) { this.posterUrl = event.posterUrl }
                    }
                    cachedAgenda = HomePageList("Agenda (Ao Vivo e Em Breve)", agendaEvents, isHorizontalImages = true)
                } else {
                    cachedAgenda = null
                }
                lastAgendaFetch = currentTime
            }
        } catch (e: Exception) {}

        cachedAgenda?.let { homeCategories.add(it) }

        // ==========================================
        // 2. LÓGICA DOS CANAIS (Baseada no antigo.txt)
        // ==========================================
        try {
            if (cachedChannelCategories == null || (currentTime - lastChannelsFetch) > CHANNELS_CACHE_MS) {
                val categoriesList = mutableListOf<HomePageList>()
                val allChannelsMap = linkedMapOf<String, SearchResponse>() 
                
                val indexUrl = "$baseUrl/"
                val doc = app.get(indexUrl, headers = defaultHeaders, cacheTime = 15).document
                
                val genreOptions = doc.select("select[name=genre] option").mapNotNull {
                    val value = it.attr("value")
                    val name = it.text().trim()
                    if (value.isNotBlank() && value != "all" && value != "Todas as categorias") Pair(value, name) else null
                }

                val chunks = genreOptions.chunked(4)
                for (chunk in chunks) {
                    val chunkResults = chunk.amap { (slug, catName) ->
                        val catChannels = mutableListOf<SearchResponse>()
                        var currentCatUrl = "$baseUrl/?genre=$slug"
                        var keepFetchingCat = true
                        var p = 1
                        
                        while(keepFetchingCat && p <= 15) { 
                            try {
                                val pageDoc = app.get(currentCatUrl, headers = defaultHeaders, cacheTime = 15).document
                                val cards = pageDoc.select("article[data-channel-card]")
                                
                                if (cards.isEmpty()) {
                                    keepFetchingCat = false
                                } else {
                                    for (card in cards) {
                                        val title = card.selectFirst("h4")?.text()?.trim() ?: continue
                                        val channelUrlRaw = card.selectFirst("a")?.attr("href") ?: continue
                                        
                                        var logoUrl = card.selectFirst("img")?.attr("src") ?: ""
                                        if (logoUrl.startsWith("//")) logoUrl = "https:$logoUrl"

                                        var bgUrl = ""
                                        val bgDiv = card.selectFirst("div[style*='background-image']")
                                        if (bgDiv != null) {
                                            val bgMatch = Regex("""url\(['"]?(.*?)['"]?\)""").find(bgDiv.attr("style"))
                                            if (bgMatch != null) bgUrl = bgMatch.groupValues[1]
                                        }
                                        if (bgUrl.startsWith("//")) bgUrl = "https:$bgUrl"
                                        
                                        // URL PAYLOAD: Transporta o Link + Logo + Fundo escondidos
                                        val payloadUrl = "$channelUrlRaw||$logoUrl||$bgUrl"

                                        catChannels.add(newLiveSearchResponse(title, payloadUrl, TvType.Live) {
                                            // Usa o logoUrl para manter as miniaturas perfeitamente visíveis!
                                            this.posterUrl = logoUrl
                                        })
                                    }
                                    
                                    val nextBtn = pageDoc.selectFirst("a.channels-api-page-btn[rel=next]")
                                    if (nextBtn != null) {
                                        var nextUrl = nextBtn.attr("href")
                                        if (nextUrl.startsWith("//")) nextUrl = "https:$nextUrl"
                                        else if (nextUrl.startsWith("/")) nextUrl = "$baseUrl$nextUrl"
                                        
                                        currentCatUrl = nextUrl.replace("&amp;", "&")
                                        p++
                                    } else {
                                        keepFetchingCat = false
                                    }
                                }
                            } catch(e: Exception) {
                                keepFetchingCat = false
                            }
                        }
                        Pair(catName, catChannels)
                    }

                    for ((catName, channels) in chunkResults) {
                        if (channels.isNotEmpty()) {
                            // Limpa duplicatas considerando apenas o link real, ignorando as imagens do payload
                            val distinctChannels = channels.distinctBy { it.url.split("||")[0] }
                            
                            // Adiciona na aba Todos 
                            distinctChannels.forEach { allChannelsMap[it.url.split("||")[0]] = it }
                            
                            // Cria as abas individuais, ESCONDENDO a categoria Adulto
                            if (!catName.equals("Adulto", ignoreCase = true)) {
                                categoriesList.add(HomePageList(catName, distinctChannels, isHorizontalImages = true))
                            }
                        }
                    }
                }

                if (allChannelsMap.isNotEmpty()) {
                    categoriesList.add(0, HomePageList("Todos", allChannelsMap.values.toList(), isHorizontalImages = true))
                }

                if (categoriesList.size >= 3) {
                    cachedChannelCategories = categoriesList
                    lastChannelsFetch = currentTime
                }
            }
        } catch (e: Exception) {
            val errorMsg = e.message ?: e.toString().take(150)
            val errorItem = newLiveSearchResponse(errorMsg, baseUrl, TvType.Live) {
                this.posterUrl = "https://via.placeholder.com/300x450.png?text=ERRO+HTML"
            }
            homeCategories.add(HomePageList("🚨 DEBUG: ERRO HTML", listOf(errorItem), isHorizontalImages = true))
        }

        cachedChannelCategories?.let { homeCategories.addAll(it) }

        return newHomePageResponse(homeCategories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        // Desmembra o Payload para recuperar a URL real e as imagens
        val parts = url.split("||")
        val realUrl = parts[0]
        val passedLogo = parts.getOrNull(1) ?: ""
        val passedBg = parts.getOrNull(2) ?: ""

        if (realUrl.contains("/eventos/")) {
            val doc = app.get(realUrl, headers = defaultHeaders).document
            val title = doc.select("h1.event-glow-title").text().trim()
            val plot = doc.select("aside.theme-card p").joinToString("\n") { it.text() }
            
            var pageBg = doc.select(".event-stadium img.event-hero-bg").attr("src") ?: ""
            if (pageBg.startsWith("//")) pageBg = "https:$pageBg"
            if (pageBg.isEmpty()) pageBg = passedBg
            
            return newMovieLoadResponse(title, realUrl, TvType.Live, realUrl) {
                this.posterUrl = passedLogo.takeIf { it.isNotEmpty() } ?: pageBg
                this.backgroundPosterUrl = pageBg.takeIf { it.isNotEmpty() } ?: passedBg.takeIf { it.isNotEmpty() }
                this.plot = plot
            }
        }

        val doc = app.get(realUrl, headers = defaultHeaders).document
        val title = doc.selectFirst("title")?.text()?.replace(" - Rei dos Embeds", "")?.trim() ?: "Canal Ao Vivo"
        
        val embedUrl = doc.selectFirst("iframe#play-inner-frame")?.attr("src") 
            ?: doc.selectFirst("iframe")?.attr("src") 
            ?: realUrl 
            
        return newMovieLoadResponse(title, realUrl, TvType.Live, embedUrl) {
            // Aplica o fundo e a capa na página de Detalhes!
            this.posterUrl = passedLogo.takeIf { it.isNotEmpty() }
            this.backgroundPosterUrl = passedBg.takeIf { it.isNotEmpty() } ?: passedLogo.takeIf { it.isNotEmpty() }
            this.plot = "Assista $title ao vivo!"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        try {
            val searchUrl = "$baseUrl/?s=${query.replace(" ", "+")}"
            val doc = app.get(searchUrl, headers = defaultHeaders).document
            val cards = doc.select("article[data-channel-card]")
            
            val results = mutableListOf<SearchResponse>()
            
            for (card in cards) {
                val title = card.selectFirst("h4")?.text()?.trim() ?: continue
                val channelUrlRaw = card.selectFirst("a")?.attr("href") ?: continue
                
                var logoUrl = card.selectFirst("img")?.attr("src") ?: ""
                if (logoUrl.startsWith("//")) logoUrl = "https:$logoUrl"

                var bgUrl = ""
                val bgDiv = card.selectFirst("div[style*='background-image']")
                if (bgDiv != null) {
                    val bgMatch = Regex("""url\(['"]?(.*?)['"]?\)""").find(bgDiv.attr("style"))
                    if (bgMatch != null) bgUrl = bgMatch.groupValues[1]
                }
                if (bgUrl.startsWith("//")) bgUrl = "https:$bgUrl"
                
                val payloadUrl = "$channelUrlRaw||$logoUrl||$bgUrl"
                
                results.add(newLiveSearchResponse(title, payloadUrl, TvType.Live) { 
                    this.posterUrl = logoUrl 
                })
            }
            
            val agendaDoc = app.get("$baseUrl/agenda?s=${query.replace(" ", "+")}", headers = defaultHeaders).document
            val eventCards = agendaDoc.select("article[data-event-card]")
            for (card in eventCards) {
                val title = card.select("h3").text().trim()
                val mainUrl = card.select("div.flex.gap-2 a[href^=http]").firstOrNull()?.attr("href") ?: ""
                var posterUrl = card.select("img").firstOrNull()?.attr("src") ?: ""
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                if (mainUrl.isNotEmpty()) {
                    val payloadUrl = "$mainUrl||$posterUrl||$posterUrl"
                    results.add(newLiveSearchResponse(title, payloadUrl, TvType.Live) { 
                        this.posterUrl = posterUrl 
                    })
                }
            }
            
            return results
        } catch (e: Exception) {
            return emptyList()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains("/eventos/")) {
            val doc = app.get(data, headers = defaultHeaders).document
            val choices = doc.select(".player-choice[data-player-url]")
            
            if (choices.isNotEmpty()) {
                choices.amap { choice ->
                    try {
                        val optUrl = choice.attr("data-player-url")
                        val optName = choice.select(".block.truncate").text().trim()
                        if (optUrl.isNotEmpty()) resolvePlayer(optUrl, data, optName, subtitleCallback, callback)
                    } catch (e: Exception) {} 
                }
                return true
            } else {
                val mainIframe = doc.select("iframe#event-player-frame").attr("src")
                if (mainIframe.isNotEmpty()) {
                    resolvePlayer(mainIframe, data, "Principal", subtitleCallback, callback)
                    return true
                }
            }
        }

        resolvePlayer(data, data, name, subtitleCallback, callback)
        return true
    }

    private suspend fun resolvePlayer(
        startUrl: String, 
        initialReferer: String, 
        sourceName: String, 
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = startUrl
        var referer = initialReferer

        for (i in 0..2) {
            try {
                if (currentUrl.contains("youtube.com") || currentUrl.contains("youtu.be") || currentUrl.contains("twitch.tv")) {
                    loadExtractor(currentUrl, subtitleCallback, callback)
                    return
                }

                val reqHeaders = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Referer" to referer
                )

                val currentHtml = app.get(currentUrl, headers = reqHeaders).text
                
                val sourcesPattern = Regex("""var sources\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
                val sourcesMatch = sourcesPattern.find(currentHtml)
                
                if (sourcesMatch != null) {
                    val sourcesArray = JSONArray(sourcesMatch.groupValues[1])
                    val uri = URI(currentUrl)
                    val origin = "${uri.scheme}://${uri.host}"

                    for (j in 0 until sourcesArray.length()) {
                        val source = sourcesArray.getJSONObject(j)
                        val streamUrl = source.getString("src").replace("\\/", "/")
                        val label = source.optString("label", "Source ${j + 1}")
                        val finalName = if (sourceName.isNotEmpty()) "$sourceName - $label" else label
                        
                        M3u8Helper.generateM3u8(
                            finalName,
                            streamUrl,
                            currentUrl,
                            headers = mapOf(
                                "Referer" to currentUrl,
                                "Origin" to origin,
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                            )
                        ).forEach(callback)
                    }
                    return 
                }
                
                var iframeMatch = Regex("""<iframe[^>]*src="([^"]*(?:rde\.lat|play|embed|youtube|twitch)[^"]*)"[^>]*>""").find(currentHtml)
                if (iframeMatch == null) iframeMatch = Regex("""<iframe[^>]*src="([^"]+)"[^>]*>""").find(currentHtml)
                
                if (iframeMatch != null) {
                    var nextUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                    if (nextUrl.startsWith("//")) nextUrl = "https:$nextUrl"
                    
                    if (nextUrl.contains("youtube.com") || nextUrl.contains("youtu.be") || nextUrl.contains("twitch.tv")) {
                        loadExtractor(nextUrl, subtitleCallback, callback)
                        return
                    }
                    
                    referer = currentUrl
                    currentUrl = nextUrl
                } else {
                    break
                }
            } catch (e: Exception) {
                break
            }
        }
    }
}