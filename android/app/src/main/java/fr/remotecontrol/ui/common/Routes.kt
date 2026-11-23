package fr.remotecontrol.ui.common

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute

/** Tri « récemment ajoutés » de [BrowseRoute.sort] (honoré par l'écran de parcours, Task 9). */
const val SORT_RECENT = "recent"

@Serializable data class BrowseRoute(val type: String? = null, val collectionId: Long? = null, val sort: String? = null)

@Serializable data object SearchRoute

@Serializable data class WorkRoute(val id: Long)

@Serializable data class SeasonRoute(val id: Long)

@Serializable data object RemoteRoute

@Serializable data object ScanRoute

@Serializable data object SettingsRoute
