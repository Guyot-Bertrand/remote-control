package fr.remotecontrol.ui.search

import fr.remotecontrol.data.db.SearchHit
import fr.remotecontrol.data.db.WorkSummary
import org.junit.Assert.assertEquals
import org.junit.Test

class SearchSectionsTest {
    private fun hit(
        id: Long,
        title: String,
        directors: List<String> = emptyList(),
        cast: List<String> = emptyList(),
        vo: String? = null,
        alt: String? = null,
    ) = SearchHit(
        WorkSummary(id, "film", title, 2000, null, true, false, directors, null, false),
        titleVo = vo,
        titleAlt = alt,
        cast = cast,
    )

    @Test fun people_from_directors_and_cast_with_counts() {
        val hits = listOf(
            hit(1, "Ava", directors = listOf("Léa Mysius"), cast = listOf("Laure Calamy")),
            hit(2, "Les Cinq Diables", directors = listOf("Léa Mysius")),
        )
        val s = searchSections("lea mysi", hits)
        assertEquals(listOf(PersonHit("Léa Mysius", directing = true, workCount = 2)), s.people)
        assertEquals(listOf(1L, 2L), s.works.map { it.work.id })
        assertEquals(emptyList<AkaHit>(), s.aka)
    }

    @Test fun cast_member_is_not_directing() {
        val s = searchSections("seydoux", listOf(hit(3, "Leaving", cast = listOf("Léa Seydoux"))))
        assertEquals(listOf(PersonHit("Léa Seydoux", directing = false, workCount = 1)), s.people)
    }

    @Test fun alternative_titles_go_to_their_own_section() {
        val hits = listOf(
            hit(2, "Alien, le huitième passager", directors = listOf("Ridley Scott"), vo = "Alien", alt = "L'Étranger"),
            hit(7, "L'Étranger", directors = listOf("Luchino Visconti")),
        )
        val s = searchSections("etranger", hits)
        assertEquals(listOf(7L), s.works.map { it.work.id })
        assertEquals(listOf(AkaHit(hits[0].work, AltKind.Alternative, "L'Étranger")), s.aka)
        assertEquals(emptyList<PersonHit>(), s.people)
    }

    @Test fun original_title_is_labelled_as_such() {
        val s = searchSections("alien", listOf(hit(2, "Le Huitième Passager", vo = "Alien")))
        assertEquals(listOf(AkaHit(s.aka[0].work, AltKind.Original, "Alien")), s.aka)
    }

    @Test fun cross_field_match_stays_with_works() {
        // « ava mysius » : le titre et la réalisatrice répondent chacun à un mot.
        val s = searchSections("ava mysius", listOf(hit(1, "Ava", directors = listOf("Léa Mysius"))))
        assertEquals(listOf(1L), s.works.map { it.work.id })
        assertEquals(emptyList<PersonHit>(), s.people)
    }

    @Test fun ranges_cover_prefixes_ignoring_accents_and_join_across_spaces() {
        assertEquals(listOf(0..7), matchRanges("Léa Mysius", "lea mysi"))
        assertEquals(listOf(2..6), matchRanges("L'Étranger", "etran"))
        assertEquals(listOf(0..2, 6..8), matchRanges("Ava · Ava, Ava", "ava").take(2))
        assertEquals(emptyList<IntRange>(), matchRanges("Ava", ""))
        assertEquals(listOf(4..4), matchRanges("Les Œuvres", "oe"))
    }
}
