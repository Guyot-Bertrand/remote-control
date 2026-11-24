package fr.remotecontrol.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/**
 * Annule les coroutines du ViewModel et attend leur fin, avant resetMain et db.close() :
 * sinon une requête Room en cours reprend sur Dispatchers.Main pendant le démontage.
 */
suspend fun ViewModel.clearAndJoin() = viewModelScope.coroutineContext.job.cancelAndJoin()

/** Événements reçus par un collecteur démarré tout de suite ; [stop] l'arrête. */
class Collected<T>(val items: MutableList<T>, private val job: Job) {
    fun stop() = job.cancel()
}

/**
 * Collecte [flow] dès maintenant, sans dispatch (le collecteur est suspendu en attente avant le retour) :
 * un événement émis ensuite y est rangé immédiatement, de façon déterministe.
 */
fun <T> CoroutineScope.collectNow(flow: Flow<T>): Collected<T> {
    val items = mutableListOf<T>()
    val job = launch(Dispatchers.Unconfined) { flow.collect { items += it } }
    return Collected(items, job)
}
