package com.SuperFlix.extractors

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class FembedExtractor : ExtractorApi() {
    override val name = "Fembed"
    override val mainUrl = "https://fembed.sx"
    
    // Domínios suportados
    private val supportedDomains = listOf(
        "fembed.sx",
        "www.fembed.com",
        "fembed.to",
        "feurl.com",
        "fcdn.stream",
        "femax20.com",
        "fembeder.com",
        "fembed.net",
        "fembad.org",
        "femoload.xyz",
        "fembed-hd.com",
        "vanfem.com",
        "24hd.club",
        "vcdn.io",
        "asianclub.tv",
        "embedsito.com"
    )
    
    // Tipos MIME suportados
    private val supportedMimes = listOf(
        "video/mp4",
        "video/webm",
        "application/x-mpegURL",
        "application/vnd.apple.mpegurl"
    )
    
    override suspend fun getUrl(url: String, referer: String?): List<ExtractorLink>? {
        val links = mutableListOf<ExtractorLink>()
        
        try {
            // Extrair ID do vídeo
            val videoId = extractVideoId(url)
            if (videoId.isBlank()) return null
            
            // Determinar domínio base
            val domain = getDomainFromUrl(url)
            
            // Tentar diferentes APIs
            val apiUrls = listOf(
                "https://$domain/api/source/$videoId",
                "https://www.$domain/api/source/$videoId",
                "https://api.$domain/api/source/$videoId"
            )
            
            for (apiUrl in apiUrls) {
                try {
                    println("FembedExtractor: Tentando API: $apiUrl")
                    
                    val response = app.post(
                        apiUrl,
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "Referer" to "https://$domain/",
                            "X-Requested-With" to "XMLHttpRequest",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        ),
                        data = mapOf(
                            "r" to "",
                            "d" to domain
                        )
                    )
                    
                    val json = response.parsedSafe<FembedResponse>()
                    if (json?.success == true && json.data != null) {
                        println("FembedExtractor: Encontrados ${json.data.size} streams")
                        
                        json.data.forEach { stream ->
                            val fileUrl = stream.file ?: return@forEach
                            val label = stream.label ?: "Unknown"
                            
                            // Verificar se é um URL válido
                            if (isValidUrl(fileUrl)) {
                                val quality = parseQuality(label)
                                val isM3u8 = fileUrl.contains(".m3u8") || fileUrl.contains("master.m3u8")
                                
                                links.add(
                                    newExtractorLink(
                                        url = fixUrl(fileUrl),
                                        source = name,
                                        name = label,
                                        quality = quality.value,
                                        referer = "https://$domain/",
                                        isM3u8 = isM3u8
                                    ) ?: return@forEach
                                )
                                
                                println("FembedExtractor: Adicionado: $label ($quality)")
                            }
                        }
                        
                        if (links.isNotEmpty()) {
                            return links
                        }
                    }
                } catch (e: Exception) {
                    println("FembedExtractor: Erro na API $apiUrl: ${e.message}")
                }
            }
            
            // Se as APIs falharem, tentar extrair do HTML da página
            return tryExtractFromPage(url) ?: links.takeIf { it.isNotEmpty() }
            
        } catch (e: Exception) {
            println("FembedExtractor: Erro geral: ${e.message}")
            return null
        }
    }
    
    private fun extractVideoId(url: String): String {
        // Padrões: 
        // https://fembed.sx/v/304115/1-1
        // https://fembed.sx/e/304115
        // /v/304115/1-1
        // 304115/1-1
        // 304115
        
        val patterns = listOf(
            Regex("""(?:fembed|feurl|fcdn|femax20|fembeder|fembed-hd|vanfem|24hd|vcdn|asianclub|embedsito)\.(?:sx|com|to|stream|org|xyz|tv|club|io)/(?:e|v|f)/([a-zA-Z0-9]+(?:/[a-zA-Z0-9\-]+)?)""", RegexOption.IGNORE_CASE),
            Regex("""/(?:e|v|f)/([a-zA-Z0-9]+(?:/[a-zA-Z0-9\-]+)?)"""),
            Regex("""([a-zA-Z0-9]+(?:/[a-zA-Z0-9\-]+)?)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null && match.groupValues.size > 1) {
                val id = match.groupValues[1]
                println("FembedExtractor: ID extraído: $id")
                return id
            }
        }
        
        return ""
    }
    
    private fun getDomainFromUrl(url: String): String {
        val domainPattern = Regex("""https?://([^/]+)""")
        val match = domainPattern.find(url)
        
        if (match != null) {
            val fullDomain = match.groupValues[1]
            // Remover www. se existir
            return fullDomain.replace("www.", "")
        }
        
        // Domínio padrão
        return "fembed.sx"
    }
    
    private fun isValidUrl(url: String): Boolean {
        return url.startsWith("http") && 
               (url.contains(".mp4") || 
                url.contains(".m3u8") || 
                url.contains("video/") ||
                url.contains("stream/"))
    }
    
    private fun parseQuality(label: String): Qualities {
        val labelLower = label.lowercase()
        
        return when {
            labelLower.contains("4k") || labelLower.contains("2160") -> Qualities.P2160
            labelLower.contains("1440") || labelLower.contains("qhd") -> Qualities.P1440
            labelLower.contains("1080") || labelLower.contains("fhd") -> Qualities.P1080
            labelLower.contains("720") || labelLower.contains("hd") -> Qualities.P720
            labelLower.contains("480") -> Qualities.P480
            labelLower.contains("360") -> Qualities.P360
            labelLower.contains("240") -> Qualities.P240
            labelLower.contains("144") -> Qualities.P144
            else -> {
                // Extrair número
                val numMatch = Regex("""(\d+)""").find(labelLower)
                numMatch?.groupValues?.get(1)?.toIntOrNull()?.let { num ->
                    when (num) {
                        in 2160..9999 -> Qualities.P2160
                        in 1440..2159 -> Qualities.P1440
                        in 1080..1439 -> Qualities.P1080
                        in 720..1079 -> Qualities.P720
                        in 480..719 -> Qualities.P480
                        in 360..479 -> Qualities.P360
                        in 240..359 -> Qualities.P240
                        in 144..239 -> Qualities.P144
                        else -> Qualities.Unknown
                    }
                } ?: Qualities.Unknown
            }
        }
    }
    
    private suspend fun tryExtractFromPage(url: String): List<ExtractorLink>? {
        try {
            val response = app.get(url)
            val html = response.text
            
            // Procurar por iframes
            val iframePattern = Regex("""<iframe[^>]+src=["']([^"']+)["']""")
            val iframeMatches = iframePattern.findAll(html)
            
            for (match in iframeMatches) {
                val iframeUrl = match.groupValues[1]
                if (iframeUrl.contains("fembed")) {
                    println("FembedExtractor: Encontrado iframe: $iframeUrl")
                    return getUrl(iframeUrl, url)
                }
            }
            
            // Procurar por scripts com URLs
            val scriptPattern = Regex("""(https?://[^"'\s]+/v/[a-zA-Z0-9]+[^"'\s]*)""")
            val scriptMatches = scriptPattern.findAll(html)
            
            for (match in scriptMatches) {
                val scriptUrl = match.value
                if (scriptUrl.contains("fembed")) {
                    println("FembedExtractor: Encontrado em script: $scriptUrl")
                    return getUrl(scriptUrl, url)
                }
            }
            
        } catch (e: Exception) {
            println("FembedExtractor: Erro ao extrair da página: ${e.message}")
        }
        
        return null
    }
    
    // Classes para parsing JSON
    data class FembedResponse(
        @JsonProperty("success") val success: Boolean = false,
        @JsonProperty("data") val data: List<FembedStream>? = null
    )
    
    data class FembedStream(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("type") val type: String? = null
    )
}
