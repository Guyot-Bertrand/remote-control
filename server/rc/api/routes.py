"""Routes HTTP de l'API (/api/...)."""

from __future__ import annotations

import asyncio
import logging
import re
import shutil
import sqlite3
from collections.abc import Awaitable, Callable
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import TYPE_CHECKING, Annotated, Literal

from fastapi import APIRouter, Request
from fastapi.exception_handlers import http_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, Field, RootModel, StringConstraints
from starlette.exceptions import HTTPException as StarletteHTTPException

from rc.api import serialize
from rc.assets import SIZES, asset_path
from rc.config import Config
from rc.db import current_revision
from rc.events import EventBus
from rc.messages import RESOURCE_NOT_FOUND, error_body
from rc.player.mpv import MpvError
from rc.player.session import (PlaybackService, PlayError, PlayRequest, TvController,
                               load_tracks, pick_tracks, resolve_version)
from rc.probe import MediaTracks

if TYPE_CHECKING:
    from rc.mirror import MirrorService

log = logging.getLogger(__name__)

CACHE_IMMUTABLE = "public, max-age=31536000, immutable"
ASSET_SIZES = {"orig", *SIZES}
_HASH = re.compile(r"[0-9a-f]{1,64}")


@dataclass
class Services:
    conn: sqlite3.Connection
    config: Config
    bus: EventBus
    tv: TvController
    playback: PlaybackService
    indexes_getter: Callable[[], dict[str, dict[str, Path]]]
    mirror: MirrorService

    def player_snapshot(self) -> dict:
        return {"state": asdict(self.playback.state), "current": self.playback.current}


# -- corps des requêtes ---------------------------------------------------

class SubNone(BaseModel):
    kind: Literal["none"]


class SubEmbedded(BaseModel):
    kind: Literal["embedded"]
    index: int


class SubExternal(BaseModel):
    kind: Literal["external"]
    path: str


SubSelModel = Annotated[SubNone | SubEmbedded | SubExternal, Field(discriminator="kind")]


class SubSelBody(RootModel[SubSelModel]):
    pass


class ItemRef(BaseModel):
    kind: Literal["work", "episode", "collection"]
    id: int


class Person(BaseModel):
    name: str
    role: str
    count: int


class Section(BaseModel):
    label: str
    kind: Literal["resume", "recent", "collections", "people", "works", "aka"] | None = None
    people: list[Person] = Field(default_factory=list, max_length=40)
    items: list[ItemRef] = Field(default_factory=list, max_length=40)


class TvViewBody(BaseModel):
    """Vue du téléphone (arbitrage 14)."""
    phone_id: Annotated[str, StringConstraints(min_length=1, max_length=64)]
    seq: int = Field(ge=0)
    screen: Literal["home", "browse", "collection", "search", "work", "season", "preplay",
                    "remote"]
    title: str | None = None
    subtitle: str | None = None
    filter: Literal["playable", "all"] | None = None
    query: str | None = None
    work_id: int | None = None
    season_id: int | None = None
    episode_id: int | None = None
    media_file_id: int | None = None
    collection_id: int | None = None
    audio_track: int | None = None
    subtitle_track: SubSelModel | None = None
    items: list[ItemRef] = Field(default_factory=list, max_length=40)
    anchor: int | None = None
    position: dict | None = None
    sections: list[Section] = Field(default_factory=list, max_length=6)


class PlayBody(BaseModel):
    target_kind: Literal["work", "episode"]
    target_id: int
    start: Literal["start", "resume"]
    media_file_id: int | None = None
    audio: int | None = None
    subtitle: SubSelModel | None = None


class PauseBody(BaseModel):
    paused: bool


class SeekBody(BaseModel):
    seconds: float
    relative: bool


class VolumeBody(BaseModel):
    level: int = Field(ge=0, le=100)


class AudioBody(BaseModel):
    index: int | None


# -- réponses -------------------------------------------------------------

OK = {"ok": True}


def not_found() -> JSONResponse:
    return JSONResponse({"error": "not_found", "volume_label": None,
                         "message": RESOURCE_NOT_FOUND}, status_code=404)


def conflict(code: str, volume_label: str | None = None) -> JSONResponse:
    return JSONResponse(error_body(code, volume_label), status_code=409)


def invalid() -> JSONResponse:
    return JSONResponse(error_body("invalid"), status_code=422)


async def http_error_handler(request: Request, exc: StarletteHTTPException) -> Response:
    """Erreurs de routage (route inconnue, méthode non permise) : format de l'API sous
    /api/, comportement par défaut ailleurs (page télé)."""
    if not request.url.path.startswith("/api/"):
        return await http_exception_handler(request, exc)
    if exc.status_code == 404:
        return not_found()
    code = "internal" if exc.status_code >= 500 else "invalid"
    return JSONResponse(error_body(code), status_code=exc.status_code,
                        headers=getattr(exc, "headers", None))


async def validation_error_handler(request: Request,
                                   exc: RequestValidationError) -> JSONResponse:
    return invalid()


def _db_file(conn: sqlite3.Connection) -> str:
    return next(r["file"] for r in conn.execute("PRAGMA database_list") if r["name"] == "main")


def _tracks_in_thread(db_file: str, mf: sqlite3.Row, path: Path) -> MediaTracks:
    # Connexion propre au thread : sqlite3 interdit le partage de connexion entre threads.
    conn = sqlite3.connect(db_file, isolation_level=None, timeout=5)
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        return load_tracks(conn, mf, path)
    finally:
        conn.close()


def build_router(services: Services) -> APIRouter:
    s = services
    router = APIRouter(prefix="/api")

    @router.get("/info")
    async def info():
        return {"name": "Remote-Control", "api": 1, "revision": current_revision(s.conn),
                "player_available": shutil.which(s.config.mpv_path) is not None}

    @router.get("/sync")
    async def sync(since: int | None = None):
        return serialize.sync(s.conn, since)

    @router.get("/works/{work_id}")
    async def work(work_id: int):
        d = serialize.work_detail(s.conn, work_id)
        return not_found() if d is None else d

    @router.get("/seasons/{season_id}")
    async def season(season_id: int):
        d = serialize.season_detail(s.conn, season_id)
        return not_found() if d is None else d

    @router.get("/media/{media_file_id}/tracks")
    async def media_tracks(media_file_id: int):
        mf = s.conn.execute("SELECT * FROM media_file WHERE id = ?",
                            (media_file_id,)).fetchone()
        if mf is None:
            return not_found()
        try:
            path = resolve_version(s.conn, mf, s.indexes_getter())
        except PlayError as exc:
            return conflict(exc.code, exc.volume_label)
        db_file = _db_file(s.conn)
        if db_file:
            tracks = await asyncio.to_thread(_tracks_in_thread, db_file, mf, path)
        else:  # base en mémoire : pas de seconde connexion possible
            tracks = load_tracks(s.conn, mf, path)
        kind, target = ("work", mf["work_id"]) if mf["work_id"] is not None else (
            "episode", mf["episode_id"])
        prog = s.conn.execute(
            "SELECT * FROM progress WHERE target_kind = ? AND target_id = ?",
            (kind, target)).fetchone()
        audio, subtitle = pick_tracks(prog, tracks)
        return serialize.tracks(tracks, audio, subtitle)

    @router.get("/assets/{hash}/{size}.jpg")
    async def asset(hash: str, size: str):
        if size not in ASSET_SIZES or not _HASH.fullmatch(hash):
            return not_found()
        path = asset_path(s.config.data_dir / "assets", hash, size)
        if not path.is_file():
            return not_found()
        return FileResponse(path, media_type="image/jpeg",
                            headers={"Cache-Control": CACHE_IMMUTABLE})

    @router.get("/tv/home")
    async def tv_home():
        return serialize.tv_home(s.conn)

    @router.get("/tv/state")
    async def tv_state():
        return asdict(s.tv.state)

    @router.post("/tv/view")
    async def tv_view(body: TvViewBody):
        return {"accepted": await s.mirror.accept(body.model_dump())}

    @router.get("/player/pause.jpg")
    async def pause_image():
        path = s.config.data_dir / "pause" / "pause.jpg"
        if not path.is_file():
            return not_found()
        return FileResponse(path, media_type="image/jpeg",
                            headers={"Cache-Control": "no-store"})

    @router.post("/play")
    async def play(body: PlayBody):
        req = PlayRequest(body.target_kind, body.target_id, body.start, body.media_file_id,
                          body.audio,
                          None if body.subtitle is None else body.subtitle.model_dump())
        try:
            await s.playback.play(req)
        except PlayError as exc:
            if exc.code == "invalid":
                return invalid()
            return conflict(exc.code, exc.volume_label)
        return OK

    def idle() -> bool:
        return s.playback.current is None

    async def command(action: Awaitable) -> JSONResponse | dict:
        """Commande au lecteur : mpv figé, socket fermée ou lecture terminée entre-temps
        donnent 409 {"error": "player"} plutôt qu'une erreur 500."""
        try:
            await action
        except PlayError:  # sélection absente des pistes connues
            return invalid()
        except (MpvError, ConnectionError, RuntimeError):
            log.warning("commande au lecteur impossible", exc_info=True)
            return conflict("player")
        return OK

    @router.post("/player/pause")
    async def pause(body: PauseBody):
        if idle():
            return conflict("idle")
        return await command(s.playback.pause(body.paused))

    @router.post("/player/seek")
    async def seek(body: SeekBody):
        if idle():
            return conflict("idle")
        return await command(s.playback.seek(body.seconds, body.relative))

    @router.post("/player/volume")
    async def volume(body: VolumeBody):
        if idle():
            return conflict("idle")
        return await command(s.playback.set_volume(body.level))

    @router.post("/player/audio")
    async def audio(body: AudioBody):
        if idle():
            return conflict("idle")
        return await command(s.playback.set_audio(body.index))

    @router.post("/player/subtitle")
    async def subtitle(body: SubSelBody):
        if idle():
            return conflict("idle")
        return await command(s.playback.set_subtitle(body.root.model_dump()))

    @router.post("/player/stop")
    async def stop():
        if idle():
            return conflict("idle")
        await s.playback.stop()
        return OK

    @router.get("/player/state")
    async def player_state():
        return s.player_snapshot()

    return router
