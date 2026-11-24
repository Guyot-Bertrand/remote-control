package fr.remotecontrol.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RoutesTest {
    @Test fun browse_retap_resets_to_default() {
        assertEquals(TopNavigation(BrowseRoute(type = "film"), reset = true), topNavigation(TopDestination.Browse, TopDestination.Browse))
    }

    @Test fun browse_from_other_tab_restores() {
        assertEquals(TopNavigation(BrowseRoute(type = "film"), reset = false), topNavigation(TopDestination.Browse, TopDestination.Home))
        assertEquals(TopNavigation(SearchRoute, reset = false), topNavigation(TopDestination.Search, TopDestination.Search))
    }

    @Test fun home_pops_back_to_home_without_restoring_a_saved_stack() {
        // Restaurer l'état sauvé ramenait le parcours ouvert depuis l'accueil au lieu de l'accueil.
        assertEquals(TopNavigation(HomeRoute, reset = false, restore = false), topNavigation(TopDestination.Home, TopDestination.Search))
        assertEquals(TopNavigation(HomeRoute, reset = false, restore = false), topNavigation(TopDestination.Home, TopDestination.Browse))
    }

    @Test fun highlighted_tab_is_the_stack_shown() {
        // Parcourir ouvert depuis une fiche scannée : la pile reste celle de « Scanner ».
        assertEquals(TopDestination.Scan, shownTab(TopDestination.Browse, lastSelected = TopDestination.Scan))
        // Fiche ou autre écran hors barre : l'onglet ne change pas.
        assertEquals(TopDestination.Search, shownTab(null, lastSelected = TopDestination.Search))
        // Parcours ouvert depuis l'accueil (type, collection, « Tout voir ») : « Accueil » reste en évidence.
        assertEquals(TopDestination.Home, shownTab(TopDestination.Browse, lastSelected = TopDestination.Home))
        // Retour jusqu'à l'accueil : c'est sa pile qui est affichée.
        assertEquals(TopDestination.Home, shownTab(TopDestination.Home, lastSelected = TopDestination.Scan))
        assertEquals(TopDestination.Browse, shownTab(TopDestination.Browse, lastSelected = TopDestination.Browse))
    }
}
