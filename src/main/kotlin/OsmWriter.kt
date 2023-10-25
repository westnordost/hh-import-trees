import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.xml.stream.XMLOutputFactory
import javax.xml.stream.XMLStreamWriter

fun writeOsm(nodes: List<OsmNode>, file: File) {
    val fos = FileOutputStream(file)
    val bos = BufferedOutputStream(fos)
    XMLOutputFactory.newFactory().createXMLStreamWriter(bos).write {
        element("osm") {
            attr("version", "0.6")
            attr("generator", "hh-import-trees")
            writeCharacters("\n")
            for (node in nodes) {
                osmNode(node)
            }
        }
    }
}

fun writeOsmChange(added: List<OsmNode>, modified: List<OsmNode>, deleted: List<OsmNode>, file: File) {
    val fos = FileOutputStream(file)
    val bos = BufferedOutputStream(fos)
    XMLOutputFactory.newFactory().createXMLStreamWriter(bos).write {
        element("osmChange") {
            attr("version", "0.6")
            attr("generator", "hh-import-trees")
            writeCharacters("\n")
            element("create") {
                writeCharacters("\n")
                added.forEach { osmNode(it) }
            }
            element("modify") {
                writeCharacters("\n")
                modified.forEach { osmNode(it) }
            }
            element("delete") {
                writeCharacters("\n")
                deleted.forEach { osmNode(it) }
            }
        }
    }
}

private fun XMLStreamWriter.write(block: XMLStreamWriter.() -> Unit) {
    apply(block)
    flush()
    close()
}

private fun XMLStreamWriter.element(name: String, block: XMLStreamWriter.() -> Unit) {
    writeStartElement(name)
    block()
    writeEndElement()
    writeCharacters("\n")
}

private fun XMLStreamWriter.osmNode(node: OsmNode) {
    element("node") {
        attr("id", node.id)
        attr("version", node.version)
        attr("lat", node.position.latitude.format(7))
        attr("lon", node.position.longitude.format(7))
        writeCharacters("\n")
        for ((key, value) in node.tags) {
            element("tag") {
                attr("k", key)
                attr("v", value)
            }
        }
    }
}

private fun XMLStreamWriter.attr(k: String, v: Any?) = v?.let {
    writeAttribute(k, v.toString())
}
