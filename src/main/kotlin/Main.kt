import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

fun main(args: Array<String>) {
    val inputPath = args.getOrNull(0)
        ?: return println("Nutzung: hh-import-trees [Straßenbaumkataster-Datei]")

    val input = File(inputPath)
    if (!input.isFile) {
        return println("Straßenbaumkataster-Datei existiert nicht")
    }

    val outputAdded = File("$inputPath-neu.osm")
    if (outputAdded.exists()) {
        return println("Ausgabe-Datei ${outputAdded.name} existiert bereits")
    }
    val outputModifiedDeleted = File("$inputPath-aktualisiert_oder_entfernt.osc")
    if (outputModifiedDeleted.exists()) {
        return println("Ausgabe-Datei ${outputModifiedDeleted.name} existiert bereits")
    }

    println("Lade Straßenbaumkataster...")
    val katasterTrees = parseKataster(BufferedInputStream(FileInputStream(input)))
    // Erwarte dass alle Bäume im Kataster eine Nummer haben
    for (katasterTree in katasterTrees) {
        check(katasterTree.tags["ref"] != null)
    }
    val katasterTreesById = parseKataster(BufferedInputStream(FileInputStream(input)))
        .associateBy { it.tags["ref"]!! }

    println("Lade Bäume aus OpenStreetMap...")
    // Relation #62782 is Hamburg
    val overpassQuery = """
        area(3600062782)->.searchArea;
        node["natural"="tree"](area.searchArea);
        out meta;
        """.trimIndent()
    val urlQuery = URLEncoder.encode(overpassQuery, Charsets.UTF_8)
    val url = URL("http://overpass-api.de/api/interpreter?data=$urlQuery")
    val osmTrees = parseOsmNodes(BufferedInputStream(url.openStream()))

    val osmTreesById = osmTrees
        // nur die Straßenbaumkataster-Bäume
        .filter { it.tags["operator"] == "BUKEA Hamburg" && it.tags["ref"] != null }
        .associateBy { it.tags["ref"]!! }

    println("Verarbeite...")

    // neu gepflanzt
    val addedTrees = ArrayList<OsmNode>()
    // nach wie vor bestehend aber ein Attribut hat sich verändert
    val updatedTrees = ArrayList<OsmNode>()

    katasterTreesById.values.forEach { katasterTree ->
        val osmTree = osmTreesById[katasterTree.tags["ref"]]
        if (osmTree != null) {
            // Die Position des Bäumes wird NICHT upgedatet: OSMler sollen die Bäume ein paar Pixel herumschieben können
            // Die baumids (refs) der Kataster-Bäume sind nämlich stabil
            val somethingChanged = katasterTree.tags.any { (k,v) -> osmTree.tags[k] != v }
            if (somethingChanged) {

                val osmCheckDate = osmTree.tags["check_date"]?.toInstant()
                    ?: osmTree.timestamp?.let { Instant.parse(it) }
                val katasterCheckDate = katasterTree.tags["check_date"]?.toInstant()
                if (osmCheckDate == null || katasterCheckDate == null || osmCheckDate.isBefore(katasterCheckDate)) {
                    osmTree.tags.putAll(katasterTree.tags)
                    updatedTrees.add(osmTree)
                }
            }
        } else {
            addedTrees.add(katasterTree)
        }
    }

    // vermutlich gefällt, jedenfalls nicht mehr im Kataster
    val removedTrees = osmTreesById.values.filter { osmTree ->
        osmTree.tags["ref"] !in katasterTreesById.keys
    }


    if (updatedTrees.isNotEmpty() || removedTrees.isNotEmpty()) {
        println()
        println("Schreibe ${outputModifiedDeleted}...")
        println("--------------------")
        println("Diese Datei im OsmChange-Format enthält alle Straßenbäume die aktualisiert ")
        println("werden sollen weil sich ihre Attribute im Kataster verändert haben, sowie alle ")
        println("Bäume die entfernt werden sollen weil sie nicht mehr im Kataster geführt werden")
        println()
        writeOsmChange(updatedTrees, removedTrees, outputModifiedDeleted)
    }


    if (addedTrees.isNotEmpty()) {
        println()
        println("Schreibe ${outputAdded}...")
        println("--------------------")
        println("Diese Datei im Osm-Format enthält alle Straßenbäume aus dem Kataster die zu ")
        println("OSM hinzugefügt werden sollen weil noch kein Baum mit dieser Nummer in OSM")
        println("vorhanden ist.")
        println("ACHTUNG: Manuelles Review erforderlich, da viele dieser Bäume schon gemappt ")
        println("sind - nur eben bisher ohne Baumnummer des Katasters")
        println()

        writeOsm(addedTrees, outputAdded)
    }
}
