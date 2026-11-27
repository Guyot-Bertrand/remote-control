package fr.remotecontrol.ui.work

import fr.remotecontrol.net.RatingDto
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

class WorkNotesTest {
    private fun r(code: String, name: String, value: Double? = null, url: String? = null) = RatingDto(code, name, value, url)

    @Test fun full_example() {
        val ui = notesUi(
            listOf(
                r("IM", "IMDb", 7.0, "https://imdb/x"),
                r("SC", "Sens Critique", 6.2, "https://sc/x"),
                r("SC@ES", "Éric Schwald", 8.0, "https://sc/es"),
                r("SC@AG", "Antoine Goya", 7.0),
                r("NP", "Ma note", 7.0),
                r("NT", "Technique", 5.0),
                r("WK", "Wikipédia", null, "https://wk/x"),
            ),
            "https://tmdb/x",
        )!!
        assertEquals(listOf("Ma note" to "très bien", "Technique" to "bien"), ui.personal)
        assertEquals(
            listOf(
                RatingPill("IMDb", "7,0", true),
                RatingPill("Sens Critique", "6,2", true),
                RatingPill("É. Schwald", "8", false),
                RatingPill("A. Goya", "7", false),
            ),
            ui.pills,
        )
        assertEquals(
            listOf(
                NoteLink("Sens Critique", "https://sc/x"),
                NoteLink("IMDb", "https://imdb/x"),
                NoteLink("Wikipédia", "https://wk/x"),
                NoteLink("TMDB", "https://tmdb/x"),
            ),
            ui.links,
        )
    }

    @Test fun personal_note_alone() {
        assertEquals(listOf("Ma note" to "excellent"), notesUi(listOf(r("NP", "x", 9.0)), null)!!.personal)
    }

    @Test fun even_personal_value_in_digits() {
        assertEquals(listOf("Ma note" to "6"), notesUi(listOf(r("NP", "x", 6.0)), null)!!.personal)
    }

    @Test fun critic_without_value_is_hidden() {
        val ui = notesUi(listOf(r("SC@ZZ", "Zoe Zed", null), r("NP", "x", 5.0)), null)!!
        assertEquals(emptyList<Any>(), ui.pills)
    }

    @Test fun critic_half_point() {
        assertEquals("7,5", notesUi(listOf(r("SC@ES", "Éric Schwald", 7.5)), null)!!.pills.single().value)
    }

    @Test fun single_word_critic_name_unchanged() {
        assertEquals("Mubi", notesUi(listOf(r("SC@MU", "Mubi", 7.0)), null)!!.pills.single().label)
    }

    @Test fun links_only() {
        val ui = notesUi(listOf(r("WK", "Wikipédia", null, "https://wk/x")), null)!!
        assertEquals(emptyList<Any>(), ui.personal)
        assertEquals(emptyList<Any>(), ui.pills)
        assertEquals(listOf(NoteLink("Wikipédia", "https://wk/x")), ui.links)
    }

    @Test fun nothing_gives_null() {
        assertNull(notesUi(emptyList(), null))
    }
}
