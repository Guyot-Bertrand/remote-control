package fr.remotecontrol.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SearchTest {
    @Test fun fold_strips_accents_and_ligatures() {
        assertEquals("dix petits negres", fold("Dix Petits Nègres"))
        assertEquals("oedipe", fold("Œdipe"))
    }

    @Test fun query_building() {
        assertEquals("l* etr*", buildFtsQuery("l'etr"))
        assertNull(buildFtsQuery("  "))
        assertEquals("or*", buildFtsQuery("\"*-OR"))
        assertEquals("patelliere*", buildFtsQuery("Patellière"))
    }
}
