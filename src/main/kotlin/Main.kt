import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

// Minimale Distanz, die ein Baum aus dem Kataster zu einem Baum der bereits in OSM gemappt ist haben darf, damit der
// Baum aus dem Kataster ohne Review hinzugefügt werden kann.
const val SAFE_TREE_DISTANCE = 8.0
// Maximale Distanz, die ein Baum aus dem Kataster zu einem Baum der bereits in OSM gemappt ist haben darf, damit der
// Baum aus dem Kataster ohne Review mit diesem Baum gemergt wird.
const val TREE_MERGE_DISTANCE = 1.5
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
    val outputModifiedDeleted = File("$inputPath-aenderungen.osc")
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

    val baumkatasterOperators = setOf("BUKEA Hamburg", "Hamburg Port Authority")
    val (osmKatasterTrees, osmOtherTrees) = osmTrees.partition {
        it.tags["operator"] in baumkatasterOperators && it.tags["ref"] != null
    }

    val osmKatasterTreesById = osmKatasterTrees.associateBy { it.tags["ref"]!! }

    // sortiere alle nicht-Kataster Bäume in ein Raster aus ~30x30m
    val osmOtherTreesRaster = LatLonRaster(IMPORT_AREA, 0.0005)
    osmOtherTrees.forEach { osmOtherTreesRaster.insert(it.position) }
    // und noch ein Index nach Position
    val osmOtherTreesByPosition = osmOtherTrees.associateBy { it.position }

    // neu hinzugekommen aber ein Baum der bereits in OSM vorhanden hat ist relativ nah dran
    val addedTreesNearOtherOsmTrees = ArrayList<OsmNode>()
    // neu hinzugekommen
    val addedTrees = ArrayList<OsmNode>()
    // nach wie vor bestehend aber ein Attribut hat sich verändert
    val updatedTrees = ArrayList<OsmNode>()

    for (katasterTree in katasterTrees) {
        val osmTree = osmKatasterTreesById[katasterTree.tags["ref"]]
        // Kataster-Baum bereits in OSM-Daten vorhanden
        if (osmTree != null) {

            // Die Position des Bäumes wird NICHT upgedatet: Mapper sollen die Bäume herumschieben können,
            // die baumids (refs) der Kataster-Bäume sind nämlich stabil
            val somethingChanged = katasterTree.tags.any { (k,v) -> osmTree.tags[k] != v }
            if (somethingChanged) {
                // nur übernehmen, wenn Datum aus Baumkataster neuer als zuletzt von Mappern bearbeitet
                val osmCheckDate = osmTree.checkDateOrLastEditDate()
                val katasterCheckDate = katasterTree.checkDateOrLastEditDate()

                if (osmCheckDate.isBefore(katasterCheckDate)) {
                    osmTree.tags.putAll(katasterTree.tags)
                    updatedTrees.add(osmTree)
                }
                // (ansonsten, Update des Katasters ignorieren)
            }

        // Kataster-Baum noch nicht in OSM-Daten vorhanden
        } else {
            val nearestOtherOsmTree = osmOtherTreesRaster
                .getAll(katasterTree.position.enclosingBoundingBox(SAFE_TREE_DISTANCE))
                .map { osmOtherTreesByPosition[it]!! }
                // OSM-Bäume die bereits ein "ref" haben, herausfiltern, um folgende Situation korrekt zu handlen:
                // Zwei Kataster-Bäume A,B sind sehr dicht an OSM-Bäumen X,Y dran, so dass diese gemergt werden sollen.
                // Allerdings sind sowohl A als auch B dichter dran an X als an Y. Ohne dass X ausscheidet wenn z.B.
                // A damit gemergt wird, würden A und B beide mit X mergen und sich daher gegenseitig überschreiben,
                // während Y nicht mit irgendeinem Baum aus dem Kataster gemergt wird.
                .filter { it.tags["ref"] == null }
                .minByOrNull { katasterTree.position.distanceTo(it.position) }

            val nearestOtherOsmTreeDistance = nearestOtherOsmTree?.position?.let { katasterTree.position.distanceTo(it) }

            if (nearestOtherOsmTreeDistance != null && nearestOtherOsmTreeDistance < SAFE_TREE_DISTANCE) {
                if (nearestOtherOsmTreeDistance <= TREE_MERGE_DISTANCE) {
                    val osmCheckDate = nearestOtherOsmTree.checkDateOrLastEditDate()
                    val katasterCheckDate = katasterTree.checkDateOrLastEditDate()

                    // nur übernehmen, wenn Datum aus Baumkataster neuer als zuletzt von Mappern bearbeitet
                    if (osmCheckDate.isBefore(katasterCheckDate)) {
                        nearestOtherOsmTree.tags.putAll(katasterTree.tags)
                        updatedTrees.add(nearestOtherOsmTree)
                    }
                    // ansonsten muss manuell reviewt werden
                    else {
                        addedTreesNearOtherOsmTrees.add(katasterTree)
                    }
                } else {
                    addedTreesNearOtherOsmTrees.add(katasterTree)
                }
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
            ${addedTreesNearOtherOsmTrees.size} Straßenbäume kommen hinzu, aber deren Mittelpunkte sind jeweils 
            zwischen $TREE_MERGE_DISTANCE und $SAFE_TREE_DISTANCE Metern von einem bereits gemappten Baum entfernt.
            ACHTUNG: Diese sollten manuell reviewt werden.
            
        """.trimIndent())
        writeOsm(addedTreesNearOtherOsmTrees, outputAdded)
    }
}

private fun OsmNode.checkDateOrLastEditDate(): Instant =
    tags["check_date"]?.toInstant()
    ?: tags["survey:date"]?.toInstant()
    ?: Instant.parse(timestamp)