// just copied from StreetComplete

data class BoundingBox(val min: LatLon, val max: LatLon) {
    constructor(
        minLatitude: Double,
        minLongitude: Double,
        maxLatitude: Double,
        maxLongitude: Double
    ) : this(LatLon(minLatitude, minLongitude), LatLon(maxLatitude, maxLongitude))

    init {
        require(min.latitude <= max.latitude) {
            "Min latitude ${min.latitude} is greater than max latitude ${max.latitude}"
        }
    }

    val crosses180thMeridian get() =
        normalizeLongitude(min.longitude) > normalizeLongitude(max.longitude)
}