"""Secours TMDB : une affiche manquante, jamais un champ texte."""

from __future__ import annotations

import asyncio
import contextlib
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


async def _find(client: httpx.AsyncClient, imdb_id: str,
                api_key: str) -> tuple[str | None, str | None]:
    """(chemin de l'affiche, URL de la fiche TMDB) du résultat retenu."""
    headers, extra = _auth(api_key)
    resp = await client.get(
        f"{API}/find/{imdb_id}",
        params={"external_source": "imdb_id", "language": "fr-FR", **extra},
        headers=headers)
    if resp.status_code == 429:
        raise RateLimited
    resp.raise_for_status()
    data = resp.json()
    chosen: tuple[str, dict] | None = None
    for kind, segment in (("movie_results", "movie"), ("tv_results", "tv")):
        for item in data.get(kind) or []:
            if not isinstance(item, dict):
                continue
            if isinstance(item.get("poster_path"), str):
                chosen = (segment, item)
                break
            if chosen is None:
                chosen = (segment, item)
        if chosen and isinstance(chosen[1].get("poster_path"), str):
            break
    if chosen is None:
        return None, None
    segment, item = chosen
    path = item.get("poster_path")
    path = path if isinstance(path, str) and POSTER_RE.fullmatch(path) else None
    ident = item.get("id")
    url = (f"https://www.themoviedb.org/{segment}/{ident}"
           if isinstance(ident, int) and not isinstance(ident, bool) else None)
    return path, url


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


def _missing(conn: sqlite3.Connection, row: sqlite3.Row,
             indexes: dict[str, dict[str, Path]]) -> bool:
    """Affiche vraiment manquante : aucune référence locale, ou le disque qui devrait
    la porter est présent et ne l'a pas. Un disque débranché n'est pas « manquant »."""
    if row["poster_relpath"] is None:
        return True
    uuids = {r[0] for r in conn.execute(
        "SELECT volume_uuid FROM media_file WHERE volume_uuid IS NOT NULL AND "
        "(work_id = ? OR episode_id IN (SELECT e.id FROM episode e "
        "JOIN season s ON s.id = e.season_id WHERE s.work_id = ?))", (row["id"], row["id"]))}
    return any(u in indexes for u in uuids)


async def fetch_missing_posters(conn: sqlite3.Connection, assets_dir: Path,
                                api_key: str, client: httpx.AsyncClient,
                                limit: int = 20,
                                indexes: dict[str, dict[str, Path]] | None = None) -> int:
    if not api_key:
        return 0
    rows = conn.execute(
        "SELECT id, imdb_id, poster_relpath, poster_source FROM work "
        "WHERE imdb_id IS NOT NULL "
        "AND (poster_asset IS NULL OR poster_source = 'frame') "
        "AND NOT EXISTS (SELECT 1 FROM meta WHERE key = 'tmdb_tried:' || work.id) "
        "ORDER BY id").fetchall()
    rows = [r for r in rows if r["poster_source"] == "frame"
            or _missing(conn, r, indexes or {})][:limit]
    tried: list[int] = []
    results: dict[int, list[str | None]] = {}  # id -> [affiche, url]
    tmp_root = assets_dir.parent / "tmp"
    try:
        for row in rows:
            imdb_id = (row["imdb_id"] or "").strip()
            if not IMDB_RE.fullmatch(imdb_id):
                log.debug("TMDB : imdb_id mal formé pour l'œuvre %d, ignoré", row["id"])
                tried.append(row["id"])
                continue
            # Seule une issue définitive (aucune correspondance, 404, image illisible)
            # marque l'œuvre ; un incident passager laisse un nouvel essai possible.
            try:
                path, url = await _find(client, imdb_id, api_key)
                if url:
                    results[row["id"]] = [None, url]
                if path:
                    data = await _download(client, path)
                    h = await asyncio.to_thread(_process, data, assets_dir, tmp_root)
                    results.setdefault(row["id"], [None, None])[0] = h
            except RateLimited:
                log.warning("TMDB : limite atteinte, passage interrompu")
                break
            except httpx.TransportError as exc:
                log.warning("TMDB : réseau indisponible (%s), passage interrompu",
                            type(exc).__name__)
                break
            except httpx.HTTPStatusError as exc:
                status = exc.response.status_code
                if status in (401, 403):
                    log.warning("TMDB : clé refusée (HTTP %d), passage interrompu", status)
                    break
                log.warning("TMDB : affiche indisponible pour %s (HTTP %d)", imdb_id, status)
                if status != 404:
                    continue  # erreur passagère du service : nouvel essai plus tard
            except Exception as exc:  # réponse ou image illisible : une seule tentative
                log.warning("TMDB : affiche indisponible pour %s (%s)",
                            imdb_id, type(exc).__name__)
            tried.append(row["id"])
    finally:
        _commit(conn, tried, results, "tmdb_tried")
        with contextlib.suppress(OSError):
            tmp_root.rmdir()  # seulement s'il est vide
    return sum(1 for h, _ in results.values() if h)


async def fetch_missing_tmdb_links(conn: sqlite3.Connection, api_key: str,
                                   client: httpx.AsyncClient, limit: int = 20) -> int:
    """Recherche d'identifiant seule : l'URL de la fiche des œuvres sans lien."""
    if not api_key:
        return 0
    rows = conn.execute(
        "SELECT id, imdb_id FROM work WHERE imdb_id IS NOT NULL AND tmdb_url IS NULL "
        "AND NOT EXISTS (SELECT 1 FROM meta WHERE key = 'tmdb_link_tried:' || work.id) "
        "ORDER BY id LIMIT ?", (limit,)).fetchall()
    tried: list[int] = []
    results: dict[int, list[str | None]] = {}
    try:
        for row in rows:
            imdb_id = (row["imdb_id"] or "").strip()
            if not IMDB_RE.fullmatch(imdb_id):
                tried.append(row["id"])
                continue
            try:
                _, url = await _find(client, imdb_id, api_key)
                if url:
                    results[row["id"]] = [None, url]
            except RateLimited:
                log.warning("TMDB : limite atteinte, passage interrompu")
                break
            except httpx.TransportError as exc:
                log.warning("TMDB : réseau indisponible (%s), passage interrompu",
                            type(exc).__name__)
                break
            except httpx.HTTPStatusError as exc:
                status = exc.response.status_code
                if status in (401, 403):
                    log.warning("TMDB : clé refusée (HTTP %d), passage interrompu", status)
                    break
                log.warning("TMDB : lien indisponible pour %s (HTTP %d)", imdb_id, status)
                if status != 404:
                    continue
            except Exception as exc:
                log.warning("TMDB : lien indisponible pour %s (%s)",
                            imdb_id, type(exc).__name__)
            tried.append(row["id"])
    finally:
        _commit(conn, tried, results, "tmdb_link_tried")
    return len(results)


def _commit(conn: sqlite3.Connection, tried: list[int],
            results: dict[int, list[str | None]], marker: str) -> None:
    if not tried and not results:
        return
    conn.execute("BEGIN IMMEDIATE")
    try:
        rev = next_revision(conn) if results else None
        for work_id in tried:
            conn.execute("INSERT OR IGNORE INTO meta (key, value) VALUES (?, '1')",
                         (f"{marker}:{work_id}",))
        for work_id, (h, url) in results.items():
            if h:
                conn.execute("INSERT OR IGNORE INTO asset (hash, source) VALUES (?, 'tmdb')",
                             (h,))
                conn.execute("UPDATE work SET poster_asset = ?, poster_source = 'tmdb', "
                             "rev = ? WHERE id = ?", (h, rev, work_id))
            if url:
                conn.execute("UPDATE work SET tmdb_url = ?, rev = ? WHERE id = ?",
                             (url, rev, work_id))
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise
