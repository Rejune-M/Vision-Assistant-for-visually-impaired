package com.example.visionassist

import kotlin.math.*

object GeoUtils {
    fun metersToFeet(m: Double): Int = (m * 3.28084).roundToInt()

    fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val R = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * asin(sqrt(a))
        return R * c
    }

    fun distancePointToSegmentMeters(
        lat: Double, lon: Double,
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Double {
        val x = (lon - lon1) * cos(Math.toRadians((lat + lat1) / 2))
        val y = (lat - lat1)
        val x2 = (lon2 - lon1) * cos(Math.toRadians((lat2 + lat1) / 2))
        val y2 = (lat2 - lat1)

        val dot = x * x2 + y * y2
        val len2 = x2 * x2 + y2 * y2
        val t = if (len2 == 0.0) 0.0 else (dot / len2).coerceIn(0.0, 1.0)

        val projX = x2 * t
        val projY = y2 * t

        val dx = x - projX
        val dy = y - projY

        val metersPerDegLat = 111320.0
        val metersPerDegLon = 111320.0 * cos(Math.toRadians(lat))

        val mx = dx * metersPerDegLon
        val my = dy * metersPerDegLat

        return sqrt(mx * mx + my * my)
    }

    fun distanceToPolylineMeters(
        lat: Double,
        lon: Double,
        points: List<Pair<Double, Double>>
    ): Double {
        if (points.size < 2) return Double.MAX_VALUE
        var best = Double.MAX_VALUE
        for (i in 0 until points.size - 1) {
            val a = points[i]
            val b = points[i + 1]
            val d = distancePointToSegmentMeters(lat, lon, a.first, a.second, b.first, b.second)
            if (d < best) best = d
        }
        return best
    }
}
