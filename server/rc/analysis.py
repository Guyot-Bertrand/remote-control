"""Analyse en tâche de fond : langues, résolution et durée de chaque fichier présent."""

from __future__ import annotations

import asyncio
import json
import logging
import os
import sqlite3
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import TYPE_CHECKING

from rc.catalog.paths import QUALITY_ORDER
from rc.db import next_revision
from rc.frames import FfmpegMissing, extract_one
from rc.probe import cached_tracks, find_external_subs, media_summary
from rc.volumes import resolve_media_path

if TYPE_CHECKING:
    from rc.app import AppState

log = logging.getLogger("rc.analysis")

ANNOUNCE_EVERY = 30.0  # secondes entre deux annonces `catalog` pendant l'analyse
_PROBE_ERRORS = (subprocess.CalledProcessError, FileNotFoundError,
                 json.JSONDecodeError, OSError)


def pending_files(conn: sqlite3.Connection, indexes) -> list[tuple[int, Path]]:
    """Fichiers présents résolus sur un disque monté et pas encore analysés."""
    out: list[tuple[int, Path]] = []
    for r in conn.execute("SELECT id FROM media_file WHERE present = 1"
                          " AND width IS NULL AND duration_s IS NULL ORDER BY id"):
        path = resolve_media_path(conn, r["id"], indexes)
        if path is not None:
            out.append((r["id"], path))
    return out


def recheck_candidates(conn: sqlite3.Connection, indexes
                       ) -> list[tuple[int, Path, float | None, int | None]]:
    """Fichiers présents résolus, avec mtime et taille du cache d'analyse (`None` sans cache).
    Aucun `stat` : la liste se lit dans le fil principal, le `stat` se fait ailleurs."""
    cache = {r["media_file_id"]: (r["mtime"], r["size"])
             for r in conn.execute("SELECT media_file_id, mtime, size FROM probe_cache")}
    out = []
    for r in conn.execute("SELECT id FROM media_file WHERE present = 1 ORDER BY id"):
        path = resolve_media_path(conn, r["id"], indexes)
        if path is not None:
            mtime, size = cache.get(r["id"], (None, None))
            out.append((r["id"], path, mtime, size))
    return out


def stale_files(candidates) -> list[tuple[int, Path]]:
    """Garde les fichiers à ré-analyser : sans cache, modifiés (comparaison exacte, comme
    `cached_tracks`) ou dont le `stat` échoue. Bloquant : à lancer dans un fil."""
    out: list[tuple[int, Path]] = []
    for media_file_id, path, mtime, size in candidates:
        if mtime is None or size is None:
            out.append((media_file_id, path))
            continue
        try:
            st = os.stat(path)
        except OSError:
            out.append((media_file_id, path))
            continue
        if st.st_mtime != mtime or st.st_size != size:
            out.append((media_file_id, path))
    return out


def _rank(quality: str) -> int:
    return QUALITY_ORDER.index(quality) if quality in QUALITY_ORDER else len(QUALITY_ORDER)


def _best_file_id(conn: sqlite3.Connection, episode_id: int) -> int | None:
    rows = conn.execute("SELECT id, quality, present FROM media_file WHERE episode_id = ?",
                        (episode_id,)).fetchall()
    if not rows:
        return None
    return min(rows, key=lambda r: (not r["present"], _rank(r["quality"]), r["id"]))["id"]


def _values(conn: sqlite3.Connection, media_file_id: int, path: Path) -> tuple | None:
    """Valeurs à écrire ; `None` si le fichier est introuvable en base."""
    mf = conn.execute("SELECT subtitles_json FROM media_file WHERE id = ?",
                      (media_file_id,)).fetchone()
    if mf is None:
        return None
    try:
        tracks = cached_tracks(conn, media_file_id, path)
    except _PROBE_ERRORS as exc:
        log.warning("analyse impossible pour %s : %s", path, exc)
        return (None, None, 0, None, None)  # width = 0 : marqué analysé, jamais rejoué
    declared = [str(path.parent / s["filename"]) for s in json.loads(mf["subtitles_json"])]
    tracks.external = find_external_subs(path, declared)
    s = media_summary(tracks)
    width, duration = s.get("width"), s.get("duration_s")
    if width is None and duration is None:
        width = 0  # rien d'exploitable : marqué analysé comme un échec, sinon rejoué sans fin
    return (json.dumps(s["audio_langs"]), json.dumps(s["subtitle_langs"]),
            width, s.get("height"), duration)


def analyze_file(conn: sqlite3.Connection, media_file_id: int, path: Path) -> bool:
    """Analyse un fichier (bloquant) ; vrai si une valeur a changé (une révision de plus)."""
    values = _values(conn, media_file_id, path)
    if values is None:
        return False
    conn.execute("BEGIN IMMEDIATE")
    try:
        old = conn.execute(
            "SELECT audio_langs_json, subtitle_langs_json, width, height, duration_s,"
            " episode_id FROM media_file WHERE id = ?", (media_file_id,)).fetchone()
        duration = values[4]
        episode_id = old["episode_id"]
        new_minutes = None
        if (duration is not None and episode_id is not None
                and _best_file_id(conn, episode_id) == media_file_id):
            new_minutes = int(duration / 60 + 0.5)
        ep_old = None
        if new_minutes is not None:
            ep_old = conn.execute("SELECT duration_min FROM episode WHERE id = ?",
                                  (episode_id,)).fetchone()
        file_changed = tuple(old)[:5] != values
        ep_changed = ep_old is not None and ep_old["duration_min"] != new_minutes
        if file_changed or ep_changed:
            rev = next_revision(conn)
            if file_changed:
                conn.execute(
                    "UPDATE media_file SET audio_langs_json = ?, subtitle_langs_json = ?,"
                    " width = ?, height = ?, duration_s = ?, rev = ? WHERE id = ?",
                    (*values, rev, media_file_id))
            if ep_changed:
                conn.execute("UPDATE episode SET duration_min = ?, rev = ? WHERE id = ?",
                             (new_minutes, rev, episode_id))
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    return file_changed or ep_changed


def _analyze_in_thread(db_file: str, media_file_id: int, path: Path) -> bool:
    # Connexion propre au thread : sqlite3 interdit le partage de connexion entre threads.
    conn = sqlite3.connect(db_file, isolation_level=None, timeout=5)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        return analyze_file(conn, media_file_id, path)
    finally:
        conn.close()


def _extract_in_thread(db_file: str, assets_dir: Path, indexes: dict[str, dict[str, Path]],
                       failed: set[int], tmdb_enabled: bool) -> bool:
    conn = sqlite3.connect(db_file, isolation_level=None, timeout=5)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA foreign_keys=ON")
    try:
        return extract_one(conn, assets_dir, indexes, failed, tmdb_enabled)
    finally:
        conn.close()


async def analysis_loop(state: AppState, sleep: Callable = asyncio.sleep) -> None:
    """Un fichier à la fois, suspendue pendant une lecture ; annonce au plus toutes les 30 s.
    File vide : une image extraite par tour pour une œuvre sans affiche."""
    from rc.app import _db_file, publish_catalog
    from rc.db import current_revision

    db_file = _db_file(state.conn)
    poll = state.config.poll_interval
    queue: list[tuple[int, Path]] = []
    failed: set[int] = set()  # analyses en erreur : écartées jusqu'au prochain index complet
    frame_failed: set[int] = set()  # œuvres dont l'extraction a échoué, idem
    frames_off = False  # ffmpeg introuvable : plus d'extraction jusqu'au prochain index complet
    handled_index: float | None = None
    last_announce = state.clock()
    while True:
        try:
            if state.playback is not None and state.playback.current is not None:
                await sleep(poll)
                continue
            if not queue:
                full = state.last_full_index
                recheck = full is not None and full != handled_index
                if recheck:
                    queue = await asyncio.to_thread(
                        stale_files, recheck_candidates(state.conn, state.indexes))
                else:
                    queue = pending_files(state.conn, state.indexes)
                if recheck:
                    handled_index = full  # seulement une fois la liste obtenue
                    failed.clear()
                    frame_failed.clear()
                    frames_off = False
                queue = [f for f in queue if f[0] not in failed]
                if not queue:
                    idle = state.playback is None or state.playback.current is None
                    wrote = False
                    if idle and not frames_off:
                        try:
                            wrote = await asyncio.to_thread(
                                _extract_in_thread, db_file, state.config.data_dir / "assets",
                                state.indexes, frame_failed, bool(state.config.tmdb_api_key))
                        except FfmpegMissing as exc:
                            frames_off = True
                            log.warning("ffmpeg introuvable, images extraites suspendues"
                                        " jusqu'au prochain index complet (%s)", exc)
                        if wrote:  # une œuvre par tour, sans attendre la suivante
                            if state.clock() - last_announce >= ANNOUNCE_EVERY:
                                publish_catalog(state, current_revision(state.conn))
                                last_announce = state.clock()
                            continue  # le tour sans image annonce de toute façon
                    publish_catalog(state, current_revision(state.conn))
                    last_announce = state.clock()
                    await sleep(poll)
                    continue
            if state.playback is not None and state.playback.current is not None:
                await sleep(poll)  # lecture lancée pendant la vérification : file gardée
                continue
            media_file_id, path = queue.pop(0)
            try:
                await asyncio.to_thread(_analyze_in_thread, db_file, media_file_id, path)
            except Exception:
                failed.add(media_file_id)
                log.exception("échec de l'analyse de %s", path)
            if not queue or state.clock() - last_announce >= ANNOUNCE_EVERY:
                publish_catalog(state, current_revision(state.conn))
                last_announce = state.clock()
        except Exception:  # CancelledError n'en hérite pas : l'annulation passe
            log.exception("tour d'analyse en échec, nouvel essai au prochain tour")
            await sleep(poll)
