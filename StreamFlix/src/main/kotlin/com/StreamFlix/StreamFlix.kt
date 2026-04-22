package com.StreamFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.fasterxml.jackson.annotation.JsonProperty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.*

@CloudstreamPlugin
class StreamFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(StreamFlix())
    }
}

class StreamFlix : MainAPI() {
    override var mainUrl = "https://streamflix.live"
    override var name = "StreamFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    private var cachedMovies: JSONArray? = null
    private var cachedSeries: JSONArray? = null
    private val PAGE_SIZE = 30

    private val tmdbImageUrl = "https://image.tmdb.org/t/p"
    private val TMDB_API_KEY = BuildConfig.TMDB_API_KEY
    private val TMDB_ACCESS_TOKEN = BuildConfig.TMDB_ACCESS_TOKEN

    private data class Category(val id: String, val name: String, val count: Int = 0)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val categories = mutableListOf<HomePageList>()
        
        val movieCategories = getMovieCategories()
        for (category in movieCategories.take(4)) {
            val movies = getMoviesByCategory(category.id, 0)
            if (movies.isNotEmpty()) {
                categories.add(HomePageList("Filmes - ${category.name}", movies, isHorizontalImages = false))
            }
        }
        
        val seriesCategories = getSeriesCategories()
        for (category in seriesCategories.take(4)) {
            val series = getSeriesByCategory(category.id, 0)
            if (series.isNotEmpty()) {
                categories.add(HomePageList("Séries - ${category.name}", series, isHorizontalImages = false))
            }
        }
        
        return newHomePageResponse(categories, hasNext = false)
    }

    private suspend fun getMovieCategories(): List<Category> {
        return withContext(Dispatchers.IO) {
            try {
                val response = app.get("$mainUrl/api_proxy.php?action=get_vod_categories")
                val jsonArray = JSONArray(response.body.string())
                val allMovies = getAllMovies()
                val countMap = mutableMapOf<String, Int>()
                
                for (i in 0 until allMovies.length()) {
                    val movie = allMovies.getJSONObject(i)
                    val categories = getCategoryIds(movie)
                    for (catId in categories) {
                        countMap[catId] = countMap.getOrDefault(catId, 0) + 1
                    }
                }
                
                val categories = mutableListOf<Category>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.getString("category_id")
                    val name = cleanCategoryName(obj.getString("category_name"))
                    val count = countMap.getOrDefault(id, 0)
                    
                    if (count >= 25) {
                        categories.add(Category(id, name, count))
                    }
                }
                
                return@withContext categories
                    .groupBy { it.name.lowercase() }
                    .map { it.value.first() }
                    .sortedByDescending { it.count }
                    .shuffled()
                    .take(4)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private suspend fun getSeriesCategories(): List<Category> {
        return withContext(Dispatchers.IO) {
            try {
                val response = app.get("$mainUrl/api_proxy.php?action=get_series_categories")
                val jsonArray = JSONArray(response.body.string())
                val allSeries = getAllSeries()
                val countMap = mutableMapOf<String, Int>()
                
                for (i in 0 until allSeries.length()) {
                    val series = allSeries.getJSONObject(i)
                    val categories = getCategoryIds(series)
                    for (catId in categories) {
                        countMap[catId] = countMap.getOrDefault(catId, 0) + 1
                    }
                }
                
                val categories = mutableListOf<Category>()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val id = obj.getString("category_id")
                    val name = cleanCategoryName(obj.getString("category_name"))
                    val count = countMap.getOrDefault(id, 0)
                    
                    if (count >= 25) {
                        categories.add(Category(id, name, count))
                    }
                }
                
                return@withContext categories
                    .groupBy { it.name.lowercase() }
                    .map { it.value.first() }
                    .sortedByDescending { it.count }
                    .shuffled()
                    .take(4)
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    private fun cleanCategoryName(name: String): String {
        var cleaned = name
        
        val unicodePattern = Regex("\\\\u([0-9a-fA-F]{4})")
        cleaned = unicodePattern.replace(cleaned) { matchResult ->
            val code = matchResult.groupValues[1].toInt(16)
            code.toChar().toString()
        }
        
        cleaned = cleaned.replace(Regex("[☑✅⚡✏️🔞🎬📺🇰🇷🇯🇵⭐]"), "")
        cleaned = cleaned.replace(Regex("[\\[\\]()]"), "")
        cleaned = cleaned.trim()
        cleaned = cleaned.replace(Regex("\\s+"), " ")
        
        cleaned = cleaned.split(" ").joinToString(" ") { word ->
            if (word.isNotEmpty()) word.replaceFirstChar { it.uppercase() } else word
        }
        
        return cleaned
    }

    private suspend fun getMoviesByCategory(categoryId: String, page: Int): List<SearchResponse> {
        val allMovies = getAllMovies()
        
        val categoryMovies = mutableListOf<JSONObject>()
        for (i in 0 until allMovies.length()) {
            val movie = allMovies.getJSONObject(i)
            val movieCategories = getCategoryIds(movie)
            if (movieCategories.contains(categoryId)) {
                categoryMovies.add(movie)
            }
        }
        
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, categoryMovies.size)
        
        if (start >= categoryMovies.size) return emptyList()
        
        val results = mutableListOf<SearchResponse>()
        for (i in start until end) {
            val movie = categoryMovies[i]
            val rawName = movie.getString("name")
            
            if (isAdultContent(rawName)) continue
            
            val (cleanName, dubStatus, qualityTag) = processTitle(rawName, false)
            val finalName = cleanTitle(cleanName)
            
            val id = movie.getInt("stream_id")
            val poster = fixImageUrl(movie.optString("stream_icon"))
            val ratingValue = movie.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
            
            results.add(
                newAnimeSearchResponse(finalName, "movie?id=$id", TvType.Movie) {
                    this.posterUrl = poster
                    if (qualityTag != null) this.quality = qualityTag
                    if (dubStatus != null) this.dubStatus = dubStatus
                    this.score = Score.from10(ratingValue)
                }
            )
        }
        return results
    }

    private suspend fun getSeriesByCategory(categoryId: String, page: Int): List<SearchResponse> {
        val allSeries = getAllSeries()
        
        val categorySeries = mutableListOf<JSONObject>()
        for (i in 0 until allSeries.length()) {
            val series = allSeries.getJSONObject(i)
            val seriesCategories = getCategoryIds(series)
            if (seriesCategories.contains(categoryId)) {
                categorySeries.add(series)
            }
        }
        
        val start = page * PAGE_SIZE
        val end = minOf(start + PAGE_SIZE, categorySeries.size)
        
        if (start >= categorySeries.size) return emptyList()
        
        val results = mutableListOf<SearchResponse>()
        for (i in start until end) {
            val series = categorySeries[i]
            val rawName = series.getString("name")
            
            if (isAdultContent(rawName)) continue
            
            val (cleanName, dubStatus, qualityTag) = processTitle(rawName, false)
            val finalName = cleanTitle(cleanName)
            
            val id = series.getInt("series_id")
            val poster = fixImageUrl(series.optString("cover"))
            val ratingValue = series.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
            
            results.add(
                newAnimeSearchResponse(finalName, "series?id=$id", TvType.TvSeries) {
                    this.posterUrl = poster
                    if (qualityTag != null) this.quality = qualityTag
                    if (dubStatus != null) this.dubStatus = dubStatus
                    this.score = Score.from10(ratingValue)
                }
            )
        }
        return results
    }

    private fun processTitle(rawTitle: String, isFourKCategory: Boolean): Triple<String, EnumSet<DubStatus>?, SearchQuality?> {
        var cleanTitle = rawTitle.trim()
        var dubStatus: EnumSet<DubStatus>? = null
        var qualityTag: SearchQuality? = null
        
        if (isFourKCategory || Regex("\\b4K\\b", RegexOption.IGNORE_CASE).containsMatchIn(cleanTitle)) {
            qualityTag = SearchQuality.FourK
        }
        
        val hasLegTag = Regex("\\[L\\]", RegexOption.IGNORE_CASE).containsMatchIn(cleanTitle)
        
        if (hasLegTag) {
            dubStatus = EnumSet.of(DubStatus.Subbed)
            cleanTitle = cleanTitle.replace(Regex("\\s*\\[L\\]\\s*", RegexOption.IGNORE_CASE), " ")
            cleanTitle = cleanTitle.replace(Regex("\\s*\\[L\\]\\s*\$", RegexOption.IGNORE_CASE), "")
        } else {
            dubStatus = EnumSet.of(DubStatus.Dubbed)
        }
        
        return Triple(cleanTitle, dubStatus, qualityTag)
    }

    private fun cleanTitle(title: String): String {
        var cleaned = title.trim()
        cleaned = cleaned.replace(Regex("\\b4K\\b", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("\\s*4K\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*\\(\\d{4}\\)\\s*"), " ")
        cleaned = cleaned.replace(Regex("\\s*\\(\\d{4}\\)\$"), "")
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*"), " ")
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*\$"), "")
        cleaned = cleaned.replace(Regex("\\s*HDR\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*HYBRID\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*HD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*FULLHD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*UHD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        return cleaned
    }

    private fun getCategoryIds(obj: JSONObject): Set<String> {
        val ids = mutableSetOf<String>()
        
        val categoryIdsArray = obj.optJSONArray("category_ids")
        if (categoryIdsArray != null) {
            for (i in 0 until categoryIdsArray.length()) {
                ids.add(categoryIdsArray.getInt(i).toString())
            }
        }
        
        val categoryId = obj.optString("category_id", null)
        if (categoryId != null && categoryId.isNotEmpty() && categoryId != "null") {
            ids.add(categoryId)
        } else {
            val categoryIdInt = obj.optInt("category_id", -1)
            if (categoryIdInt != -1) {
                ids.add(categoryIdInt.toString())
            }
        }
        
        return ids
    }

    private suspend fun getAllMovies(): JSONArray {
        if (cachedMovies != null) return cachedMovies!!
        return withContext(Dispatchers.IO) {
            val response = app.get("$mainUrl/api_proxy.php?action=get_vod_streams")
            val json = JSONArray(response.body.string())
            cachedMovies = json
            json
        }
    }

    private suspend fun getAllSeries(): JSONArray {
        if (cachedSeries != null) return cachedSeries!!
        return withContext(Dispatchers.IO) {
            val response = app.get("$mainUrl/api_proxy.php?action=get_series")
            val json = JSONArray(response.body.string())
            cachedSeries = json
            json
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val results = mutableListOf<SearchResponse>()
        val queryLower = query.lowercase()
        
        val allMovies = getAllMovies()
        for (i in 0 until allMovies.length()) {
            val movie = allMovies.getJSONObject(i)
            val rawName = movie.getString("name")
            if (rawName.lowercase().contains(queryLower)) {
                if (isAdultContent(rawName)) continue
                
                val (cleanName, dubStatus, qualityTag) = processTitle(rawName, false)
                val finalName = cleanTitle(cleanName)
                val id = movie.getInt("stream_id")
                val poster = fixImageUrl(movie.optString("stream_icon"))
                val ratingValue = movie.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
                
                results.add(
                    newAnimeSearchResponse(finalName, "movie?id=$id", TvType.Movie) {
                        this.posterUrl = poster
                        if (qualityTag != null) this.quality = qualityTag
                        if (dubStatus != null) this.dubStatus = dubStatus
                        this.score = Score.from10(ratingValue)
                    }
                )
            }
        }
        
        val allSeries = getAllSeries()
        for (i in 0 until allSeries.length()) {
            val series = allSeries.getJSONObject(i)
            val rawName = series.getString("name")
            if (rawName.lowercase().contains(queryLower)) {
                if (isAdultContent(rawName)) continue
                
                val (cleanName, dubStatus, qualityTag) = processTitle(rawName, false)
                val finalName = cleanTitle(cleanName)
                val id = series.getInt("series_id")
                val poster = fixImageUrl(series.optString("cover"))
                val ratingValue = series.optDouble("rating_5based", 0.0).let { it.toFloat() * 2 }
                
                results.add(
                    newAnimeSearchResponse(finalName, "series?id=$id", TvType.TvSeries) {
                        this.posterUrl = poster
                        if (qualityTag != null) this.quality = qualityTag
                        if (dubStatus != null) this.dubStatus = dubStatus
                        this.score = Score.from10(ratingValue)
                    }
                )
            }
        }
        
        return results
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url
            .removePrefix("https://streamflix.live/")
            .removePrefix("http://streamflix.live/")
            .removePrefix("streamflix.live/")
        
        return when {
            cleanUrl.startsWith("movie?id=") -> {
                val id = cleanUrl.substringAfter("movie?id=")
                loadMovie(id)
            }
            cleanUrl.startsWith("series?id=") -> {
                val id = cleanUrl.substringAfter("series?id=")
                loadSeries(id)
            }
            else -> null
        }
    }

    private fun isAdultContent(title: String): Boolean {
        val adultKeywords = listOf(
            "XXX", "ADULTOS", "Porn", "Sexo", "Erótico", "Erótica",
            "Hardcore", "18+", "Adult", "Erotico", "18 anos"
        )
        val titleUpper = title.uppercase(Locale.getDefault())
        return adultKeywords.any { titleUpper.contains(it.uppercase(Locale.getDefault())) }
    }

    private fun cleanTitleForTMDB(title: String): String {
        var cleaned = title.trim()
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*"), " ")
        cleaned = cleaned.replace(Regex("\\s*\\[[^\\]]+\\]\\s*\$"), "")
        cleaned = cleaned.replace(Regex("\\b4K\\b", RegexOption.IGNORE_CASE), "")
        cleaned = cleaned.replace(Regex("\\s*4K\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*\\(\\d{4}\\)\\s*"), " ")
        cleaned = cleaned.replace(Regex("\\s*\\(\\d{4}\\)\$"), "")
        cleaned = cleaned.replace(Regex("\\s*HD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*FULLHD\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*HDR\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s*HYBRID\\s*", RegexOption.IGNORE_CASE), " ")
        cleaned = cleaned.replace(Regex("\\s+"), " ").trim()
        return cleaned
    }

    private suspend fun loadMovie(id: String): LoadResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_vod_info&vod_id=$id")
                val infoJson = JSONObject(infoResponse.body.string())
                val info = infoJson.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.optString("name", "Título indisponível")
                val (cleanName, _, _) = processTitle(rawTitle, false)
                val finalTitle = cleanTitle(cleanName)
                
                val posterFallback = fixImageUrl(info.optString("cover_big"))
                
                val tmdbData = searchMovieOnTMDB(cleanTitleForTMDB(finalTitle))
                
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover_big"))
                val poster = tmdbData?.posterUrl ?: posterFallback
                val plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                val year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                val rating = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).let { Score.from10(it.toFloat() * 2) }
                val duration = tmdbData?.duration ?: info.optInt("duration_secs", 0).takeIf { it > 0 }?.let { it / 60 }
                val tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val actors = tmdbData?.actors
                val trailerUrl = tmdbData?.youtubeTrailer
                
                val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=movie&id=$id")
                val streamJson = JSONObject(streamResponse.body.string())
                val videoUrl = streamJson.getString("stream_url")
                
                newMovieLoadResponse(finalTitle, "movie?id=$id", TvType.Movie, videoUrl) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    this.score = rating
                    this.duration = duration
                    this.tags = tags
                    
                    if (actors != null && actors.isNotEmpty()) {
                        addActors(actors)
                    }
                    if (trailerUrl != null) {
                        addTrailer(trailerUrl)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun searchMovieOnTMDB(query: String): TMDBMovieInfo? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/movie?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            if (response.code != 200) return null
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null
            
            val details = getTMDBMovieDetails(result.id)
            
            TMDBMovieInfo(
                title = result.title,
                year = result.release_date?.substring(0, 4)?.toIntOrNull(),
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details?.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details?.overview,
                rating = details?.vote_average?.takeIf { it > 0 },
                genres = details?.genres?.map { it.name },
                duration = details?.runtime,
                actors = details?.credits?.cast?.take(15)?.mapNotNull { actor ->
                    if (actor.name.isNotBlank()) {
                        val actorObj = Actor(name = actor.name, image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" })
                        Pair(actorObj, actor.character)
                    } else null
                },
                youtubeTrailer = getHighQualityTrailer(details?.videos?.results)
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBMovieDetails(tmdbId: Int): TMDBDetailsResponse? {
        return try {
            val url = "https://api.themoviedb.org/3/movie/$tmdbId?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            val response = app.get(url, headers = headers, timeout = 10_000)
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadSeries(id: String): LoadResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val infoResponse = app.get("$mainUrl/api_proxy.php?action=get_series_info&series_id=$id")
                val json = JSONObject(infoResponse.body.string())
                val info = json.optJSONObject("info") ?: JSONObject()
                
                val rawTitle = info.getString("name")
                val (cleanName, _, _) = processTitle(rawTitle, false)
                val finalTitle = cleanTitle(cleanName)
                
                val posterFallback = fixImageUrl(info.optString("cover"))
                
                val tmdbData = searchSeriesOnTMDB(cleanTitleForTMDB(finalTitle))
                
                val backdrop = tmdbData?.backdropUrl ?: fixImageUrl(info.optString("cover"))
                val poster = tmdbData?.posterUrl ?: posterFallback
                val plot = tmdbData?.overview ?: info.optString("plot", "Sinopse não disponível.")
                val year = tmdbData?.year ?: info.optString("releaseDate").takeIf { it.isNotEmpty() }?.substring(0, 4)?.toIntOrNull()
                val seriesRating = tmdbData?.rating?.let { Score.from10(it) } ?: info.optDouble("rating_5based", 0.0).let { Score.from10(it.toFloat() * 2) }
                val tags = tmdbData?.genres ?: info.optString("genre").split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val actors = tmdbData?.actors
                val trailerUrl = tmdbData?.youtubeTrailer
                
                val episodes = extractEpisodes(json, tmdbData, seriesRating)
                
                if (episodes.isEmpty()) return@withContext null
                
                newTvSeriesLoadResponse(finalTitle, "series?id=$id", TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.plot = plot
                    this.year = year
                    this.score = seriesRating
                    this.tags = tags
                    
                    if (actors != null && actors.isNotEmpty()) {
                        addActors(actors)
                    }
                    if (trailerUrl != null) {
                        addTrailer(trailerUrl)
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun searchSeriesOnTMDB(query: String): TMDBSeriesInfo? {
        return try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "https://api.themoviedb.org/3/search/tv?api_key=$TMDB_API_KEY&query=$encodedQuery&language=pt-BR"
            
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            
            val response = app.get(url, headers = headers, timeout = 10_000)
            if (response.code != 200) return null
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            val result = searchResult.results.firstOrNull() ?: return null
            
            val details = getTMDBSeriesDetails(result.id)
            
            TMDBSeriesInfo(
                title = result.id.toString(),
                year = result.first_air_date?.substring(0, 4)?.toIntOrNull(),
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details?.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details?.overview,
                rating = details?.vote_average?.takeIf { it > 0 },
                genres = details?.genres?.map { it.name },
                actors = details?.credits?.cast?.take(15)?.mapNotNull { actor ->
                    if (actor.name.isNotBlank()) {
                        val actorObj = Actor(name = actor.name, image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" })
                        Pair(actorObj, actor.character)
                    } else null
                },
                youtubeTrailer = getHighQualityTrailer(details?.videos?.results),
                seasonsEpisodes = getTMDBAllSeasons(result.id, details)
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBSeriesDetails(tmdbId: Int): TMDBDetailsResponse? {
        return try {
            val url = "https://api.themoviedb.org/3/tv/$tmdbId?api_key=$TMDB_API_KEY&language=pt-BR&append_to_response=credits,videos"
            val headers = mapOf(
                "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                "accept" to "application/json"
            )
            val response = app.get(url, headers = headers, timeout = 10_000)
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getTMDBAllSeasons(seriesId: Int, details: TMDBDetailsResponse?): Map<Int, List<TMDBEpisode>> {
        val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()
        val seasons = details?.seasons ?: return emptyMap()
        
        for (season in seasons) {
            if (season.season_number > 0) {
                val seasonNumber = season.season_number
                
                val seasonUrl = "https://api.themoviedb.org/3/tv/$seriesId/season/$seasonNumber?api_key=$TMDB_API_KEY&language=pt-BR"
                val headers = mapOf(
                    "Authorization" to "Bearer $TMDB_ACCESS_TOKEN",
                    "accept" to "application/json"
                )
                val seasonResponse = app.get(seasonUrl, headers = headers, timeout = 10_000)
                if (seasonResponse.code == 200) {
                    val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                    seasonData?.episodes?.let { episodes ->
                        seasonsEpisodes[seasonNumber] = episodes
                    }
                }
            }
        }
        
        return seasonsEpisodes
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) return null
        
        val trailerInfo = videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true -> Triple(video.key, 10, "YouTube Trailer Oficial")
                video.site == "YouTube" && video.type == "Trailer" -> Triple(video.key, 9, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" && video.official == true -> Triple(video.key, 8, "YouTube Teaser Oficial")
                video.site == "YouTube" && video.type == "Teaser" -> Triple(video.key, 7, "YouTube Teaser")
                else -> null
            }
        }.sortedByDescending { it.second }.firstOrNull()
        
        return trailerInfo?.let { (key, _, _) -> "https://www.youtube.com/watch?v=$key" }
    }

    private suspend fun extractEpisodes(json: JSONObject, tmdbData: TMDBSeriesInfo?, seriesRating: Score?): List<Episode> {
        val episodes = mutableListOf<Episode>()
        val episodesJson = json.optJSONObject("episodes")
        
        if (episodesJson != null) {
            val seasonKeys = episodesJson.keys()
            while (seasonKeys.hasNext()) {
                val seasonNum = seasonKeys.next().toIntOrNull() ?: continue
                val seasonArray = episodesJson.getJSONArray(seasonNum.toString())
                
                for (i in 0 until seasonArray.length()) {
                    val ep = seasonArray.getJSONObject(i)
                    val epNum = ep.getInt("episode_num")
                    val epTitle = ep.getString("title")
                    val epId = ep.getString("id")
                    
                    val epInfo = ep.optJSONObject("info") ?: JSONObject()
                    val epPlotFallback = epInfo.optString("plot").takeIf { it.isNotEmpty() }
                    val epImageFallback = fixImageUrl(epInfo.optString("movie_image").takeIf { it.isNotEmpty() })
                    val epDurationFallback = epInfo.optInt("duration_secs", 0).takeIf { it > 0 }
                    
                    val tmdbEpisode = tmdbData?.seasonsEpisodes?.get(seasonNum)?.find { it.episode_number == epNum }
                    
                    val streamResponse = app.get("$mainUrl/api_proxy.php?action=get_stream_url&type=series&id=$epId")
                    val streamJson = JSONObject(streamResponse.body.string())
                    val videoUrl = streamJson.getString("stream_url")
                    
                    val thumb = tmdbEpisode?.still_path?.let { "$tmdbImageUrl/w300$it" } ?: epImageFallback
                    val description = tmdbEpisode?.overview?.takeIf { it.isNotEmpty() } ?: epPlotFallback
                    val duration = tmdbEpisode?.runtime ?: (epDurationFallback?.let { it / 60 })
                    
                    var epRating: Score? = null
                    val seriesTmdbId = tmdbData?.title?.toIntOrNull()
                    
                    if (seriesTmdbId != null && seasonNum != null && epNum != null) {
                        try {
                            val episodeDetailsUrl = "https://api.themoviedb.org/3/tv/$seriesTmdbId/season/$seasonNum/episode/$epNum?api_key=$TMDB_API_KEY&language=pt-BR"
                            val episodeResponse = app.get(episodeDetailsUrl, timeout = 5_000)
                            if (episodeResponse.code == 200) {
                                val epDetailsJson = JSONObject(episodeResponse.text)
                                val ratingValue = epDetailsJson.optDouble("vote_average").takeIf { it > 0 }
                                if (ratingValue != null) {
                                    epRating = Score.from10(ratingValue)
                                }
                            }
                        } catch (e: Exception) { }
                    }
                    
                    if (epRating == null && seriesRating != null) {
                        epRating = seriesRating
                    }
                    
                    episodes.add(
                        newEpisode(videoUrl) {
                            this.name = tmdbEpisode?.name?.takeIf { it.isNotEmpty() } ?: epTitle
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = thumb
                            this.description = description
                            if (duration != null && duration > 0) this.runTime = duration
                            if (epRating != null) this.score = epRating
                            if (tmdbEpisode?.air_date != null) {
                                try {
                                    val dateFormatter = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                    val date = dateFormatter.parse(tmdbEpisode.air_date)
                                    this.date = date?.time
                                } catch (e: Exception) { }
                            }
                        }
                    )
                }
            }
        }
        
        return episodes
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        
        var quality = 0
        var resolution = ""
        
        try {
            val response = app.get(data, headers = mapOf(
                "Range" to "bytes=0-5242880",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ))
            
            val bytes = response.body.bytes()
            
            for (i in 0 until bytes.size - 20) {
                if (bytes[i] == 0x74.toByte() && bytes[i+1] == 0x6B.toByte() && 
                    bytes[i+2] == 0x68.toByte() && bytes[i+3] == 0x64.toByte()) {
                    
                    for (offset in 48..80) {
                        if (i + offset + 8 <= bytes.size) {
                            val widthFixed = ((bytes[i+offset].toInt() and 0xFF) shl 24) or
                                           ((bytes[i+offset+1].toInt() and 0xFF) shl 16) or
                                           ((bytes[i+offset+2].toInt() and 0xFF) shl 8) or
                                           (bytes[i+offset+3].toInt() and 0xFF)
                            val heightFixed = ((bytes[i+offset+4].toInt() and 0xFF) shl 24) or
                                            ((bytes[i+offset+5].toInt() and 0xFF) shl 16) or
                                            ((bytes[i+offset+6].toInt() and 0xFF) shl 8) or
                                            (bytes[i+offset+7].toInt() and 0xFF)
                            
                            val width = Math.round(widthFixed / 65536.0)
                            val height = Math.round(heightFixed / 65536.0)
                            
                            if (width in 640..7680 && height in 360..4320) {
                                resolution = "${width}x${height}"
                                val pixels = width * height
                                quality = when {
                                    pixels >= 6000000 -> 2160
                                    pixels >= 1400000 -> 1080
                                    pixels >= 700000 -> 720
                                    else -> 480
                                }
                                break
                            }
                        }
                    }
                    if (resolution.isNotEmpty()) break
                }
            }
            
            val qualityString = when (quality) {
                2160 -> "4K"
                1080 -> "1080p"
                720 -> "720p"
                480 -> "480p"
                else -> if (resolution.isNotEmpty()) "?" else "?"
            }
            
            val sourceName = if (resolution.isNotEmpty()) {
                "$name - ${qualityString} (${resolution})"
            } else {
                "$name - ${qualityString}"
            }
            
            callback(
                newExtractorLink(
                    source = sourceName,
                    name = sourceName,
                    url = data,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                    if (quality > 0) this.quality = quality
                }
            )
            return true
            
        } catch (e: Exception) {
            callback(
                newExtractorLink(
                    source = "$name - ?",
                    name = "$name - ?",
                    url = data,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = mainUrl
                }
            )
            return true
        }
    }

    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("data:")) return null
        
        var fixed = url.trim()
        if (fixed.startsWith("//")) fixed = "https:$fixed"
        if (!fixed.startsWith("http") && fixed.startsWith("/")) fixed = "$mainUrl$fixed"
        
        return fixed
    }

    private data class TMDBMovieInfo(
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val rating: Double?,
        val genres: List<String>?,
        val duration: Int?,
        val actors: List<Pair<Actor, String?>>?,
        val youtubeTrailer: String?
    )

    private data class TMDBSeriesInfo(
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val rating: Double?,
        val genres: List<String>?,
        val actors: List<Pair<Actor, String?>>?,
        val youtubeTrailer: String?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>>
    )

    private data class TMDBSearchResponse(
        @JsonProperty("results") val results: List<TMDBResult>
    )

    private data class TMDBResult(
        @JsonProperty("id") val id: Int,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("release_date") val release_date: String? = null,
        @JsonProperty("first_air_date") val first_air_date: String? = null,
        @JsonProperty("poster_path") val poster_path: String?
    )

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?,
        @JsonProperty("vote_average") val vote_average: Double?,
        @JsonProperty("seasons") val seasons: List<TMDBSeason>? = null
    )

    private data class TMDBSeason(
        @JsonProperty("season_number") val season_number: Int,
        @JsonProperty("episode_count") val episode_count: Int
    )

    private data class TMDBSeasonResponse(
        @JsonProperty("episodes") val episodes: List<TMDBEpisode>,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBEpisode(
        @JsonProperty("episode_number") val episode_number: Int,
        @JsonProperty("name") val name: String,
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("still_path") val still_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("air_date") val air_date: String?
    )

    private data class TMDBGenre(
        @JsonProperty("name") val name: String
    )

    private data class TMDBCredits(
        @JsonProperty("cast") val cast: List<TMDBCast>
    )

    private data class TMDBCast(
        @JsonProperty("name") val name: String,
        @JsonProperty("character") val character: String?,
        @JsonProperty("profile_path") val profile_path: String?
    )

    private data class TMDBVideos(
        @JsonProperty("results") val results: List<TMDBVideo>
    )

    private data class TMDBVideo(
        @JsonProperty("key") val key: String,
        @JsonProperty("site") val site: String,
        @JsonProperty("type") val type: String,
        @JsonProperty("official") val official: Boolean? = false
    )
}
