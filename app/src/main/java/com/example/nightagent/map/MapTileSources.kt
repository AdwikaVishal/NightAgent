package com.example.nightagent.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.nightagent.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.ITileSource
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "MapTileSources"

/** OSM-compatible raster XYZ endpoint documented by MapTiler. */
private const val MAPTILER_BASE_URL = "https://api.maptiler.com/maps/streets-v4/256/"

/** Attribution required by MapTiler for maps based on OpenStreetMap data. */
private const val MAPTILER_ATTRIBUTION = "© MapTiler © OpenStreetMap contributors"

/** A user agent is part of the key's allowed-user-agent security rule. */
private const val MAP_USER_AGENT_SUFFIX = "NightAgentMapTiles/1.0"

/** Do not probe on every resume; avoid unnecessary tile requests. */
private const val PROBE_INTERVAL_MS = 60_000L

private var lastProbeAt = 0L

object MapTileSources {

    /**
     * Loads osmdroid configuration once per process-facing call and makes sure
     * debug logging is off: osmdroid can log full tile URLs, which would
     * include the MapTiler key.
     */
    fun initializeOsmdroid(context: Context) {
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = "${BuildConfig.APPLICATION_ID}/$MAP_USER_AGENT_SUFFIX"
            isDebugMode = false
            isDebugMapTileDownloader = false
        }
    }

    /** Exposed for unit tests: no key value is logged or hardcoded. */
    internal fun tileUrl(z: Int, x: Int, y: Int): String =
        MAPTILER_BASE_URL + "$z/$x/$y.png?key=" + BuildConfig.MAPTILER_API_KEY.trim()

    /** True only when a real, locally supplied key is available. */
    fun hasConfiguredKey(): Boolean {
        val key = BuildConfig.MAPTILER_API_KEY.trim()
        return key.isNotEmpty() &&
            !key.equals("YOUR_MAPTILER_API_KEY", ignoreCase = true) &&
            !key.equals("CHANGE_ME", ignoreCase = true)
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork ?: return false
            val capabilities = manager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        @Suppress("DEPRECATION")
        return manager.activeNetworkInfo?.isConnected == true
    }

    /** Called from the AndroidView factory: no network work on the UI thread. */
    fun configureMapView(mapView: MapView, context: Context) {
        initializeOsmdroid(context)
        applyTileSource(mapView, preferredTileSource(context))
        ensureAttributionOverlay(mapView, context)
    }

    /**
     * Verifies that MapTiler can actually serve tiles, then falls back to the
     * permitted OSM source when the key, network or service is unavailable.
     * Logs only coarse status information - never a URL or the key.
     */
    suspend fun refreshTileSource(mapView: MapView, context: Context, force: Boolean = false) {
        if (!hasConfiguredKey() || !isNetworkAvailable(context)) {
            applyTileSource(mapView, fallbackTileSource())
            Log.d(TAG, "Using OSM tile source (MapTiler key absent or device offline)")
            return
        }

        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastProbeAt < PROBE_INTERVAL_MS) return

        val reachable = probeMapTilerTile()
        lastProbeAt = SystemClock.elapsedRealtime()

        val source = if (reachable) mapTilerTileSource() else fallbackTileSource()
        applyTileSource(mapView, source)
        Log.d(TAG, "Tile source selected: ${source.name()}")
    }

    private fun preferredTileSource(context: Context): ITileSource =
        if (hasConfiguredKey() && isNetworkAvailable(context)) mapTilerTileSource() else fallbackTileSource()

    private fun mapTilerTileSource(): XYTileSource = XYTileSource(
        "MapTiler Streets",
        0,
        19,
        256,
        ".png?key=${BuildConfig.MAPTILER_API_KEY.trim()}",
        arrayOf(MAPTILER_BASE_URL),
        MAPTILER_ATTRIBUTION,
        TileSourcePolicy(
            2,
            TileSourcePolicy.FLAG_NO_BULK or
                TileSourcePolicy.FLAG_NO_PREVENTIVE or
                TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
        )
    )

    private fun fallbackTileSource(): ITileSource = TileSourceFactory.MAPNIK

    private fun applyTileSource(mapView: MapView, source: ITileSource) {
        if (mapView.tileProvider.tileSource.name() != source.name()) {
            mapView.setTileSource(source)
            mapView.invalidate()
        }
    }

    private fun ensureAttributionOverlay(mapView: MapView, context: Context) {
        if (mapView.overlays.none { it is CopyrightOverlay }) {
            mapView.overlays.add(CopyrightOverlay(context))
        }
    }

    /**
     * A single lightweight tile request. Catches auth/quota/outage failures
     * so the app can switch to the offline-cached OSM source without crashing.
     * The URL (and therefore the key) is never logged.
     */
    private suspend fun probeMapTilerTile(): Boolean = withContext(Dispatchers.IO) {
        var connection: HttpURLConnection? = null
        try {
            connection = URL(tileUrl(0, 0, 0)).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = true

            val status = connection.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "MapTiler tile probe returned HTTP $status")
                return@withContext false
            }
            val contentType = connection.contentType.orEmpty()
            if (!contentType.contains("image", ignoreCase = true)) {
                Log.w(TAG, "MapTiler tile probe returned a non-image response")
                return@withContext false
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "MapTiler tile probe failed (${e.javaClass.simpleName})")
            false
        } catch (e: Exception) {
            Log.w(TAG, "MapTiler tile probe failed (${e.javaClass.simpleName})")
            false
        } finally {
            connection?.disconnect()
        }
    }
}
