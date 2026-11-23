package fr.remotecontrol.testing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job

/**
 * Annule les coroutines du ViewModel et attend leur fin, avant resetMain et db.close() :
 * sinon une requête Room en cours reprend sur Dispatchers.Main pendant le démontage.
 */
suspend fun ViewModel.clearAndJoin() = viewModelScope.coroutineContext.job.cancelAndJoin()
