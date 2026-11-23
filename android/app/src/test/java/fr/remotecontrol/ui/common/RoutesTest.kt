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
}
