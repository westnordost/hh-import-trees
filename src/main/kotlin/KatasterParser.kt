import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.events.XMLEvent
import kotlin.collections.ArrayList
import kotlin.collections.HashMap

private data class KatasterTree(
    val tags: Map<String, String>,
    val isHPA: Boolean
)

fun parseKataster(inputStream: InputStream): List<OsmNode> {
    val reader = XMLInputFactory.newDefaultFactory().createXMLStreamReader(inputStream)
    val trees = ArrayList<KatasterTree>()
    var tree: MutableMap<String, String>? = null
    val elements = ArrayList<String>()
    val crsFactory = CRSFactory()
    var publicationTimeStamp: String? = null
    var isHPA = false
    val transform = CoordinateTransformFactory().createTransform(
        crsFactory.createFromName("epsg:25832"),
        crsFactory.createFromName("epsg:4326")
    )
    while (reader.hasNext()) {
        when (reader.next()) {
            XMLEvent.START_ELEMENT -> {
                val name = reader.name.localPart
                when (name) {
                    "strassenbaumkataster" -> isHPA = false
                    "strassenbaumkataster_hpa" -> isHPA = true
                    "FeatureCollection" -> publicationTimeStamp = reader.attributes["timeStamp"]!!
                    "featureMember" -> tree = HashMap()
                    // check if points are in expected coordinate system
                    "Point" -> check(reader.attributes["srsName"] == "EPSG:25832")
                }
                elements.add(name)
            }
            XMLEvent.END_ELEMENT -> {
                val name = reader.name.localPart
                if (name == "featureMember") {
                    trees.add(KatasterTree(tree!!, isHPA))
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
    return trees.mapIndexed { index, t ->
        transformKatasterToOsm(t.tags, -(index + 1L), t.isHPA, publicationTimeStamp!!)
    }
}

private fun transformKatasterToOsm(
    tags: Map<String, String>,
    id: Long,
    isHPA: Boolean,
    publicationTimeStamp: String
): OsmNode {
    val osmTags = HashMap<String, String>()
    osmTags["natural"] = "tree"
    osmTags.putAll(tags.mapNotNull { (k, v) ->
        when (k) {
            "baumid" -> "ref:bukea" to v
            "pflanzjahr" -> {
                // einige Bäume im Quelldatensatz haben Pflanzjahr = 0
                val year = v.toIntOrNull()?.takeIf { it != 0 }
                if (year != null) "start_date" to v else null
            }
            "kronendurchmesser" -> {
                // einige Bäume im Quelldatensatz haben kronendurchmesser = 0
                val diameter = v.toIntOrNull()?.takeIf { it != 0 }
                if (diameter != null) "diameter_crown" to v else null
            }
            "stammumfang" -> {
                // einige Bäume im Quelldatensatz haben stammumfang = 0
                val circumference = v.toDoubleOrNull()?.takeIf { it != 0.0 }
                if (circumference != null) "circumference" to (circumference / 100).format(2) else null
            }
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
        timestamp = publicationTimeStamp,
        position = LatLon(tags.getValue("lat").toDouble(), tags.getValue("lon").toDouble()),
        tags = osmTags
    )
}
