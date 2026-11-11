package app.etracker.tracker.data

import app.etracker.tracker.data.PackSlots.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackSlotsTest {
    private val s193 = PackSlots.slot("sheet", "NGA-TOPO-100K", "193")
    private val s171 = PackSlots.slot("sheet", "NGA-TOPO-100K", "171 NW")
    private val ov = PackSlots.slot("overview", "NGA-TOPO-OVERVIEW", "")

    @Test fun slots() {
        assertEquals("sheet|NGA-TOPO-100K|193", s193)
        assertEquals("overview|NGA-TOPO-OVERVIEW", ov)
        assertNull(PackSlots.slot("custom", "X", "1"))
        assertNull(PackSlots.slot("sheet", "", "1"))
    }

    @Test fun withdrawnPackWithoutReplacementIsDeleted() {
        val installed = listOf(Key("MAP-1", s193), Key("MAP-2", null))
        assertEquals(setOf("MAP-1", "MAP-2"), PackSlots.packsToDelete(installed, emptyList()))
    }

    @Test fun oldSheetStaysUntilReplacementIsInstalled() {
        val offered = listOf(Key("MAP-9", s193))
        assertEquals(emptySet<String>(), PackSlots.packsToDelete(listOf(Key("MAP-1", s193)), offered))
        assertEquals(mapOf("MAP-9" to "MAP-1"), PackSlots.replacements(listOf(Key("MAP-1", s193)), offered))
        // replacement in: old goes
        val both = listOf(Key("MAP-1", s193), Key("MAP-9", s193))
        assertEquals(setOf("MAP-1"), PackSlots.packsToDelete(both, offered))
        assertEquals(setOf("MAP-1"), PackSlots.supersededBy(Key("MAP-9", s193), both, offered))
    }

    @Test fun otherSlotDoesNotKeepAPack() {
        val offered = listOf(Key("MAP-9", s171))
        assertEquals(setOf("MAP-1"), PackSlots.packsToDelete(listOf(Key("MAP-1", s193)), offered))
        assertEquals(emptyMap<String, String>(), PackSlots.replacements(listOf(Key("MAP-1", s193)), offered))
    }

    @Test fun offeredPacksAndCustomPacksStay() {
        val installed = listOf(Key("MAP-1", s193), Key("MAP-3", null), Key("MAP-4", ov))
        val offered = listOf(Key("MAP-1", s193), Key("MAP-3", null), Key("MAP-4", ov))
        assertEquals(emptySet<String>(), PackSlots.packsToDelete(installed, offered))
        assertEquals(emptySet<String>(), PackSlots.supersededBy(Key("MAP-1", s193), installed, offered))
    }

    @Test fun overviewReplacement() {
        val offered = listOf(Key("MAP-8", ov))
        assertEquals(emptySet<String>(), PackSlots.packsToDelete(listOf(Key("MAP-4", ov)), offered))
    }

    @Test fun naturalSheetOrder() {
        val sorted = listOf("193", "9", "171 NW", "10", "171 NE", "171").sortedWith(PackSlots.naturalOrder)
        assertEquals(listOf("9", "10", "171", "171 NE", "171 NW", "193"), sorted)
    }
}
