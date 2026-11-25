"""Analyse en tâche de fond : langues, résolution et durée de chaque fichier présent."""

from __future__ import annotations

import asyncio
import json
import logging
import sqlite3
import subprocess
from collections.abc import Callable
from pathlib import Path
from typing import TYPE_CHECKING

from rc.catalog.paths import QUALITY_ORDER
from rc.db import next_revision
from rc.probe import cached_tracks, find_external_subs, media_summary
from rc.volumes import resolve_media_path

if TYPE_CHECKING:
    from rc.app import AppState

log = logging.getLogger("rc.analysis")

ANNOUNCE_EVERY = 30.0  # secondes entre deux annonces `catalog` pendant l'analyse
_PROBE_ERRORS = (subprocess.CalledProcessError, FileNotFoundError,
                 json.JSONDecodeError, OSError)


def pending_files(conn: sqlite3.Connection, indexes, recheck: bool) -> list[tuple[int, Path]]:
    """Fichiers présents résolus sur un disque monté ; seuls les non analysés sauf `recheck`
    (le cache d'analyse décide alors s'il faut relancer ffprobe)."""
    where = "" if recheck else " AND width IS NULL AND duration_s IS NULL"
    out: list[tuple[int, Path]] = []
    for r in conn.execute(f"SELECT id FROM media_file WHERE present = 1{where} ORDER BY id"):
        path = resolve_media_path(conn, r["id"], indexes)
        if path is not None:
            out.append((r["id"], path))
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
    return (json.dumps(s["audio_langs"]), json.dumps(s["subtitle_langs"]),
            s.get("width"), s.get("height"), s.get("duration_s"))


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


async def analysis_loop(state: AppState, sleep: Callable = asyncio.sleep) -> None:
    """Un fichier à la fois, suspendue pendant une lecture ; annonce au plus toutes les 30 s."""
    from rc.app import _db_file, publish_catalog
    from rc.db import current_revision

    db_file = _db_file(state.conn)
    poll = state.config.poll_interval
    queue: list[tuple[int, Path]] = []
    handled_index: float | None = None
    last_announce = state.clock()
    while True:
        if state.playback is not None and state.playback.current is not None:
            await sleep(poll)
            continue
        if not queue:
            recheck = state.last_full_index is not None and state.last_full_index != handled_index
            if recheck:
                handled_index = state.last_full_index
            try:
                queue = pending_files(state.conn, state.indexes, recheck)
            except sqlite3.Error:
                log.exception("liste des fichiers à analyser indisponible")
            if not queue:
                publish_catalog(state, current_revision(state.conn))
                last_announce = state.clock()
                await sleep(poll)
                continue
        media_file_id, path = queue.pop(0)
        try:
            await asyncio.to_thread(_analyze_in_thread, db_file, media_file_id, path)
        except Exception:
            log.exception("échec de l'analyse de %s", path)
        if not queue or state.clock() - last_announce >= ANNOUNCE_EVERY:
            publish_catalog(state, current_revision(state.conn))
            last_announce = state.clock()
