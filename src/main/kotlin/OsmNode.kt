data class OsmNode(
    val id: Long,
    val version: Int,
    val timestamp: String?,
    val lat: Double,
    val lon: Double,
    val tags: MutableMap<String, String>
)
