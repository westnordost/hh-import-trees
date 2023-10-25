import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

const val SAFE_TREE_DISTANCE = 10.0
// Hamburg. So groß wegen Neuwerk
val IMPORT_AREA = BoundingBox(53.3951118, 8.1044993, 54.0276500, 10.3252805)
// Relation #62782 in OSM ist Hamburg
const val IMPORT_AREA_RELATION = 62782L

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

    println("Lade Straßenbaumkataster-Datei...")
    val katasterTrees = parseKataster(BufferedInputStream(FileInputStream(input)))
    // Erwarte, dass alle Bäume im Kataster eine Nummer haben
    for (katasterTree in katasterTrees) {
        check(katasterTree.tags["ref"] != null)
    }

    println("Lade Bäume aus OpenStreetMap via Overpass...")
    val overpassQuery = """
        area(36000$IMPORT_AREA_RELATION)->.searchArea;
        node["natural"="tree"](area.searchArea);
        out meta;
        """.trimIndent()
    val urlQuery = URLEncoder.encode(overpassQuery, Charsets.UTF_8)
    val url = URL("http://overpass-api.de/api/interpreter?data=$urlQuery")
    val osmTrees = parseOsmNodes(BufferedInputStream(url.openStream()))

    println("Verarbeite...")

    val katasterTreesById = katasterTrees.associateBy { it.tags["ref"]!! }

    val (osmKatasterTrees, osmOtherTrees) = osmTrees.partition {
        it.tags["operator"] == "BUKEA Hamburg" && it.tags["ref"] != null
    }

    val osmKatasterTreesById = osmKatasterTrees.associateBy { it.tags["ref"]!! }

    // sortiere alle Bäume in ein Raster aus ~30x30m
    val osmOtherTreesRaster = LatLonRaster(IMPORT_AREA, 0.0005)
    osmOtherTrees.forEach { osmOtherTreesRaster.insert(it.position) }

    // neu gepflanzt aber schon (ohne Baumnummer) in OSM vorhanden
    val addedTreesNearOtherOsmTrees = ArrayList<OsmNode>()
    // neu gepflanzt
    val addedTrees = ArrayList<OsmNode>()
    // nach wie vor bestehend aber ein Attribut hat sich verändert
    val updatedTrees = ArrayList<OsmNode>()

    katasterTreesById.values.forEach { katasterTree ->
        val osmTree = osmKatasterTreesById[katasterTree.tags["ref"]]
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
            val anyOtherOsmTreeIsNear = osmOtherTreesRaster
                .getAll(katasterTree.position.enclosingBoundingBox(SAFE_TREE_DISTANCE))
                .any { katasterTree.position.distanceTo(it) < SAFE_TREE_DISTANCE }

            if (anyOtherOsmTreeIsNear) {
                addedTreesNearOtherOsmTrees.add(katasterTree)
            } else {
                addedTrees.add(katasterTree)
            }
        }
    }

    // vermutlich gefällt, jedenfalls nicht mehr im Kataster
    val removedTrees = osmKatasterTreesById.values.filter { osmTree ->
        osmTree.tags["ref"] !in katasterTreesById.keys
    }

    println()

    if (addedTrees.isNotEmpty() || updatedTrees.isNotEmpty() || removedTrees.isNotEmpty()) {
        println("""
            Schreibe ${outputModifiedDeleted}...
            --------------------
            ${addedTrees.size} Straßenbäume kommen neu hinzu
            ${updatedTrees.size} Straßenbäume wurden aktualisiert
            ${removedTrees.size} Straßenbäume wurden entfernt
            
            """.trimIndent()
        )
        writeOsmChange(addedTrees, updatedTrees, removedTrees, outputModifiedDeleted)
    }

    if (addedTreesNearOtherOsmTrees.isNotEmpty()) {
        println("""
            Schreibe ${outputAdded}...
            --------------------
            ${addedTreesNearOtherOsmTrees.size} Straßenbäume kommen hinzu, aber sind jeweils unter ${SAFE_TREE_DISTANCE}m von einem bereits gemappten Baum entfernt
            ACHTUNG: Daher ist für diese ein manuelles Review erforderlich
            
        """.trimIndent())
        writeOsm(addedTreesNearOtherOsmTrees, outputAdded)
    }
}
