package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

/**
 * Extractor para Filemoon e Fembed
 * Suporta URLs:
 * - https://filemoon.in/e/{id}
 * - https://fembed.sx/e/{id}
 * - https://fembed.sx/v/{id}
 */
class Filemoon : ExtractorApi() {
    override val name = "Filemoon"
    override val mainUrl = "https://filemoon.in"
    override val requiresReferer = true

 // ESSENCIAL: Diz ao Cloudstream quais URLs este Extractor suporta
     
    // FilemoonExtractor.kt - NOVO método getUrl

override suspend fun getUrl(
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    println("FilemoonExtractor: getUrl - INÍCIO")
    val videoId = extractVideoId(url)
    if (videoId.isEmpty()) return

    // 1. URLs
    // A URL original do embed (ex: https://fembed.sx/e/1441563) é o REFERER
    val playerEmbedUrl = if (url.contains("fembed.sx")) {
        "https://fembed.sx/e/$videoId"
    } else {
        "https://filemoon.in/e/$videoId"
    }
    
    // O endpoint da API (ex: https://fembed.sx/api.php?s=1441563&c=)
    // O Filemoon/Fembed usa o mesmo ID no 's' e no Referer
    val apiUrl = "https://fembed.sx/api.php?s=$videoId&c="
    val requestReferer = referer ?: "https://fembed.sx/" // Usa o referer do Cloudstream ou um default

    println("FilemoonExtractor: POST API URL: $apiUrl")
    println("FilemoonExtractor: POST Referer Header: $playerEmbedUrl")

    try {
        // 2. CORPO DA REQUISIÇÃO POST (Form Data)
        // O JS que você viu envia: { action: 'getPlayer', lang: 'DUB', key: 'MA==' }
        val body = mapOf(
            "action" to "getPlayer",
            // Nota: O idioma 'DUB' ou 'LEG' é definido pelo SuperFlix. 
            // Para simplificar, vamos assumir DUB, mas você pode parametrizar isso se necessário.
            "lang" to "DUB", 
            "key" to "MA==" // Base64 de "0" (o primeiro servidor)
        )
        
        // 3. HEADERS para o POST
        val postHeaders = getHeaders(apiUrl, playerEmbedUrl).toMutableMap()
        postHeaders["Origin"] = playerEmbedUrl.substringBefore("/e/") // https://fembed.sx

        println("FilemoonExtractor: Fazendo requisição POST para Player HTML")

        // 4. Executar o POST
        val response = app.post(
            apiUrl, 
            headers = postHeaders,
            requestBody = body,
            referer = playerEmbedUrl // Garantir que o app use o referer correto
        )

        if (!response.isSuccessful) {
            println("FilemoonExtractor: ERRO: POST falhou com status ${response.code}")
            return
        }

        val finalPlayerHtml = response.text
        println("FilemoonExtractor: Player HTML obtido (${finalPlayerHtml.length} chars)")
        
        // 5. Extrair M3U8 do HTML de resposta
        val m3u8Url = extractM3u8Url(finalPlayerHtml)

        if (m3u8Url != null) {
            println("FilemoonExtractor: M3U8 FINAL encontrado: $m3u8Url")

            // 6. Gerar Streams M3U8
            val links = M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8Url,
                // O referer para o M3U8 é a URL da API que o retornou, ou o playerEmbedUrl.
                referer = playerEmbedUrl,
                headers = getHeaders(m3u8Url, playerEmbedUrl)
            )

            println("FilemoonExtractor: ${links.size} link(s) gerado(s)")
            links.forEach(callback)
            return
        } else {
            println("FilemoonExtractor: ERRO: Não encontrou URL M3U8 no HTML FINAL do POST.")
        }

    } catch (e: Exception) {
        println("FilemoonExtractor: EXCEÇÃO: ${e.message}")
        e.printStackTrace()
    }
}
