package fr.remotecontrol.ui.preplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.net.messageFr
import fr.remotecontrol.player.PlayLauncher
import fr.remotecontrol.player.PlayTarget
import fr.remotecontrol.player.StartMode
import fr.remotecontrol.player.SubtitleOption
import fr.remotecontrol.player.TargetKind
import fr.remotecontrol.player.TrackChoice
import fr.remotecontrol.player.TrackOption
import fr.remotecontrol.player.availabilityOf
import fr.remotecontrol.player.label
import fr.remotecontrol.player.preferredVersion
import fr.remotecontrol.player.sortVersions
import fr.remotecontrol.player.subtitleOptions
import fr.remotecontrol.player.trackOptions
import fr.remotecontrol.ui.common.NavEvent
import fr.remotecontrol.ui.common.PlayAction
import fr.remotecontrol.ui.work.VersionUi
import fr.remotecontrol.ui.work.toVersionUi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PrePlayArgs(val target: PlayTarget, val start: StartMode)

/**
 * [message] : erreur de chargement des pistes ou d'un lancement. [canConfirm] : pistes chargées pour une
 * version présente, et aucun lancement en cours.
 */
data class PrePlayUi(
    val versions: List<VersionUi> = emptyList(),
    val selectedMediaFileId: Long? = null,
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<SubtitleOption> = emptyList(),
    val selectedAudio: Int? = null,
    val selectedSubtitle: SubSel = SubSel.None,
    val loading: Boolean = true,
    val message: String? = null,
    val canConfirm: Boolean = false,
)

/**
 * Feuille « Version et pistes… » : versions de la cible (les absentes sont listées, pas sélectionnables),
 * pistes de la version choisie lues via `/tracks`, dont le `default` reflète déjà le dernier choix (arbitrage 8).
 * [confirm] envoie toujours la version et les pistes affichées, par le même chemin que les fiches ([PlayAction]).
 */
class PrePlayViewModel(
    private val args: PrePlayArgs,
    private val dao: CatalogDao,
    private val api: RcApi,
    launcher: PlayLauncher,
) : ViewModel() {
    private val playAction = PlayAction(viewModelScope, launcher)
    private val local = MutableStateFlow(PrePlayUi())
    private var tracksJob: Job? = null

    /** Navigation demandée (télécommande après un lancement) ; livrée seulement si l'écran l'écoute. */
    val events: Flow<NavEvent> = playAction.events

    val state: StateFlow<PrePlayUi> = combine(local, playAction.message, playAction.launching) { s, launchMsg, busy ->
        s.copy(message = launchMsg ?: s.message, canConfirm = s.canConfirm && !busy)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PrePlayUi())

    init {
        viewModelScope.launch {
            val files = when (args.target.kind) {
                TargetKind.Work -> dao.mediaFilesOfWork(args.target.id)
                TargetKind.Episode -> dao.mediaFilesOfEpisode(args.target.id)
            }.first()
            val progress = dao.progress(args.target.kind.wire, args.target.id).first()
            val selected = preferredVersion(files, progress?.mediaFileId, args.start)
            local.value = PrePlayUi(
                versions = sortVersions(files).map { it.toVersionUi() },
                selectedMediaFileId = selected,
                loading = selected != null,
                message = if (selected == null) availabilityOf(files).label() else null,
            )
            if (selected != null) loadTracks(selected)
        }
    }

    fun selectVersion(mediaFileId: Long) {
        val s = local.value
        if (mediaFileId == s.selectedMediaFileId) return
        if (s.versions.none { it.mediaFileId == mediaFileId && it.present }) return
        local.update { it.copy(selectedMediaFileId = mediaFileId) }
        loadTracks(mediaFileId)
    }

    fun selectAudio(index: Int) = local.update { it.copy(selectedAudio = index) }

    fun selectSubtitle(sel: SubSel) = local.update { it.copy(selectedSubtitle = sel) }

    fun confirm() {
        val s = state.value
        val mediaFileId = s.selectedMediaFileId ?: return
        if (!s.canConfirm) return
        playAction.launch(args.target, args.start, TrackChoice(mediaFileId, s.selectedAudio, s.selectedSubtitle))
    }

    private fun loadTracks(mediaFileId: Long) {
        tracksJob?.cancel()
        local.update {
            it.copy(audio = emptyList(), subtitles = emptyList(), selectedAudio = null, selectedSubtitle = SubSel.None,
                loading = true, message = null, canConfirm = false)
        }
        tracksJob = viewModelScope.launch {
            val r = api.tracks(mediaFileId)
            local.update {
                when (r) {
                    is ApiResult.Ok -> it.copy(
                        audio = trackOptions(r.value.audio),
                        subtitles = subtitleOptions(r.value),
                        selectedAudio = r.value.default.audio,
                        selectedSubtitle = r.value.default.subtitle,
                        loading = false,
                        canConfirm = true,
                    )
                    is ApiResult.Err -> it.copy(loading = false, message = r.error.messageFr(), canConfirm = false)
                }
            }
        }
    }
}
