"""Session de lecture : choix de la version, reprise, suivi de progression, état de la télé."""

from __future__ import annotations

import asyncio
import copy
import json
import logging
import sqlite3
import subprocess
import time
from collections.abc import Callable
from dataclasses import asdict, dataclass, replace
from pathlib import Path
from typing import Literal

from rc.catalog.paths import QUALITY_ORDER
from rc.db import current_revision, next_revision
from rc.events import EventBus
from rc.messages import INTERRUPTED, message
from rc.player.base import Player, PlayerState, SubSel
from rc.probe import MediaTracks, cached_tracks, default_selection, find_external_subs
from rc.volumes import resolve_media_path

log = logging.getLogger(__name__)

SAVE_INTERVAL = 10
POSITION_PUBLISH_INTERVAL = 1  # secondes entre deux événements de simple avancement
# Reprise d'une vidéo mise de côté : la télé garde la capture en plein écran jusqu'à ce
# que mpv ait repris l'image (sa fenêtre la couvre alors), puis passe en « lecture ».
RESUME_SETTLE = 0.5  # secondes après la première avance de la vidéo
RESUME_FALLBACK = 4  # secondes au plus, si mpv ne signale rien
WATCHED_RATIO = 0.9

Screen = Literal["home", "work", "season", "view", "playing", "paused", "message"]


@dataclass
class TvState:
    screen: Screen
    work_id: int | None = None
    season_id: int | None = None
    message: str | None = None
    view: dict | None = None
    pause: dict | None = None


class TvController:
    def __init__(self, bus: EventBus) -> None:
        self.bus = bus
        self.state = TvState("home")
        self._last_screen = TvState("home")
        self.on_change: Callable[[TvState], None] = lambda state: None  # après chaque écran
        # Vue retenue remise à jour (progression…) quand elle réapparaît en fin de lecture.
        self.refresh_view: Callable[[dict], dict] = lambda view: view

    def show(self, screen: Screen, work_id: int | None = None,
             season_id: int | None = None, force: bool = False) -> bool:
        """Pendant la lecture (sans `force`), la fiche demandée n'est pas affichée mais
        retenue : elle apparaîtra à la fin de la lecture."""
        wanted = TvState(screen, work_id, season_id)
        if screen in ("home", "work", "season"):
            self._last_screen = wanted  # une fiche remplace la vue retenue
        if self.state.screen == "playing" and not force:
            return False
        self._set(wanted)
        return True

    def show_view(self, view: dict, pause: dict | None = None) -> None:
        """Affiche la vue du téléphone (avec la vignette de pause le cas échéant) et la
        retient comme dernier écran hors lecture."""
        self._last_screen = TvState("view", view=view)
        self._set(TvState("view", view=view, pause=pause))

    def show_paused(self, pause: dict) -> None:
        self._set(TvState("paused", pause=pause))

    def remember_view(self, view: dict) -> None:
        """Retient la vue sans l'afficher (lecture en cours)."""
        self._last_screen = TvState("view", view=view)

    def update_pause(self, pause: dict) -> None:
        """Republie l'écran courant avec une nouvelle `pause` (vue ou pause seulement)."""
        if self.state.screen in ("view", "paused"):
            self._set(replace(self.state, pause=pause))

    def set_playing(self, work_id: int | None) -> None:
        self._set(TvState("playing", work_id=work_id))

    def playback_ended(self, message: str | None) -> None:
        if message is None:
            last = self._last_screen
            if last.screen == "view" and last.view is not None:
                try:
                    last = self._last_screen = replace(last, view=self.refresh_view(last.view))
                except Exception:
                    log.exception("mise à jour de la vue retenue impossible")
            self._set(last)
        else:
            self._set(TvState("message", message=message))

    def _set(self, state: TvState) -> None:
        self.state = state
        self.bus.publish({"type": "tv", "state": asdict(state)})
        try:
            self.on_change(state)
        except Exception:
            log.exception("on_change a levé une exception")


@dataclass
class PlayRequest:
    target_kind: Literal["work", "episode"]
    target_id: int
    start: Literal["start", "resume"]
    media_file_id: int | None = None
    audio: int | None = None
    subtitle: SubSel | None = None


class PlayError(Exception):
    def __init__(self, code: Literal["not_playable", "volume_absent", "not_found", "player",
                              "player_missing", "invalid"],
                 volume_label: str | None = None) -> None:
        super().__init__(code)
        self.code = code
        self.volume_label = volume_label


def decode_audio(text: str | None) -> int | None:
    return None if text is None else int(text)


def decode_subtitle(text: str | None) -> SubSel | None:
    return None if text is None else json.loads(text)


def resolve_version(conn: sqlite3.Connection, mf: sqlite3.Row,
                    indexes: dict[str, dict[str, Path]]) -> Path:
    """Chemin du fichier d'une version, ou PlayError (volume_absent / not_found)."""
    path = resolve_media_path(conn, mf["id"], indexes)
    if path is not None:
        return path
    uuid = mf["volume_uuid"]
    if uuid is not None and uuid not in indexes:
        row = conn.execute("SELECT label FROM volume WHERE uuid = ?", (uuid,)).fetchone()
        raise PlayError("volume_absent", (row["label"] if row else None) or uuid)
    raise PlayError("not_found")


def load_tracks(conn: sqlite3.Connection, mf: sqlite3.Row, path: Path) -> MediaTracks:
    """Pistes d'une version (analyse en cache) et sous-titres externes ; appel bloquant.

    Une analyse impossible donne des pistes intégrées vides, jamais une erreur."""
    try:
        tracks = cached_tracks(conn, mf["id"], path)
    except (subprocess.CalledProcessError, FileNotFoundError,
            json.JSONDecodeError, OSError) as exc:
        log.warning("analyse des pistes impossible pour %s : %s", path, exc)
        tracks = MediaTracks([], [], [], None, None)
    declared = [str(path.parent / s["filename"]) for s in json.loads(mf["subtitles_json"])]
    tracks.external = find_external_subs(path, declared)
    return tracks


def audio_valid(index: int | None, tracks: MediaTracks) -> bool:
    return index is None or index in {t.index for t in tracks.audio}


def sub_valid(sel: SubSel | None, tracks: MediaTracks) -> bool:
    if sel is None:
        return False
    kind = sel.get("kind")
    if kind == "embedded":
        return sel.get("index") in {t.index for t in tracks.subtitles}
    if kind == "external":
        path = str(sel.get("path", ""))
        known = {str(e.path) for e in tracks.external}
        return path in known and Path(path).is_file()
    return kind == "none"


def pick_tracks(prog: sqlite3.Row | None, tracks: MediaTracks, audio: int | None = None,
                subtitle: SubSel | None = None) -> tuple[int | None, SubSel]:
    """Choix explicite (refusé s'il n'est pas dans les pistes connues : PlayError invalid),
    sinon dernier choix enregistré s'il est encore valide, sinon défaut."""
    default_audio, default_sub = default_selection(tracks)
    if audio is not None and not audio_valid(audio, tracks):
        raise PlayError("invalid")
    if subtitle is not None and not sub_valid(subtitle, tracks) and subtitle != {"kind": "none"}:
        raise PlayError("invalid")
    if audio is None:
        stored = decode_audio(prog["audio"]) if prog is not None else None
        audio = stored if stored in {t.index for t in tracks.audio} else default_audio
    if subtitle is None:
        stored_sub = decode_subtitle(prog["subtitle"]) if prog is not None else None
        subtitle = stored_sub if sub_valid(stored_sub, tracks) else default_sub
    return audio, subtitle


def _quality_rank(quality: str) -> int:
    return QUALITY_ORDER.index(quality) if quality in QUALITY_ORDER else len(QUALITY_ORDER)


def pause_detail(conn: sqlite3.Connection, current: dict) -> str | None:
    """Ligne de détail de la pause : « titre d'épisode · qualité résolution », parties
    inconnues omises ; None si tout l'est (arbitrage 20)."""
    row = conn.execute("SELECT quality, width, height FROM media_file WHERE id = ?",
                       (current["media_file_id"],)).fetchone()
    title = None
    if current["target_kind"] == "episode":
        ep = conn.execute("SELECT title FROM episode WHERE id = ?",
                          (current["target_id"],)).fetchone()
        title = (ep[0] or "").strip() if ep else None
    quality, width, height = (row[0], row[1], row[2]) if row else ("", None, None)
    parts = [quality or ""]
    if width and height and width > 0 and height > 0:
        parts.append(f"{width}\u202f×\u202f{height}")
    group = " ".join(p for p in parts if p)
    return " · ".join(p for p in (title, group) if p) or None


@dataclass
class _Session:
    current: dict
    audio: int | None
    subtitle: SubSel
    last_save: float
    tracks: MediaTracks
    path: Path
    paused: bool = False
    # Vidéo mise de côté : "hide" (piste vidéo de mpv retirée, fenêtre fermée),
    # "minimize" (mpv réduit) ou "restart" (mpv arrêté, relancé à la reprise ; position,
    # durée et volume gardés dans l'état publié).
    parked: Literal["hide", "minimize", "restart"] | None = None
    image: str | None = None  # adresse de la capture de cette pause


class PlaybackService:
    def __init__(self, conn: sqlite3.Connection, player: Player, tv: TvController,
                 bus: EventBus, indexes_getter: Callable[[], dict],
                 clock: Callable[[], float] = time.monotonic,
                 on_catalog: Callable[[int], None] | None = None,
                 player_available: Callable[[], bool] = lambda: True,
                 pause_image: Path | None = None,
                 pause_mode: Callable[[], str] = lambda: "hide") -> None:
        self.conn, self.player, self.tv, self.bus = conn, player, tv, bus
        self.player_available = player_available  # exécutable du lecteur présent ?
        self.indexes_getter, self.clock = indexes_getter, clock
        # Annonce d'une nouvelle révision (progression enregistrée) ; par défaut sur le bus.
        self.on_catalog = on_catalog or (
            lambda rev: bus.publish({"type": "catalog", "revision": rev}))
        self._state = PlayerState()
        self._session: _Session | None = None
        self._lock = asyncio.Lock()
        self._replacing = False  # arrêt interne avant une nouvelle lecture : télé inchangée
        self._published: PlayerState | None = None  # dernier état publié
        self._published_at = 0.0
        self._trailing: asyncio.TimerHandle | None = None  # publication différée en attente
        # Reprise en cours : (session, position de reprise, appels différés) ; la télé
        # passe en « lecture » quand la vidéo avance ou au bout de RESUME_FALLBACK.
        self._resuming: tuple[_Session, float, list[asyncio.TimerHandle]] | None = None
        self.pause_image, self.pause_mode = pause_image, pause_mode
        self._captures = 0  # captures réussies (paramètre `v` de l'image)
        self._parking = False  # mise de côté en cours : commandes sous le verrou
        # Appelé quand la pause change pendant une session.
        self.on_pause_change: Callable[[bool], None] = lambda paused: None
        player.on_state = self._on_state

    @property
    def state(self) -> PlayerState:
        return copy.copy(self._state)

    @property
    def current(self) -> dict | None:
        return dict(self._session.current) if self._session else None

    @property
    def parked(self) -> bool:
        return self._session is not None and self._session.parked is not None

    def pause_info(self) -> dict | None:
        """Vidéo en pause, au format `pause` de l'état télé ; None hors pause."""
        s = self._session
        if s is None or not self._state.paused:
            return None
        return self._pause_dict(s, s.image)

    def _pause_dict(self, s: _Session, image: str | None) -> dict:
        """Objet `pause` de l'état télé, calculé à chaque appel (la résolution d'une
        analyse arrivée pendant la pause apparaît)."""
        return {"image": image, "title": s.current["title"],
                "position": float(self._state.position),
                "duration": self._state.duration, "work_id": s.current["work_id"],
                "detail": pause_detail(self.conn, s.current)}

    # -- lancement ------------------------------------------------------

    async def play(self, req: PlayRequest) -> None:
        async with self._lock:
            await self._play_locked(req)

    async def _play_locked(self, req: PlayRequest) -> None:
        if self._is_current(req) and self._session.parked == "restart":
            self._apply_to_kept(req)
            await self._unpark_locked()
            return
        if self._is_current(req):
            try:
                s = self._session
                unparked = s.parked in ("hide", "minimize")
                image = s.image  # effacée par la reprise, montrée jusqu'au retour
                if unparked:
                    self._check_tracks(s, req.audio, req.subtitle)
                    await self._restore_video()
                if await self._rejoin(req, unparked, image):
                    return
            except PlayError:
                raise
            except Exception:  # lecteur figé ou parti : on relance normalement
                log.warning("reprise de la lecture en cours impossible, relance",
                            exc_info=True)
        mf = self._choose_version(req)
        path = self._resolve(mf)
        await self._stop_locked(show_tv=False)
        if not self.player_available():  # après l'arrêt : aucune session périmée
            self._fail_start("player_missing")
        prog = self._progress(req.target_kind, req.target_id)
        start_at = 0.0
        if req.start == "resume" and prog is not None:
            start_at = prog["position"] or 0.0
        tracks = load_tracks(self.conn, mf, path)
        audio, subtitle = pick_tracks(prog, tracks, req.audio, req.subtitle)
        external = [e.path for e in tracks.external]
        try:
            await self.player.start(path, start_at, audio, subtitle, external)
        except FileNotFoundError:  # exécutable du lecteur introuvable
            log.warning("lecteur vidéo introuvable : impossible de lire %s", path)
            self._fail_start("player_missing")
        except Exception:
            log.exception("échec du démarrage de la lecture de %s", path)
            self._fail_start("player")
        current = self._describe(req, mf["id"])
        self._session = _Session(current, audio, subtitle, self.clock(), tracks, path)
        self._state = PlayerState(position=start_at, running=True)
        self.tv.set_playing(current["work_id"])
        self._publish()

    def _is_current(self, req: PlayRequest) -> bool:
        s = self._session
        return (s is not None and s.current["target_kind"] == req.target_kind
                and s.current["target_id"] == req.target_id
                and req.media_file_id in (None, s.current["media_file_id"]))

    async def _rejoin(self, req: PlayRequest, unparked: bool = False,
                      image: str | None = None) -> bool:
        """Lecture demandée du média déjà en cours : pas de relance du lecteur (la télé
        ne clignote pas). « start » revient au début, « resume » garde la position ;
        pistes demandées appliquées (refusées si inconnues), pause levée.

        Faux si la lecture s'est terminée entre-temps (fin de fichier pendant une
        commande) : l'appelant relance alors normalement. Après une mise de côté
        (`unparked`), la télé garde la capture `image` jusqu'au retour de la vidéo."""
        s = self._session
        assert s is not None
        self._check_tracks(s, req.audio, req.subtitle)
        if req.start == "start":
            await self.player.seek(0, False)
        if req.audio is not None and req.audio != s.audio:
            await self.player.set_audio(req.audio)
            s.audio = req.audio
        if req.subtitle is not None and req.subtitle != s.subtitle:
            await self.player.set_subtitle(req.subtitle)
            s.subtitle = req.subtitle
        if self._state.paused:
            await self.player.set_pause(False)
        if self._session is not s:
            return False
        if unparked:
            self._await_video(s, image)
            self._publish()
        elif self.tv.state.screen != "playing":
            self.tv.set_playing(s.current["work_id"])
        return True

    def _check_tracks(self, s: _Session, audio: int | None, subtitle: SubSel | None) -> None:
        if audio is not None and not audio_valid(audio, s.tracks):
            raise PlayError("invalid")
        if subtitle is not None and subtitle != {"kind": "none"} and not sub_valid(
                subtitle, s.tracks):
            raise PlayError("invalid")

    def _fail_start(self, code: Literal["player", "player_missing"]) -> None:
        self._state = replace(self._state, running=False)
        self.tv.playback_ended(message(code))
        self._publish()
        raise PlayError(code) from None

    def _choose_version(self, req: PlayRequest) -> sqlite3.Row:
        col = "work_id" if req.target_kind == "work" else "episode_id"
        rows = self.conn.execute(
            f"SELECT * FROM media_file WHERE {col} = ?", (req.target_id,)).fetchall()
        by_id = {r["id"]: r for r in rows}
        if req.media_file_id is not None:
            if req.media_file_id not in by_id:
                raise PlayError("not_playable")
            return by_id[req.media_file_id]
        if req.start == "resume":
            prog = self._progress(req.target_kind, req.target_id)
            if prog is not None and prog["media_file_id"] in by_id:
                return by_id[prog["media_file_id"]]
        if not rows:
            raise PlayError("not_playable")
        return min(rows, key=lambda r: (not r["present"], _quality_rank(r["quality"])))

    def _resolve(self, mf: sqlite3.Row) -> Path:
        try:
            return resolve_version(self.conn, mf, self.indexes_getter())
        except PlayError as exc:
            if exc.code == "volume_absent" and self._session is None:
                self.tv.playback_ended(message(exc.code, exc.volume_label))
            raise

    def _describe(self, req: PlayRequest, media_file_id: int) -> dict:
        if req.target_kind == "work":
            row = self.conn.execute(
                "SELECT title_fr FROM work WHERE id = ?", (req.target_id,)).fetchone()
            work_id, season_id, title = req.target_id, None, row["title_fr"]
        else:
            row = self.conn.execute(
                "SELECT w.id AS work_id, w.title_fr, s.id AS season_id,"
                " s.number AS season, e.number AS episode FROM episode e"
                " JOIN season s ON s.id = e.season_id JOIN work w ON w.id = s.work_id"
                " WHERE e.id = ?", (req.target_id,)).fetchone()
            work_id, season_id = row["work_id"], row["season_id"]
            title = f"{row['title_fr']} — S{row['season']:02d}E{row['episode']:02d}"
        return {"target_kind": req.target_kind, "target_id": req.target_id,
                "media_file_id": media_file_id, "work_id": work_id,
                "season_id": season_id, "title": title}

    # -- commandes ------------------------------------------------------

    def _kept(self) -> _Session | None:
        """Session mise de côté par arrêt du lecteur (commandes mémorisées)."""
        s = self._session
        return s if s is not None and s.parked == "restart" else None

    def _needs_lock(self) -> bool:
        return self._parking or self.parked

    async def pause(self, paused: bool) -> None:
        # Toujours sous le verrou : une reprise envoyée juste avant une mise de côté passe
        # avant elle, et la mise de côté la voit (état noté sans attendre l'écho de mpv).
        async with self._lock:
            if self.parked and not paused:
                await self._unpark_locked()
            elif self._kept() is None:
                await self.player.set_pause(paused)
                if self._session is not None:
                    self._state = replace(self._state, paused=paused)

    async def seek(self, seconds: float, relative: bool) -> None:
        if not self._needs_lock():
            await self.player.seek(seconds, relative)
            return
        async with self._lock:
            if self._kept() is None:
                await self.player.seek(seconds, relative)
                return
            st = self._state
            position = max(0.0, st.position + seconds if relative else seconds)
            if st.duration:
                position = min(position, st.duration)
            self._state = replace(st, position=position)
            self._publish()
            self.tv.update_pause(self.pause_info())

    async def set_volume(self, level: int) -> None:
        if not self._needs_lock():
            await self.player.set_volume(level)
            return
        async with self._lock:
            if self._kept() is None:
                await self.player.set_volume(level)
                return
            self._state = replace(self._state, volume=level)
            self._publish()

    async def set_audio(self, index: int | None) -> None:
        if self._needs_lock():
            async with self._lock:
                await self._set_audio(index)
        else:
            await self._set_audio(index)

    async def _set_audio(self, index: int | None) -> None:
        if self._session is not None and not audio_valid(index, self._session.tracks):
            raise PlayError("invalid")
        if self._kept() is None:
            await self.player.set_audio(index)
        else:
            self._state = replace(self._state, audio=index)
            self._publish()
        if self._session is not None:
            self._session.audio = index

    async def set_subtitle(self, sel: SubSel) -> None:
        if self._needs_lock():
            async with self._lock:
                await self._set_subtitle(sel)
        else:
            await self._set_subtitle(sel)

    async def _set_subtitle(self, sel: SubSel) -> None:
        if self._session is not None and sel != {"kind": "none"} and not sub_valid(
                sel, self._session.tracks):
            raise PlayError("invalid")
        if self._kept() is None:
            await self.player.set_subtitle(sel)
        else:
            self._state = replace(self._state, subtitle=sel)
            self._publish()
        if self._session is not None:
            self._session.subtitle = sel

    # -- mise de côté ---------------------------------------------------

    async def park(self) -> dict | None:
        """Met de côté la vidéo en pause : capture, puis piste vidéo retirée (`hide`),
        mpv réduit (`minimize`) ou arrêté et relancé à la reprise (`restart`). None s'il
        n'y a rien à faire."""
        async with self._lock:
            s = self._session
            if s is None or s.parked is not None or not self._state.paused:
                return None
            self._parking = True
            try:
                return await self._park_locked(s)
            finally:
                self._parking = False

    async def _park_locked(self, s: _Session) -> dict | None:
        self._cancel_resuming()
        s.image = await self._capture()
        if self._session is not s or not self._state.paused:  # fin pendant la capture
            return None
        mode = self.pause_mode()
        if mode == "hide":
            try:
                await self.player.set_video(False)
                s.parked = "hide"
            except Exception:
                log.warning("masquage de la vidéo impossible, arrêt du lecteur",
                            exc_info=True)
        elif mode == "minimize":
            try:
                await self.player.set_minimized(True)
                s.parked = "minimize"
            except Exception:
                log.warning("réduction de la fenêtre impossible, arrêt du lecteur",
                            exc_info=True)
        if s.parked is None:
            kept = self._state
            self._save(kept)
            s.parked = "restart"  # les événements du lecteur sont ignorés dès maintenant
            try:
                await self.player.stop()
            except Exception:
                log.warning("arrêt du lecteur impossible pendant la mise de côté",
                            exc_info=True)
            self._state = replace(kept, running=True, paused=True)
        self._publish()
        return self.pause_info()

    async def _capture(self) -> str | None:
        if self.pause_image is None:
            return None
        try:
            await self.player.screenshot(self.pause_image)
        except Exception:
            log.warning("capture de la pause impossible", exc_info=True)
            return None
        self._captures += 1
        return f"/api/player/pause.jpg?v={self._captures}"

    def _apply_to_kept(self, req: PlayRequest) -> None:
        """Lecture demandée du média mis de côté : début et pistes appliqués à l'état
        gardé (pistes refusées si inconnues)."""
        s = self._session
        assert s is not None
        self._check_tracks(s, req.audio, req.subtitle)
        if req.start == "start":
            self._state = replace(self._state, position=0)
        if req.audio is not None:
            s.audio = req.audio
        if req.subtitle is not None:
            s.subtitle = req.subtitle

    async def _restore_video(self) -> None:
        """Rend la vidéo masquée (`hide`) ou réduite (`minimize`) ; un échec est
        journalisé et la reprise continue."""
        s = self._session
        assert s is not None
        mode = s.parked
        s.parked, s.image = None, None
        try:
            if mode == "hide":
                await self.player.set_video(True)
            else:
                await self.player.set_minimized(False)
        except Exception:
            log.warning("restauration de la vidéo impossible", exc_info=True)

    async def _unpark_locked(self) -> None:
        """Ramène la vidéo mise de côté, en plein écran et en lecture."""
        s = self._session
        assert s is not None and s.parked is not None
        image = s.image  # effacée par la reprise, montrée jusqu'au retour de la vidéo
        if s.parked in ("hide", "minimize"):
            await self._restore_video()
            await self.player.set_pause(False)
        elif not await self._restart(s):
            return
        self._await_video(s, image)
        self._publish()

    def _await_video(self, s: _Session, image: str | None) -> None:
        """La vidéo revient : la télé montre la capture en plein écran (même image que
        celle que mpv va afficher) et ne passe en « lecture » qu'une fois mpv reparti,
        sous sa fenêtre plein écran : pas d'écran intermédiaire visible."""
        self._cancel_resuming()
        info = self._pause_dict(s, image)
        if self.tv.state.screen != "paused" or self.tv.state.pause != info:
            self.tv.show_paused(info)
        timer = asyncio.get_running_loop().call_later(
            RESUME_FALLBACK, self._video_back, s)
        self._resuming = (s, self._state.position, [timer])

    def _video_moved(self, state: PlayerState) -> None:
        r = self._resuming
        if r is None or r[0] is not self._session or state.paused:
            return
        if state.position != r[1] and len(r[2]) == 1:
            r[2].append(asyncio.get_running_loop().call_later(
                RESUME_SETTLE, self._video_back, r[0]))

    def _video_back(self, s: _Session) -> None:
        self._cancel_resuming()
        if self._session is s and s.parked is None:
            self.tv.set_playing(s.current["work_id"])

    def _cancel_resuming(self) -> None:
        if self._resuming is not None:
            for t in self._resuming[2]:
                t.cancel()
            self._resuming = None

    async def _restart(self, s: _Session) -> bool:
        """Relance du lecteur arrêté à la mise de côté. Faux (session terminée, message
        à la télé) si le fichier n'est plus accessible ; PlayError si le lecteur ne
        démarre pas."""
        kept = self._state
        mf = self.conn.execute("SELECT * FROM media_file WHERE id = ?",
                               (s.current["media_file_id"],)).fetchone()
        try:
            if mf is None:
                raise PlayError("not_found")
            path = resolve_version(self.conn, mf, self.indexes_getter())
        except PlayError as exc:
            self._finish(kept, message(exc.code, exc.volume_label))
            return False
        tracks = load_tracks(self.conn, mf, path)
        try:
            await self.player.start(path, kept.position, s.audio, s.subtitle,
                                    [e.path for e in tracks.external])
        except FileNotFoundError:  # exécutable du lecteur introuvable
            log.warning("lecteur vidéo introuvable : impossible de relancer %s", path)
            self._finish(kept, message("player_missing"))
            raise PlayError("player_missing") from None
        except Exception:
            log.exception("échec de la relance de la vidéo mise de côté %s", path)
            self._finish(kept, message("player"))
            raise PlayError("player") from None
        s.path, s.tracks, s.parked, s.image = path, tracks, None, None
        s.paused = False
        self._state = replace(kept, running=True, paused=False)
        if kept.volume != 100:
            try:
                await self.player.set_volume(kept.volume)
            except Exception:
                log.warning("volume non rétabli après la relance", exc_info=True)
        self._notify_pause(False)
        return True

    def _notify_pause(self, paused: bool) -> None:
        try:
            self.on_pause_change(paused)
        except Exception:
            log.exception("on_pause_change a levé une exception")

    async def stop(self) -> None:
        async with self._lock:
            await self._stop_locked(show_tv=True)

    async def _stop_locked(self, show_tv: bool) -> None:
        s = self._session
        if s is None:
            return
        self._replacing = not show_tv
        try:
            await self.player.stop()
            if self._session is s:  # le lecteur n'a pas signalé la fin
                self._finish(self._state, None)
        finally:
            self._replacing = False

    # -- suivi ----------------------------------------------------------

    def _on_state(self, state: PlayerState) -> None:
        s = self._session
        if s is not None and s.parked == "restart":
            return  # lecteur arrêté par la mise de côté : la session continue
        if s is None:
            self._state = replace(state, running=False)
        elif state.error is not None:
            self._finish(state, INTERRUPTED)
            return
        elif state.ended or not state.running:
            self._finish(state, None)
            return
        else:
            moved = state.position != self._state.position
            self._state = state
            if moved:
                self._video_moved(state)
            if moved and s.parked in ("hide", "minimize") and state.paused:
                # mpv masqué ou réduit suit les sauts : la vignette de pause aussi. En pause, mpv
                # ne signale la position qu'à un saut (même rythme que la publication).
                self.tv.update_pause(self.pause_info())
            changed = state.paused != s.paused
            if changed or self.clock() - s.last_save >= SAVE_INTERVAL:
                s.paused = state.paused
                self._save(state)
            if changed:
                self._notify_pause(state.paused)
        self._publish_throttled()

    def _publish_throttled(self) -> None:
        """Tout changement est publié aussitôt, sauf le simple avancement de la position
        (mpv le signale à chaque image) : au plus une fois par seconde. En pause, mpv
        n'envoie plus de position : un saut est alors publié aussitôt. Une position
        retenue est toujours publiée en fin d'intervalle (appel différé unique)."""
        last = self._published
        if (last is not None and not self._state.paused
                and replace(last, position=0) == replace(self._state, position=0)):
            if last.position == self._state.position:
                return
            remaining = POSITION_PUBLISH_INTERVAL - (self.clock() - self._published_at)
            if remaining > 0:
                if self._trailing is None:
                    self._trailing = asyncio.get_running_loop().call_later(
                        remaining, self._publish_trailing)
                return
        self._publish()

    def _publish_trailing(self) -> None:
        self._trailing = None
        if self._published is None or self._published != self._state:
            self._publish()

    def _cancel_trailing(self) -> None:
        if self._trailing is not None:
            self._trailing.cancel()
            self._trailing = None

    def _finish(self, state: PlayerState, message: str | None) -> None:
        self._cancel_trailing()
        self._cancel_resuming()
        try:
            self._save(state)
            self.on_catalog(current_revision(self.conn))  # progression visible sans attendre
        except Exception:
            log.exception("enregistrement de la progression impossible")
        finally:
            self._session = None
            self._state = replace(state, running=False)
            if not self._replacing:
                self.tv.playback_ended(message)
            self._publish()

    def _save(self, state: PlayerState) -> None:
        s = self._session
        assert s is not None
        s.last_save = self.clock()
        cur = s.current
        position, watched = state.position, 0
        # La durée connue décide seule : une fin de fichier prématurée (fichier tronqué)
        # ne vaut pas « vu ». Sans durée, la fin de fichier fait foi.
        done = (position >= WATCHED_RATIO * state.duration if state.duration
                else state.ended)
        if done:
            position, watched = 0, 1
        audio = None if s.audio is None else str(s.audio)
        subtitle = None if s.subtitle is None else json.dumps(s.subtitle)
        self.conn.execute("BEGIN IMMEDIATE")
        try:
            rev = next_revision(self.conn)
            self.conn.execute(
                "INSERT INTO progress (target_kind, target_id, media_file_id, position,"
                " duration, watched, audio, subtitle, updated_at, rev)"
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                " ON CONFLICT(target_kind, target_id) DO UPDATE SET"
                " media_file_id = excluded.media_file_id, position = excluded.position,"
                " duration = excluded.duration,"
                " watched = MAX(progress.watched, excluded.watched),"
                " audio = excluded.audio, subtitle = excluded.subtitle,"
                " updated_at = excluded.updated_at, rev = excluded.rev",
                (cur["target_kind"], cur["target_id"], cur["media_file_id"], position,
                 state.duration, watched, audio, subtitle, time.time(), rev))
            self.conn.execute("COMMIT")
        except BaseException:
            self.conn.execute("ROLLBACK")
            raise

    def _progress(self, kind: str, target_id: int) -> sqlite3.Row | None:
        return self.conn.execute(
            "SELECT * FROM progress WHERE target_kind = ? AND target_id = ?",
            (kind, target_id)).fetchone()

    def _publish(self) -> None:
        self._cancel_trailing()
        self._published, self._published_at = copy.copy(self._state), self.clock()
        self.bus.publish({"type": "player", "state": asdict(self._state),
                          "current": self.current})
