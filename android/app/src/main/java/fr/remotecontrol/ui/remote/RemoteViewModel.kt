package fr.remotecontrol.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.CurrentDto
import fr.remotecontrol.net.MediaTracksDto
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.PlayerStateDto
import fr.remotecontrol.net.RcApi
import fr.remotecontrol.net.SubSel
import fr.remotecontrol.net.messageFr
import fr.remotecontrol.player.RemoteController
import fr.remotecontrol.player.SubtitleOption
import fr.remotecontrol.player.TrackOption
import fr.remotecontrol.player.subtitleOptions
import fr.remotecontrol.player.trackOptions
import fr.remotecontrol.ui.common.Texts
import fr.remotecontrol.ui.common.formatClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Télécommande. [running] : une lecture est en cours (commandes actives). [finished] : la lecture suivie par
 * l'écran s'est terminée ([interrupted] si sur une erreur du lecteur) ; [finishedTitle], [workId] et [seasonId]
 * restent alors connus pour revenir à la fiche (saison si [seasonId] n'est pas nul, œuvre sinon).
 */
data class RemoteUi(
    val running: Boolean = false,
    val title: String = Texts.idle,
    val position: Double = 0.0,
    val duration: Double? = null,
    val positionLabel: String = "0:00",
    val durationLabel: String = "",
    val paused: Boolean = false,
    val volume: Int = 100,
    val audio: List<TrackOption> = emptyList(),
    val subtitles: List<SubtitleOption> = emptyList(),
    val selectedAudio: Int? = null,
    val selectedSubtitle: SubSel = SubSel.None,
    val message: String? = null,
    val remainingLabel: String = "",
    val workId: Long? = null,
    val seasonId: Long? = null,
    val finished: Boolean = false,
    val interrupted: Boolean = false,
    val finishedTitle: String? = null,
)

/** Saisie en cours : position ou volume tenus par le doigt (ou envoyés et pas encore confirmés). */
private data class Local(val seek: Double? = null, val volume: Int? = null, val message: String? = null)

private const val TICK_MS = 1_000L

class RemoteViewModel(private val api: RcApi, private val controller: RemoteController) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val tracks = MutableStateFlow<Map<Long, MediaTracksDto>>(emptyMap())
    private val requested = mutableSetOf<Long>()

    /** Dernière lecture vue par l'écran : garde le lien vers la fiche quand le serveur l'oublie (fin de lecture). */
    private val lastCurrent = MutableStateFlow<CurrentDto?>(null)

    /**
     * Instantanés du serveur, plus une position extrapolée chaque seconde pendant la lecture : le serveur
     * n'envoie la position qu'au plus une fois par seconde, l'affichage avance sans attendre le prochain événement.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<PlayerSnapshotDto?> = controller.snapshot.transformLatest { s ->
        emit(s)
        val st = s?.state ?: return@transformLatest
        if (!st.running || st.paused || st.ended) return@transformLatest
        var position = st.position
        while (true) {
            delay(TICK_MS)
            position += TICK_MS / 1_000.0
            st.duration?.let { position = position.coerceAtMost(it) }
            emit(s.copy(state = st.copy(position = position)))
        }
    }

    val state: StateFlow<RemoteUi> = combine(live, local, tracks, lastCurrent) { s, l, t, last ->
        toUi(s, l, t, last)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RemoteUi())

    init {
        viewModelScope.launch {
            controller.snapshot.collect { s ->
                val current = s?.current ?: return@collect
                lastCurrent.value = current
                loadTracks(current.mediaFileId)
            }
        }
    }

    /** Pistes lues une fois par fichier ; un échec permet un nouvel essai au prochain changement. */
    private fun loadTracks(mediaFileId: Long) {
        if (!requested.add(mediaFileId)) return
        viewModelScope.launch {
            when (val r = api.tracks(mediaFileId)) {
                is ApiResult.Ok -> tracks.update { it + (mediaFileId to r.value) }
                is ApiResult.Err -> {
                    requested.remove(mediaFileId)
                    local.update { it.copy(message = r.error.messageFr()) }
                }
            }
        }
    }

    fun togglePause() {
        val paused = state.value.paused
        command { api.pause(!paused) }
    }

    fun restart() = command { api.seek(0.0, relative = false) }

    fun skip(seconds: Int) = command { api.seek(seconds.toDouble(), relative = true) }

    fun onSeekDrag(seconds: Double) = local.update { it.copy(seek = seconds) }

    /** Une seule commande au relâchement ; la position tenue reste affichée jusqu'au prochain état du serveur. */
    fun onSeekRelease() {
        val target = local.value.seek ?: return
        holdUntilServer({ api.seek(target, relative = false) }, { it.seek == target }, { it.copy(seek = null) })
    }

    fun onVolumeDrag(level: Int) = local.update { it.copy(volume = level) }

    fun onVolumeRelease() {
        val target = local.value.volume ?: return
        holdUntilServer({ api.volume(target) }, { it.volume == target }, { it.copy(volume = null) })
    }

    fun selectAudio(index: Int?) = command { api.audio(index) }

    fun selectSubtitle(sel: SubSel) = command { api.subtitle(sel) }

    fun stop() = command { api.stop() }

    fun clearMessage() = local.update { it.copy(message = null) }

    private fun command(block: suspend () -> ApiResult<Unit>) {
        viewModelScope.launch { fail(block()) }
    }

    private fun fail(r: ApiResult<Unit>): Boolean {
        if (r is ApiResult.Err) local.update { it.copy(message = r.error.messageFr()) }
        return r is ApiResult.Err
    }

    /**
     * Envoie [send] ; en cas de succès, la valeur tenue reste affichée jusqu'au prochain état du serveur
     * (au plus 2 s), pour que le curseur ne revienne pas en arrière entre la réponse et l'événement.
     * [stillHeld] : le doigt n'a pas repris le curseur entre-temps.
     */
    private fun holdUntilServer(send: suspend () -> ApiResult<Unit>, stillHeld: (Local) -> Boolean, release: (Local) -> Local) {
        viewModelScope.launch {
            val before = controller.snapshot.value
            if (!fail(send()) && controller.snapshot.value == before) {
                withTimeoutOrNull(2_000) { controller.snapshot.drop(1).first() }
            }
            local.update { if (stillHeld(it)) release(it) else it }
        }
    }
}

private fun toUi(s: PlayerSnapshotDto?, l: Local, t: Map<Long, MediaTracksDto>, last: CurrentDto?): RemoteUi {
    val st = s?.state ?: PlayerStateDto()
    val current = s?.current
    val running = st.running && !st.ended
    val position = l.seek ?: st.position
    val media = current?.let { t[it.mediaFileId] }
    val shown = current ?: last
    return RemoteUi(
        running = running,
        title = if (running) current?.title ?: Texts.nothingPlaying else Texts.idle,
        position = position,
        duration = st.duration,
        positionLabel = formatClock(position),
        durationLabel = st.duration?.let(::formatClock).orEmpty(),
        remainingLabel = st.duration?.let { Texts.remainingClock(it - position) }.orEmpty(),
        paused = st.paused,
        volume = l.volume ?: st.volume,
        audio = media?.let { trackOptions(it.audio) }.orEmpty(),
        subtitles = media?.let { subtitleOptions(it) }.orEmpty(),
        selectedAudio = st.audio,
        selectedSubtitle = st.subtitle,
        message = l.message,
        workId = shown?.workId,
        seasonId = shown?.seasonId,
        finished = !running && last != null,
        interrupted = !running && st.error != null,
        finishedTitle = if (!running) last?.title else null,
    )
}
