import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.*
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

fun writeOSM(trees: List<Tree>, file: File) {
    val fos = FileOutputStream(file)
    val bos = BufferedOutputStream(fos)
    XMLOutputFactory.newFactory().createXMLStreamWriter(bos).apply {
        element("osm") {
            attr("version", "0.6")
            writeCharacters("\n")
            var i = 0
            for (tree in trees) {
                element("node") {
                    attr("id", -(tree.id ?: ++i))
                    attr("version", 1)
                    attr("lat", tree.position!!.lat.format(7))
                    attr("lon", tree.position!!.lon.format(7))
                    writeCharacters("\n")
                    tag("natural", "tree")
                    tag("ref:hh-bukea", tree.reference)
                    tag("hh_bukea_id", tree.id)
                    tag("start_date", tree.yearOfPlanting)
                    tag("species", tree.species)
                    tag("species:de", tree.speciesDe)
                    tag("genus", tree.genus)
                    tag("genus:de", tree.genusDe)
                    tag("circumference", tree.trunkCircumference?.format(2))
                    tag("diameter_crown", tree.crownDiameter)
                    tag("check_date", tree.checkDate)
                }
            }
        }
        flush()
        close()
    }
}

private fun XMLStreamWriter.element(name: String, block: XMLStreamWriter.() -> Unit) {
    writeStartElement(name)
    block()
    writeEndElement()
    writeCharacters("\n")
}

private fun XMLStreamWriter.attr(k: String, v: Any?) = v?.let {
    writeAttribute(k, v.toString())
}

private fun XMLStreamWriter.tag(k: String, v: Any?) = v?.let {
    element("tag") {
        attr("k", k)
        attr("v", v)
    }
}

private fun Float.format(decimals: Int) = String.format(Locale.US, "%.${decimals}f", this)
private fun Double.format(decimals: Int) = String.format(Locale.US, "%.${decimals}f", this)