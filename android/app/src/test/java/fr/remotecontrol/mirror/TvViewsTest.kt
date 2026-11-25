package fr.remotecontrol.mirror

import fr.remotecontrol.data.db.CollectionSummary
import fr.remotecontrol.data.db.SearchHit
import fr.remotecontrol.data.db.WorkSummary
import fr.remotecontrol.net.ItemRefDto
import fr.remotecontrol.net.PositionDto
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.player.PlayTarget
import fr.remotecontrol.player.TargetKind
import fr.remotecontrol.ui.home.HomeUi
import fr.remotecontrol.ui.home.ResumeItem
import fr.remotecontrol.ui.search.AkaHit
import fr.remotecontrol.ui.search.AltKind
import fr.remotecontrol.ui.search.PersonHit
import fr.remotecontrol.ui.search.SearchSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvViewsTest {
    private fun work(id: Long, title: String = "Titre $id") = WorkSummary(
        id = id, type = "film", titleFr = title, year = null, poster = null, playable = true,
        episodic = false, directors = emptyList(), progress = null, watched = false,
    )

    @Test fun window_is_bounded_at_ends() {
        val ids = (1L..100L).toList()
        assertEquals((1L..40L).toList(), windowAround(ids, first = 2))
        assertEquals((61L..100L).toList(), windowAround(ids, first = 95))
        assertEquals((41L..80L).toList(), windowAround(ids, first = 50))
        assertEquals(listOf(1L, 2L), windowAround(listOf(1L, 2L), first = 1))
    }

    @Test fun browse_view_has_anchor_and_position() {
        val works = (1L..2552L).map { work(it, if (it == 1241L) "Mélodie" else "Alpha $it") }
        val v = browseView("Films", "2 552 films · Titre", true, works, firstVisible = 1240)
        assertEquals("browse", v.screen)
        assertEquals("Films", v.title)
        assertEquals("playable", v.filter)
        assertEquals(1241L, v.anchor)
        assertEquals(PositionDto("M", 1241, 2552), v.position)
        assertEquals(40, v.items.size)
        assertEquals(ItemRefDto("work", 1231), v.items.first())
        assertEquals("all", browseView("Tout", "", false, works, 0).filter)
    }

    @Test fun collection_view_carries_collection() {
        val v = collectionView(4, "Tchernia", "3 œuvres", listOf(work(1), work(2)), 0)
        assertEquals("collection", v.screen)
        assertEquals(4L, v.collectionId)
        assertEquals(listOf(ItemRefDto("work", 1), ItemRefDto("work", 2)), v.items)
    }

    @Test fun home_view_sections_use_episode_refs() {
        val resume = listOf(
            ResumeItem("episode", 45, 12, 3, "Heroes", null, 0.5f, ""),
            ResumeItem("work", 7, 7, null, "Film", null, 0.5f, ""),
        )
        val ui = HomeUi(
            resume = resume,
            recent = (1L..50L).map { work(it) },
            collections = listOf(CollectionSummary(4, "C", 3, 3)),
        )
        val v = homeView(ui)
        assertEquals("home", v.screen)
        assertEquals(listOf("Reprendre", "Récemment ajoutés", "Collections"), v.sections.map { it.label })
        assertEquals(listOf(ItemRefDto("episode", 45), ItemRefDto("work", 7)), v.sections[0].items)
        assertEquals(40, v.sections[1].items.size)
        assertEquals(listOf(ItemRefDto("collection", 4)), v.sections[2].items)
        assertEquals(1, homeView(HomeUi(recent = listOf(work(1)))).sections.size)
    }

    @Test fun search_view_sections_and_people() {
        val hit = SearchHit(work(1), null, null, emptyList())
        val sections = SearchSections(
            people = listOf(PersonHit("Pierre Tchernia", true, 1), PersonHit("Léa", false, 2)),
            works = listOf(hit),
            aka = listOf(AkaHit(work(2), AltKind.Original, "VO")),
        )
        val v = searchView("tchernia", sections)
        assertEquals("search", v.screen)
        assertEquals("tchernia", v.query)
        assertEquals(listOf("Personnes", "Œuvres", "Aussi connu sous"), v.sections.map { it.label })
        assertEquals("Pierre Tchernia", v.sections[0].people[0].name)
        assertEquals("Réalisation", v.sections[0].people[0].role)
        assertEquals("Interprétation", v.sections[0].people[1].role)
        assertEquals(2, v.sections[0].people[1].count)
        assertEquals(listOf(ItemRefDto("work", 1)), v.sections[1].items)
        assertEquals(listOf(ItemRefDto("work", 2)), v.sections[2].items)
        assertEquals(emptyList<Any>(), searchView("x", SearchSections()).sections)
    }

    @Test fun work_season_and_remote_views() {
        assertEquals(12L, workView(12).workId)
        assertEquals("work", workView(12).screen)
        assertEquals("remote", remoteView().screen)
    }

    @Test fun season_view_carries_selected_episode() {
        val v = seasonView(3, 12, 45)
        assertEquals("season", v.screen)
        assertEquals(3L, v.seasonId)
        assertEquals(12L, v.workId)
        assertEquals(45L, v.episodeId)
        assertNull(seasonView(3, 12, null).episodeId)
    }

    @Test fun preplay_view_carries_tracks() {
        val w = preplayView(PlayTarget(TargetKind.Work, 12), workId = 12, seasonId = null, 7, 1, SubSel.External("/s.srt"))
        assertEquals("preplay", w.screen)
        assertEquals(12L, w.workId)
        assertNull(w.seasonId)
        assertNull(w.episodeId)
        assertEquals(7L, w.mediaFileId)
        assertEquals(1, w.audioTrack)
        assertEquals(SubSel.External("/s.srt"), w.subtitleTrack)
        // Épisode : la télé a besoin de la série et de la saison pour afficher la fiche, même après un rechargement.
        val e = preplayView(PlayTarget(TargetKind.Episode, 45), workId = 5, seasonId = 9, null, null, SubSel.None)
        assertEquals(45L, e.episodeId)
        assertEquals(5L, e.workId)
        assertEquals(9L, e.seasonId)
        assertNull(e.subtitleTrack)
    }
}
