package fr.remotecontrol.player

import fr.remotecontrol.net.MediaTracksDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.net.TrackDto
import fr.remotecontrol.testing.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Test

class TracksTest {
    private val tracks: MediaTracksDto = RcJson.decodeFromString(Fixtures.text("tracks.json"))

    @Test fun audio_options_hide_title_equal_to_language() {
        assertEquals(listOf(TrackOption(1, "Anglais"), TrackOption(2, "Français")), trackOptions(tracks.audio))
    }

    @Test fun audio_option_keeps_distinct_title() {
        val options = trackOptions(listOf(TrackDto(index = 1, lang = "fr", title = "Commentaires")))
        assertEquals(listOf(TrackOption(1, "Français — Commentaires")), options)
    }

    @Test fun subtitle_options_none_then_external_then_embedded() {
        val options = subtitleOptions(tracks)
        assertEquals(listOf("Aucun", "Français (fichier)", "Anglais (intégré)"), options.map { it.label })
        assertEquals(SubSel.None, options[0].sel)
        assertEquals(
            SubSel.External("/media/beber/DISK1/Films/A/Ava [2017] [1h45,1920x1038,16x9,FR,sEN].1.fr.srt"),
            options[1].sel,
        )
        assertEquals(SubSel.Embedded(1), options[2].sel)
    }
}
