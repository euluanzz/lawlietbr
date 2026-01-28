import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.SubtitleFile
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
@@ -132,7 +133,7 @@ class Doramogo : MainAPI() {

// Extrair número do episódio
val episodeNumber = extractEpisodeNumberFromText(episodeText)
                        ?: extractEpisodeNumberFromUrlString(href)
?: 1

println("  Número extraído: $episodeNumber")
@@ -339,7 +340,7 @@ class Doramogo : MainAPI() {
else -> TvType.TvSeries
}

        val year = extractYearFromUrlString(href)

if (type == TvType.Movie) {
return newMovieSearchResponse(title, fixUrl(href), type) { 
@@ -441,7 +442,7 @@ class Doramogo : MainAPI() {
// Ano - TMDB PRIMEIRO
val year = tmdbInfo?.year
?: infoMap["ano"]?.toIntOrNull()
            ?: extractYearFromUrlString(url)
?: title.findYear()

// Gêneros - TMDB PRIMEIRO
@@ -490,7 +491,7 @@ class Doramogo : MainAPI() {
val episodeTitle = episodeItem.selectFirst(".episode-title")?.text()?.trim() ?: "Episódio"

val episodeNumber = extractEpisodeNumberFromEpisodeItem(episodeItem)
                    val seasonNumber = extractSeasonNumberFromUrlString(episodeUrl) ?: 1

episodes.add(newEpisode(episodeUrl) {
this.name = episodeTitle
@@ -504,11 +505,11 @@ class Doramogo : MainAPI() {
if (episodes.isEmpty()) {
// Extrair número do episódio da URL ou título
val episodeNum = episodeNumberFromTitle 
                    ?: extractEpisodeNumberFromUrlString(url) 
?: 1

// Extrair temporada da URL
                val seasonNum = extractSeasonNumberFromUrlString(url) ?: 1

// Criar um episódio com a própria URL
episodes.add(newEpisode(url) {
@@ -568,20 +569,28 @@ class Doramogo : MainAPI() {
callback: (ExtractorLink) -> Unit
): Boolean {
var linksFound = false

val document = app.get(data).document

        // Extrair os URLs proxy dinamicamente da página
        val proxyUrls = extractProxyUrlsFromPage(document)
        val primaryProxy = proxyUrls.primaryUrl
        val fallbackProxy = proxyUrls.fallbackUrl

        // Log para debug
        println("[Doramogo] Primary proxy: $primaryProxy")
        println("[Doramogo] Fallback proxy: $fallbackProxy")

val urlParts = data.split("/")
val slug = urlParts.getOrNull(urlParts.indexOf("series") + 1) 
?: urlParts.getOrNull(urlParts.indexOf("filmes") + 1)
?: return false

        val temporada = extractSeasonNum(data) ?: 1
        val episodio = extractEpisodeNum(data) ?: 1

val isFilme = data.contains("/filmes/")

val streamPath = if (isFilme) {
val pt = slug.first().uppercase()
"$pt/$slug/stream/stream.m3u8?nocache=${System.currentTimeMillis()}"
@@ -591,13 +600,11 @@ class Doramogo : MainAPI() {
val epNum = episodio.toString().padStart(2, '0')
"$pt/$slug/$tempNum-temporada/$epNum/stream.m3u8?nocache=${System.currentTimeMillis()}"
}

        // Construir URLs com os proxies extraídos
        val primaryStreamUrl = "$primaryProxy/$streamPath"
        val fallbackStreamUrl = "$fallbackProxy/$streamPath"

val headers = mapOf(
"accept" to "*/*",
"accept-language" to "pt-BR",
@@ -612,7 +619,7 @@ class Doramogo : MainAPI() {
"sec-fetch-site" to "cross-site",
"user-agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"
)

suspend fun tryAddLink(url: String, name: String): Boolean {
return try {
val testResponse = app.get(
@@ -621,7 +628,7 @@ class Doramogo : MainAPI() {
allowRedirects = true,
timeout = 15
)

if (testResponse.code in 200..299) {
callback(newExtractorLink(name, "Doramogo", url, ExtractorLinkType.M3U8) {
referer = mainUrl
@@ -636,58 +643,40 @@ class Doramogo : MainAPI() {
false
}
}

        // Tentar primeiro com o proxy primário
        if (tryAddLink(primaryStreamUrl, "Doramogo (Primary)")) {
linksFound = true
}

        // Se não funcionar, tentar com fallback
if (!linksFound) {
            if (tryAddLink(fallbackStreamUrl, "Doramogo (Fallback)")) {
linksFound = true
}
}

        // Se ainda não funcionar, tentar extrair URLs diretamente do JavaScript
if (!linksFound) {
            extractM3u8UrlsFromJS(document).forEach { url ->
                if (url.contains(".m3u8")) {
                    callback(newExtractorLink(name, "Doramogo (JS)", url, ExtractorLinkType.M3U8) {
                        referer = mainUrl
                        quality = Qualities.P720.value
                        this.headers = headers
                    })
                    linksFound = true
}
}
}

return linksFound
}

    // ============================
    // FUNÇÕES AUXILIARES - UNICAS
    // ============================

private fun cleanTitle(title: String): String {
return title.replace(Regex("\\s*\\(Legendado\\)", RegexOption.IGNORE_CASE), "")
.replace(Regex("\\s*\\(Dublado\\)", RegexOption.IGNORE_CASE), "")
@@ -697,7 +686,6 @@ class Doramogo : MainAPI() {
.trim()
}

private fun extractEpisodeNumberFromTitle(title: String): Int? {
val patterns = listOf(
Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE),
@@ -718,13 +706,13 @@ class Doramogo : MainAPI() {
return null
}

    private fun extractYearFromUrlString(url: String): Int? {
val pattern = Regex("""/(?:series|filmes)/[^/]+-(\d{4})/""")
val match = pattern.find(url)
return match?.groupValues?.get(1)?.toIntOrNull()
}

    private fun extractEpisodeNumberFromUrlString(url: String): Int? {
val patterns = listOf(
Regex("""episodio-(\d+)""", RegexOption.IGNORE_CASE),
Regex("""ep-(\d+)""", RegexOption.IGNORE_CASE),
@@ -741,6 +729,22 @@ class Doramogo : MainAPI() {
return null
}

    private fun extractSeasonNumberFromUrlString(url: String): Int? {
        val pattern = Regex("""temporada[_-](\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(url)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }
    
    private fun extractSeasonNum(url: String): Int? {
        val pattern = Regex("""/temporada-(\d+)""")
        return pattern.find(url)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractEpisodeNum(url: String): Int? {
        val pattern = Regex("""/episodio-(\d+)""")
        return pattern.find(url)?.groupValues?.get(1)?.toIntOrNull()
    }
    
private fun extractUrlsFromJavaScript(script: String): List<String> {
val urls = mutableListOf<String>()

@@ -842,7 +846,7 @@ class Doramogo : MainAPI() {
}

val cleanTitle = cleanTitle(title)
                    val year = extractYearFromUrlString(href)

val type = when {
href.contains("/filmes/") -> TvType.Movie
@@ -871,6 +875,151 @@ class Doramogo : MainAPI() {

return recommendations.distinctBy { it.url }.take(10)
}

    // Função para extrair proxies - NOME DIFERENTE
    private fun extractProxyUrlsFromPage(document: Document): DoramogoProxyUrls {
        var primaryUrl = ""
        var fallbackUrl = ""
        
        // Método 1: Procurar por const PRIMARY_URL e FALLBACK_URL no JavaScript
        val scriptTags = document.select("script")
        
        for (script in scriptTags) {
            val scriptContent = script.html()
            
            // Procura por const PRIMARY_URL = "..." e const FALLBACK_URL = "..."
            if (scriptContent.contains("const PRIMARY_URL") || scriptContent.contains("const FALLBACK_URL")) {
                // Regex para encontrar PRIMARY_URL = "valor"
                val primaryPattern = Regex("""const\s+PRIMARY_URL\s*=\s*["']([^"']+)["']""")
                val primaryMatch = primaryPattern.find(scriptContent)
                if (primaryMatch != null) {
                    primaryUrl = primaryMatch.groupValues[1]
                }
                
                // Regex para encontrar FALLBACK_URL = "valor"
                val fallbackPattern = Regex("""const\s+FALLBACK_URL\s*=\s*["']([^"']+)["']""")
                val fallbackMatch = fallbackPattern.find(scriptContent)
                if (fallbackMatch != null) {
                    fallbackUrl = fallbackMatch.groupValues[1]
                }
                
                // Se encontrou ambos, sair
                if (primaryUrl.isNotBlank() && fallbackUrl.isNotBlank()) {
                    break
                }
            }
            
            // Método 2: Procurar por urlConfig = { base: "..." }
            if (scriptContent.contains("urlConfig")) {
                val urlConfigPattern = Regex("""urlConfig\s*=\s*\{[^}]+\}""")
                val urlConfigMatch = urlConfigPattern.find(scriptContent)
                
                if (urlConfigMatch != null) {
                    val configContent = urlConfigMatch.value
                    val basePattern = Regex(""""base"\s*:\s*"([^"]+)"""")
                    val baseMatch = basePattern.find(configContent)
                    
                    if (baseMatch != null) {
                        fallbackUrl = baseMatch.groupValues[1]
                    }
                }
            }
        }
        
        // Fallbacks caso não encontre
        if (primaryUrl.isBlank()) {
            // Do HTML atual: "https://proxy-us-east1-outbound-series.doaswin.shop"
            primaryUrl = "https://proxy-us-east1-outbound-series.doaswin.shop"
            println("[Doramogo] Using default primary proxy")
        }
        
        if (fallbackUrl.isBlank()) {
            // Do HTML atual: "https://proxy-us-east1-forks-doramas.doaswin.shop"
            fallbackUrl = "https://proxy-us-east1-forks-doramas.doaswin.shop"
            println("[Doramogo] Using default fallback proxy")
        }
        
        // Garantir que as URLs terminam sem barra
        primaryUrl = primaryUrl.removeSuffix("/")
        fallbackUrl = fallbackUrl.removeSuffix("/")
        
        return DoramogoProxyUrls(primaryUrl, fallbackUrl)
    }

    // Função para extrair M3U8 do JS - NOME DIFERENTE
    private fun extractM3u8UrlsFromJS(document: Document): List<String> {
        val urls = mutableListOf<String>()
        
        val scripts = document.select("script")
        
        for (script in scripts) {
            val scriptContent = script.html()
            
            // Procura por URLs M3U8 no código JavaScript
            val patterns = listOf(
                // Padrão para streams construídas
                Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]"""),
                // Padrão para URLs completas com proxy
                Regex("""['"](https?://proxy-[^'"]+\.m3u8[^'"]*)['"]"""),
                // Padrão para construção de URLs
                Regex("""['"](\w+StreamUrl\s*[=:]\s*[^;]+)['"]""")
            )
            
            for (pattern in patterns) {
                val matches = pattern.findAll(scriptContent)
                matches.forEach { match ->
                    val potentialUrl = match.groupValues[1]
                    
                    // Verificar se é uma URL M3U8 válida
                    if (potentialUrl.contains(".m3u8") && 
                        (potentialUrl.startsWith("http://") || potentialUrl.startsWith("https://"))) {
                        if (!urls.contains(potentialUrl)) {
                            urls.add(potentialUrl)
                        }
                    }
                }
            }
        }
        
        return urls
    }

    private fun extractEpisodeNumberFromEpisodeItem(episodeItem: Element): Int {
        val episodeNumberSpan = episodeItem.selectFirst(".dorama-one-episode-number")
        episodeNumberSpan?.text()?.let { spanText ->
            val match = Regex("""EP\s*(\d+)""", RegexOption.IGNORE_CASE).find(spanText)
            if (match != null) {
                return match.groupValues[1].toIntOrNull() ?: 1
            }
        }
        
        val episodeTitle = episodeItem.selectFirst(".episode-title")?.text() ?: ""
        val pattern = Regex("""Episódio\s*(\d+)|Episode\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(episodeTitle)
        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: 1
    }
    
    private fun extractSeasonNumber(seasonTitle: String): Int {
        val pattern = Regex("""(\d+)°\s*Temporada|Temporada\s*(\d+)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(seasonTitle)
        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: match?.groupValues?.get(2)?.toIntOrNull()
            ?: 1
    }
    
    private fun String.findYear(): Int? {
        val pattern = Regex("""\b(19\d{2}|20\d{2})\b""")
        return pattern.find(this)?.value?.toIntOrNull()
    }
    
    private fun String?.parseDuration(): Int? {
        if (this.isNullOrBlank()) return null
        val pattern = Regex("""(\d+)\s*(min|minutes|minutos)""", RegexOption.IGNORE_CASE)
        val match = pattern.find(this)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

// === FUNÇÕES INTELIGENTES DO TMDB PARA DORAMAS ===

@@ -1189,8 +1338,15 @@ class Doramogo : MainAPI() {
?.firstOrNull()
?.let { (key, _, _) -> "https://www.youtube.com/watch?v=$key" }
}

    // ====================
    // CLASSES INTERNAS
    // ====================

    private data class DoramogoProxyUrls(
        val primaryUrl: String,
        val fallbackUrl: String
    )

private data class TMDBInfo(
val id: Int,
@@ -1290,49 +1446,4 @@ class Doramogo : MainAPI() {
val info: TMDBInfo,
val score: DoramaScore
)
}