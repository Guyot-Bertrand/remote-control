"""Lectures du catalogue mises en forme pour l'API (synchronisation et fiches)."""

from __future__ import annotations

import json
import sqlite3
from dataclasses import asdict

from rc.db import current_revision
from rc.player.base import SubSel
from rc.player.session import decode_audio, decode_subtitle
from rc.probe import MediaTracks

_JSON_SUFFIX = "_json"
_BOOLS = {"episodic", "present", "watched"}
_HIDDEN = {"rev", "content_hash", "poster_relpath", "poster_asset"}
DELETED_KINDS = ("work", "season", "episode", "media_file", "collection")
_WORK_SUMMARY = ("id", "type", "key", "title_fr", "title_vo", "year", "episodic", "poster")


def row(r: sqlite3.Row) -> dict:
    """Ligne -> dict : JSON décodé (sans suffixe), vrais booléens, internes masqués."""
    d: dict = {}
    for k in r.keys():
        v = r[k]
        if k == "poster_asset":
            d["poster"] = v
        elif k in _HIDDEN:
            continue
        elif k.endswith(_JSON_SUFFIX):
            d[k[: -len(_JSON_SUFFIX)]] = json.loads(v)
        elif k in _BOOLS:
            d[k] = bool(v)
        else:
            d[k] = v
    return d


def progress(r: sqlite3.Row) -> dict:
    d = row(r)
    d["audio"] = decode_audio(r["audio"])
    d["subtitle"] = decode_subtitle(r["subtitle"])
    return d


def tracks(t: MediaTracks, audio: int | None, subtitle: SubSel) -> dict:
    d = asdict(t)
    d["external"] = [{"path": str(e.path), "lang": e.lang} for e in t.external]
    d["default"] = {"audio": audio, "subtitle": subtitle}
    return d


def sync(conn: sqlite3.Connection, since: int | None) -> dict:
    full = since is None
    rev = -1 if full else since

    def changed(table: str) -> list[sqlite3.Row]:
        return conn.execute(f"SELECT * FROM {table} WHERE rev > ? ORDER BY rowid",
                            (rev,)).fetchall()

    conn.execute("BEGIN")  # instantané cohérent
    try:
        works = changed("work")
        links = conn.execute(
            "SELECT work_id, collection_id, rank FROM work_collection WHERE work_id IN"
            " (SELECT id FROM work WHERE rev > ?) ORDER BY work_id, collection_id",
            (rev,)).fetchall()
        out = {
            "revision": current_revision(conn),
            "full": full,
            "works": [row(w) for w in works],
            "seasons": [row(r) for r in changed("season")],
            "episodes": [row(r) for r in changed("episode")],
            "media_files": [row(r) for r in changed("media_file")],
            "collections": [row(r) for r in changed("collection")],
            "work_collections": [dict(r) for r in links],
            "progress": [progress(r) for r in changed("progress")],
            "volumes": [row(r) for r in conn.execute("SELECT * FROM volume ORDER BY uuid")],
        }
        if not full:
            deleted: dict[str, list[int]] = {k: [] for k in DELETED_KINDS}
            for r in conn.execute("SELECT kind, id FROM tombstone WHERE rev > ?"
                                  " ORDER BY kind, id", (rev,)):
                deleted.setdefault(r["kind"], []).append(r["id"])
            out["deleted"] = deleted
    finally:
        conn.execute("COMMIT")
    return out


def _media_files(conn: sqlite3.Connection, column: str, value: int) -> list[dict]:
    rows = conn.execute(
        f"SELECT mf.*, v.label AS volume_label FROM media_file mf"
        f" LEFT JOIN volume v ON v.uuid = mf.volume_uuid WHERE mf.{column} = ?"
        f" ORDER BY mf.id", (value,)).fetchall()
    return [row(r) for r in rows]


def _progress_of(conn: sqlite3.Connection, kind: str, target_id: int) -> dict | None:
    r = conn.execute("SELECT * FROM progress WHERE target_kind = ? AND target_id = ?",
                     (kind, target_id)).fetchone()
    return None if r is None else progress(r)


def work_detail(conn: sqlite3.Connection, work_id: int) -> dict | None:
    w = conn.execute("SELECT * FROM work WHERE id = ?", (work_id,)).fetchone()
    if w is None:
        return None
    d = row(w)
    d["seasons"] = [row(s) for s in conn.execute(
        "SELECT * FROM season WHERE work_id = ? ORDER BY number", (work_id,))]
    d["media_files"] = _media_files(conn, "work_id", work_id)
    d["collections"] = [row(c) for c in conn.execute(
        "SELECT c.*, wc.rank FROM collection c JOIN work_collection wc"
        " ON wc.collection_id = c.id WHERE wc.work_id = ? ORDER BY c.name", (work_id,))]
    d["progress"] = _progress_of(conn, "work", work_id)
    return d


def season_detail(conn: sqlite3.Connection, season_id: int) -> dict | None:
    s = conn.execute("SELECT * FROM season WHERE id = ?", (season_id,)).fetchone()
    if s is None:
        return None
    d = row(s)
    work = row(conn.execute("SELECT * FROM work WHERE id = ?", (s["work_id"],)).fetchone())
    d["work"] = {k: work[k] for k in _WORK_SUMMARY}
    episodes = []
    for e in conn.execute("SELECT * FROM episode WHERE season_id = ? ORDER BY number",
                          (season_id,)):
        ep = row(e)
        ep["media_files"] = _media_files(conn, "episode_id", e["id"])
        ep["progress"] = _progress_of(conn, "episode", e["id"])
        episodes.append(ep)
    d["episodes"] = episodes
    d["highlight_episode_id"] = _highlight(episodes)
    return d


def _highlight(episodes: list[dict]) -> int | None:
    """Dernier épisode entamé ou vu, sinon le premier non vu."""
    touched = [e for e in episodes if e["progress"] is not None]
    if touched:
        return max(touched, key=lambda e: e["progress"]["updated_at"] or 0)["id"]
    return episodes[0]["id"] if episodes else None
