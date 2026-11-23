package fr.remotecontrol.ui.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.CollectionEntity
import fr.remotecontrol.data.db.MediaFileRow
import fr.remotecontrol.data.db.ProgressEntity
import fr.remotecontrol.data.db.SeasonEntity
import fr.remotecontrol.data.db.WorkEntity
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.player.Availability
import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.player.PlayOutcome
import fr.remotecontrol.player.PlayTarget
import fr.remotecontrol.player.StartMode
import fr.remotecontrol.player.TargetKind
import fr.remotecontrol.player.availabilityOf
import fr.remotecontrol.player.label
import fr.remotecontrol.player.sortVersions
import fr.remotecontrol.ui.common.NavEvent
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.formatDuration
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class VersionUi(val mediaFileId: Long, val label: String, val present: Boolean, val absentLabel: String?)

/**
 * [canPlay] : œuvre non épisodique dont au moins un fichier est présent.
 * [resumeProgress] : avancement de la reprise (barre du bouton « Reprendre »), nul sans reprise.
 * [onTv] : la télé affiche cette fiche (faux hors ligne ou pendant une lecture).
 */
data class WorkUi(
    val work: WorkEntity,
    val typeLabel: String,
    val durationLabel: String?,
    val availabilityLabel: String?,
    val versions: List<VersionUi>,
    val seasons: List<SeasonEntity>,
    val collections: List<CollectionEntity>,
    val resumeLabel: String?,
    val canPlay: Boolean,
    val message: String? = null,
    val availability: Availability? = null,
    val resumeProgress: Float? = null,
    val onTv: Boolean = false,
)

private data class Catalog(
    val work: WorkEntity?,
    val files: List<MediaFileRow>,
    val seasons: List<SeasonEntity>,
    val collections: List<CollectionEntity>,
    val progress: ProgressEntity?,
)

class WorkViewModel(
    private val workId: Long,
    dao: CatalogDao,
    private val api: RcApi,
    private val launcher: PlayLauncher,
) : ViewModel() {
    private val message = MutableStateFlow<String?>(null)
    private val onTv = MutableStateFlow(false)
    private val navEvents = Channel<NavEvent>(Channel.BUFFERED)
    val events: Flow<NavEvent> = navEvents.receiveAsFlow()

    private val catalog: Flow<Catalog> = combine(
        dao.work(workId),
        dao.mediaFilesOfWork(workId),
        dao.seasonsOfWork(workId),
        dao.collectionsOfWork(workId),
        dao.progress(TargetKind.Work.wire, workId),
        ::Catalog,
    )

    val state: StateFlow<WorkUi?> = combine(catalog, message, onTv) { c, msg, tv -> c.work?.let { toUi(c, it, msg, tv) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        // Une seule fois par ouverture de fiche ; hors ligne, l'échec est ignoré (la navigation reste possible).
        viewModelScope.launch {
            val shown = api.tvShow("work", workId)
            onTv.value = shown is ApiResult.Ok && shown.value
        }
    }

    fun playFromStart() = launch(StartMode.Start)

    fun resume() = launch(StartMode.Resume)

    fun clearMessage() {
        message.value = null
    }

    private fun launch(mode: StartMode) {
        viewModelScope.launch {
            when (val outcome = launcher.launch(PlayTarget(TargetKind.Work, workId), mode)) {
                PlayOutcome.Started -> navEvents.send(NavEvent.ToRemote)
                is PlayOutcome.Failed -> message.value = outcome.message
            }
        }
    }

    private fun toUi(c: Catalog, work: WorkEntity, msg: String?, tv: Boolean): WorkUi {
        // Une œuvre épisodique n'a pas de fichier propre : sa disponibilité se lit épisode par épisode.
        val availability = if (work.episodic) null else availabilityOf(c.files)
        val progress = c.progress?.takeIf { it.position > 0 && !it.watched }
        return WorkUi(
            work = work,
            typeLabel = Texts.typeLabel(work.type),
            durationLabel = work.durationMin?.let(::formatDuration),
            availabilityLabel = availability?.label(),
            versions = if (work.episodic) emptyList() else sortVersions(c.files).map { it.toVersionUi() },
            seasons = c.seasons,
            collections = c.collections,
            resumeLabel = progress?.let { Texts.resumeAt(it.position) },
            canPlay = availability == Availability.Present,
            message = msg,
            availability = availability,
            resumeProgress = progress?.duration?.takeIf { it > 0 }?.let { (progress.position / it).coerceIn(0.0, 1.0).toFloat() },
            onTv = tv,
        )
    }
}

private fun MediaFileRow.toVersionUi() = VersionUi(
    mediaFileId = id,
    label = Texts.versionLabel(quality, size),
    present = present,
    absentLabel = if (present) null else Texts.volumeAbsent(volumeLabel),
)
