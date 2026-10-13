package com.example.visionassist.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

object NominatimApi {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // returns Pair(lat, lon) or null
    fun geocode(place: String): Pair<Double, Double>? {
        val url = "https://nominatim.openstreetmap.org/search?q=" +
                java.net.URLEncoder.encode(place, "UTF-8") +
                "&format=json&limit=1"

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VisionAssist/1.0 (Android)")
            .build()

        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null
            val arr = JSONArray(body)
            if (arr.length() == 0) return null
            val obj = arr.getJSONObject(0)
            val lat = obj.getString("lat").toDoubleOrNull() ?: return null
            val lon = obj.getString("lon").toDoubleOrNull() ?: return null
            return Pair(lat, lon)
        }
    }
}
