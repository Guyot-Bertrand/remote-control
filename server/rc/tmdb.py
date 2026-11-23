"""Secours TMDB : une affiche manquante, jamais un champ texte."""

from __future__ import annotations

import asyncio
import logging
import re
import shutil
import sqlite3
import tempfile
from pathlib import Path

import httpx

from rc.assets import MAX_POSTER_BYTES, process_image
from rc.db import next_revision

log = logging.getLogger(__name__)

API = "https://api.themoviedb.org/3"
IMAGE_BASE = "https://image.tmdb.org/t/p/w780"
IMDB_RE = re.compile(r"tt[0-9]{1,12}")
POSTER_RE = re.compile(r"/[A-Za-z0-9_-]{1,100}\.(?:jpg|jpeg|png)")


class RateLimited(Exception):
    pass


def _auth(api_key: str) -> tuple[dict[str, str], dict[str, str]]:
    if api_key.startswith("eyJ"):  # jeton v4
        return {"Authorization": f"Bearer {api_key}"}, {}
    return {}, {"api_key": api_key}


async def _find_poster_path(client: httpx.AsyncClient, imdb_id: str,
                            api_key: str) -> str | None:
    headers, extra = _auth(api_key)
    resp = await client.get(
        f"{API}/find/{imdb_id}",
        params={"external_source": "imdb_id", "language": "fr-FR", **extra},
        headers=headers)
    if resp.status_code == 429:
        raise RateLimited
    resp.raise_for_status()
    data = resp.json()
    for kind in ("movie_results", "tv_results"):
        for item in data.get(kind) or []:
            path = item.get("poster_path") if isinstance(item, dict) else None
            if isinstance(path, str):
                return path if POSTER_RE.fullmatch(path) else None
    return None


async def _download(client: httpx.AsyncClient, poster_path: str) -> bytes:
    async with client.stream("GET", IMAGE_BASE + poster_path) as resp:
        if resp.status_code == 429:
            raise RateLimited
        resp.raise_for_status()
        if int(resp.headers.get("content-length") or 0) > MAX_POSTER_BYTES:
            raise ValueError("affiche trop grosse")
        buf = bytearray()
        async for chunk in resp.aiter_bytes():
            buf += chunk
            if len(buf) > MAX_POSTER_BYTES:
                raise ValueError("affiche trop grosse")
        return bytes(buf)


def _process(data: bytes, assets_dir: Path, tmp_root: Path) -> str:
    tmp_root.mkdir(parents=True, exist_ok=True)
    tmp = Path(tempfile.mkdtemp(dir=tmp_root, prefix="tmdb-"))  # privé (0700)
    try:
        src = tmp / "poster"
        src.write_bytes(data)
        return process_image(src, assets_dir)
    finally:
        shutil.rmtree(tmp, ignore_errors=True)


async def fetch_missing_posters(conn: sqlite3.Connection, assets_dir: Path,
                                api_key: str, client: httpx.AsyncClient,
                                limit: int = 20) -> int:
    if not api_key:
        return 0
    rows = conn.execute(
        "SELECT id, imdb_id FROM work WHERE imdb_id IS NOT NULL AND poster_asset IS NULL "
        "AND NOT EXISTS (SELECT 1 FROM meta WHERE key = 'tmdb_tried:' || work.id) "
        "ORDER BY id LIMIT ?", (limit,)).fetchall()
    tried: list[int] = []
    done: list[tuple[int, str]] = []
    tmp_root = assets_dir.parent / "tmp"
    try:
        for row in rows:
            imdb_id = (row["imdb_id"] or "").strip()
            if IMDB_RE.fullmatch(imdb_id):
                try:
                    path = await _find_poster_path(client, imdb_id, api_key)
                    if path:
                        data = await _download(client, path)
                        h = await asyncio.to_thread(_process, data, assets_dir, tmp_root)
                        done.append((row["id"], h))
                except RateLimited:
                    log.warning("TMDB : limite atteinte, passage interrompu")
                    break
                except Exception as exc:  # réseau, HTTP, image : une seule tentative
                    log.warning("TMDB : affiche indisponible pour %s (%s)",
                                imdb_id, type(exc).__name__)
            tried.append(row["id"])
    finally:
        _commit(conn, tried, done)
    return len(done)


def _commit(conn: sqlite3.Connection, tried: list[int],
            done: list[tuple[int, str]]) -> None:
    if not tried:
        return
    conn.execute("BEGIN IMMEDIATE")
    try:
        rev = next_revision(conn) if done else None
        for work_id in tried:
            conn.execute("INSERT OR IGNORE INTO meta (key, value) VALUES (?, '1')",
                         (f"tmdb_tried:{work_id}",))
        for work_id, h in done:
            conn.execute("INSERT OR IGNORE INTO asset (hash, source) VALUES (?, 'tmdb')", (h,))
            conn.execute("UPDATE work SET poster_asset = ?, rev = ? WHERE id = ?",
                         (h, rev, work_id))
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise
