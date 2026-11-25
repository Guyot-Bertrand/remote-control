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
import time
from collections.abc import Callable
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from pathlib import Path

import httpx
from fastapi import FastAPI
from fastapi.staticfiles import StaticFiles

import rc
from rc.api import Services, install_api
from rc.assets import ingest_posters
from rc.catalog.importer import ImportRefused, ImportReport, import_catalog, load_aliases
from rc.catalog.model import ParseResult
from rc.catalog.parser import parse_catalog
from rc.config import Config
from rc.db import current_revision, open_db
from rc.events import EventBus
from rc.player.base import Player
from rc.player.mpv import MpvPlayer
from rc.player.session import PlaybackService, TvController
from rc.tmdb import fetch_missing_posters
from rc.volumes import (Volume, VolumeUnreadable, build_index, find_catalog_source,
                        list_volumes, locate_files)

log = logging.getLogger(__name__)

SERVICE_TYPE = "_rc._tcp.local."
SERVICE_NAME = "Remote-Control._rc._tcp.local."
DEFAULT_TV_UI = Path(rc.__file__).resolve().parents[2] / "tv-ui"

Stat = tuple[int, int]  # (taille, mtime en ns)
LOGGED_DETAILS = 20  # avertissements et orphelins détaillés dans le journal
SKIPPED_INTERFACES = ("lo", "docker", "br-", "veth", "virbr")


@dataclass
class AppState:
    config: Config
    conn: sqlite3.Connection
    bus: EventBus
    runner: Callable = subprocess.run
    clock: Callable[[], float] = time.monotonic
    volumes: list[Volume] = field(default_factory=list)
    indexes: dict[str, dict[str, Path]] = field(default_factory=dict)
    signatures: dict[str, tuple[str, int]] = field(default_factory=dict)
    last_full_index: float | None = None  # horloge du dernier index complet
    last_seen: Stat | None = None  # observation du catalogue au tour précédent
    refused: Stat | None = None  # dernière version refusée (avertie une seule fois)
    published_revision: int | None = None  # dernière révision annoncée aux clients
    poster_failures: set = field(default_factory=set)  # affiches illisibles (inchangées)
    playback: PlaybackService | None = None


def publish_catalog(state: AppState, revision: int) -> None:
    """Annonce une révision une seule fois (rafraîchissement ou fin de lecture)."""
    if revision != state.published_revision:
        state.published_revision = revision
        state.bus.publish({"type": "catalog", "revision": revision})


def _stat(path: Path) -> Stat | None:
    try:
        st = path.stat()
    except OSError:
        return None
    return st.st_size, st.st_mtime_ns


def catalog_copy(config: Config) -> Path:
    return config.data_dir / "catalog" / "medias.txt"


def _copy(src: Path, tmp: Path) -> Stat | None:
    tmp.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(src, tmp)  # copy2 conserve le mtime : la copie sert de référence
    return _stat(tmp)


def _parse_copy(tmp: Path) -> ParseResult:
    """Analyse la copie ; si elle est illisible, la copie temporaire est supprimée."""
    try:
        return parse_catalog(tmp.read_text(encoding="utf-8"))
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise


def _log_warnings(result: ParseResult) -> None:
    if not result.warnings:
        return
    log.warning("catalogue : %d avertissement(s) d'analyse", len(result.warnings))
    for w in result.warnings[:LOGGED_DETAILS]:
        log.warning("catalogue : %s", w)


def _log_orphans(report: ImportReport) -> None:
    for type_, key in report.removed[:LOGGED_DETAILS]:
        log.warning("identifiant orphelin — ajouter un alias ? %s ; %s", type_, key)
    if len(report.removed) > LOGGED_DETAILS:
        log.warning("… et %d autre(s) identifiant(s) orphelin(s)",
                    len(report.removed) - LOGGED_DETAILS)


def _run_import(conn: sqlite3.Connection, config: Config, tmp: Path, result: ParseResult,
                force: bool) -> ImportReport:
    """Importe ; en cas de succès seulement, la copie temporaire remplace l'ancienne.
    Commun au rafraîchissement et à `rc import` : avertissements et orphelins journalisés."""
    _log_warnings(result)
    aliases_file = config.data_dir / "aliases.txt"
    aliases = load_aliases(aliases_file) if aliases_file.is_file() else {}
    try:
        report = import_catalog(conn, result, aliases, force=force)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise
    os.replace(tmp, catalog_copy(config))  # copie atomique
    _log_orphans(report)
    return report


def import_file(conn: sqlite3.Connection, config: Config, src: Path,
                force: bool = False) -> ImportReport:
    """Import immédiat (CLI) : copie, analyse et import dans le même thread."""
    tmp = catalog_copy(config).with_suffix(".tmp")
    _copy(src, tmp)
    return _run_import(conn, config, tmp, _parse_copy(tmp), force)


def restore_from_copy(conn: sqlite3.Connection, config: Config) -> None:
    """Base vide (rc.db supprimée) mais copie du catalogue présente : la copie servant de
    référence au rafraîchissement, le catalogue ne serait jamais réimporté. On l'importe
    donc une fois au démarrage."""
    copy = catalog_copy(config)
    if not copy.is_file():
        return
    needs_reimport = conn.execute(
        "SELECT EXISTS (SELECT 1 FROM meta WHERE key = 'needs_reimport')").fetchone()[0]
    if not needs_reimport and conn.execute("SELECT EXISTS (SELECT 1 FROM work)").fetchone()[0]:
        return
    try:
        result = parse_catalog(copy.read_text(encoding="utf-8"))
        _log_warnings(result)
        aliases_file = config.data_dir / "aliases.txt"
        aliases = load_aliases(aliases_file) if aliases_file.is_file() else {}
        report = import_catalog(conn, result, aliases, force=bool(needs_reimport))
        conn.execute("DELETE FROM meta WHERE key = 'needs_reimport'")
    except Exception as exc:
        log.warning("base vide : réimport de la copie du catalogue impossible (%s)", exc)
        return
    log.info("catalogue réimporté depuis %s (%d œuvres)", copy, report.added)


def _posters_in_thread(db_file: str, assets_dir: Path,
                       indexes: dict[str, dict[str, Path]], failed: set) -> int:
    # Connexion propre au thread : sqlite3 interdit le partage de connexion entre threads.
    conn = sqlite3.connect(db_file, isolation_level=None, timeout=5)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        return ingest_posters(conn, assets_dir, indexes, failed)
    finally:
        conn.close()


def _db_file(conn: sqlite3.Connection) -> str:
    return next(r[2] for r in conn.execute("PRAGMA database_list") if r[1] == "main")


def _list_volumes(state: AppState) -> bool:
    """Renvoie faux si la liste des disques est indisponible pour ce tour."""
    cfg = state.config
    try:
        state.volumes = list_volumes(cfg.scan_roots, state.runner,
                                     dev_dir_volumes=cfg.dev_dir_volumes)
    except (OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
        log.warning("liste des disques indisponible, état précédent conservé (%s)", exc)
        return False
    return True


async def _index_volumes(state: AppState, full: bool) -> None:
    """Index des disques nouveaux ou modifiés ; de tous si `full`."""
    indexes: dict[str, dict[str, Path]] = {}
    signatures: dict[str, tuple[str, int]] = {}
    for v in state.volumes:
        root = _stat(v.mount)
        sig = (str(v.mount), root[1] if root else 0)
        if not full and state.signatures.get(v.uuid) == sig and v.uuid in state.indexes:
            indexes[v.uuid] = state.indexes[v.uuid]
        else:
            try:
                indexes[v.uuid] = await asyncio.to_thread(build_index, v.mount)
            except VolumeUnreadable as exc:
                log.warning("disque illisible, ignoré pour ce tour : %s", exc)
                continue
            log.info("index du disque %s : %d fichiers", v.label, len(indexes[v.uuid]))
        signatures[v.uuid] = sig
    state.indexes, state.signatures = indexes, signatures
    if full:
        state.last_full_index = state.clock()


async def _refresh_catalog(state: AppState) -> bool:
    """Étape 2 : import seulement si le fichier a changé et est stable sur deux tours.
    Renvoie vrai si un import a eu lieu."""
    cfg = state.config
    src = find_catalog_source(state.volumes, cfg.catalog_path)
    seen = _stat(src) if src is not None else None
    previous, state.last_seen = state.last_seen, seen
    if seen is None or seen != previous or seen == state.refused:
        return False
    if seen == _stat(catalog_copy(cfg)):
        return False
    tmp = catalog_copy(cfg).with_suffix(".tmp")
    if await asyncio.to_thread(_copy, src, tmp) != seen:  # modifié pendant la copie
        tmp.unlink(missing_ok=True)
        return False
    try:
        result = await asyncio.to_thread(_parse_copy, tmp)
    except Exception as exc:  # catalogue illisible (UnicodeDecodeError...) : comme un refus
        state.refused = seen
        log.warning("catalogue illisible (%s) : copie précédente conservée", exc)
        return False
    try:
        report = _run_import(state.conn, cfg, tmp, result, force=False)
    except ImportRefused as exc:
        state.refused = seen
        log.warning("import refusé (%s) : copie précédente conservée", exc)
        return False
    state.refused = None
    log.info("catalogue importé : %d ajoutées, %d modifiées, %d supprimées",
             report.added, report.updated, report.deleted)
    return True


async def _tmdb_step(state: AppState) -> None:
    """Étape facultative : jamais bloquante pour la publication du tour."""
    if not state.config.tmdb_api_key:
        return
    try:
        async with httpx.AsyncClient(timeout=10) as client:
            await fetch_missing_posters(state.conn, state.config.data_dir / "assets",
                                        state.config.tmdb_api_key, client,
                                        indexes=state.indexes)
    except Exception:
        log.exception("secours TMDB en échec, tour poursuivi")


async def refresh_once(state: AppState) -> None:
    volumes_ok = _list_volumes(state)
    if volumes_ok:
        due = (state.last_full_index is None or state.clock() - state.last_full_index
               >= state.config.index_refresh_interval)
        await _index_volumes(state, full=due)
    if await _refresh_catalog(state) and volumes_ok:
        await _index_volumes(state, full=True)  # nouveau catalogue : nouveaux fichiers
    if volumes_ok:
        locate_files(state.conn, state.volumes, state.indexes)
    await asyncio.to_thread(_posters_in_thread, _db_file(state.conn),
                            state.config.data_dir / "assets", state.indexes,
                            state.poster_failures)
    await _tmdb_step(state)
    # Toute révision non encore annoncée : import, affiches, mais aussi progressions
    # enregistrées pendant la lecture ou import fait par `rc import` dans un autre processus.
    publish_catalog(state, current_revision(state.conn))


async def refresh_loop(state: AppState) -> None:
    while True:
        try:
            await refresh_once(state)
        except Exception:
            log.exception("échec du rafraîchissement, nouvel essai au prochain tour")
        await asyncio.sleep(state.config.poll_interval)


def local_addresses() -> list[bytes]:
    """IPv4 des interfaces réelles (sans boucle locale ni ponts Docker/libvirt)."""
    import ifaddr

    return [socket.inet_aton(ip.ip) for a in ifaddr.get_adapters()
            if not a.name.startswith(SKIPPED_INTERFACES)
            for ip in a.ips if ip.is_IPv4 and not ip.ip.startswith("127.")]


def _default_zeroconf():
    from zeroconf.asyncio import AsyncZeroconf

    return AsyncZeroconf()


async def mdns_announce(port: int, zeroconf_factory: Callable = _default_zeroconf,
                        addresses: Callable[[], list[bytes]] = local_addresses,
                        interval: float = 60) -> None:
    """Annonce le service tant que la tâche vit ; retire l'annonce à l'annulation.
    Toutes les `interval` secondes (un tour de rafraîchissement), les adresses locales
    sont relues : si elles ont changé (Wi-Fi rebranché, DHCP), l'annonce est mise à jour.
    Un échec est journalisé : le serveur continue sans annonce."""
    from zeroconf.asyncio import AsyncServiceInfo

    azc = info = None
    try:
        announced = addresses()
        info = AsyncServiceInfo(SERVICE_TYPE, SERVICE_NAME, port=port,
                                properties={"api": "1"}, addresses=announced)
        azc = zeroconf_factory()
        # zeroconf renvoie une tâche : l'attendre garantit l'envoi complet des annonces.
        await (await azc.async_register_service(info))
        log.info("service mDNS annoncé : %s (port %d)", SERVICE_NAME, port)
        while True:
            await asyncio.sleep(interval)
            try:
                current = addresses()
            except Exception as exc:
                log.warning("adresses locales illisibles, annonce inchangée (%s)", exc)
                continue
            if set(current) == set(announced):
                continue
            try:
                info.addresses = current
                await (await azc.async_update_service(info))
            except Exception:
                log.exception("mise à jour de l'annonce mDNS impossible")
                continue
            announced = current
            log.info("annonce mDNS mise à jour : %s",
                     ", ".join(socket.inet_ntoa(a) for a in current) or "aucune adresse")
    except Exception:
        log.exception("annonce mDNS impossible")
    finally:
        if azc is not None:
            try:
                await (await azc.async_unregister_service(info))
            except Exception:
                log.debug("retrait de l'annonce mDNS impossible", exc_info=True)
            try:
                await azc.async_close()
            except Exception:
                log.debug("fermeture de zeroconf impossible", exc_info=True)


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
    restore_from_copy(conn, config)
    bus = EventBus()
    tv = TvController(bus)
    player_available = lambda: True  # noqa: E731 — lecteur fourni (tests)
    if player is None:
        player = MpvPlayer(config.mpv_path, config.mpv_extra_args, _runtime_dir(config))
        player_available = lambda: shutil.which(config.mpv_path) is not None  # noqa: E731
        if not player_available():
            log.warning("lecteur vidéo mpv introuvable (%s) : la lecture sera impossible."
                        " Installer mpv (sudo apt install mpv) ou régler mpv_path.",
                        config.mpv_path)
    state = AppState(config, conn, bus, runner, published_revision=current_revision(conn))
    indexes_getter = lambda: state.indexes  # noqa: E731
    playback = PlaybackService(conn, player, tv, bus, indexes_getter,
                               on_catalog=lambda rev: publish_catalog(state, rev),
                               player_available=player_available)
    state.playback = playback
    services = Services(conn, config, bus, tv, playback, indexes_getter)

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        tasks = [asyncio.create_task(refresh_loop(state))]
        if config.mdns:
            tasks.append(asyncio.create_task(
                mdns_announce(config.port, interval=config.poll_interval)))
        try:
            yield
        finally:
            for t in tasks:
                t.cancel()
            await asyncio.gather(*tasks, return_exceptions=True)
            with contextlib.suppress(Exception):
                await playback.stop()
            conn.close()

    app = FastAPI(title="Remote-Control", lifespan=lifespan)
    app.state.rc = state
    install_api(app, services)
    tv_ui = config.tv_ui_dir or DEFAULT_TV_UI
    if tv_ui.is_dir():
        app.mount("/tv", StaticFiles(directory=tv_ui, html=True), name="tv")
    else:
        log.warning("interface télé introuvable : %s", tv_ui)
    return app
