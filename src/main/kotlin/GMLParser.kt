import org.locationtech.proj4j.CRSFactory
import org.locationtech.proj4j.CoordinateTransformFactory
import org.locationtech.proj4j.ProjCoordinate
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.events.XMLEvent

fun parseGML(file: File): List<Tree> {
    val fis = FileInputStream(file)
    val bis = BufferedInputStream(fis)
    val reader =  XMLInputFactory.newDefaultFactory().createXMLStreamReader(bis)
    val trees = ArrayList<Tree>()
    var tree: Tree? = null
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
                    tree = Tree()
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
                val str = reader.text.trim()
                tree?.let { t ->
                    when (elements.last()) {
                        "baumid" -> t.id = str.toInt()
                        "baumnummer" -> t.reference = str
                        "pflanzjahr" -> t.yearOfPlanting = str.toInt()
                        "kronendurchmesser" -> t.crownDiameter = str.toInt()
                        "stammumfang" -> t.trunkCircumference = str.toFloat() / 100f
                        "gattung_latein" -> t.genus = str
                        "gattung_deutsch" -> t.genusDe = str
                        "art_latein" -> t.species = str
                        "art_deutsch" -> t.speciesDe = str
                        "stand_bearbeitung" -> t.checkDate = str
                        "pos" -> {
                            val posStr = str.split(' ', ignoreCase = false, limit = 2)
                            val x = posStr[0].toDouble()
                            val y = posStr[1].toDouble()
                            val result = ProjCoordinate()
                            transform.transform(ProjCoordinate(x, y), result)
                            t.position = LatLon(result.y, result.x)
                        }
                        else -> {}
                    }
                }
            }
        }
    }
    return trees
}
