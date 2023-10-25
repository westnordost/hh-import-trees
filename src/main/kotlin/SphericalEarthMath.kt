import kotlin.math.*

// the below is just copied from StreetComplete

fun normalizeLongitude(lon: Double): Double {
    var normalizedLon = lon % 360 // normalizedLon is -360..360
    if (normalizedLon < -180) normalizedLon += 360
    else if (normalizedLon >= 180) normalizedLon -= 360
    return normalizedLon
}

/** In meters. See https://en.wikipedia.org/wiki/Earth_radius#Mean_radius */
const val EARTH_RADIUS = 6371000.0

/** Returns the distance from this point to the other point */
fun LatLon.distanceTo(pos: LatLon, globeRadius: Double = EARTH_RADIUS): Double =
    angularDistance(
        latitude.toRadians(),
        longitude.toRadians(),
        pos.latitude.toRadians(),
        pos.longitude.toRadians()
    ) * globeRadius


/**
 * Return a bounding box that contains a circle with the given radius around this point. In
 * other words, it is a square centered at the given position and with a side length of radius*2.
 */
fun LatLon.enclosingBoundingBox(radius: Double, globeRadius: Double = EARTH_RADIUS): BoundingBox {
    val distance = sqrt(2.0) * radius
    val min = translate(distance, 225.0, globeRadius)
    val max = translate(distance, 45.0, globeRadius)
    return BoundingBox(min, max)
}

/** Returns a new point in the given distance and angle from the this point */
fun LatLon.translate(distance: Double, angle: Double, globeRadius: Double = EARTH_RADIUS): LatLon {
    val pair = translate(
        latitude.toRadians(),
        longitude.toRadians(),
        angle.toRadians(),
        distance / globeRadius
    )
    return createTranslated(pair.first.toDegrees(), pair.second.toDegrees())
}

/** Return a new point translated from the point [φ1], [λ1] in the initial bearing [α1] and angular distance [σ12] */
private fun translate(φ1: Double, λ1: Double, α1: Double, σ12: Double): Pair<Double, Double> {
    val y = sin(φ1) * cos(σ12) + cos(φ1) * sin(σ12) * cos(α1)
    val a = cos(φ1) * cos(σ12) - sin(φ1) * sin(σ12) * cos(α1)
    val b = sin(σ12) * sin(α1)
    val x = sqrt(a.pow(2) + b.pow(2))
    val φ2 = atan2(y, x)
    val λ2 = λ1 + atan2(b, a)
    return Pair(φ2, λ2)
}

/** Returns the distance of two points on a sphere */
private fun angularDistance(φ1: Double, λ1: Double, φ2: Double, λ2: Double): Double {
    // see https://mathforum.org/library/drmath/view/51879.html for derivation
    val Δλ = λ2 - λ1
    val Δφ = φ2 - φ1
    val a = sin(Δφ / 2).pow(2) + cos(φ1) * cos(φ2) * sin(Δλ / 2).pow(2)
    return 2 * asin(sqrt(a))
}

private fun createTranslated(latitude: Double, longitude: Double): LatLon {
    var lat = latitude
    var lon = longitude
    lon = normalizeLongitude(lon)
    var crossedPole = false
    // north pole
    if (lat > 90) {
        lat = 180 - lat
        crossedPole = true
    } else if (lat < -90) {
        lat = -180 - lat
        crossedPole = true
    }
    if (crossedPole) {
        lon += 180.0
        if (lon > 180) lon -= 360.0
    }
    return LatLon(lat, lon)
}

private fun Double.toRadians() = this / 180.0 * PI
private fun Double.toDegrees() = this / PI * 180.0