package fr.remotecontrol.ui.common

sealed interface NavEvent {
    data object ToRemote : NavEvent
    data class ToWork(val id: Long) : NavEvent
    data class ToSeason(val id: Long) : NavEvent
}
