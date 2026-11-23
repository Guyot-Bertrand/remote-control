package fr.remotecontrol.ui.common

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute

/** Tri « récemment ajoutés » de [BrowseRoute.sort] (honoré par l'écran de parcours, Task 9). */
const val SORT_RECENT = "recent"

@Serializable data class BrowseRoute(val type: String? = null, val collectionId: Long? = null, val sort: String? = null)

@Serializable data object SearchRoute

@Serializable data class WorkRoute(val id: Long)

@Serializable data class SeasonRoute(val id: Long)

/** Écran de choix de la version et des pistes : [kind] et [start] en valeurs du contrat (`work`, `start`…). */
@Serializable data class PrePlayRoute(val kind: String, val id: Long, val start: String)

@Serializable data object RemoteRoute

@Serializable data object ScanRoute

@Serializable data object SettingsRoute

/**
 * Cible d'un onglet de la barre de navigation. [reset] : re-toucher « Parcourir » alors qu'un parcours est
 * affiché (« Tout voir », un autre type, une collection) repart du type et du tri par défaut ; sinon l'onglet
 * retrouve l'état qu'il avait quand on l'a quitté.
 */
data class TopNavigation(val route: Any, val reset: Boolean)

fun topNavigation(target: TopDestination, current: TopDestination?): TopNavigation = when (target) {
    TopDestination.Home -> TopNavigation(HomeRoute, reset = false)
    TopDestination.Browse -> TopNavigation(BrowseRoute(type = WORK_TYPES.first()), reset = current == TopDestination.Browse)
    TopDestination.Search -> TopNavigation(SearchRoute, reset = false)
    TopDestination.Scan -> TopNavigation(ScanRoute, reset = false)
}
