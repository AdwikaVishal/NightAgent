package com.example.nightagent.journey

import com.example.nightagent.journey.model.RoutePoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object RouteProvider {
    /**
     * Fetches a walking route from OSRM public demo server. Returns empty list on failure.
     * For production, configure a self-hosted OSRM/GraphHopper or Google Directions API.
     */
    suspend fun fetchWalkingRoute(startLat: Double, startLng: Double, destLat: Double, destLng: Double): List<RoutePoint> = withContext(Dispatchers.IO) {
        try {
            val coords = "${startLng},${startLat};${destLng},${destLat}"
            val url = URL("https://router.project-osrm.org/route/v1/foot/$coords?overview=full&geometries=geojson")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "NightAgent/1.0")
            }
            conn.inputStream.use { stream ->
                val body = stream.bufferedReader().readText()
                val json = JSONObject(body)
                val routes = json.optJSONArray("routes") ?: return@withContext emptyList()
                if (routes.length() == 0) return@withContext emptyList()
                val geometry = routes.getJSONObject(0).getJSONObject("geometry")
                val coordinates = geometry.getJSONArray("coordinates")
                val points = ArrayList<RoutePoint>(coordinates.length())
                for (i in 0 until coordinates.length()) {
                    val c = coordinates.getJSONArray(i)
                    points.add(RoutePoint(latitude = c.getDouble(1), longitude = c.getDouble(0)))
                }
                points
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun geocode(query: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        try {
            val q = URLEncoder.encode(query, "UTF-8")
            val url = URL("https://nominatim.openstreetmap.org/search?format=json&limit=1&q=$q")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 10_000
                requestMethod = "GET"
                setRequestProperty("User-Agent", "NightAgent/1.0 (safety app)")
            }
            conn.inputStream.use { stream ->
                val body = stream.bufferedReader().readText()
                val arr = org.json.JSONArray(body)
                if (arr.length() == 0) return@withContext null
                val obj = arr.getJSONObject(0)
                obj.getDouble("lat") to obj.getDouble("lon")
            }
        } catch (_: Exception) {
            null
        }
    }
}
