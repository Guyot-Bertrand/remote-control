package fr.remotecontrol.ui.work

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.MediaFileRow
import fr.remotecontrol.data.db.ProgressEntity
import fr.remotecontrol.data.db.SeasonEntity
import fr.remotecontrol.data.db.WorkCollectionRow
import fr.remotecontrol.data.db.WorkEntity
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.player.Availability
import fr.remotecontrol.player.SheetAction
import fr.remotecontrol.player.live
import fr.remotecontrol.player.playsWork
import fr.remotecontrol.player.sheetAction
import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.player.PlayTarget
import fr.remotecontrol.player.StartMode
import fr.remotecontrol.player.TargetKind
import fr.remotecontrol.player.TvSheet
import fr.remotecontrol.player.TvStatus
import fr.remotecontrol.player.availabilityOf
import fr.remotecontrol.player.choiceSummary
import fr.remotecontrol.player.label
import fr.remotecontrol.player.sortVersions
import fr.remotecontrol.ui.common.NavEvent
import fr.remotecontrol.ui.common.PlayAction
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.formatDuration
import fr.remotecontrol.ui.common.languagePills
import fr.remotecontrol.ui.common.resolutionLabel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** [resolution] : « 1920 × 1080 » si connue ; [languages] : pastilles « Anglais », « ST français ». */
data class VersionUi(
    val mediaFileId: Long,
    val label: String,
    val present: Boolean,
    val absentLabel: String?,
    /** Qualité seule (« FHD »), sans la taille de [label]. */
    val quality: String = label,
    val resolution: String? = null,
    val languages: List<String> = emptyList(),
)

/**
 * [canPlay] : œuvre non épisodique dont au moins un fichier est présent.
 * [resumeProgress] : avancement de la reprise (barre du bouton « Reprendre »), nul sans reprise.
 * [onTv] : la télé affiche cette fiche (faux hors ligne ou pendant une lecture).
 * [launching] : une demande de lecture est en cours (boutons désactivés).
 */
data class WorkUi(
    val work: WorkEntity,
    val typeLabel: String,
    val durationLabel: String?,
    val availabilityLabel: String?,
    val versions: List<VersionUi>,
    val seasons: List<SeasonEntity>,
    val collections: List<WorkCollectionRow>,
    val resumeLabel: String?,
    val canPlay: Boolean,
    val message: String? = null,
    val availability: Availability? = null,
    val resumeProgress: Float? = null,
    val onTv: Boolean = false,
    val launching: Boolean = false,
    /** Ce qui sera lu (« FHD · Sans sous-titres »), en tête du lien « Modifier » ; nul si rien n'est lisible. */
    val choiceSummary: String? = null,
    /** Cette œuvre (le film, ou un épisode de la série) joue sur la télé en ce moment. */
    val playingHere: Boolean = false,
    /** Lire, rouvrir la télécommande (lecture en cours ici), ou lecteur absent du PC. */
    val action: SheetAction = SheetAction.Play,
    /** Le lecteur n'est pas installé sur le PC (bandeau ; lecture impossible). */
    val playerMissing: Boolean = false,
    /** Support d'origine (« DVD ») d'une œuvre non numérisée ; nul sinon ou s'il est inconnu. */
    val medium: String? = null,
)

private data class Catalog(
    val work: WorkEntity?,
    val files: List<MediaFileRow>,
    val seasons: List<SeasonEntity>,
    val collections: List<WorkCollectionRow>,
    val progress: ProgressEntity?,
)

class WorkViewModel(
    private val workId: Long,
    dao: CatalogDao,
    launcher: PlayLauncher,
    tvStatus: TvStatus,
    /** Lecture en cours sur la télé ([fr.remotecontrol.player.RemoteController.snapshot]). */
    live: Flow<PlayerSnapshotDto?> = flowOf(null),
    /** Le serveur connecté signale l'absence du lecteur ([fr.remotecontrol.discovery.ServerLocator.playerMissing]). */
    playerMissing: Flow<Boolean> = flowOf(false),
    private val locateServer: suspend () -> Unit = {},
) : ViewModel() {
    private val playAction = PlayAction(viewModelScope, launcher)

    /** Mention « Affichée sur la télé », suivie en direct ; l'écran publie lui-même sa vue. */
    private val tvSheet = TvSheet(viewModelScope, tvStatus, "work", workId)

    /** Navigation demandée (télécommande après un lancement) ; livrée seulement si l'écran l'écoute. */
    val events: Flow<NavEvent> = playAction.events

    private val catalog: Flow<Catalog> = combine(
        dao.work(workId),
        dao.mediaFilesOfWork(workId),
        dao.seasonsOfWork(workId),
        dao.collectionsOfWork(workId),
        dao.progress(TargetKind.Work.wire, workId),
        ::Catalog,
    )

    private val playingHere: Flow<Boolean> = live.map { it.live()?.playsWork(workId) == true }.distinctUntilChanged()

    val state: StateFlow<WorkUi?> = combine(
        catalog, playAction.message, tvSheet.onTv, playAction.launching, combine(playingHere, playerMissing, ::Pair),
    ) { c, msg, tv, busy, (here, missing) ->
        c.work?.let {
            toUi(c, it, msg, tv).copy(
                launching = busy,
                playingHere = here,
                action = sheetAction(here, playerAvailable = !missing),
                playerMissing = missing,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** La base a répondu sans cette œuvre (supprimée, ou QR-code périmé). */
    val missing: StateFlow<Boolean> = catalog.map { it.work == null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** « Réessayer » du bandeau « PC injoignable » : nouvelle recherche du serveur. */
    fun retryServer() {
        viewModelScope.launch { locateServer() }
    }

    fun playFromStart() = launch(StartMode.Start)

    fun resume() = launch(StartMode.Resume)

    fun clearMessage() {
        playAction.message.value = null
    }

    private fun launch(mode: StartMode) = playAction.launch(PlayTarget(TargetKind.Work, workId), mode)

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
            choiceSummary = if (work.episodic) null else choiceSummary(c.files, c.progress),
            medium = work.medium?.takeIf { availability == Availability.NotDigitized },
        )
    }
}

/** Version telle qu'affichée sur la fiche et sur la feuille de choix. */
fun MediaFileRow.toVersionUi() = VersionUi(
    mediaFileId = id,
    label = Texts.versionLabel(quality, size),
    present = present,
    absentLabel = if (present) null else Texts.volumeAbsent(volumeLabel),
    quality = quality,
    resolution = resolutionLabel(width, height),
    languages = languagePills(audioLangs, subtitleLangs),
)
