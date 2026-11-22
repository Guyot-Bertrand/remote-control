"""Session de lecture : choix de la version, reprise, suivi de progression, état de la télé."""

from __future__ import annotations

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
from rc.db import next_revision
from rc.events import EventBus
from rc.player.base import Player, PlayerState, SubSel
from rc.probe import MediaTracks, cached_tracks, default_selection, find_external_subs
from rc.volumes import resolve_media_path

log = logging.getLogger(__name__)

SAVE_INTERVAL = 10
WATCHED_RATIO = 0.9
MSG_INTERRUPTED = "Lecture interrompue : fichier inaccessible"

Screen = Literal["home", "work", "season", "playing", "message"]


@dataclass
class TvState:
    screen: Screen
    work_id: int | None = None
    season_id: int | None = None
    message: str | None = None


class TvController:
    def __init__(self, bus: EventBus) -> None:
        self.bus = bus
        self.state = TvState("home")
        self._last_sheet = TvState("home")

    def show(self, screen: Screen, work_id: int | None = None,
             season_id: int | None = None, force: bool = False) -> bool:
        if self.state.screen == "playing" and not force:
            return False
        self._set(TvState(screen, work_id, season_id))
        if screen in ("home", "work", "season"):
            self._last_sheet = self.state
        return True

    def set_playing(self, work_id: int | None) -> None:
        self._set(TvState("playing", work_id=work_id))

    def playback_ended(self, message: str | None) -> None:
        if message is None:
            self._set(self._last_sheet)
        else:
            self._set(TvState("message", message=message))

    def _set(self, state: TvState) -> None:
        self.state = state
        self.bus.publish({"type": "tv", "state": asdict(state)})


@dataclass
class PlayRequest:
    target_kind: Literal["work", "episode"]
    target_id: int
    start: Literal["start", "resume"]
    media_file_id: int | None = None
    audio: int | None = None
    subtitle: SubSel | None = None


class PlayError(Exception):
    def __init__(self, code: Literal["not_playable", "volume_absent", "not_found"],
                 volume_label: str | None = None) -> None:
        super().__init__(code)
        self.code = code
        self.volume_label = volume_label


def decode_audio(text: str | None) -> int | None:
    return None if text is None else int(text)


def decode_subtitle(text: str | None) -> SubSel | None:
    return None if text is None else json.loads(text)


def _quality_rank(quality: str) -> int:
    return QUALITY_ORDER.index(quality) if quality in QUALITY_ORDER else len(QUALITY_ORDER)


@dataclass
class _Session:
    current: dict
    audio: int | None
    subtitle: SubSel
    last_save: float
    paused: bool = False


class PlaybackService:
    def __init__(self, conn: sqlite3.Connection, player: Player, tv: TvController,
                 bus: EventBus, indexes_getter: Callable[[], dict],
                 clock: Callable[[], float] = time.monotonic) -> None:
        self.conn, self.player, self.tv, self.bus = conn, player, tv, bus
        self.indexes_getter, self.clock = indexes_getter, clock
        self._state = PlayerState()
        self._session: _Session | None = None
        player.on_state = self._on_state

    @property
    def state(self) -> PlayerState:
        return copy.copy(self._state)

    @property
    def current(self) -> dict | None:
        return dict(self._session.current) if self._session else None

    # -- lancement ------------------------------------------------------

    async def play(self, req: PlayRequest) -> None:
        mf = self._choose_version(req)
        path = self._resolve(mf)
        if self._session is not None:
            await self.stop()
        prog = self._progress(req.target_kind, req.target_id)
        start_at = 0.0
        if req.start == "resume" and prog is not None and not prog["watched"]:
            start_at = prog["position"] or 0.0
        tracks = self._tracks(mf, path)
        audio, subtitle = self._pick_tracks(req, prog, tracks)
        external = [e.path for e in tracks.external]
        try:
            await self.player.start(path, start_at, audio, subtitle, external)
        except Exception:
            log.exception("échec du démarrage de la lecture de %s", path)
            self._state = replace(self._state, running=False)
            self.tv.playback_ended(MSG_INTERRUPTED)
            raise PlayError("not_found") from None
        current = self._describe(req, mf["id"])
        self._session = _Session(current, audio, subtitle, self.clock())
        self._state = PlayerState(position=start_at, running=True)
        self.tv.set_playing(current["work_id"])
        self._publish()

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
        indexes = self.indexes_getter()
        path = resolve_media_path(self.conn, mf["id"], indexes)
        if path is not None:
            return path
        uuid = mf["volume_uuid"]
        if uuid is not None and uuid not in indexes:
            row = self.conn.execute(
                "SELECT label FROM volume WHERE uuid = ?", (uuid,)).fetchone()
            label = (row["label"] if row else None) or uuid
            if self._session is None:
                self.tv.playback_ended(f"Disque « {label} » non branché")
            raise PlayError("volume_absent", label)
        raise PlayError("not_found")

    def _tracks(self, mf: sqlite3.Row, path: Path) -> MediaTracks:
        try:
            tracks = cached_tracks(self.conn, mf["id"], path)
        except (subprocess.CalledProcessError, FileNotFoundError,
                json.JSONDecodeError, OSError) as exc:
            log.warning("analyse des pistes impossible pour %s : %s", path, exc)
            tracks = MediaTracks([], [], [], None, None)
        declared = [str(path.parent / s["filename"])
                    for s in json.loads(mf["subtitles_json"])]
        tracks.external = find_external_subs(path, declared)
        return tracks

    def _pick_tracks(self, req: PlayRequest, prog: sqlite3.Row | None,
                     tracks: MediaTracks) -> tuple[int | None, SubSel]:
        default_audio, default_sub = default_selection(tracks)
        audio, subtitle = req.audio, req.subtitle
        if audio is None:
            stored = decode_audio(prog["audio"]) if prog is not None else None
            valid = stored in {t.index for t in tracks.audio}
            audio = stored if valid else default_audio
        if subtitle is None:
            stored_sub = decode_subtitle(prog["subtitle"]) if prog is not None else None
            subtitle = stored_sub if self._sub_valid(stored_sub, tracks) else default_sub
        return audio, subtitle

    @staticmethod
    def _sub_valid(sel: SubSel | None, tracks: MediaTracks) -> bool:
        if sel is None:
            return False
        kind = sel.get("kind")
        if kind == "embedded":
            return sel.get("index") in {t.index for t in tracks.subtitles}
        if kind == "external":
            return Path(sel.get("path", "")).is_file()
        return kind == "none"

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

    async def pause(self, paused: bool) -> None:
        await self.player.set_pause(paused)

    async def seek(self, seconds: float, relative: bool) -> None:
        await self.player.seek(seconds, relative)

    async def set_volume(self, level: int) -> None:
        await self.player.set_volume(level)

    async def set_audio(self, index: int | None) -> None:
        await self.player.set_audio(index)
        if self._session is not None:
            self._session.audio = index

    async def set_subtitle(self, sel: SubSel) -> None:
        await self.player.set_subtitle(sel)
        if self._session is not None:
            self._session.subtitle = sel

    async def stop(self) -> None:
        if self._session is None:
            return
        await self.player.stop()
        if self._session is not None:  # le lecteur n'a pas signalé la fin
            self._finish(self._state, None)

    # -- suivi ----------------------------------------------------------

    def _on_state(self, state: PlayerState) -> None:
        s = self._session
        if s is None:
            self._state = replace(state, running=False)
        elif state.error is not None:
            self._finish(state, MSG_INTERRUPTED)
            return
        elif state.ended or not state.running:
            self._finish(state, None)
            return
        else:
            self._state = state
            if state.paused != s.paused or self.clock() - s.last_save >= SAVE_INTERVAL:
                s.paused = state.paused
                self._save(state)
        self._publish()

    def _finish(self, state: PlayerState, message: str | None) -> None:
        self._save(state)
        self._session = None
        self._state = replace(state, running=False)
        self.tv.playback_ended(message)
        self._publish()

    def _save(self, state: PlayerState) -> None:
        s = self._session
        assert s is not None
        s.last_save = self.clock()
        cur = s.current
        position, watched = state.position, 0
        if state.ended or (state.duration and position >= WATCHED_RATIO * state.duration):
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
        self.bus.publish({"type": "player", "state": asdict(self._state),
                          "current": self.current})
