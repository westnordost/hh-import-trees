data class Tree(
    var id: Int? = null,
    var reference: String? = null,
    var position: LatLon? = null,
    var yearOfPlanting: Int? = null,
    var crownDiameter: Int? = null,
    var trunkCircumference: Float? = null,
    var genus: String? = null,
    var genusDe: String? = null,
    var species: String? = null,
    var speciesDe: String? = null,
    var checkDate: String? = null
)

data class LatLon(val lat: Double, val lon: Double)