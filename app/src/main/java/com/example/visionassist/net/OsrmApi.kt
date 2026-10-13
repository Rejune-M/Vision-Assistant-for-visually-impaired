package com.example.visionassist.net

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object OsrmApi {

    data class Maneuver(
        val lat: Double,
        val lon: Double,
        val type: String,
        val modifier: String?,
        val name: String?
    )

    data class RouteResult(
        val totalDistanceMeters: Double,
        val maneuvers: List<Maneuver>,
        val pointsLatLon: List<Pair<Double, Double>>
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun routeWalk(lat1: Double, lon1: Double, lat2: Double, lon2: Double): RouteResult? {
        val url = "https://router.project-osrm.org/route/v1/walking/" +
                "$lon1,$lat1;$lon2,$lat2" +
                "?overview=full&geometries=geojson&steps=true"

        val req = Request.Builder().url(url).build()

        client.newCall(req).execute().use { res ->
            if (!res.isSuccessful) return null
            val body = res.body?.string() ?: return null

            val root = JSONObject(body)
            val routes = root.getJSONArray("routes")
            if (routes.length() == 0) return null

            val r0 = routes.getJSONObject(0)
            val dist = r0.getDouble("distance")

            // polyline points from geojson coordinates [lon,lat]
            val geometry = r0.getJSONObject("geometry")
            val coords = geometry.getJSONArray("coordinates")
            val points = ArrayList<Pair<Double, Double>>(coords.length())
            for (i in 0 until coords.length()) {
                val p = coords.getJSONArray(i)
                val lon = p.getDouble(0)
                val lat = p.getDouble(1)
                points.add(Pair(lat, lon))
            }

            val maneuvers = ArrayList<Maneuver>()
            val legs = r0.getJSONArray("legs")
            val leg0 = legs.getJSONObject(0)
            val steps = leg0.getJSONArray("steps")
            for (i in 0 until steps.length()) {
                val step = steps.getJSONObject(i)
                val man = step.getJSONObject("maneuver")
                val loc = man.getJSONArray("location") // [lon,lat]
                maneuvers.add(
                    Maneuver(
                        lat = loc.getDouble(1),
                        lon = loc.getDouble(0),
                        type = man.getString("type"),
                        modifier = if (man.has("modifier")) man.getString("modifier") else null,
                        name = if (step.has("name")) step.getString("name") else null
                    )
                )
            }

            return RouteResult(dist, maneuvers, points)
        }
    }
}
