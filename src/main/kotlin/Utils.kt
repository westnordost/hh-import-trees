import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import javax.xml.stream.XMLStreamReader

val XMLStreamReader.attributes get() = sequence<Pair<String, String>> {
    for (i in 0 until attributeCount) {
        yield(getAttributeLocalName(i) to getAttributeValue(i))
    }
}.toMap()

fun Double.format(decimals: Int) = String.format(Locale.US, "%.${decimals}f", this)

private fun String.toCheckDate(): LocalDate? {
    val groups = OSM_CHECK_DATE_REGEX.matchEntire(this)?.groupValues ?: return null
    val year = groups[1].toIntOrNull() ?: return null
    val month = groups[2].toIntOrNull() ?: return null
    val day = groups[3].toIntOrNull() ?: 1

    return try {
        LocalDate.of(year, month, day)
    } catch (e: IllegalArgumentException) {
        null
    }
}

fun String.toInstant(): Instant? =
    toCheckDate()?.atStartOfDay(ZoneId.of("Europe/Berlin"))?.toInstant()

/** Date format of the tags used for recording the date at which the element or tag with the given
 *  key should be checked again. Accepted date formats: 2000-11-11 but also 2000-11 */
private val OSM_CHECK_DATE_REGEX = Regex("([0-9]{4})-([0-9]{2})(?:-([0-9]{2}))?")
