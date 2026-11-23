package fr.remotecontrol.ui.preplay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.db.CatalogDao
import fr.remotecontrol.data.db.ProgressEntity
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
import fr.remotecontrol.ui.common.PrePlayRoute
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.formatDuration
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

fun PrePlayArgs.toRoute() = PrePlayRoute(target.kind.wire, target.id, start.wire)

fun PrePlayRoute.toArgs() = PrePlayArgs(
    PlayTarget(TargetKind.entries.first { it.wire == kind }, id),
    StartMode.entries.first { it.wire == start },
)

/**
 * En-tête : [title] (œuvre ou série), [details] (« 2001 · David Lynch · 2 h 26 », ou « S01E02 »).
 * Badges « Par défaut » / « Dernier choix » sur la version et les pistes proposées d'office.
 * [message] : erreur de chargement des pistes ou d'un lancement ; [canRetry] : le chargement des pistes a échoué.
 * [canConfirm] : pistes chargées pour une version présente, et aucun lancement en cours.
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
    val title: String = "",
    val poster: String? = null,
    val year: Int? = null,
    val details: String? = null,
    val versionBadges: Map<Long, String> = emptyMap(),
    val audioBadges: Map<Int, String> = emptyMap(),
    val subtitleBadges: Map<SubSel, String> = emptyMap(),
    val canRetry: Boolean = false,
)

/**
 * Écran « Version et pistes… » (un ViewModel par entrée de navigation) : versions de la cible (les absentes sont listées, pas sélectionnables),
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
    private var progress: ProgressEntity? = null

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
            val p = dao.progress(args.target.kind.wire, args.target.id).first()
            progress = p
            val selected = preferredVersion(files, p?.mediaFileId, args.start)
            val lastVersion = args.start == StartMode.Resume && selected != null && selected == p?.mediaFileId
            local.value = header().copy(
                versions = sortVersions(files).map { it.toVersionUi() },
                selectedMediaFileId = selected,
                versionBadges = selected?.let { mapOf(it to badge(lastVersion)) }.orEmpty(),
                loading = selected != null,
                message = if (selected == null) availabilityOf(files).label() else null,
            )
            if (selected != null) loadTracks(selected)
        }
    }

    private suspend fun header(): PrePlayUi = when (args.target.kind) {
        TargetKind.Work -> dao.work(args.target.id).first()?.let { w ->
            PrePlayUi(
                title = w.titleFr,
                poster = w.poster,
                year = w.year,
                details = Texts.details(
                    w.year?.toString(),
                    w.directors.joinToString(", ").ifEmpty { null },
                    w.durationMin?.let(::formatDuration),
                ).ifEmpty { null },
            )
        }
        TargetKind.Episode -> dao.episodeHeader(args.target.id).first()?.let { e ->
            PrePlayUi(
                title = e.workTitle,
                poster = e.poster,
                year = e.year,
                details = Texts.episodeCode(e.seasonNumber, e.number),
            )
        }
    } ?: PrePlayUi()

    /** Une version déjà affichée n'est rechargée que si ses pistes manquent (échec précédent). */
    fun selectVersion(mediaFileId: Long) {
        val s = local.value
        if (s.versions.none { it.mediaFileId == mediaFileId && it.present }) return
        if (mediaFileId == s.selectedMediaFileId && (s.loading || s.subtitles.isNotEmpty())) return
        local.update { it.copy(selectedMediaFileId = mediaFileId) }
        loadTracks(mediaFileId)
    }

    /** Nouvel essai de lecture des pistes de la version choisie (disque rebranché, serveur revenu). */
    fun retry() {
        local.value.selectedMediaFileId?.let(::loadTracks)
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
            it.copy(
                audio = emptyList(), subtitles = emptyList(), selectedAudio = null, selectedSubtitle = SubSel.None,
                audioBadges = emptyMap(), subtitleBadges = emptyMap(),
                loading = true, message = null, canConfirm = false, canRetry = false,
            )
        }
        tracksJob = viewModelScope.launch {
            val r = api.tracks(mediaFileId)
            local.update {
                when (r) {
                    is ApiResult.Ok -> {
                        val d = r.value.default
                        val p = progress
                        it.copy(
                            audio = trackOptions(r.value.audio),
                            subtitles = subtitleOptions(r.value),
                            selectedAudio = d.audio,
                            selectedSubtitle = d.subtitle,
                            audioBadges = d.audio?.let { a -> mapOf(a to badge(p?.audio == a)) }.orEmpty(),
                            subtitleBadges = mapOf(d.subtitle to badge(p?.subtitle == d.subtitle)),
                            loading = false,
                            canConfirm = true,
                        )
                    }
                    is ApiResult.Err -> it.copy(loading = false, message = r.error.messageFr(), canConfirm = false, canRetry = true)
                }
            }
        }
    }
}

/** « Dernier choix » quand la valeur proposée d'office est celle mémorisée dans la progression. */
private fun badge(fromProgress: Boolean) = if (fromProgress) Texts.badgeLastChoice else Texts.badgeDefault
