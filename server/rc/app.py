"""Assemblage du service : API, UI télé, rafraîchissement périodique et mDNS."""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
import os
import shutil
import socket
import sqlite3
import subprocess
from collections.abc import Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from pathlib import Path

from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

import rc
from rc.api import Services, install_api
from rc.assets import ingest_posters
from rc.catalog.importer import ImportRefused, ImportReport, import_catalog, load_aliases
from rc.catalog.parser import parse_catalog
from rc.config import Config
from rc.db import current_revision, open_db
from rc.events import EventBus
from rc.player.base import Player
from rc.player.mpv import MpvPlayer
from rc.player.session import PlaybackService, TvController
from rc.volumes import (Volume, VolumeUnreadable, build_index, find_catalog_source,
                        list_volumes, locate_files)

log = logging.getLogger(__name__)

SERVICE_TYPE = "_rc._tcp.local."
SERVICE_NAME = "Remote-Control._rc._tcp.local."
DEFAULT_TV_UI = Path(rc.__file__).resolve().parents[2] / "tv-ui"

Stat = tuple[int, int]  # (taille, mtime en ns)


@dataclass
class AppState:
    config: Config
    conn: sqlite3.Connection
    bus: EventBus
    runner: Callable = subprocess.run
    volumes: list[Volume] = field(default_factory=list)
    indexes: dict[str, dict[str, Path]] = field(default_factory=dict)
    signatures: dict[str, tuple[str, int]] = field(default_factory=dict)
    last_seen: Stat | None = None  # observation du catalogue au tour précédent
    refused: Stat | None = None  # dernière version refusée (avertie une seule fois)


def _stat(path: Path) -> Stat | None:
    try:
        st = path.stat()
    except OSError:
        return None
    return st.st_size, st.st_mtime_ns


def catalog_copy(config: Config) -> Path:
    return config.data_dir / "catalog" / "medias.txt"


def _copy_and_parse(src: Path, tmp: Path):
    tmp.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, tmp)  # copy2 conserve le mtime : la copie sert de référence
    return parse_catalog(tmp.read_text(encoding="utf-8"))


def _run_import(conn: sqlite3.Connection, config: Config, tmp: Path, result,
                force: bool) -> ImportReport:
    """Importe ; en cas de succès seulement, la copie temporaire remplace l'ancienne."""
    aliases_file = config.data_dir / "aliases.txt"
    aliases = load_aliases(aliases_file) if aliases_file.is_file() else {}
    try:
        report = import_catalog(conn, result, aliases, force=force)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
    os.replace(tmp, catalog_copy(config))  # copie atomique
    return report


def import_file(conn: sqlite3.Connection, config: Config, src: Path,
                force: bool = False) -> ImportReport:
    """Import immédiat (CLI) : copie, analyse et import dans le même thread."""
    tmp = catalog_copy(config).with_suffix(".tmp")
    return _run_import(conn, config, tmp, _copy_and_parse(src, tmp), force)


def _posters_in_thread(db_file: str, assets_dir: Path,
                       indexes: dict[str, dict[str, Path]]) -> int:
    # Connexion propre au thread : sqlite3 interdit le partage de connexion entre threads.
    conn = sqlite3.connect(db_file, isolation_level=None, timeout=5)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        return ingest_posters(conn, assets_dir, indexes)
    finally:
        conn.close()


def _db_file(conn: sqlite3.Connection) -> str:
    return next(r[2] for r in conn.execute("PRAGMA database_list") if r[1] == "main")


async def _refresh_volumes(state: AppState) -> bool:
    """Étape 1. Renvoie faux si la liste des disques est indisponible pour ce tour."""
    cfg = state.config
    try:
        volumes = list_volumes(cfg.scan_roots, state.runner,
                               dev_dir_volumes=cfg.dev_dir_volumes)
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        log.warning("liste des disques indisponible, état précédent conservé (%s)", exc)
        return False
    indexes: dict[str, dict[str, Path]] = {}
    signatures: dict[str, tuple[str, int]] = {}
    for v in volumes:
        root = _stat(v.mount)
        sig = (str(v.mount), root[1] if root else 0)
        if state.signatures.get(v.uuid) == sig and v.uuid in state.indexes:
            indexes[v.uuid] = state.indexes[v.uuid]
        else:
            try:
                indexes[v.uuid] = await asyncio.to_thread(build_index, v.mount)
            except VolumeUnreadable as exc:
                log.warning("disque illisible, ignoré pour ce tour : %s", exc)
                continue
            log.info("index du disque %s : %d fichiers", v.label, len(indexes[v.uuid]))
        signatures[v.uuid] = sig
    state.volumes, state.indexes, state.signatures = volumes, indexes, signatures
    return True


async def _refresh_catalog(state: AppState) -> None:
    """Étape 2 : import seulement si le fichier a changé et est stable sur deux tours."""
    cfg = state.config
    src = find_catalog_source(state.volumes, cfg.catalog_path)
    seen = _stat(src) if src is not None else None
    previous, state.last_seen = state.last_seen, seen
    if seen is None or seen != previous or seen == state.refused:
        return
    if seen == _stat(catalog_copy(cfg)):
        return
    tmp = catalog_copy(cfg).with_suffix(".tmp")
    result = await asyncio.to_thread(_copy_and_parse, src, tmp)
    try:
        report = _run_import(state.conn, cfg, tmp, result, force=False)
    except ImportRefused as exc:
        state.refused = seen
        log.warning("import refusé (%s) : copie précédente conservée", exc)
        return
    state.refused = None
    log.info("catalogue importé : %d ajoutées, %d modifiées, %d supprimées",
             report.added, report.updated, report.deleted)


async def refresh_once(state: AppState) -> None:
    before = current_revision(state.conn)
    volumes_ok = await _refresh_volumes(state)
    await _refresh_catalog(state)
    if volumes_ok:
        locate_files(state.conn, state.volumes, state.indexes)
    await asyncio.to_thread(_posters_in_thread, _db_file(state.conn),
                            state.config.data_dir / "assets", state.indexes)
    revision = current_revision(state.conn)
    if revision != before:
        state.bus.publish({"type": "catalog", "revision": revision})


async def refresh_loop(state: AppState) -> None:
    while True:
        try:
            await refresh_once(state)
        except Exception:
            log.exception("échec du rafraîchissement, nouvel essai au prochain tour")
        await asyncio.sleep(state.config.poll_interval)


def _local_addresses() -> list[bytes]:
    from zeroconf import get_all_addresses

    return [socket.inet_aton(a) for a in get_all_addresses()
            if not a.startswith("127.")]


async def mdns_announce(port: int) -> None:
    """Annonce le service tant que la tâche vit ; retire l'annonce à l'annulation."""
    from zeroconf.asyncio import AsyncServiceInfo, AsyncZeroconf

    info = AsyncServiceInfo(SERVICE_TYPE, SERVICE_NAME, port=port,
                            properties={"api": "1"}, addresses=_local_addresses())
    azc = AsyncZeroconf()
    try:
        await azc.async_register_service(info)
        log.info("service mDNS annoncé : %s (port %d)", SERVICE_NAME, port)
        await asyncio.Event().wait()
    except asyncio.CancelledError:
        raise
    except Exception:
        log.exception("annonce mDNS impossible")
    finally:
        with contextlib.suppress(Exception):
            await azc.async_unregister_service(info)
        await azc.async_close()


def _runtime_dir(config: Config) -> Path:
    base = os.environ.get("XDG_RUNTIME_DIR")
    return Path(base) / "rc" if base else config.data_dir / "run"


def create_app(config: Config, player: Player | None = None,
               runner=subprocess.run) -> FastAPI:
    config.data_dir.mkdir(parents=True, exist_ok=True)
    (config.data_dir / "catalog").mkdir(exist_ok=True)
    # check_same_thread=False : l'appli est créée dans un thread et servie dans celui de la
    # boucle d'événements (uvicorn, TestClient). La connexion n'est utilisée que dans ce
    # dernier ; les threads auxiliaires ouvrent leur propre connexion.
    conn = open_db(config.data_dir / "rc.db", check_same_thread=False)
    bus = EventBus()
    tv = TvController(bus)
    if player is None:
        player = MpvPlayer(config.mpv_path, config.mpv_extra_args, _runtime_dir(config))
    state = AppState(config, conn, bus, runner)
    indexes_getter = lambda: state.indexes  # noqa: E731
    playback = PlaybackService(conn, player, tv, bus, indexes_getter)
    services = Services(conn, config, bus, tv, playback, indexes_getter)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        tasks = [asyncio.create_task(refresh_loop(state))]
        if config.mdns:
            tasks.append(asyncio.create_task(mdns_announce(config.port)))
        try:
            yield
        finally:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            with contextlib.suppress(Exception):
                await playback.stop()

    app = FastAPI(title="Remote-Control", lifespan=lifespan)
    app.state.rc = state
    install_api(app, services)
    tv_ui = config.tv_ui_dir or DEFAULT_TV_UI
    if tv_ui.is_dir():
        app.mount("/tv", StaticFiles(directory=tv_ui, html=True), name="tv")
    else:
        log.warning("interface télé introuvable : %s", tv_ui)
    return app
