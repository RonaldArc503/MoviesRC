package sv.edu.catolica.rex.repository

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import sv.edu.catolica.rex.models.MediaItem
import sv.edu.catolica.rex.models.Section
import sv.edu.catolica.rex.network.AllCalidadScraper
import sv.edu.catolica.rex.network.TmdbService
import sv.edu.catolica.rex.network.smart.PelispediaScraper
import java.util.ArrayList

data class HomeDataResult(
    val sections: List<Section>,
    val heroCandidates: List<MediaItem>,
    val allItemsToEnrich: List<MediaItem>
)

class HomeRepository {

    suspend fun fetchHomeData(): HomeDataResult = withContext(Dispatchers.IO) {
        coroutineScope {
            // Ejecutar llamadas en paralelo mediante async
            val featuredDeferred = async { safeCall { AllCalidadScraper.getFeaturedMovies() } }
            val popularDeferred = async { safeCall { AllCalidadScraper.getPopularMovies() } }
            val latestDeferred = async { safeCall { AllCalidadScraper.getMovies(1) } }
            val seriesDeferred = async { safeCall { AllCalidadScraper.getTvShows(1) } }
            val popularSeriesDeferred = async { safeCall { AllCalidadScraper.getPopularTvShows() } }

            val tmdbPopularDeferred = async { safeCall { TmdbService.getPopularHomeItems(20) } }
            val tmdbTrendingDeferred = async { safeCall { TmdbService.getTrendingHomeItems(20) } }
            val tmdbNowPlayingDeferred = async { safeCall { TmdbService.getNowPlayingMovies(16) } }
            val tmdbUpcomingDeferred = async { safeCall { TmdbService.getUpcomingMovies(16) } }
            val tmdbTopRatedMoviesDeferred = async { safeCall { TmdbService.getTopRatedMovies(16) } }
            val tmdbTopRatedTvDeferred = async { safeCall { TmdbService.getTopRatedTvShows(16) } }
            val tmdbOnTheAirDeferred = async { safeCall { TmdbService.getOnTheAirTvShows(16) } }
            val tmdbDiscoverDeferred = async { safeCall { TmdbService.getDiscoverItems(20) } }

            val pelispediaDeferred = async { safeCall { PelispediaScraper().getHomeItems(12) } }

            val featured = featuredDeferred.await()
            val popular = popularDeferred.await()
            val latest = latestDeferred.await()
            val series = seriesDeferred.await()
            val popularSeries = popularSeriesDeferred.await()

            val tmdbPopular = tmdbPopularDeferred.await()
            val tmdbTrending = tmdbTrendingDeferred.await()
            val tmdbNowPlaying = tmdbNowPlayingDeferred.await()
            val tmdbUpcoming = tmdbUpcomingDeferred.await()
            val tmdbTopRatedMovies = tmdbTopRatedMoviesDeferred.await()
            val tmdbTopRatedTv = tmdbTopRatedTvDeferred.await()
            val tmdbOnTheAir = tmdbOnTheAirDeferred.await()
            val tmdbDiscover = tmdbDiscoverDeferred.await()

            val pelispediaItems = pelispediaDeferred.await()

            val featuredItems = mapItems(featured, 10)
            val popularItems = mapItems(popular, 16)
            val latestItems = mapItems(latest, 16)
            val seriesItems = mapItems(series, 16)
            val popularSeriesItems = mapItems(popularSeries, 16)

            val sections = buildSections(
                featuredItems, popularItems, latestItems,
                seriesItems, popularSeriesItems,
                tmdbPopular, tmdbTrending,
                tmdbNowPlaying, tmdbUpcoming,
                tmdbTopRatedMovies, tmdbTopRatedTv,
                tmdbOnTheAir, tmdbDiscover
            )

            if (!pelispediaItems.isNullOrEmpty()) {
                sections.add(Section("Pelispedia - Destacadas", pelispediaItems))
            }

            val heroCandidates = ArrayList<MediaItem>()
            if (tmdbTrending != null) heroCandidates.addAll(tmdbTrending)
            if (tmdbPopular != null) heroCandidates.addAll(tmdbPopular)
            heroCandidates.addAll(featuredItems)
            heroCandidates.addAll(popularItems)

            val allItems = ArrayList<MediaItem>()
            allItems.addAll(featuredItems)
            allItems.addAll(popularItems)
            allItems.addAll(latestItems)
            allItems.addAll(seriesItems)
            allItems.addAll(popularSeriesItems)
            if (tmdbPopular != null) allItems.addAll(tmdbPopular)
            if (tmdbTrending != null) allItems.addAll(tmdbTrending)
            if (tmdbNowPlaying != null) allItems.addAll(tmdbNowPlaying)
            if (tmdbUpcoming != null) allItems.addAll(tmdbUpcoming)
            if (tmdbTopRatedMovies != null) allItems.addAll(tmdbTopRatedMovies)
            if (tmdbTopRatedTv != null) allItems.addAll(tmdbTopRatedTv)
            if (tmdbOnTheAir != null) allItems.addAll(tmdbOnTheAir)
            if (tmdbDiscover != null) allItems.addAll(tmdbDiscover)

            HomeDataResult(sections, heroCandidates, allItems)
        }
    }

    suspend fun enrichItems(items: List<MediaItem>) = withContext(Dispatchers.IO) {
        try {
            TmdbService.enrichMediaItemsParallel(items)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private inline fun <T> safeCall(block: () -> T): T? {
        return try {
            block()
        } catch (e: Exception) {
            null
        }
    }

    private fun mapItems(items: List<AllCalidadScraper.ContentItem>?, limit: Int): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        if (items == null) return result
        val max = Math.min(limit, items.size)
        for (i in 0 until max) {
            result.add(AllCalidadScraper.toMediaItem(items[i]))
        }
        return result
    }

    private fun buildSections(
        fi: List<MediaItem>, pi: List<MediaItem>,
        li: List<MediaItem>, si: List<MediaItem>, psi: List<MediaItem>,
        tmdbPopular: List<MediaItem>?, tmdbTrending: List<MediaItem>?,
        tmdbNowPlaying: List<MediaItem>?, tmdbUpcoming: List<MediaItem>?,
        tmdbTopRatedMovies: List<MediaItem>?, tmdbTopRatedTv: List<MediaItem>?,
        tmdbOnTheAir: List<MediaItem>?, tmdbDiscover: List<MediaItem>?
    ): MutableList<Section> {
        val s = ArrayList<Section>()
        s.add(Section("🔥 Destacadas", if (fi.isEmpty()) li else fi))
        if (!tmdbNowPlaying.isNullOrEmpty()) {
            s.add(Section("🎥 Ahora en Cines", tmdbNowPlaying))
        }
        s.add(Section("📈 Populares del Mes", if (pi.isEmpty()) li else pi))
        if (!tmdbOnTheAir.isNullOrEmpty()) {
            s.add(Section("📡 Series en Emisión", tmdbOnTheAir))
        }
        s.add(Section("🎬 Últimas Películas", li))
        if (!tmdbTopRatedMovies.isNullOrEmpty()) {
            s.add(Section("🏆 Mejor Valoradas Películas", tmdbTopRatedMovies))
        }
        if (si.isNotEmpty()) s.add(Section("📺 Series", si))
        if (!tmdbTopRatedTv.isNullOrEmpty()) {
            s.add(Section("🌟 Mejor Valoradas Series", tmdbTopRatedTv))
        }
        if (psi.isNotEmpty()) s.add(Section("⭐ Series Populares", psi))
        if (!tmdbUpcoming.isNullOrEmpty()) {
            s.add(Section("📅 Próximos Estrenos", tmdbUpcoming))
        }
        if (!tmdbPopular.isNullOrEmpty()) {
            s.add(Section("🔥 Populares TMDB", tmdbPopular))
        }
        if (!tmdbTrending.isNullOrEmpty()) {
            s.add(Section("⚡ Tendencias", tmdbTrending))
        }
        if (!tmdbDiscover.isNullOrEmpty()) {
            s.add(Section("✨ Descubrimiento", tmdbDiscover))
        }
        return s
    }
}
