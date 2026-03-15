package com.EmbedTV

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import java.io.ByteArrayOutputStream

@CloudstreamPlugin
class EmbedTvProvider : BasePlugin() {
    override fun load() {
        registerMainAPI(EmbedTv())
    }
}

class EmbedTv : MainAPI() {
    override var name = "EmbedTV"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.Live)
    
    private val mainSite = "https://embedtv.best"
    private val baseUrl = "https://www3.embedtv.best"
    private val decryptionKey = "embedtv@123"
    private val blockedChannels = listOf("sexyhot", "playboy")

    private fun fixImageUrl(url: String): String {
        return when {
            url.contains("cloudfront.net") -> {
                val cleanUrl = url.split("?")[0]
                "$cleanUrl?width=300&height=170&fit=crop&format=webp"
            }
            url.startsWith("//") -> "https:$url"
            url.startsWith("http") -> url
            url.startsWith("/") -> "$mainSite$url"
            else -> url
        }
    }

    private fun decryptStreamUrl(encodedString: String): String? {
        return try {
            val decodedBytes = Base64.decode(encodedString, Base64.DEFAULT)
            val decodedString = String(decodedBytes, Charsets.ISO_8859_1)
            val reversedString = decodedString.reversed()
            
            val keyBytes = decryptionKey.toByteArray(Charsets.ISO_8859_1)
            val inputBytes = reversedString.toByteArray(Charsets.ISO_8859_1)
            val outputStream = ByteArrayOutputStream()
            
            for (i in inputBytes.indices) {
                val keyByte = keyBytes[i % keyBytes.size]
                outputStream.write((inputBytes[i].toInt() xor keyByte.toInt()).toByte().toInt())
            }
            
            outputStream.toString(Charsets.UTF_8.name())
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainSite).document
        val allCategories = mutableListOf<HomePageList>()
        val jogosSection = doc.selectFirst(".session.futebol")
        if (jogosSection != null) {
            val jogosCards = jogosSection.select(".card")
            
            val jogosList = mutableListOf<SearchResponse>()
            for (card in jogosCards) {
                val channelId = card.attr("data-channel")
                if (channelId.isBlank() || channelId in blockedChannels) continue
                
                val nameElement = card.selectFirst("h3") ?: continue
                val gameName = nameElement.text().trim()
                
                val imgElement = card.selectFirst("img") ?: continue
                val imageUrl = imgElement.attr("src").ifEmpty { imgElement.attr("data-src") }
                
                val timeElement = card.selectFirst("span")
                val time = timeElement?.text()?.trim()
                val displayName = if (!time.isNullOrBlank()) "$gameName ($time)" else gameName
                
                val jogoUrl = "$baseUrl/$channelId?source=jogos"
                
                jogosList.add(
                    newLiveSearchResponse(
                        displayName,
                        jogoUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = fixImageUrl(imageUrl)
                    }
                )
            }
            
            if (jogosList.isNotEmpty()) {
                allCategories.add(HomePageList("📺 Jogos de Hoje", jogosList, isHorizontalImages = true))
            }
        }
        
        val categories = doc.select(".categorie")
        
        for (category in categories) {
            val titleElement = category.selectFirst(".title") ?: continue
            val categoryTitle = titleElement.text().trim()
            
            val channelList = mutableListOf<SearchResponse>()
            val cards = category.select(".card")
            
            for (card in cards) {
                val channelId = card.attr("data-channel")
                if (channelId.isBlank() || channelId in blockedChannels) continue
                
                val nameElement = card.selectFirst("h3") ?: continue
                val channelName = nameElement.text().trim()
                
                val imgElement = card.selectFirst("img") ?: continue
                val imageUrl = imgElement.attr("src").ifEmpty { imgElement.attr("data-src") }
                
                val canalUrl = "$baseUrl/$channelId"
                
                channelList.add(
                    newLiveSearchResponse(
                        channelName,
                        canalUrl,
                        TvType.Live
                    ) {
                        this.posterUrl = fixImageUrl(imageUrl)
                    }
                )
            }
            
            if (channelList.isNotEmpty()) {
                allCategories.add(HomePageList(categoryTitle, channelList, isHorizontalImages = true))
            }
        }
        
        if (allCategories.isEmpty()) {
            throw ErrorLoadingException("Nenhum canal encontrado.")
        }
        
        return newHomePageResponse(allCategories, hasNext = false)
    }

    override suspend fun load(url: String): LoadResponse {
        val isFromJogos = url.contains("source=jogos")
        val cleanUrl = url.replace("?source=jogos", "")
        
        val channelId = cleanUrl.substringAfterLast("/")
        if (channelId in blockedChannels) {
            throw ErrorLoadingException("Canal não disponível")
        }
        
        val mainPage = app.get(mainSite).document
        val card = mainPage.selectFirst(".card[data-channel=\"$channelId\"]") ?: return newMovieLoadResponse(
            "Canal $channelId",
            cleanUrl,
            TvType.Live,
            cleanUrl
        ) {
            this.posterUrl = "https://embedtv.best/assets/icon.png"
        }
        
        val name = card.selectFirst("h3")?.text()?.trim() ?: "Canal $channelId"
        val img = card.selectFirst("img")?.let { img ->
            img.attr("src").ifEmpty { img.attr("data-src") }
        } ?: "https://embedtv.best/assets/icon.png"
        
        val time = card.selectFirst("span")?.text()?.trim()
        val displayName = if (!time.isNullOrBlank() && isFromJogos) "$name ($time)" else name

        return newMovieLoadResponse(
            displayName,
            cleanUrl,
            TvType.Live,
            cleanUrl
        ) {
            this.posterUrl = fixImageUrl(img)
            this.plot = "Assista $displayName ao vivo no EmbedTv"
        }
    }

    override suspend fun search(query: String): List<SearchResponse>? {
        val doc = app.get(mainSite).document
        val allCards = doc.select(".card")
        val results = mutableListOf<SearchResponse>()
        
        for (card in allCards) {
            val channelId = card.attr("data-channel")
            if (channelId.isBlank() || channelId in blockedChannels) continue
            
            val nameElement = card.selectFirst("h3") ?: continue
            val channelName = nameElement.text().trim()
            
            if (!channelName.contains(query, ignoreCase = true)) continue
            
            val imgElement = card.selectFirst("img") ?: continue
            val imageUrl = imgElement.attr("src").ifEmpty { imgElement.attr("data-src") }
            
            val timeElement = card.selectFirst("span")
            val hasHorario = timeElement != null
            
            val finalUrl = if (hasHorario) {
                "$baseUrl/$channelId?source=jogos"
            } else {
                "$baseUrl/$channelId"
            }
            
            val time = timeElement?.text()?.trim()
            val displayName = if (!time.isNullOrBlank()) "$channelName ($time)" else channelName
            
            results.add(
                newLiveSearchResponse(
                    displayName,
                    finalUrl,
                    TvType.Live
                ) {
                    this.posterUrl = fixImageUrl(imageUrl)
                }
            )
        }
        
        return results
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = data.split("?")[0]
        val channelUrl = cleanUrl.ifEmpty { return false }
        
        try {
            val doc = app.get(channelUrl).document
            val html = doc.html()
            
            val encodedPattern = Regex("""dc\("([A-Za-z0-9+/=]+)"\)""")
            val encodedMatch = encodedPattern.find(html)
            val encodedString = encodedMatch?.groupValues?.get(1)
            
            var finalUrl: String? = null
            
            if (encodedString != null) {
                val tokenUrl = decryptStreamUrl(encodedString)
                
                if (tokenUrl != null) {
                    if (tokenUrl.endsWith(".txt", ignoreCase = true)) {
                        val testResponse = app.get(tokenUrl, headers = mapOf(
                            "User-Agent" to USER_AGENT
                        )).text
                        
                        if (testResponse.contains("#EXTM3U")) {
                            finalUrl = tokenUrl
                        }
                    } else {
                        finalUrl = tokenUrl
                    }
                }
            }
            
            if (finalUrl == null) {
                val directPattern = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
                finalUrl = directPattern.find(html)?.value
            }
            
            if (finalUrl == null) return false
            
            val headers = mapOf(
                "referer" to baseUrl,
                "origin" to baseUrl,
                "user-agent" to USER_AGENT,
                "accept" to "*/*"
            )
            
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "EmbedTv Live",
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = baseUrl
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                }
            )
            
            return true
            
        } catch (e: Exception) {
            return false
        }
    }
}
