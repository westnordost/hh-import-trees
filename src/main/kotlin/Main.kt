import java.io.File
import kotlin.io.path.Path
import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val inputPath = args.getOrNull(0)
        ?: return println("Usage: hh-import-trees [INPUT FILE]")

    val input = File(inputPath)
    if (!input.isFile) {
        return println("Input file does not exist")
    }

    val output = File("$inputPath.osm")
    if (output.exists()) {
        return println("Output file already exists")
    }

    val trees = parseGML(input)
        .filter { it.position != null }
        .sortedBy { it.id ?: 0 }

    writeOSM(trees, output)
}
