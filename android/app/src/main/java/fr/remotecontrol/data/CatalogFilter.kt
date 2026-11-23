package fr.remotecontrol.data

import kotlinx.coroutines.flow.MutableStateFlow

/** Filtre « œuvres lisibles » partagé par l'accueil, le parcours et la recherche (non persistant). */
class CatalogFilter {
    val playableOnly: MutableStateFlow<Boolean> = MutableStateFlow(true)
}
