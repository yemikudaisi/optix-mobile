package app.etracker.tracker.data

/**
 * Pure rules for map-sheet packs (no Android classes, so they run in a plain JVM unit test).
 *
 * A "slot" is the place a pack fills: one map sheet of one series ("sheet|NGA-TOPO-100K|193")
 * or one overview series ("overview|NGA-TOPO-OVERVIEW"). Custom packs have no slot. When the
 * server re-issues a sheet it offers a new pack id for the same slot; the old one stays on the
 * phone (and on the map) until the new one has downloaded and verified.
 */
object PackSlots {
    /** The minimum the rules need to know about a pack. */
    data class Key(val id: String, val slot: String?)

    fun slot(kind: String, series: String, sheetNo: String): String? = when (kind) {
        "sheet" -> if (series.isNotBlank() && sheetNo.isNotBlank()) "sheet|$series|$sheetNo" else null
        "overview" -> if (series.isNotBlank()) "overview|$series" else null
        else -> null
    }

    /**
     * Installed packs to delete now. A pack the server still offers stays. One it no longer
     * offers stays only while an offered pack fills the same slot and is not on the phone yet
     * (the replacement); otherwise it goes.
     */
    fun packsToDelete(installed: List<Key>, offered: List<Key>): Set<String> {
        val offeredIds = offered.map { it.id }.toSet()
        val installedIds = installed.map { it.id }.toSet()
        val pendingSlots = offered.filter { it.slot != null && it.id !in installedIds }.mapNotNull { it.slot }.toSet()
        return installed.filter { it.id !in offeredIds && (it.slot == null || it.slot !in pendingSlots) }.map { it.id }.toSet()
    }

    /** Old packs to delete once [done] (an offered pack) is installed: same slot, no longer offered. */
    fun supersededBy(done: Key, installed: List<Key>, offered: List<Key>): Set<String> {
        val slot = done.slot ?: return emptySet()
        val offeredIds = offered.map { it.id }.toSet()
        return installed.filter { it.id != done.id && it.slot == slot && it.id !in offeredIds }.map { it.id }.toSet()
    }

    /** For each offered pack not on the phone, the installed older pack of the same slot it replaces. */
    fun replacements(installed: List<Key>, offered: List<Key>): Map<String, String> {
        val offeredIds = offered.map { it.id }.toSet()
        val installedIds = installed.map { it.id }.toSet()
        val old = installed.filter { it.slot != null && it.id !in offeredIds }.associateBy { it.slot }
        return offered.filter { it.slot != null && it.id !in installedIds }
            .mapNotNull { o -> old[o.slot]?.let { o.id to it.id } }.toMap()
    }

    /** Natural order for sheet numbers: "9" < "10" < "171 NE" < "171 NW" < "193". */
    val naturalOrder: Comparator<String> = Comparator { a, b ->
        val ca = chunks(a)
        val cb = chunks(b)
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val nx = x.toBigIntegerOrNull()
            val ny = y.toBigIntegerOrNull()
            val c = when {
                nx != null && ny != null -> nx.compareTo(ny)
                nx != null -> -1
                ny != null -> 1
                else -> x.compareTo(y, ignoreCase = true)
            }
            if (c != 0) return@Comparator c
        }
        ca.size.compareTo(cb.size)
    }

    private fun chunks(s: String): List<String> =
        Regex("\\d+|[^\\d\\s]+").findAll(s.trim()).map { it.value }.toList()
}
