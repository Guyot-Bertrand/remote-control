package fr.remotecontrol.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fr.remotecontrol.data.db.PlayingInfo
import fr.remotecontrol.net.ApiError
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
import fr.remotecontrol.ui.common.FilePills
import fr.remotecontrol.ui.common.languagePills
import fr.remotecontrol.ui.common.resolutionLabel
import fr.remotecontrol.ui.common.formatClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Télécommande. [running] : une lecture est en cours (commandes actives). [finished] : la lecture suivie par
 * l'écran s'est terminée ([interrupted] si sur une erreur du lecteur) ; [finishedTitle], [workId] et [seasonId]
 * restent alors connus pour revenir à la fiche (saison si [seasonId] n'est pas nul, œuvre sinon).
 * [loading] : l'état du lecteur n'est pas encore connu (ouverture, retour après plus de 5 s) ; ce n'est
 * jamais une fin de lecture.
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
    val loading: Boolean = false,
    /** Affiche de l'œuvre en cours (catalogue local) ; nulle si inconnue. */
    val poster: String? = null,
    /** « 2017 · FHD · Français » : année, qualité du fichier, langue de la piste audio choisie (éléments connus). */
    val details: String = "",
    /** Pastilles du fichier lu (maquette 4c) : qualité, résolution, langues ; nulles si sa qualité est inconnue. */
    val pills: FilePills? = null,
)

/** Saisie en cours : position ou volume tenus par le doigt (ou envoyés et pas encore confirmés). */
private data class Local(val seek: Double? = null, val volume: Int? = null, val message: String? = null)

private const val TICK_MS = 1_000L

/** Délai maximal pendant lequel une position ou un volume envoyés restent affichés en attendant le serveur. */
private const val HOLD_MS = 2_000L
private const val SEEK_TOLERANCE = 2.0
private const val VOLUME_TOLERANCE = 2

/**
 * [playingInfo] : affiche, année et qualité d'une lecture (œuvre, fichier), lues dans le catalogue local.
 * [pendingVolume] et [volumeFailures] : ceux des touches de volume du téléphone ([fr.remotecontrol.player.VolumeStepper]) ;
 * le curseur suit le volume visé, le doigt gardant la priorité, et un échec affiche le même message que le curseur.
 */
class RemoteViewModel(
    private val api: RcApi,
    private val controller: RemoteController,
    private val pendingVolume: StateFlow<Int?> = MutableStateFlow(null),
    private val volumeFailures: Flow<ApiError> = emptyFlow(),
    private val playingInfo: (workId: Long, mediaFileId: Long) -> Flow<PlayingInfo?> = { _, _ -> flowOf(null) },
) : ViewModel() {
    private val local = MutableStateFlow(Local())
    private val tracks = MutableStateFlow<Map<Long, MediaTracksDto>>(emptyMap())
    private val loading = mutableSetOf<Long>()
    private var shownMediaFileId: Long? = null

    /** Dernière lecture vue par l'écran : garde le lien vers la fiche quand le serveur l'oublie (fin de lecture). */
    private val lastCurrent = MutableStateFlow<CurrentDto?>(null)

    /**
     * Instantanés du serveur (avec la mémoire de la lecture et le chargement des pistes), plus une position
     * extrapolée chaque seconde pendant la lecture : le serveur n'envoie la position qu'au plus une fois par
     * seconde, l'affichage avance sans attendre le prochain événement. Tout s'arrête avec l'abonnement à [state].
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val live: Flow<PlayerSnapshotDto?> = controller.snapshot.onEach(::onSnapshot).transformLatest { s ->
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val info: Flow<PlayingInfo?> = lastCurrent
        .map { it?.let { c -> c.workId to c.mediaFileId } }
        .distinctUntilChanged()
        .flatMapLatest { ids -> ids?.let { (w, m) -> playingInfo(w, m) } ?: flowOf(null) }

    /**
     * Saisie affichée : celle du doigt, sinon le volume visé par les touches. Les échecs des touches ne sont
     * écoutés que tant que l'écran l'est (abonnement à [state]).
     */
    private val shownLocal: Flow<Local> = channelFlow {
        launch { volumeFailures.collect { e -> local.update { it.copy(message = e.messageFr()) } } }
        combine(local, pendingVolume) { l, p -> if (l.volume == null && p != null) l.copy(volume = p) else l }
            .collect { send(it) }
    }

    val state: StateFlow<RemoteUi> = combine(live, shownLocal, tracks, lastCurrent, info) { s, l, t, last, i ->
        toUi(s, l, t, last, i)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RemoteUi())

    private fun onSnapshot(s: PlayerSnapshotDto?) {
        val current = s?.current ?: return
        lastCurrent.value = current
        if (current.mediaFileId == shownMediaFileId) return
        shownMediaFileId = current.mediaFileId
        loadTracks(current.mediaFileId)
    }

    /**
     * Pistes lues une fois par fichier, au changement de fichier seulement : un échec n'est pas relancé
     * à chaque événement (ni son message réaffiché), mais au prochain changement vers ce fichier.
     */
    private fun loadTracks(mediaFileId: Long) {
        if (mediaFileId in tracks.value || !loading.add(mediaFileId)) return
        viewModelScope.launch {
            val r = api.tracks(mediaFileId)
            loading.remove(mediaFileId)
            when (r) {
                is ApiResult.Ok -> tracks.update { it + (mediaFileId to r.value) }
                is ApiResult.Err -> local.update { it.copy(message = r.error.messageFr()) }
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
        holdUntilServer(
            send = { api.seek(target, relative = false) },
            reached = { abs(it.position - target) <= SEEK_TOLERANCE },
            stillHeld = { it.seek == target },
            release = { it.copy(seek = null) },
        )
    }

    fun onVolumeDrag(level: Int) = local.update { it.copy(volume = level) }

    fun onVolumeRelease() {
        val target = local.value.volume ?: return
        holdUntilServer(
            send = { api.volume(target) },
            reached = { abs(it.volume - target) <= VOLUME_TOLERANCE },
            stillHeld = { it.volume == target },
            release = { it.copy(volume = null) },
        )
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
     * Envoie [send] ; en cas de succès, la valeur tenue reste affichée jusqu'à ce que le serveur l'ait atteinte
     * ([reached], à la tolérance près) ou au plus [HOLD_MS] : le curseur ne revient pas en arrière sur un
     * événement antérieur à la commande. [stillHeld] : le doigt n'a pas repris le curseur entre-temps.
     */
    private fun holdUntilServer(
        send: suspend () -> ApiResult<Unit>,
        reached: (PlayerStateDto) -> Boolean,
        stillHeld: (Local) -> Boolean,
        release: (Local) -> Local,
    ) {
        viewModelScope.launch {
            val failed = fail(send())
            if (!failed) {
                withTimeoutOrNull(HOLD_MS) { controller.snapshot.first { it != null && reached(it.state) } }
            }
            local.update { if (stillHeld(it)) release(it) else it }
        }
    }

}

private fun toUi(s: PlayerSnapshotDto?, l: Local, t: Map<Long, MediaTracksDto>, last: CurrentDto?, info: PlayingInfo?): RemoteUi {
    val st = s?.state ?: PlayerStateDto()
    val current = s?.current
    val running = st.running && !st.ended
    val position = l.seek ?: st.position
    val media = current?.let { t[it.mediaFileId] }
    val shown = current ?: last
    val loading = s == null
    return RemoteUi(
        running = running,
        title = when {
            running -> current?.title ?: Texts.nothingPlaying
            loading -> last?.title ?: Texts.idle
            else -> Texts.idle
        },
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
        finished = !loading && !running && last != null,
        interrupted = !loading && !running && st.error != null,
        finishedTitle = if (!loading && !running) last?.title else null,
        loading = loading,
        poster = info?.poster,
        details = Texts.details(
            info?.year?.toString(),
            info?.quality,
            // Piste choisie inconnue (pistes non lues, langue non étiquetée) : première langue audio du fichier.
            media?.audio?.firstOrNull { it.index == st.audio }?.lang?.let(Texts::languageName)
                ?: info?.audioLangs?.firstOrNull()?.let(Texts::languageName),
        ),
        pills = info?.quality?.let {
            FilePills(it, resolutionLabel(info.width, info.height), languagePills(info.audioLangs, info.subtitleLangs))
        },
    )
}
