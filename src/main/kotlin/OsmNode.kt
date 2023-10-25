data class OsmNode(
    val id: Long,
    val version: Int,
    val timestamp: String?,
    val position: LatLon,
    val tags: MutableMap<String, String>
)
