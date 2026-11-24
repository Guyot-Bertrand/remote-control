package fr.remotecontrol.ui.season

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeasonScreenTest {
    @Test fun bar_shows_series_title_once_header_is_scrolled_away() {
        // Ouverture amenée sur l'épisode en cours : l'en-tête de la série est hors écran, la barre porte son titre.
        assertEquals("Back to Life", seasonBarTitle("Back to Life", headerVisible = false))
        // En-tête visible : le grand titre suffit.
        assertNull(seasonBarTitle("Back to Life", headerVisible = true))
        // Série inconnue du cache : pas de titre vide.
        assertNull(seasonBarTitle("", headerVisible = false))
    }
}
