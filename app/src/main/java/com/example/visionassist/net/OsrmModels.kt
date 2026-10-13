package com.example.visionassist.net.models

data class OsrmResponse(
    val routes: List<Route>?
) {
    data class Route(
        val distance: Double,
        val legs: List<Leg>,
        val geometry: Geometry
    )

    data class Leg(
        val steps: List<Step>
    )

    data class Step(
        val name: String?,
        val maneuver: Maneuver
    )

    data class Maneuver(
        val type: String,
        val modifier: String?,
        val location: List<Double> // [lon, lat]
    )

    data class Geometry(
        val coordinates: List<List<Double>> // [ [lon,lat], ... ]
    )
}
