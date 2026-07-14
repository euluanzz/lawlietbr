package com.reidosembeds

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject
import org.json.JSONArray

@CloudstreamPlugin
class ReiDosEmbedsProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(ReiDosEmbeds())
    }
}

class ReiDosEmbeds : MainAPI() {
    override var name = "Rei dos Embeds"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)

    private val apiUrl = "https://reidosembeds.com/api"
    private var channelsCache: MutableMap<String, Pair<String, String>> = mutableMapOf()
    private val blockedCategories = listOf("Adulto", "adulto", "ADULTO")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categories = mutableListOf<HomePageList>()

        // 1. Scraping da Agenda (nova feature)
        try {
            val agendaDoc = app.get("https://reidosembeds.com/agenda").document
            val agendaEvents = mutableListOf<SearchResponse>()
            val eventCards = agendaDoc.select("article[data-event-card]")
            
            for (card in eventCards) {
                val statusBadge = card.select("div.absolute.right-2.top-2").text().trim()
                
                // Filtrar apenas Ao Vivo e Em Breve
                if (statusBadge.equals("AO VIVO", ignoreCase = true) || statusBadge.equals("EM BREVE", ignoreCase = true)) {
                    val title = card.select("h3").text().trim()
                    val posterUrl = card.select("img").firstOrNull()?.attr("src") ?: ""
                    val mainUrl = card.select("a[href^=http]").firstOrNull()?.attr("href") ?: ""
                    
                    val optionsArray = JSONArray()
                    val optionLinks = card.select("div[data-event-options-menu] a")
                    
                    for (opt in optionLinks) {
                        val optName = opt.select("span.truncate").text().trim().ifEmpty {
                            opt.attr("title").replace("Abrir ", "").trim()
                        }
                        val optUrl = opt.attr("href")
                        
                        if (optName.isNotEmpty() && optUrl.isNotEmpty()) {
                            val optObj = JSONObject()
                            optObj.put("name", optName)
                            optObj.put("url", optUrl)
                            optionsArray.put(optObj)
                        }
                    }

                    // Montamos os dados num JSON que será passado como URL
                    val eventData = JSONObject()
                    eventData.put("is_agenda", true)
                    eventData.put("title", title)
                    eventData.put("poster", posterUrl)
                    eventData.put("url", mainUrl)
                    eventData.put("options", optionsArray)

                    agendaEvents.add(
                        newLiveSearchResponse(title, eventData.toString(), TvType.Live) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }

            if (agendaEvents.isNotEmpty()) {
                categories.add(HomePageList("Agenda (Ao Vivo e Em Breve)", agendaEvents, isHorizontalImages = true))
            }
        } catch (e: Exception) {
            // Se falhar o load da agenda, ignorar para não quebrar o load dos canais padrões da home
        }

        // 2. Fetch de Categorias (codigo existente)
        val categoriesResponse = app.get("$apiUrl/channels/categories").text
        val categoriesJson = JSONObject(categoriesResponse)
        val categoriesArray = categoriesJson.getJSONArray("data")
        
        for (i in 0 until categoriesArray.length()) {
            val category = categoriesArray.getJSONObject(i)
            val categoryName = category.getString("name")
            val categoryId = category.getString("id")
            
            if (blockedCategories.contains(categoryName)) continue
            
            val channelsResponse = app.get("$apiUrl/channels?category=${categoryId.replace(" ", "%20")}").text
            val channelsJson = JSONObject(channelsResponse)
            val channelsArray = channelsJson.getJSONArray("data")
            
            val channels = mutableListOf<SearchResponse>()
            
            for (j in 0 until channelsArray.length()) {
                val channel = channelsArray.getJSONObject(j)
                val name = channel.getString("name")
                val slug = channel.getString("id")
                val embedUrl = channel.getString("embed_url")
                val logoUrl = channel.getString("logo_url")
                
                var posterUrl = logoUrl
                if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
                
                channelsCache[slug] = Pair(name, posterUrl)
                
                channels.add(
                    newLiveSearchResponse(name, embedUrl, TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                )
            }
            
            if (channels.isNotEmpty()) {
                categories.add(HomePageList(categoryName, channels, isHorizontalImages = true))
            }
        }
        
        // 3. Fetch de Todos os Canais (codigo existente)
        val allChannelsResponse = app.get("$apiUrl/channels").text
        val allChannelsJson = JSONObject(allChannelsResponse)
        val allChannelsArray = allChannelsJson.getJSONArray("data")
        
        val allChannels = mutableListOf<SearchResponse>()
        for (i in 0 until allChannelsArray.length()) {
            val channel = allChannelsArray.getJSONObject(i)
            val name = channel.getString("name")
            val embedUrl = channel.getString("embed_url")
            val logoUrl = channel.getString("logo_url")
            val category = channel.optString("category", "")
            
            if (blockedCategories.contains(category)) continue
            
            var posterUrl = logoUrl
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            allChannels.add(
                newLiveSearchResponse(name, embedUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }
        
        categories.add(0, HomePageList("Todos", allChannels, isHorizontalImages = true))
        
        return newHomePageResponse(categories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        // Detecta se a URL foi gerada pelo layout da Agenda JSON
        if (url.startsWith("{")) {
            val json = JSONObject(url)
            val title = json.getString("title")
            val posterUrl = json.optString("poster", "")
            val plot = "Assista $title ao vivo!"
            
            return newMovieLoadResponse(title, url, TvType.Live, url) {
                this.posterUrl = posterUrl
                this.plot = plot
            }
        }

        // Lógica padrao existente para canais unicos
        val slug = url.substringAfterLast("/")
        val channelData = channelsCache[slug]
        val title = channelData?.first ?: slug.replace("-", " ").split(" ").joinToString(" ") { word ->
            if (word.isNotEmpty()) word.replaceFirstChar { it.uppercase() } else word
        }
        val posterUrl = channelData?.second ?: ""
        val plot = "Assista $title ao vivo!"
        
        return newMovieLoadResponse(title, url, TvType.Live, url) {
            this.posterUrl = posterUrl
            this.plot = plot
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val response = app.get("$apiUrl/pesquisa?q=${query.replace(" ", "%20")}").text
        val json = JSONObject(response)
        val data = json.getJSONObject("data")
        
        val results = mutableListOf<SearchResponse>()
        
        val channelsArray = data.getJSONArray("channels")
        for (i in 0 until channelsArray.length()) {
            val channel = channelsArray.getJSONObject(i)
            val name = channel.getString("name")
            val embedUrl = channel.getString("embed_url")
            val logoUrl = channel.optString("logo_url", "")
            val category = channel.optString("category", "")
            
            if (blockedCategories.contains(category)) continue
            
            var posterUrl = logoUrl
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            results.add(
                newLiveSearchResponse(name, embedUrl, TvType.Live) {
                    this.posterUrl = posterUrl
                }
            )
        }
        
        val eventsArray = data.getJSONArray("events")
        for (i in 0 until eventsArray.length()) {
            val event = eventsArray.getJSONObject(i)
            val title = event.getString("title")
            val poster = event.optString("poster", "")
            val embeds = event.getJSONArray("embeds")
            
            var posterUrl = poster
            if (posterUrl.startsWith("//")) posterUrl = "https:$posterUrl"
            
            if (embeds.length() > 0) {
                val embedUrl = embeds.getJSONObject(0).getString("embed_url")
                results.add(
                    newLiveSearchResponse(title, embedUrl, TvType.Live) {
                        this.posterUrl = posterUrl
                    }
                )
            }
        }
        
        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Se formos acionados via evento da Agenda (múltiplas opções mapeadas)
        if (data.startsWith("{")) {
            val json = JSONObject(data)
            if (json.optBoolean("is_agenda")) {
                val options = json.getJSONArray("options")
                val optionPairs = mutableListOf<Pair<String, String>>()
                for (i in 0 until options.length()) {
                    val opt = options.getJSONObject(i)
                    optionPairs.add(Pair(opt.getString("name"), opt.getString("url")))
                }

                // Dispara os requests em paralelo para extrair as m3u8 de forma super-rapida 
                coroutineScope {
                    optionPairs.map { (optName, optUrl) ->
                        async {
                            try {
                                val channelHtml = app.get(optUrl).text
                                val iframePattern = Regex("""<iframe[^>]*src="([^"]*__play[^"]*)"[^>]*>""")
                                val iframeMatch = iframePattern.find(channelHtml)

                                if (iframeMatch != null) {
                                    val playerUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                                    val playerHtml = app.get(playerUrl, headers = mapOf("Referer" to optUrl)).text
                                    val sourcesPattern = Regex("""var sources\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
                                    val sourcesMatch = sourcesPattern.find(playerHtml)

                                    if (sourcesMatch != null) {
                                        val sourcesArray = JSONArray(sourcesMatch.groupValues[1])
                                        for (i in 0 until sourcesArray.length()) {
                                            val source = sourcesArray.getJSONObject(i)
                                            val streamUrl = source.getString("src").replace("\\/", "/")
                                            val label = source.optString("label", "Source ${i + 1}")

                                            M3u8Helper.generateM3u8(
                                                "$optName - $label",
                                                streamUrl,
                                                playerUrl,
                                                headers = mapOf(
                                                    "Referer" to playerUrl,
                                                    "Origin" to "https://v2.rde.lat",
                                                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                                                )
                                            ).forEach(callback)
                                        }
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignora caso de erro na extração de uma opção específica para nao crashar as outras
                            }
                        }
                    }.awaitAll()
                }
                return true
            }
        }

        // Lógica original padrao de extração individual
        val channelHtml = app.get(data).text
        val iframePattern = Regex("""<iframe[^>]*src="([^"]*__play[^"]*)"[^>]*>""")
        val iframeMatch = iframePattern.find(channelHtml) ?: return false
        
        val playerUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
        
        val playerHtml = app.get(playerUrl, headers = mapOf("Referer" to data)).text
        val sourcesPattern = Regex("""var sources\s*=\s*(\[.*?\]);""", RegexOption.DOT_MATCHES_ALL)
        val sourcesMatch = sourcesPattern.find(playerHtml) ?: return false
        
        val sourcesArray = JSONArray(sourcesMatch.groupValues[1])
        
        for (i in 0 until sourcesArray.length()) {
            val source = sourcesArray.getJSONObject(i)
            val streamUrl = source.getString("src").replace("\\/", "/")
            val label = source.optString("label", "Source ${i + 1}")
            
            M3u8Helper.generateM3u8(
                "$name - $label",
                streamUrl,
                playerUrl,
                headers = mapOf(
                    "Referer" to playerUrl,
                    "Origin" to "https://v2.rde.lat",
                    "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36"
                )
            ).forEach(callback)
        }
        
        return true
    }
}