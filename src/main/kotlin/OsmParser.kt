import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.events.XMLEvent

fun parseOsmNodes(inputStream: InputStream): List<OsmNode> {
    val reader = XMLInputFactory.newDefaultFactory().createXMLStreamReader(inputStream)
    var node: OsmNode? = null
    val nodes = ArrayList<OsmNode>()

    while (reader.hasNext()) {
        when (reader.next()) {
            XMLEvent.START_ELEMENT -> {
                val name = reader.name.localPart
                if (name == "node") {
                    val attrs = reader.attributes
                    node = OsmNode(
                        id = attrs["id"]!!.toLong(),
                        position = LatLon(attrs["lat"]!!.toDouble(), attrs["lon"]!!.toDouble()),
                        version = attrs["version"]!!.toInt(),
                        timestamp = attrs["timestamp"]!!,
                        tags = HashMap()
                    )
                }
                if (name == "tag") {
                    val attrs = reader.attributes
                    val key = attrs["k"]!!
                    val value = attrs["v"]!!
                    node!!.tags[key] = value
                }
            }
            XMLEvent.END_ELEMENT -> {
                val name = reader.name.localPart
                if (name == "node") {
                    nodes.add(node!!)
                    node = null
                }
            }
        }
    }
    return nodes
}
