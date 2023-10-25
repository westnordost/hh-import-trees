import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.events.XMLEvent
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

fun parseKataster(inputStream: InputStream): List<OsmNode> {
    val reader = XMLInputFactory.newDefaultFactory().createXMLStreamReader(inputStream)
    val trees = ArrayList<Map<String, String>>()
    var tree: MutableMap<String, String>? = null
    val elements = ArrayList<String>()
    val crsFactory = CRSFactory()
    val transform = CoordinateTransformFactory().createTransform(
        crsFactory.createFromName("epsg:25832"),
        crsFactory.createFromName("epsg:4326")
    )
    while (reader.hasNext()) {
        when (reader.next()) {
            XMLEvent.START_ELEMENT -> {
                val name = reader.name.localPart
                if (name == "featureMember") {
                    tree = HashMap()
                }
                // check if points are in expected coordinate system
                if (name == "Point" && reader.prefix == "gml") {
                    val attrs = reader.attributes.toMap()
                    check(attrs["srsName"] == "EPSG:25832")
                }
                elements.add(name)
            }
            XMLEvent.END_ELEMENT -> {
                val name = reader.name.localPart
                if (name == "featureMember") {
                    trees.add(tree!!)
                    tree = null
                }
                elements.removeLast()
            }
            XMLEvent.CHARACTERS -> {
                val value = reader.text.trim()
                val key = elements.last()
                if (tree == null) continue
                // transform epsg:25832 to epsg:4326 coordinates
                if (key == "pos") {
                    val posStr = value.split(' ', ignoreCase = false, limit = 2)
                    val x = posStr[0].toDouble()
                    val y = posStr[1].toDouble()
                    val result = ProjCoordinate()
                    transform.transform(ProjCoordinate(x, y), result)

                    tree["lat"] = result.y.toString()
                    tree["lon"] = result.x.toString()
                } else {
                    tree[key] = value
                }
            }
        }
    }
    return trees.mapIndexed { index, tags -> transformKatasterToOsm(tags, -(index + 1L)) }
}

private fun transformKatasterToOsm(tags: Map<String, String>, id: Long): OsmNode {
    val osmTags = HashMap<String, String>()
    osmTags["natural"] = "tree"
    // TODO was ist mit HPA?!
    // TODO: stand_bearbeitung gibt es nur bei HPA, für BUKEA wäre das wohl das Datum der Veröffentlichung
    osmTags["operator"] = "BUKEA Hamburg"
    osmTags.putAll(tags.mapNotNull { (k, v) ->
        when (k) {
            "baumid" -> "ref" to v
            "pflanzjahr" -> "start_date" to v
            "kronendurchmesser" -> "diameter_crown" to v
            "stammumfang" -> "circumference" to (v.toDouble() / 100).format(2)
            "stand_bearbeitung" -> "check_date" to v
            "gattung_latein" -> "genus" to v
            "gattung_deutsch" -> "genus:de" to v
            "art_latein" -> "species" to v
            "art_deutsch" -> "species:de" to v
            else -> null
        }
    })

    return OsmNode(
        id = id,
        version = 1,
        timestamp = null,
        lat = tags.getValue("lat").toDouble(),
        lon = tags.getValue("lon").toDouble(),
        tags = osmTags
    )
}
