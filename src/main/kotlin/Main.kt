import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.format.DateTimeParseException

// Minimale Distanz, die ein Baum aus dem Kataster zu einem Baum der bereits in OSM gemappt ist haben darf, damit der
// Baum aus dem Kataster ohne Review als neuer Baum hinzugefügt werden kann.
const val SAFE_TREE_DISTANCE = 6.5 // = ungefähr "andere Straßenseite" in Wohnstraßen
// Maximale Distanz, die ein Baum aus dem Kataster zu einem Baum der bereits in OSM gemappt ist haben darf, damit der
// Baum aus dem Kataster ohne Review mit diesem Baum gemergt wird. (von Mittelpunkt des Stammes zu Mittelpunkt des 
// Stammes)
const val TREE_MERGE_DISTANCE = 2.5
// Hamburg. So groß wegen Neuwerk
val IMPORT_AREA = BoundingBox(53.3951118, 8.1044993, 54.0276500, 10.3252805)
// Relation #62782 in OSM ist Hamburg
const val IMPORT_AREA_RELATION = 62782L

fun main(args: Array<String>) {
    val inputPath = args.getOrNull(0)
    val oldInputPath = args.getOrNull(1)
    val oldImportDate = args.getOrNull(2)

    if (inputPath == null || (oldInputPath != null && oldImportDate == null)) {
        println("Nutzung: hh-import-trees <aktuelles Straßenbaumkataster> [<Straßenbaumkataster des letzten Imports> <Timestamp an dem der letzte Import abgeschlossen war>]")
        return
    }

    val input = File(inputPath)
    if (!input.isFile) {
        return println("aktuelle Straßenbaumkataster-Datei existiert nicht")
    }

    val outputToBeReviewed = File("$inputPath-review.osm")
    if (outputToBeReviewed.exists()) {
        return println("Ausgabe-Datei ${outputToBeReviewed.name} existiert bereits")
    }
    val outputToBeApplied = File("$inputPath-aenderungen.osc")
    if (outputToBeApplied.exists()) {
        return println("Ausgabe-Datei ${outputToBeApplied.name} existiert bereits")
    }

    val oldInput: File?
    if (oldInputPath == null) {
        println("Importiere das gesamte Straßenbaumkataster.")
        println("Achtung: Dies soll nur für den initialen Import genutzt werden!")
        println()
        oldInput = null
    } else {
        oldInput = File(oldInputPath)
        if (!oldInput.isFile) {
            return println("Straßenbaumkataster-Datei des letzten Imports existiert nicht")
        }
        try {
            Instant.parse(oldImportDate!!)
        } catch (e: DateTimeParseException) {
            return println("Erwarte Timestamp im Format 2011-12-03T10:15:30Z")
        }

        println("Importiere was sich seit dem letzten Import des Straßenbaumkatasters geändert hat.")
        println()
    }

    print("Lade aktuelle Straßenbaumkataster-Datei...")
    val newKatasterTrees = loadKatasterTrees(input, null)
    val newKatasterTreesById = newKatasterTrees.associateBy { it.katasterId!! }
    println(" " + newKatasterTrees.size + " Bäume gelesen")
    println()

    val katasterTrees: List<OsmNode>
    if (oldInput != null) {
        print("Lade Straßenbaumkataster-Datei des letzten Imports...")
        val oldKatasterTrees = loadKatasterTrees(oldInput, oldImportDate)
        println(" " + oldKatasterTrees.size + " Bäume gelesen")

        val oldKatasterTreesById = oldKatasterTrees.associateBy { it.katasterId!! }

        val removedTreesCount = oldKatasterTreesById.count { it.key !in newKatasterTreesById.keys }
        val addedTreesCount = newKatasterTreesById.count { it.key !in oldKatasterTreesById.keys }
        println("$removedTreesCount Bäume wurden entfernt")
        println("$addedTreesCount Bäume wurden neu gepflanzt")
        var changedTreeCount = 0
        var unchangedTreeCount = 0

        katasterTrees = newKatasterTrees
            .mapNotNull { katasterTree ->
                val oldKatasterTree = oldKatasterTreesById[katasterTree.katasterId]

                // ein neuer Baum
                if (oldKatasterTree == null) {
                    katasterTree
                }
                // ein Baum dessen Daten sich geändert haben (z.B. neuer Stammumfang)
                else if (oldKatasterTree.tags != katasterTree.tags) {
                    // wir nutzen hier das Veröffentlichungsdatum des alten Baumes weil wir ja nur wissen, dass sich
                    // der Baum IRGENDWANN ZWISCHEN Veröffentlichungsdatum des alten und neuen Datensatzes geändert
                    // haben muss
                    changedTreeCount++
                    katasterTree.copy(timestamp = oldKatasterTree.timestamp)
                }
                else {
                    unchangedTreeCount++
                    null
                }
            }

        println("$changedTreeCount Bäume haben sich geändert")
        println("$unchangedTreeCount Bäume bleiben unverändert")
        println()
    } else {
        katasterTrees = newKatasterTrees
    }

    print("Lade Bäume aus OpenStreetMap via Overpass...")
    val osmTrees = retrieveOsmTreesInArea(IMPORT_AREA_RELATION)
    println(" " + osmTrees.size + " Bäume gelesen")

    val katasterTreesById = katasterTrees.associateBy { it.katasterId!! }

    val (osmKatasterTrees, osmOtherTrees) = osmTrees.partition { it.katasterId != null }

    val osmKatasterTreesById = osmKatasterTrees.associateBy { it.katasterId!! }

    // sortiere alle nicht-Kataster Bäume in ein Raster aus ~30x30m
    val osmOtherTreesRaster = LatLonRaster(IMPORT_AREA, 0.0005)
    osmOtherTrees.forEach { osmOtherTreesRaster.insert(it.position) }
    // und noch ein Index nach Position
    val osmOtherTreesByPosition = osmOtherTrees.associateBy { it.position }

    // neu hinzugekommen aber ein Baum der bereits in OSM vorhanden hat, ist relativ nah dran
    val toBeReviewedTrees = ArrayList<OsmNode>()
    // neu hinzugekommen
    val addedTrees = ArrayList<OsmNode>()
    // nach wie vor bestehend aber ein Attribut hat sich verändert
    val updatedTrees = ArrayList<OsmNode>()

    var toBeReviewedBecauseChangedInOsmInTheMeantimeCount = 0
    var toBeReviewedBecauseCloseToOtherTreeCount = 0

    for (katasterTree in katasterTrees) {
        val osmTree = osmKatasterTreesById[katasterTree.katasterId]
        // Kataster-Baum bereits in OSM-Daten vorhanden
        if (osmTree != null) {

            // Die Position des Bäumes wird NICHT upgedatet: Mapper sollen die Bäume herumschieben können,
            // die baumids (refs) der Kataster-Bäume sind nämlich stabil
            val somethingChanged = katasterTree.tags.any { (k,v) -> osmTree.tags[k] != v }
            if (somethingChanged) {
                // für diese Felder immer der autorativen Quelle vertrauen
                val conflictingChanges = osmTree.tags
                    .filter { (k,v) -> katasterTree.tags.containsKey(k) && katasterTree.tags[k] != v }
                    .filterKeys { it !in listOf("circumference", "diameter_crown") }

                // nur übernehmen, wenn Datum aus Baumkataster neuer als zuletzt von Mappern bearbeitet
                val osmCheckDate = osmTree.checkDateOrLastEditDate()
                val katasterCheckDate = katasterTree.checkDateOrLastEditDate()

                if (osmCheckDate.isBefore(katasterCheckDate) || conflictingChanges.isEmpty()) {
                    // → kann ohne Review aktualisiert werden
                    osmTree.tags.putAll(katasterTree.tags)
                    updatedTrees.add(osmTree)
                }
                else {
                    // → ansonsten, muss manuell überprüft werden
                    toBeReviewedTrees.add(katasterTree)
                    toBeReviewedBecauseChangedInOsmInTheMeantimeCount++
                }
            }
        }
        // Kataster-Baum noch nicht in OSM-Daten vorhanden
        else {
            val nearOtherOsmTrees = osmOtherTreesRaster
                .getAll(katasterTree.position.enclosingBoundingBox(SAFE_TREE_DISTANCE))
                .filter { katasterTree.position.distanceTo(it) < SAFE_TREE_DISTANCE }
                .map { osmOtherTreesByPosition[it]!! }
                .toList()

            // in OSM bisher kein Baum im Umfeld vorhanden
            if (nearOtherOsmTrees.isEmpty()) {
                // → Katasterbaum kann ohne Review hinzugefügt werden
                addedTrees.add(katasterTree)
            }
            // in OSM mehr als ein Baum im Umfeld vorhanden
            else if (nearOtherOsmTrees.size > 1) {
                // → Baum muss manuell reviewt werden
                toBeReviewedTrees.add(katasterTree)
                toBeReviewedBecauseCloseToOtherTreeCount++
            }
            // in OSM genau ein Baum im Umfeld vorhanden
            else {
                val nearOtherOsmTree = nearOtherOsmTrees.single()

                val osmCheckDate = nearOtherOsmTree.checkDateOrLastEditDate()
                val katasterCheckDate = katasterTree.checkDateOrLastEditDate()

                // und dieser Baum ist nah genug dran
                // und Bearbeitungsdatum aus Baumkataster ist jünger als zuletzt von Mappern bearbeitet
                // und Zusammenführen des Katasterbaumes mit dem OSM-Baum überschreibt keine Daten aus OSM mit anderen
                //     Daten (z.B. andere Baumart)
                if (katasterTree.position.distanceTo(nearOtherOsmTree.position) <= TREE_MERGE_DISTANCE
                    && katasterCheckDate.isAfter(osmCheckDate)
                    && katasterTree.tags.hasNoConflictsWith(nearOtherOsmTree.tags)
                ) {
                    // → Tags des Katasterbaumes ohne Review zum vorhandenen OSM Baum hinzufügen
                    nearOtherOsmTree.tags.putAll(katasterTree.tags)
                    updatedTrees.add(nearOtherOsmTree)
                }
                // ansonsten...
                else {
                    // → Baum muss manuell reviewt werden
                    toBeReviewedTrees.add(katasterTree)
                    toBeReviewedBecauseCloseToOtherTreeCount++
                }
            }
        }
    }

    // vermutlich gefällt, jedenfalls nicht mehr im Kataster
    val removedTrees = osmKatasterTreesById
        // gegen Bäume in aktueller Kataster-Datei checken, nicht katasterTreesById weil dies bei
        // jedem außer dem ersten Import ja nur die Änderungen enthält!
        .filterKeys { it !in newKatasterTreesById.keys }
        .map { it.value }

    println()

    if (addedTrees.isNotEmpty() || updatedTrees.isNotEmpty() || removedTrees.isNotEmpty()) {
        println("""
            Schreibe ${outputToBeApplied}...
            --------------------
            ${addedTrees.size} Straßenbäume kommen neu hinzu
            ${updatedTrees.size} Straßenbäume wurden aktualisiert
            ${removedTrees.size} Straßenbäume wurden entfernt
            
            """.trimIndent()
        )
        writeOsmChange(addedTrees, updatedTrees, removedTrees, outputToBeApplied)
    }

    if (toBeReviewedTrees.isNotEmpty()) {
        println("""
            Schreibe ${outputToBeReviewed}...
            --------------------
            $toBeReviewedBecauseChangedInOsmInTheMeantimeCount Straßenbäume wurden zwischenzeitlich in OSM editiert und
            $toBeReviewedBecauseCloseToOtherTreeCount neue Straßenbäume sind jeweils zwischen $TREE_MERGE_DISTANCE und $SAFE_TREE_DISTANCE Metern von einem bereits gemappten Baum entfernt.
            ACHTUNG: Diese sollten manuell reviewt werden.
            
        """.trimIndent())
        writeOsm(toBeReviewedTrees, outputToBeReviewed)
    }
}

private fun loadKatasterTrees(file: File, importTimeStamp: String?): List<OsmNode> {
    return parseKataster(BufferedInputStream(FileInputStream(file)), importTimeStamp)
}

private fun retrieveOsmTreesInArea(areaId: Long): List<OsmNode> {
    val overpassQuery = """
        area(36000$areaId)->.searchArea;
        node["natural"="tree"](area.searchArea);
        out meta;
        """.trimIndent()
    val urlQuery = URLEncoder.encode(overpassQuery, Charsets.UTF_8)
    val url = URL("http://overpass-api.de/api/interpreter?data=$urlQuery")
    return parseOsmNodes(BufferedInputStream(url.openStream()))
}

private fun OsmNode.checkDateOrLastEditDate(): Instant =
    tags["check_date"]?.toInstant()
    ?: tags["survey:date"]?.toInstant()
    ?: Instant.parse(timestamp)

private fun Map<String, String>.hasNoConflictsWith(other: Map<String,String>): Boolean =
    all { (k, v) -> !other.containsKey(k) || other[k] == v }


private enum class Operator { BUKEA, HPA }

private val OsmNode.katasterId: Pair<Operator, String>? get() =
    tags["ref:bukea"]?.let { Pair(Operator.BUKEA, it) } ?:
    tags["ref:hpa"]?.let { Pair(Operator.HPA, it) }