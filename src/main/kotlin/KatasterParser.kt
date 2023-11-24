import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import java.io.InputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.events.XMLEvent
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import kotlin.math.PI

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
    osmTags["denotation"] = "avenue"
    osmTags.putAll(tags.mapNotNull { (k, v) ->
        when (k) {
            "baumid" -> (if (isHPA) "ref:hpa" else "ref:bukea") to v
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
            "sorte_latein" -> "taxon" to v
            else -> null
        }
    })

    // wenn species gleicher Wert wie genus, entfernen:
    // auch wenn explizit geschrieben steht, dass die Art unbekannt ist
    if (osmTags["species"] == osmTags["genus"] || osmTags["species"]?.endsWith(" spec.") == true) {
        osmTags.remove("species")
    }
    if (osmTags["species:de"] == osmTags["genus:de"] || osmTags["species:de"]?.endsWith(" Art unbekannt") == true) {
        osmTags.remove("species:de")
    }

    // wenn taxon gleicher Wert wie Species oder genus, entfernen
    if (osmTags["taxon"] == osmTags["species"] || osmTags["taxon"] == osmTags["genus"]) {
        osmTags.remove("taxon")
    }
    // taxon:cultivar aus taxon extrahieren
    val taxon = osmTags["taxon"]
    val taxonCultivarRegex = Regex(".*'(.*)'")
    if (taxon != null) {
        val match = taxonCultivarRegex.matchEntire(taxon)
        if (match != null) {
            osmTags["taxon:cultivar"] = match.groupValues[1]
            osmTags.remove("taxon")
        }
    }

    // SEHR implausible Daten entfernen
    val trunkCircumference = osmTags["circumference"]?.toDouble()
    val crownDiameter = osmTags["diameter_crown"]?.toDouble()
    if (trunkCircumference != null && crownDiameter != null) {
        val ratio = crownDiameter * PI / trunkCircumference
        if (ratio < 2 || ratio > 100) {
            osmTags.remove("circumference")
            osmTags.remove("diameter_crown")
        }
    }

    return OsmNode(
        id = id,
        version = 1,
        timestamp = publicationTimeStamp,
        position = LatLon(tags.getValue("lat").toDouble(), tags.getValue("lon").toDouble()),
        tags = osmTags
    )
}
