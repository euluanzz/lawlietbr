package com.SuperFlix

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.app
import org.jsoup.nodes.Element
import com.fasterxml.jackson.annotation.JsonProperty
import java.text.SimpleDateFormat

class SuperFlix : MainAPI() {
    override var mainUrl = "https://superflix21.lol"
    override var name = "SuperFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)
    override val usesWebView = true

    // ============ PROXY COM SUPORTE A TEMPORADAS ============
    private val TMDB_PROXY_URL = "https://lawliet.euluan1912.workers.dev"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p"

    override val mainPage = mainPageOf(
        "$mainUrl/lancamentos" to "Lançamentos",
        "$mainUrl/filmes" to "Últimos Filmes",
        "$mainUrl/series" to "Últimas Séries",
        "$mainUrl/animes" to "Últimas Animes"
    )

    // ... (funções getMainPage, search, etc permanecem iguais) ...

    override suspend fun load(url: String): LoadResponse? {
        println("🔍 [DEBUG] Iniciando load para URL: $url")
        
        val document = app.get(url).document

        val titleElement = document.selectFirst("h1, .title")
        val title = titleElement?.text() ?: return null
        println("🔍 [DEBUG] Título encontrado no site: $title")

        val year = Regex("\\((\\d{4})\\)").find(title)?.groupValues?.get(1)?.toIntOrNull()
        val cleanTitle = title.replace(Regex("\\(\\d{4}\\)"), "").trim()
        println("🔍 [DEBUG] Título limpo: $cleanTitle | Ano: $year")

        val isAnime = url.contains("/anime/") || title.contains("(Anime)", ignoreCase = true)
        val isSerie = url.contains("/serie/") || url.contains("/tv/") ||
                     (!isAnime && document.selectFirst(".episode-list, .season-list, .seasons") != null)
        println("🔍 [DEBUG] Tipo: ${if (isAnime) "Anime" else if (isSerie) "Série" else "Filme"}")

        println("🔍 [DEBUG] Buscando no TMDB...")
        val tmdbInfo = if (isAnime || isSerie) {
            searchOnTMDB(cleanTitle, year, true)
        } else {
            searchOnTMDB(cleanTitle, year, false)
        }

        if (tmdbInfo == null) {
            println("⚠️ [DEBUG] TMDB não retornou informações!")
        } else {
            println("✅ [DEBUG] TMDB OK! Título: ${tmdbInfo.title}, Ano: ${tmdbInfo.year}")
            println("✅ [DEBUG] Poster URL: ${tmdbInfo.posterUrl}")
            println("✅ [DEBUG] Backdrop URL: ${tmdbInfo.backdropUrl}")
            println("✅ [DEBUG] Overview: ${tmdbInfo.overview?.take(50)}...")
            println("✅ [DEBUG] Atores: ${tmdbInfo.actors?.size ?: 0}")
            println("✅ [DEBUG] Trailer: ${tmdbInfo.youtubeTrailer}")
            println("✅ [DEBUG] Temporadas/Episódios TMDB: ${tmdbInfo.seasonsEpisodes.size}")
        }

        val siteRecommendations = extractRecommendationsFromSite(document)

        return if (tmdbInfo != null) {
            println("✅ [DEBUG] Criando resposta COM dados do TMDB")
            createLoadResponseWithTMDB(tmdbInfo, url, document, isAnime, isSerie, siteRecommendations)
        } else {
            println("⚠️ [DEBUG] Criando resposta APENAS com dados do site")
            createLoadResponseFromSite(document, url, cleanTitle, year, isAnime, isSerie)
        }
    }

    private suspend fun searchOnTMDB(query: String, year: Int?, isTv: Boolean): TMDBInfo? {
        println("🔍 [TMDB DEBUG] Iniciando busca no TMDB")
        println("🔍 [TMDB DEBUG] Query: $query")
        println("🔍 [TMDB DEBUG] Ano: $year")
        println("🔍 [TMDB DEBUG] Tipo: ${if (isTv) "TV" else "Movie"}")
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
            val yearParam = year?.let { "&year=$it" } ?: ""

            val searchUrl = "$TMDB_PROXY_URL/search?query=$encodedQuery&type=$type$yearParam"
            println("🔗 [TMDB DEBUG] URL da busca: $searchUrl")

            val response = app.get(searchUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status da resposta: ${response.code}")
            
            if (response.code != 200) return null
            
            val searchResult = response.parsedSafe<TMDBSearchResponse>() ?: return null
            println("✅ [TMDB DEBUG] Parsing OK! Resultados: ${searchResult.results.size}")
            
            val result = searchResult.results.firstOrNull() ?: return null
            
            // Buscar detalhes completos
            val details = getTMDBDetails(result.id, isTv) ?: return null
            
            // Extrair atores
            val allActors = details.credits?.cast?.take(15)?.mapNotNull { actor ->
                if (actor.name.isNotBlank()) {
                    Actor(
                        name = actor.name,
                        image = actor.profile_path?.let { "$tmdbImageUrl/w185$it" }
                    )
                } else null
            }

            // Buscar trailer
            val youtubeTrailer = getHighQualityTrailer(details.videos?.results)
            
            // IMPORTANTE: AGORA COM TEMPORADAS!
            val seasonsEpisodes = if (isTv) {
                println("🔍 [TMDB DEBUG] Buscando temporadas com novo endpoint...")
                getTMDBAllSeasons(result.id)
            } else {
                emptyMap()
            }

            TMDBInfo(
                id = result.id,
                title = if (isTv) result.name else result.title,
                year = if (isTv) {
                    result.first_air_date?.substring(0, 4)?.toIntOrNull()
                } else {
                    result.release_date?.substring(0, 4)?.toIntOrNull()
                },
                posterUrl = result.poster_path?.let { "$tmdbImageUrl/w500$it" },
                backdropUrl = details.backdrop_path?.let { "$tmdbImageUrl/original$it" },
                overview = details.overview,
                genres = details.genres?.map { it.name },
                actors = allActors,
                youtubeTrailer = youtubeTrailer,
                duration = if (!isTv) details.runtime else null,
                seasonsEpisodes = seasonsEpisodes
            )
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO na busca do TMDB: ${e.message}")
            null
        }
    }

    // FUNÇÃO ATUALIZADA PARA USAR O NOVO ENDPOINT DO PROXY
    private suspend fun getTMDBAllSeasons(seriesId: Int): Map<Int, List<TMDBEpisode>> {
        println("🔍 [TMDB DEBUG] Buscando todas as temporadas para série ID: $seriesId")
        
        return try {
            // Primeiro, pegar detalhes da série para saber quantas temporadas
            val seriesDetailsUrl = "$TMDB_PROXY_URL/tv/$seriesId"
            println("🔗 [TMDB DEBUG] URL detalhes série: $seriesDetailsUrl")
            
            val seriesResponse = app.get(seriesDetailsUrl, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status da resposta: ${seriesResponse.code}")
            
            if (seriesResponse.code != 200) {
                println("❌ [TMDB DEBUG] Erro HTTP: ${seriesResponse.code}")
                return emptyMap()
            }
            
            val seriesDetails = seriesResponse.parsedSafe<TMDBTVDetailsResponse>() ?: return emptyMap()

            println("✅ [TMDB DEBUG] Série OK! Total temporadas: ${seriesDetails.seasons.size}")
            
            val seasonsEpisodes = mutableMapOf<Int, List<TMDBEpisode>>()

            // Agora buscar cada temporada individualmente
            for (season in seriesDetails.seasons) {
                if (season.season_number > 0) { // Ignorar temporada 0 (especiais)
                    val seasonNumber = season.season_number
                    println("🔍 [TMDB DEBUG] Buscando temporada $seasonNumber...")
                    
                    // USANDO O NOVO ENDPOINT DO SEU PROXY!
                    val seasonUrl = "$TMDB_PROXY_URL/tv/$seriesId/season/$seasonNumber"
                    println("🔗 [TMDB DEBUG] URL temporada: $seasonUrl")
                    
                    val seasonResponse = app.get(seasonUrl, timeout = 10_000)
                    println("📡 [TMDB DEBUG] Status temporada: ${seasonResponse.code}")
                    
                    if (seasonResponse.code == 200) {
                        val seasonData = seasonResponse.parsedSafe<TMDBSeasonResponse>()
                        seasonData?.episodes?.let { episodes ->
                            seasonsEpisodes[seasonNumber] = episodes
                            println("✅ [TMDB DEBUG] Temporada $seasonNumber: ${episodes.size} episódios")
                        }
                    } else {
                        println("❌ [TMDB DEBUG] Falha na temporada $seasonNumber")
                    }
                }
            }

            println("✅ [TMDB DEBUG] Total temporadas com dados: ${seasonsEpisodes.size}")
            seasonsEpisodes
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO ao buscar temporadas: ${e.message}")
            emptyMap()
        }
    }

    private suspend fun getTMDBDetails(id: Int, isTv: Boolean): TMDBDetailsResponse? {
        println("🔍 [TMDB DEBUG] Buscando detalhes para ID $id")
        
        return try {
            val type = if (isTv) "tv" else "movie"
            val url = "$TMDB_PROXY_URL/$type/$id"
            println("🔗 [TMDB DEBUG] URL detalhes: $url")
            
            val response = app.get(url, timeout = 10_000)
            println("📡 [TMDB DEBUG] Status: ${response.code}")
            
            if (response.code != 200) return null
            
            response.parsedSafe<TMDBDetailsResponse>()
        } catch (e: Exception) {
            println("❌ [TMDB DEBUG] ERRO detalhes: ${e.message}")
            null
        }
    }

    private fun getHighQualityTrailer(videos: List<TMDBVideo>?): String? {
        if (videos.isNullOrEmpty()) return null
        
        return videos.mapNotNull { video ->
            when {
                video.site == "YouTube" && video.type == "Trailer" && video.official == true ->
                    Triple(video.key, 10, "YouTube Trailer Oficial")
                video.site == "YouTube" && video.type == "Trailer" ->
                    Triple(video.key, 9, "YouTube Trailer")
                video.site == "YouTube" && video.type == "Teaser" && video.official == true ->
                    Triple(video.key, 8, "YouTube Teaser Oficial")
                video.site == "YouTube" && video.type == "Teaser" ->
                    Triple(video.key, 7, "YouTube Teaser")
                else -> null
            }
        }
        ?.sortedByDescending { it.second }
        ?.firstOrNull()
        ?.let { (key, _, _) -> "https://www.youtube.com/watch?v=$key" }
    }

    // ... (restante do código permanece similar) ...

    private suspend fun createLoadResponseWithTMDB(
        tmdbInfo: TMDBInfo,
        url: String,
        document: org.jsoup.nodes.Document,
        isAnime: Boolean,
        isSerie: Boolean,
        siteRecommendations: List<SearchResponse>
    ): LoadResponse {
        println("🏗️ [DEBUG] Criando resposta com dados TMDB")
        
        return if (isAnime || isSerie) {
            println("🏗️ [DEBUG] Criando série/Anime")
            
            // AGORA COM DADOS DO TMDB!
            val episodes = extractEpisodesWithTMDBInfo(
                document = document,
                url = url,
                tmdbInfo = tmdbInfo,
                isAnime = isAnime,
                isSerie = isSerie
            )

            println("🏗️ [DEBUG] Total de episódios: ${episodes.size}")
            val type = if (isAnime) TvType.Anime else TvType.TvSeries

            newTvSeriesLoadResponse(
                name = tmdbInfo.title ?: "",
                url = url,
                type = type,
                episodes = episodes
            ) {
                this.posterUrl = tmdbInfo.posterUrl
                this.backgroundPosterUrl = tmdbInfo.backdropUrl
                this.year = tmdbInfo.year
                this.plot = tmdbInfo.overview
                this.tags = tmdbInfo.genres

                tmdbInfo.actors?.let { actors ->
                    println("🏗️ [DEBUG] Adicionando ${actors.size} atores")
                    addActors(actors)
                }

                tmdbInfo.youtubeTrailer?.let { trailerUrl ->
                    println("🏗️ [DEBUG] Adicionando trailer: $trailerUrl")
                    addTrailer(trailerUrl)
                }

                this.recommendations = siteRecommendations.takeIf { it.isNotEmpty() }
                println("🏗️ [DEBUG] Recomendações: ${siteRecommendations.size}")
            }
        } else {
            // ... (código para filmes permanece igual) ...
        }
    }

    private suspend fun extractEpisodesWithTMDBInfo(
        document: org.jsoup.nodes.Document,
        url: String,
        tmdbInfo: TMDBInfo?,
        isAnime: Boolean,
        isSerie: Boolean = false
    ): List<Episode> {
        println("🔍 [DEBUG] Extraindo episódios da URL: $url")
        val episodes = mutableListOf<Episode>()

        val episodeElements = document.select("button.bd-play[data-url], a.episode-card, .episode-item, .episode-link, [class*='episode']")
        println("🔍 [DEBUG] Elementos de episódio encontrados: ${episodeElements.size}")

        if (episodeElements.isNotEmpty()) {
            episodeElements.forEachIndexed { index, element ->
                try {
                    val dataUrl = element.attr("data-url") ?: element.attr("href") ?: ""
                    if (dataUrl.isBlank()) return@forEachIndexed

                    val epNumber = extractEpisodeNumber(element, index + 1)
                    val seasonNumber = element.attr("data-season").toIntOrNull() ?: 1

                    // AGORA COM DADOS DO TMDB!
                    val tmdbEpisode = findTMDBEpisode(tmdbInfo, seasonNumber, epNumber)
                    
                    val episode = if (tmdbEpisode != null) {
                        // Episódio com dados do TMDB
                        newEpisode(fixUrl(dataUrl)) {
                            this.name = tmdbEpisode.name ?: "Episódio $epNumber"
                            this.season = seasonNumber
                            this.episode = epNumber
                            this.posterUrl = tmdbEpisode.still_path?.let { "$tmdbImageUrl/w300$it" }
                            this.description = tmdbEpisode.overview
                            
                            tmdbEpisode.air_date?.let { airDate ->
                                try {
                                    val dateFormatter = SimpleDateFormat("yyyy-MM-dd")
                                    val date = dateFormatter.parse(airDate)
                                    this.date = date.time
                                } catch (e: Exception) {}
                            }
                        }
                    } else {
                        // Episódio sem dados do TMDB
                        newEpisode(fixUrl(dataUrl)) {
                            this.name = "Episódio $epNumber"
                            this.season = seasonNumber
                            this.episode = epNumber
                        }
                    }

                    episodes.add(episode)
                } catch (e: Exception) {
                    println("❌ [DEBUG] Erro episódio $index: ${e.message}")
                }
            }
        }

        println("✅ [DEBUG] Total de episódios extraídos: ${episodes.size}")
        return episodes
    }

    private fun findTMDBEpisode(tmdbInfo: TMDBInfo?, season: Int, episode: Int): TMDBEpisode? {
        if (tmdbInfo == null) return null
        
        val episodes = tmdbInfo.seasonsEpisodes[season]
        if (episodes == null) {
            println("⚠️ [DEBUG] Temporada $season não encontrada no TMDB")
            return null
        }
        
        return episodes.find { it.episode_number == episode }
    }

    private fun extractEpisodeNumber(element: Element, default: Int): Int {
        return element.attr("data-ep").toIntOrNull() ?:
               element.selectFirst(".ep-number, .number, .episode-number")?.text()?.toIntOrNull() ?:
               Regex("Ep\\.?\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               Regex("Epis[oó]dio\\s*(\\d+)").find(element.text())?.groupValues?.get(1)?.toIntOrNull() ?:
               default
    }

    // ... (restante do código permanece) ...

    // ============ CLASSES DE DADOS ============
    
    private data class TMDBInfo(
        val id: Int,
        val title: String?,
        val year: Int?,
        val posterUrl: String?,
        val backdropUrl: String?,
        val overview: String?,
        val genres: List<String>?,
        val actors: List<Actor>?,
        val youtubeTrailer: String?,
        val duration: Int?,
        val seasonsEpisodes: Map<Int, List<TMDBEpisode>> = emptyMap()
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

    private data class TMDBTVDetailsResponse(
        @JsonProperty("seasons") val seasons: List<TMDBSeasonInfo>
    )

    private data class TMDBSeasonInfo(
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

    private data class TMDBDetailsResponse(
        @JsonProperty("overview") val overview: String?,
        @JsonProperty("backdrop_path") val backdrop_path: String?,
        @JsonProperty("runtime") val runtime: Int?,
        @JsonProperty("genres") val genres: List<TMDBGenre>?,
        @JsonProperty("credits") val credits: TMDBCredits?,
        @JsonProperty("videos") val videos: TMDBVideos?
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