package com.PobreFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.jsoup.nodes.Element
import java.net.URLEncoder
import com.PobreFlix.extractor.PobreFlixExtractor

@CloudstreamPlugin
class PobreFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PobreFlix())
    }
}

class PobreFlix : MainAPI() {
    override var mainUrl = "https://lospobreflix.site"
    override var name = "PobreFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/pesquisar"
        
        private val MAIN_SECTIONS = listOf(
            "" to "Em Alta",
            "/filmes" to "Filmes",
            "/series" to "Séries",
            "/animes" to "Animes",
            "/doramas" to "Doramas"
        )
    }

    override val mainPage = mainPageOf(
        *MAIN_SECTIONS.map { (path, name) ->
            if (path.isEmpty()) mainUrl to name
            else "$mainUrl$path" to name
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        var url = request.data
        
        if (request.name != "Em Alta" && page > 1) {
            url = if (url.contains("?")) "$url&page=$page" else "$url?page=$page"
        }
        
        if (request.name == "Em Alta") {
            val document = app.get(url).document
            val elements = document.select(".swiper_top10_home .swiper-slide, .top-10-items .item")
            val items = elements.mapNotNull { it.toSearchResult() }
            return newHomePageResponse(request.name, items, hasNext = false)
        }
        
        val document = app.get(url).document
        val elements = document.select("article.group, .group\\/card, .grid article")
        val items = elements.mapNotNull { it.toSearchResult() }
        val hasNextPage = document.select("a[href*='?page=${page + 1}'], a[href*='&page=${page + 1}'], a:contains(Próxima)").isNotEmpty()
        
        return newHomePageResponse(request.name, items, hasNext = hasNextPage)
    }
    
    private fun Element.toSearchResult(): SearchResponse? {
        val linkElement = selectFirst("a[href*='/filme/'], a[href*='/serie/'], a[href*='/anime/'], a[href*='/dorama/']") 
            ?: selectFirst("a") ?: return null
        
        var href = linkElement.attr("href")
        if (href.isBlank()) return null
        if (!href.startsWith("http")) {
            href = if (href.startsWith("/")) "$mainUrl$href" else "$mainUrl/$href"
        }
        
        val imgElement = selectFirst("img")
        var poster: String? = null
        
        if (imgElement != null) {
            poster = imgElement.attr("src")
            if (poster.isNullOrBlank()) poster = imgElement.attr("data-src")
            if (!poster.isNullOrBlank()) {
                poster = fixImageUrl(poster)
            }
        }
        
        var title = selectFirst("h3")?.text()
        if (title.isNullOrBlank()) title = selectFirst(".line-clamp-1")?.text()
        if (title.isNullOrBlank()) title = imgElement?.attr("alt")
        if (title.isNullOrBlank()) return null
        
        var year: Int? = null
        val yearSpan = selectFirst(".text-white\\/70.text-xs, .text-xs")
        yearSpan?.text()?.let { 
            if (it.matches(Regex("\\d{4}"))) {
                year = it.toIntOrNull()
            }
        }
        
        if (year == null) {
            val yearMatch = Regex("\\((\\d{4})\\)").find(title)
            if (yearMatch != null) {
                year = yearMatch.groupValues[1].toIntOrNull()
                title = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
            }
        }
        
        val finalTitle = title.trim()
        
        var scoreValue: Float? = null
        val scoreTextElement = selectFirst(".absolute.top-2.right-2 .text-\\[10px\\].font-bold, .absolute.top-2.right-2 text")
        if (scoreTextElement != null) {
            val scoreText = scoreTextElement.text().trim()
            val scoreMatch = Regex("(\\d+)").find(scoreText)
            if (scoreMatch != null) {
                val percent = scoreMatch.groupValues[1].toFloatOrNull()
                scoreValue = percent?.let { it / 10 }
            }
        }
        
        if (scoreValue == null) {
            val dashArray = selectFirst("path[stroke-dasharray]")?.attr("stroke-dasharray") ?: ""
            if (dashArray.isNotBlank()) {
                val dashMatch = Regex("(\\d+(?:\\.\\d+)?)").find(dashArray)
                if (dashMatch != null) {
                    val percent = dashMatch.groupValues[1].toFloatOrNull()
                    if (percent != null && percent <= 100) {
                        scoreValue = percent / 10
                    }
                }
            }
        }
        
        val isAnime = href.contains("/anime/")
        val isSerie = href.contains("/serie/") || href.contains("/dorama/")
        
        return when {
            isAnime -> newAnimeSearchResponse(finalTitle, href, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
                if (scoreValue != null) this.score = Score.from10(scoreValue)
            }
            isSerie -> newTvSeriesSearchResponse(finalTitle, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.year = year
                if (scoreValue != null) this.score = Score.from10(scoreValue)
            }
            else -> newMovieSearchResponse(finalTitle, href, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
                if (scoreValue != null) this.score = Score.from10(scoreValue)
            }
        }
    }

    
    override suspend fun search(query: String): List<SearchResponse> {
    if (query.length < 2) return emptyList()
    
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    val searchUrl = "$mainUrl$SEARCH_PATH?s=$encodedQuery"
    
    return try {
        val document = app.get(searchUrl).document
        document.select("article.group, .grid article, .group\\/card")
            .mapNotNull { element ->
                try {
                    element.toSearchResult()
                } catch (e: Exception) {
                    null
                }
            }
    } catch (e: Exception) {
        emptyList()
    }
}

override suspend fun search(query: String, page: Int): SearchResponseList? {
    if (query.length < 2) return null
    
    val encodedQuery = URLEncoder.encode(query, "UTF-8")
    val searchUrl = if (page > 1) {
        "$mainUrl$SEARCH_PATH?s=$encodedQuery&page=$page"
    } else {
        "$mainUrl$SEARCH_PATH?s=$encodedQuery"
    }
    
    return try {
        val document = app.get(searchUrl).document
        val results = document.select("article.group, .grid article, .group\\/card")
            .mapNotNull { element ->
                try {
                    element.toSearchResult()
                } catch (e: Exception) {
                    null
                }
            }
        
        var hasNextPage = false
        if (results.isNotEmpty()) {
            val nextPageUrl = "$mainUrl$SEARCH_PATH?s=$encodedQuery&page=${page + 1}"
            try {
                val nextPageDoc = app.get(nextPageUrl).document
                val nextPageResults = nextPageDoc.select("article.group, .grid article, .group\\/card")
                hasNextPage = nextPageResults.isNotEmpty()
            } catch (e: Exception) {
                hasNextPage = false
            }
        }
        
        results.toNewSearchResponseList(hasNextPage)
    } catch (e: Exception) {
        null
    }
}

    override suspend fun load(url: String): LoadResponse? {
        try {
            val document = app.get(url).document

            val titleElement = document.selectFirst("h1.text-3xl.text-lead.font-bold")
            val title = titleElement?.text()?.trim() ?: return null

            var year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
            val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()

            val isAnime = url.contains("/anime/")
            val isSerie = url.contains("/serie/") || url.contains("/dorama/")

            var poster = document.selectFirst("meta[property='og:image']")?.attr("content")
            if (poster.isNullOrBlank()) {
                poster = document.selectFirst("img.w-full.aspect-\\[2\\/3\\]")?.attr("src")
            }
            poster = fixImageUrl(poster)
            
            var backdrop: String? = null
            
            backdrop = document.selectFirst("#movie-player-container")?.attr("data-backdrop")
            
            if (backdrop.isNullOrBlank()) {
                backdrop = document.selectFirst("#movie-player-container img")?.attr("src")
            }
            
            if (backdrop.isNullOrBlank()) {
                backdrop = document.selectFirst("img[alt*='backdrop']")?.attr("src")
            }
            
            backdrop = fixImageUrl(backdrop)
            
            var rating: Float? = null
            
            val ratingSvg = document.selectFirst(".inline-flex.items-center.gap-3.rounded-2xl .text-\\[12px\\].font-extrabold")
            if (ratingSvg != null) {
                val ratingText = ratingSvg.text().trim()
                rating = if (ratingText.contains("%")) {
                    ratingText.replace("%", "").trim().toFloatOrNull()?.let { it / 10 }
                } else {
                    ratingText.toFloatOrNull()
                }
            }
            
            if (rating == null) {
                val infoBar = document.selectFirst(".flex.gap-2.text-sm.flex-wrap.items-center")
                if (infoBar != null) {
                    val ratingSpan = infoBar.selectFirst(".text-lead")
                    if (ratingSpan != null) {
                        rating = ratingSpan.text().trim().toFloatOrNull()
                    }
                }
            }
            
            var duration: Int? = null
            if (!isAnime && !isSerie) {
                val infoBar = document.selectFirst(".flex.gap-2.text-sm.flex-wrap.items-center")
                if (infoBar != null) {
                    val durationSpan = infoBar.select("span").lastOrNull()
                    if (durationSpan != null) {
                        val durationText = durationSpan.text()
                        duration = parseDuration(durationText)
                    }
                }
            }
            
            if (year == null) {
                val infoBar = document.selectFirst(".flex.gap-2.text-sm.flex-wrap.items-center")
                if (infoBar != null) {
                    val yearSpan = infoBar.select("span").firstOrNull { it.text().matches(Regex("\\d{4}")) }
                    if (yearSpan != null) {
                        year = yearSpan.text().toIntOrNull()
                    }
                }
            }
            
            var synopsis = document.selectFirst(".text-slate-700.dark\\:text-slate-200.md\\:text-lg")?.text()?.trim()
            if (synopsis.isNullOrBlank()) {
                synopsis = document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            }
            synopsis = synopsis?.replace(Regex("\\|.*$"), "")?.trim()
            
            val tags = document.select(".flex.flex-wrap.gap-2.pt-4 a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .takeIf { it.isNotEmpty() }
            
            val cast = document.select("#cast-section .swiper-slide, .cast-swiper .swiper-slide")
                .mapNotNull { element ->
                    try {
                        val name = element.selectFirst(".text-sm.font-bold")?.text()?.trim() ?: return@mapNotNull null
                        val imageUrl = element.selectFirst("img")?.attr("src")?.let { fixImageUrl(it) }
                        Actor(name = name, image = imageUrl)
                    } catch (e: Exception) { null }
                }
                .takeIf { it.isNotEmpty() }
            
            var trailerUrl: String? = null
            try {
                val scripts = document.select("script")
                for (script in scripts) {
                    val data = script.data()
                    if (data.contains("__trailerKeys")) {
                        val pattern = Regex("""__trailerKeys\s*=\s*\[([^\]]+)\]""")
                        val match = pattern.find(data)
                        if (match != null) {
                            val keysString = match.groupValues[1]
                            val keyPattern = Regex("[\"']([^\"']+)[\"']")
                            val keyMatch = keyPattern.find(keysString)
                            if (keyMatch != null) {
                                val trailerKey = keyMatch.groupValues[1]
                                trailerUrl = "https://www.youtube.com/watch?v=$trailerKey"
                                break
                            }
                        }
                    }
                }
                
                if (trailerUrl == null) {
                    val trailerSection = document.selectFirst("#trailers-section .trailer-thumb-btn")
                    if (trailerSection != null) {
                        val trailerKey = trailerSection.attr("data-yt")
                        if (trailerKey.isNotBlank()) {
                            trailerUrl = "https://www.youtube.com/watch?v=$trailerKey"
                        }
                    }
                }
                
                if (trailerUrl == null) {
                    val youtubeLink = document.selectFirst("a[href*='youtube.com/watch']")?.attr("href")
                    if (youtubeLink != null && youtubeLink.isNotBlank()) {
                        trailerUrl = youtubeLink
                    }
                }
            } catch (e: Exception) { }
            
            val recommendations = document.select("#relatedSection .swiper-slide a, .related-swiper .swiper-slide a")
                .mapNotNull { element ->
                    try {
                        val recUrl = element.attr("href")
                        if (recUrl.isBlank()) return@mapNotNull null
                        
                        var recImg: String? = null
                        val imgElement = element.selectFirst("img")
                        if (imgElement != null) {
                            recImg = imgElement.attr("src")
                            if (recImg.isNullOrBlank()) recImg = imgElement.attr("data-src")
                            if (!recImg.isNullOrBlank()) {
                                recImg = fixImageUrl(recImg)
                            }
                        }
                        
                        val recTitle = element.selectFirst("h3, .text-white.font-bold")?.text()?.trim()
                            ?: return@mapNotNull null
                        
                        val recIsAnime = recUrl.contains("/anime/")
                        val recIsSerie = recUrl.contains("/serie/") || recUrl.contains("/dorama/")
                        val recYear = element.selectFirst(".text-white\\/70.text-xs")?.text()?.toIntOrNull()
                        
                        var recScore: Float? = null
                        val scoreText = element.selectFirst(".absolute.top-2.right-2 .text-\\[10px\\].font-bold, .absolute.top-2.right-2 text")?.text()
                        if (!scoreText.isNullOrBlank()) {
                            val percent = scoreText.replace("%", "").trim().toFloatOrNull()
                            recScore = percent?.let { it / 10 }
                        }
                        
                        when {
                            recIsAnime -> newAnimeSearchResponse(recTitle, fixUrl(recUrl), TvType.Anime) {
                                this.posterUrl = recImg
                                this.year = recYear
                                if (recScore != null) this.score = Score.from10(recScore)
                            }
                            recIsSerie -> newTvSeriesSearchResponse(recTitle, fixUrl(recUrl), TvType.TvSeries) {
                                this.posterUrl = recImg
                                this.year = recYear
                                if (recScore != null) this.score = Score.from10(recScore)
                            }
                            else -> newMovieSearchResponse(recTitle, fixUrl(recUrl), TvType.Movie) {
                                this.posterUrl = recImg
                                this.year = recYear
                                if (recScore != null) this.score = Score.from10(recScore)
                            }
                        }
                    } catch (e: Exception) { null }
                }
            
            val playerUrl = findPlayerUrl(document)
            val tmdbId = document.selectFirst("section[data-contentid]")?.attr("data-contentid")?.toIntOrNull()
            
            if (!isAnime && !isSerie) {
                return newMovieLoadResponse(cleanTitle, url, TvType.Movie, playerUrl ?: url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = synopsis
                    this.tags = tags
                    this.duration = duration
                    this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                    if (rating != null) this.score = Score.from10(rating)
                    if (cast != null && cast.isNotEmpty()) addActors(cast)
                    if (trailerUrl != null) addTrailer(trailerUrl)
                }
            }
            
            val episodes = extractEpisodesFromSite(document, url, tmdbId)
            val type = if (isAnime) TvType.Anime else TvType.TvSeries
            
            return newTvSeriesLoadResponse(cleanTitle, url, type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations.takeIf { it.isNotEmpty() }
                if (rating != null) this.score = Score.from10(rating)
                if (cast != null && cast.isNotEmpty()) addActors(cast)
                if (trailerUrl != null) addTrailer(trailerUrl)
            }
            
        } catch (e: Exception) {
            return null
        }
    }

    private suspend fun extractEpisodesFromSite(
        document: org.jsoup.nodes.Document,
        seriesUrl: String,
        tmdbId: Int?
    ): List<Episode> {
        val episodes = mutableListOf<Episode>()

        val scriptData = document.selectFirst("script:containsData(window.allEpisodes)")?.data()
        if (scriptData != null) {
            try {
                val jsonMatch = Regex("window\\.allEpisodes\\s*=\\s*(\\{[\\s\\S]+?\\});").find(scriptData)
                val jsonString = jsonMatch?.groupValues?.get(1)
                
                if (jsonString != null) {
                    val seasonPattern = Regex("\"(\\d+)\"\\s*:\\s*\\[")
                    val seasonMatches = seasonPattern.findAll(jsonString).toList()
                    
                    for (seasonMatch in seasonMatches) {
                        val seasonNum = seasonMatch.groupValues[1].toIntOrNull() ?: continue
                        val startIndex = seasonMatch.range.last + 1
                        
                        var bracketCount = 1
                        var endIndex = startIndex
                        while (endIndex < jsonString.length && bracketCount > 0) {
                            when (jsonString[endIndex]) {
                                '[' -> bracketCount++
                                ']' -> bracketCount--
                            }
                            endIndex++
                        }
                        
                        val seasonJson = jsonString.substring(startIndex, endIndex - 1)
                        
                        val episodePattern = Regex("""\{([^{}]*?)\}""")
                        val episodeMatches = episodePattern.findAll(seasonJson)
                        
                        for (epMatch in episodeMatches) {
                            val episodeData = epMatch.groupValues[1]
                            
                            val epNum = Regex("\"epi_num\"\\s*:\\s*(\\d+)").find(episodeData)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                            val epTitle = Regex("\"title\"\\s*:\\s*\"([^\"]*)\"").find(episodeData)?.groupValues?.get(1)?.ifEmpty { "Episódio $epNum" } ?: "Episódio $epNum"
                            val sinopse = Regex("\"sinopse\"\\s*:\\s*\"([^\"]*)\"").find(episodeData)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() && it != "null" }
                            var thumbUrl = Regex("\"thumb_url\"\\s*:\\s*\"([^\"]*)\"").find(episodeData)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() && it != "null" }
                            val durationMin = Regex("\"duration\"\\s*:\\s*(\\d+)").find(episodeData)?.groupValues?.get(1)?.toIntOrNull()
                            val airDate = Regex("\"air_date\"\\s*:\\s*\"([^\"]*)\"").find(episodeData)?.groupValues?.get(1)?.takeIf { it.isNotEmpty() && it != "null" }
                            
                            thumbUrl = thumbUrl?.let {
                                when {
                                    it.startsWith("//") -> "https:$it"
                                    it.startsWith("/") && !it.startsWith("//") -> "$mainUrl$it"
                                    !it.startsWith("http") && it.isNotBlank() -> "https://image.tmdb.org/t/p/w500$it"
                                    else -> it
                                }
                            }
                            
                            if (thumbUrl.isNullOrBlank()) {
                                val backdropElement = document.selectFirst("#movie-player-container")?.attr("data-backdrop")
                                thumbUrl = backdropElement?.let { fixImageUrl(it) }
                            }
                            
                            val episodeUrl = "$seriesUrl/$seasonNum/$epNum"
                            
                            episodes.add(newEpisode(fixUrl(episodeUrl)) {
                                this.name = epTitle
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = thumbUrl
                                this.description = sinopse
                                this.runTime = durationMin
                                if (airDate != null) this.addDate(airDate)
                                if (tmdbId != null) this.data = "$tmdbId|$seasonNum|$epNum"
                            })
                        }
                    }
                    
                    if (episodes.isNotEmpty()) {
                        return episodes
                    }
                }
            } catch (e: Exception) { }
        }

        val episodeElements = document.select("#episodes-list article")
        
        episodeElements.forEachIndexed { index, element ->
            try {
                val link = element.selectFirst("a[href]") ?: return@forEachIndexed
                val episodeUrl = link.attr("href")
                if (episodeUrl.isBlank()) return@forEachIndexed

                val epNumberText = element.selectFirst(".text-lead.shrink-0")?.text() ?: "E${index + 1}"
                val epMatch = Regex("E(\\d+)", RegexOption.IGNORE_CASE).find(epNumberText)
                val epNumber = epMatch?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)
                
                var epTitle = element.selectFirst("h2, .truncate")?.text()?.trim()
                if (epTitle.isNullOrBlank()) epTitle = "Episódio $epNumber"
                
                val sinopse = element.selectFirst(".line-clamp-2.text-xs")?.text()?.trim()
                
                var thumb: String? = null
                val imgElement = element.selectFirst("img")
                if (imgElement != null) {
                    thumb = imgElement.attr("data-src")
                    if (thumb.isNullOrBlank()) thumb = imgElement.attr("src")
                    
                    thumb = thumb?.let {
                        when {
                            it.startsWith("//") -> "https:$it"
                            it.startsWith("/") -> "$mainUrl$it"
                            !it.startsWith("http") -> "https://image.tmdb.org/t/p/w500$it"
                            else -> it
                        }
                    }
                }
                
                var durationMin: Int? = null
                val durationText = element.selectFirst(".text-\\[11px\\].font-bold.absolute.end-3.bottom-3")?.text()
                if (!durationText.isNullOrBlank()) {
                    durationMin = durationText.replace("min", "").trim().toIntOrNull()
                }
                
                var airDate: String? = null
                val badgeText = element.selectFirst(".absolute.start-3.top-3")?.text()
                if (badgeText != null && badgeText.contains("Em breve")) {
                    airDate = badgeText.replace("Em breve •", "").trim()
                }

                episodes.add(newEpisode(fixUrl(episodeUrl)) {
                    this.name = epTitle
                    this.season = 1
                    this.episode = epNumber
                    this.posterUrl = thumb
                    this.description = sinopse
                    this.runTime = durationMin
                    if (airDate != null) this.addDate(airDate)
                    if (tmdbId != null) this.data = "$tmdbId|1|$epNumber"
                })
            } catch (e: Exception) { }
        }
        
        return episodes
    }

    private fun findPlayerUrl(document: org.jsoup.nodes.Document): String? {
        val iframe = document.selectFirst("iframe[src*='player'], iframe[src*='embed'], iframe[src*='fembed'], iframe[src*='filemoon']")
        return iframe?.attr("src")
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val parts = data.split("|")
            
            if (parts.size == 3) {
                val tmdbId = parts[0].toIntOrNull()
                val season = parts[1].toIntOrNull() ?: 1
                val episode = parts[2].toIntOrNull() ?: 1
                
                if (tmdbId == null) return false
                
                val streams = PobreFlixExtractor.getStreams(tmdbId, "serie", season, episode)
                
                if (streams.isEmpty()) return false
                
                streams.forEach { callback(it) }
                true
            } else {
                val document = app.get(data).document
                val urlPath = data.substringAfter(mainUrl).substringBefore("?")
                
                if (urlPath.contains("/filme/")) {
                    val tmdbId = document.selectFirst("section[data-contentid]")?.attr("data-contentid")?.toIntOrNull()
                        ?: document.selectFirst("#movie-player-container")?.attr("data-apicontentid")?.toIntOrNull()
                    
                    if (tmdbId == null) return false
                    
                    val streams = PobreFlixExtractor.getStreams(tmdbId, "filme", 1, 1)
                    
                    if (streams.isEmpty()) return false
                    
                    streams.forEach { callback(it) }
                    true
                } else {
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }
    
    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("data:image")) return null
        
        var fixedUrl = url.trim()
        
        if (fixedUrl.startsWith("//")) {
            fixedUrl = "https:$fixedUrl"
        } else if (fixedUrl.startsWith("/") && !fixedUrl.startsWith("//")) {
            fixedUrl = "$mainUrl$fixedUrl"
        }
        
        if (fixedUrl.contains("d1muf25xaso8hp.cloudfront.net/")) {
            val afterCdn = fixedUrl.substringAfter("d1muf25xaso8hp.cloudfront.net/")
            if (afterCdn.startsWith("https://")) {
                fixedUrl = afterCdn
            } else {
                fixedUrl = "https://image.tmdb.org/t/p/w500$afterCdn"
            }
        }
        
        return fixedUrl
    }
    
    private fun parseDuration(durationStr: String): Int? {
        val str = durationStr.lowercase().trim()
        
        val hoursMatch = Regex("(\\d+)\\s*h").find(str)
        val minutesMatch = Regex("(\\d+)\\s*m(?:in)?").find(str)
        
        val hours = hoursMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = minutesMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
        
        if (hours > 0 || minutes > 0) {
            return (hours * 60) + minutes
        }
        
        val justMinutes = Regex("(\\d+)\\s*m(?:in)?").find(str)
        return justMinutes?.groupValues?.get(1)?.toIntOrNull()
    }
}
