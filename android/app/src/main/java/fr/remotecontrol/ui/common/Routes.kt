package fr.remotecontrol.ui.common

import kotlinx.serialization.Serializable

@Serializable data object HomeRoute

@Serializable data class BrowseRoute(val type: String? = null, val collectionId: Long? = null)

@Serializable data object SearchRoute

@Serializable data class WorkRoute(val id: Long)

@Serializable data class SeasonRoute(val id: Long)

@Serializable data object RemoteRoute

@Serializable data object ScanRoute

@Serializable data object SettingsRoute
