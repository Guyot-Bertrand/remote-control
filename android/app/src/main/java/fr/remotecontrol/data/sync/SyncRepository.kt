package fr.remotecontrol.data.sync

import fr.remotecontrol.data.META_REVISION
import fr.remotecontrol.data.META_SYNCED_AT
import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.db.MetaEntity
import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.RcApi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface SyncOutcome {
    data class Done(val revision: Long, val full: Boolean) : SyncOutcome
    data class Failed(val error: ApiError) : SyncOutcome
}

/** Avancement d'une synchro complète ; une synchro incrémentale reste [Idle]. */
sealed interface SyncProgress {
    data object Idle : SyncProgress
    data object Downloading : SyncProgress
    data class Writing(val done: Int, val total: Int, val fraction: Float) : SyncProgress
}

class SyncRepository(
    private val db: AppDatabase,
    private val api: RcApi,
    private val writer: SyncWriter = SyncWriter(db),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _progress = MutableStateFlow<SyncProgress>(SyncProgress.Idle)
    val progress: StateFlow<SyncProgress> = _progress.asStateFlow()

    suspend fun localRevision(): Long? = db.metaDao().get(META_REVISION)?.toLongOrNull()

    /** Heure (ms depuis l'époque) de la dernière synchronisation réussie ; nulle tant qu'aucune n'a abouti. */
    val syncedAt: Flow<Long?> = db.metaDao().observe(META_SYNCED_AT).map { it?.toLongOrNull() }

    suspend fun sync(forceFull: Boolean = false): SyncOutcome = mutex.withLock {
        _syncing.value = true
        try {
            // La lecture de la révision locale est dans le même filet que l'écriture : une base en panne
            // donne un échec de synchronisation, jamais une exception.
            val since = try {
                if (forceFull) null else localRevision()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@withLock SyncOutcome.Failed(ApiError.BadResponse)
            }
            if (since == null) _progress.value = SyncProgress.Downloading
            when (val r = api.sync(since)) {
                is ApiResult.Err -> SyncOutcome.Failed(r.error)
                is ApiResult.Ok -> {
                    try {
                        writer.apply(r.value) { done, total, fraction ->
                            if (r.value.full) _progress.value = SyncProgress.Writing(done, total, fraction)
                        }
                        db.metaDao().put(MetaEntity(META_SYNCED_AT, clock().toString()))
                        SyncOutcome.Done(r.value.revision, r.value.full)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SyncOutcome.Failed(ApiError.BadResponse)
                    }
                }
            }
        } finally {
            _progress.value = SyncProgress.Idle
            _syncing.value = false
        }
    }
}
