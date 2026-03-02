package com.Goyabu

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.jsoup.Jsoup
import kotlinx.coroutines.delay

object GoyabuExtractor {
    private val itagQualityMap = mapOf(
        18 to 360, 22 to 720, 37 to 1080, 59 to 480,
        133 to 240, 134 to 360, 135 to 480, 136 to 720,
        137 to 1080, 160 to 144, 242 to 240, 243 to 360,
        244 to 480, 247 to 720, 248 to 1080
    )

    suspend fun extractVideoLinks(
        url: String,
        name: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "https://goyabu.io/"
                )
            )

            val html = response.text
            val doc = Jsoup.parse(html)
            
            val token = extractBloggerToken(doc)
            
            if (token == null) {
                return false
            }
            
            val bloggerUrl = "https://www.blogger.com/video.g?token=$token"
            
            val bloggerResponse = app.get(
                bloggerUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
                    "Referer" to "https://goyabu.io/"
                )
            )
            
            val bloggerHtml = bloggerResponse.text
            val (f_sid, bl) = extractBloggerParams(bloggerHtml)
            
            delay(500)
            
            val videos = callBloggerBatchApi(token, f_sid, bl)
            
            if (videos.isEmpty()) {
                return false
            }
            
            videos.forEach { (videoUrl, itag) ->
                val quality = itagQualityMap[itag] ?: 360
                val qualityLabel = when(quality) {
                    1080 -> "FHD"
                    720 -> "HD"
                    480 -> "SD"
                    else -> "SD"
                }
                
                val urlDecodificada = decodeUrl(videoUrl)
                val urlLimpa = urlDecodificada.replace("\\&", "&")
                val urlFinal = if (urlLimpa.endsWith("\\")) {
                    urlLimpa.dropLast(1)
                } else {
                    urlLimpa
                }
                
                callback(
                    newExtractorLink(
                        source = "Goyabu",
                        name = "$name ($qualityLabel)",
                        url = urlFinal,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://www.blogger.com/"
                        this.quality = quality
                        this.headers = mapOf(
                            "Referer" to "https://www.blogger.com/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        )
                    }
                )
            }
            
            true
            
        } catch (e: Exception) {
            false
        }
    }
    
    private fun extractBloggerToken(doc: org.jsoup.nodes.Document): String? {
        val iframe = doc.selectFirst("iframe[src*='blogger.com/video.g']")
        if (iframe != null) {
            val src = iframe.attr("src")
            val pattern = """token=([a-zA-Z0-9_\-]+)""".toRegex()
            val match = pattern.find(src)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        val pattern = """video\.g\?token=([a-zA-Z0-9_\-]+)""".toRegex()
        doc.select("script").forEach { script ->
            pattern.find(script.html())?.let {
                return it.groupValues[1]
            }
        }
        
        return null
    }
    
    private fun extractBloggerParams(html: String): Pair<String, String> {
        var f_sid = "-7535563745894756252"
        var bl = "boq_bloggeruiserver_20260223.02_p0"
        
        val wizPattern = """window\.WIZ_global_data\s*=\s*\{([^}]+)\}""".toRegex()
        val wizMatch = wizPattern.find(html)
        
        if (wizMatch != null) {
            val wizData = wizMatch.groupValues[1]
            
            val sidPattern = """"FdrFJe":"([^"]+)"""".toRegex()
            sidPattern.find(wizData)?.let {
                f_sid = it.groupValues[1]
            }
            
            val blPattern = """"cfb2h":"([^"]+)"""".toRegex()
            blPattern.find(wizData)?.let {
                bl = it.groupValues[1]
            }
        }
        
        return Pair(f_sid, bl)
    }
    
    private suspend fun callBloggerBatchApi(
        token: String,
        f_sid: String,
        bl: String
    ): List<Pair<String, Int>> {
        
        val apiUrl = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute"
        val reqid = (10000..99999).random()
        
        val headers = mapOf(
            "authority" to "www.blogger.com",
            "accept" to "*/*",
            "content-type" to "application/x-www-form-urlencoded;charset=UTF-8",
            "origin" to "https://www.blogger.com",
            "referer" to "https://www.blogger.com/",
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36",
            "x-same-domain" to "1"
        )
        
        val urlWithParams = "$apiUrl?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$f_sid&bl=$bl&hl=pt-BR&_reqid=$reqid&rt=c"
        
        val body = mapOf(
            "f.req" to "%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22%2Cnull%2C%22generic%22%5D%5D%5D"
        )
        
        val response = app.post(
            url = urlWithParams,
            headers = headers,
            data = body
        )
        
        return extractVideoUrlsFromResponse(response.text)
    }
    
    private fun extractVideoUrlsFromResponse(response: String): List<Pair<String, Int>> {
        val videos = mutableListOf<Pair<String, Int>>()
        
        val jsonData = extractGoogleJson(response)
        val sources = listOf(response, jsonData).distinct()
        
        val patterns = listOf(
            """https?:\\/\\/[^"\\]+?\.googlevideo\.com\\/[^"\\]+?videoplayback[^"\\]*""".toRegex(),
            """https?:\\?/\\?/[^"\\]+?\.googlevideo\.com\\?/[^"\\]+?videoplayback[^"\\]*""".toRegex(),
            """https?://[^"'\s]+?\.googlevideo\.com/[^"'\s]+?videoplayback[^"'\s]*""".toRegex(),
            """googlevideo\.com/[^"'\s]+?videoplayback[^"'\s]*""".toRegex()
        )
        
        for (source in sources) {
            for (pattern in patterns) {
                val matches = pattern.findAll(source)
                for (match in matches) {
                    var url = match.value
                    if (!url.startsWith("http")) {
                        url = "https://$url"
                    }
                    url = decodeUrl(url)
                    val itag = extractItagFromUrl(url)
                    if (!videos.any { it.first == url }) {
                        videos.add(Pair(url, itag))
                    }
                }
            }
        }
        
        if (videos.isEmpty()) {
            val googleVideoIndices = response.indicesOf("googlevideo")
            val urlStartPattern = """https?://""".toRegex()
            for (match in urlStartPattern.findAll(response)) {
                val start = match.range.first
                val end = response.indexOf('"', start)
                if (end > start) {
                    val url = response.substring(start, end)
                    if ("googlevideo" in url && "videoplayback" in url) {
                        val decodedUrl = decodeUrl(url)
                        val itag = extractItagFromUrl(decodedUrl)
                        if (!videos.any { it.first == decodedUrl }) {
                            videos.add(Pair(decodedUrl, itag))
                        }
                    }
                }
            }
        }
        
        val qualityOrder = listOf(37, 22, 18, 59)
        return videos
            .distinctBy { it.second }
            .sortedBy { qualityOrder.indexOf(it.second) }
    }
    
    private fun extractGoogleJson(response: String): String {
        try {
            var data = response.replace(Regex("""^\)\]\}'\s*\n?"""), "")
            val pattern = """\[\s*\[\s*"wrb\.fr"\s*,\s*"[^"]*"\s*,\s*"(.+?)"\s*\]""".toRegex(RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(data)
            
            if (match != null) {
                var jsonStr = match.groupValues[1]
                jsonStr = jsonStr.replace("\\\"", "\"")
                jsonStr = jsonStr.replace("\\\\", "\\")
                jsonStr = decodeUnicodeEscapes(jsonStr)
                return jsonStr
            }
        } catch (e: Exception) {
        }
        return response
    }
    
    private fun decodeUrl(url: String): String {
        var decoded = url
        decoded = decodeUnicodeEscapes(decoded)
        decoded = decoded.replace("\\/", "/")
        decoded = decoded.replace("\\\\", "\\")
        decoded = decoded.replace("\\=", "=")
        decoded = decoded.replace("\\&", "&")
        if (decoded.endsWith("\\")) {
            decoded = decoded.dropLast(1)
        }
        decoded = decoded.trim('"')
        return decoded
    }
    
    private fun decodeUnicodeEscapes(text: String): String {
        var result = text
        val pattern = """\\u([0-9a-fA-F]{4})""".toRegex()
        result = pattern.replace(result) { matchResult ->
            val hexCode = matchResult.groupValues[1]
            try {
                hexCode.toInt(16).toChar().toString()
            } catch (e: Exception) {
                "?"
            }
        }
        return result
    }
    
    private fun extractItagFromUrl(url: String): Int {
        val patterns = listOf(
            """itag[=?&](\d+)""".toRegex(),
            """itag%3D(\d+)""".toRegex(),
            """itag\\u003d(\d+)""".toRegex()
        )
        
        for (pattern in patterns) {
            pattern.find(url)?.let {
                return it.groupValues[1].toIntOrNull() ?: 18
            }
        }
        
        return when {
            "itag=22" in url || "itag%3D22" in url || "itag\\u003d22" in url -> 22
            "itag=18" in url || "itag%3D18" in url || "itag\\u003d18" in url -> 18
            "itag=37" in url || "itag%3D37" in url || "itag\\u003d37" in url -> 37
            "itag=59" in url || "itag%3D59" in url || "itag\\u003d59" in url -> 59
            else -> 18
        }
    }
    
    private fun String.indicesOf(substr: String, ignoreCase: Boolean = true): List<Int> {
        val indices = mutableListOf<Int>()
        var index = 0
        while (index < length) {
            index = indexOf(substr, index, ignoreCase)
            if (index < 0) break
            indices.add(index)
            index += substr.length
        }
        return indices
    }
}