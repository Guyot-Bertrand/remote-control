"""Lectures du catalogue mises en forme pour l'API (synchronisation et fiches)."""

from __future__ import annotations

import json
import random
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
            d[k[: -len(_JSON_SUFFIX)]] = None if v is None else json.loads(v)
        elif k in _BOOLS:
            d[k] = bool(v)
        else:
            d[k] = v
    if d.get("width") == 0:  # marqueur « analysé » sans résolution : rien à afficher
        d["width"] = d["height"] = None
    return d


def season(r: sqlite3.Row) -> dict:
    """Saison ; libellé jamais nul (« Épisodes » pour la saison 0, sinon « Saison n »)."""
    d = row(r)
    if d["label"] is None:
        d["label"] = "Épisodes" if d["number"] == 0 else f"Saison {d['number']}"
    return d


def progress(r: sqlite3.Row) -> dict:
    """Progression ; position et date jamais nulles (0.0 à défaut)."""
    d = row(r)
    d["position"] = d["position"] if d["position"] is not None else 0.0
    d["updated_at"] = d["updated_at"] if d["updated_at"] is not None else 0.0
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
            "seasons": [season(r) for r in changed("season")],
            "episodes": [row(r) for r in changed("episode")],
            "media_files": [row(r) for r in changed("media_file")],
            "collections": [row(r) for r in changed("collection")],
            "work_collections": [dict(r) for r in links],
            "progress": [progress(r) for r in changed("progress")],
            "volumes": [row(r) for r in conn.execute("SELECT * FROM volume ORDER BY uuid")],
        }
        if full:
            out["total_works"] = len(works)
        else:
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
    d["seasons"] = [season(s) for s in conn.execute(
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
    d = season(s)
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
        return max(touched, key=lambda e: e["progress"]["updated_at"])["id"]
    return episodes[0]["id"] if episodes else None


RECENT_MAX = 8


def _ratio(position: float | None, duration: float | None, watched: bool) -> float | None:
    if not watched and position and duration:
        return min(position / duration, 1.0)
    return None


def _remaining_min(position: float | None, duration: float | None,
                   watched: bool) -> int | None:
    if watched or not duration:
        return None
    return round((duration - (position or 0.0)) / 60)


def _tv_summary(w: sqlite3.Row, position: float | None, duration: float | None,
                watched: bool) -> dict:
    """Résumé d'œuvre pour l'écran télé, avec la progression de sa dernière lecture."""
    ratio = _ratio(position, duration, watched)
    return {"id": w["id"], "title_fr": w["title_fr"], "year": w["year"],
            "poster": w["poster_asset"], "progress_ratio": ratio, "watched": watched}


def tv_home(conn: sqlite3.Connection, rng: random.Random | None = None) -> dict:
    """Veille de la télé : dernières œuvres vues ou entamées, et une affiche à montrer."""
    rows = conn.execute(
        "SELECT w.*, p.position AS p_position, p.duration AS p_duration,"
        " p.watched AS p_watched FROM progress p"
        " LEFT JOIN episode e ON p.target_kind = 'episode' AND e.id = p.target_id"
        " LEFT JOIN season s ON s.id = e.season_id"
        " JOIN work w ON w.id = CASE p.target_kind WHEN 'work' THEN p.target_id"
        " ELSE s.work_id END"
        " ORDER BY p.updated_at IS NULL, p.updated_at DESC").fetchall()
    recent: list[dict] = []
    seen: set[int] = set()
    for r in rows:  # une œuvre à épisodes n'apparaît qu'une fois (son épisode le plus récent)
        if r["id"] in seen:
            continue
        seen.add(r["id"])
        recent.append(_tv_summary(r, r["p_position"], r["p_duration"], bool(r["p_watched"])))
        if len(recent) == RECENT_MAX:
            break
    return {"recent": recent, "showcase": _showcase(conn, rng or random)}


def _showcase(conn: sqlite3.Connection, rng: random.Random) -> dict | None:
    """Une œuvre avec affiche, tirée au hasard, de préférence membre d'une collection."""
    ids = [r[0] for r in conn.execute(
        "SELECT id FROM work WHERE poster_asset IS NOT NULL"
        " AND id IN (SELECT work_id FROM work_collection)")]
    if not ids:
        ids = [r[0] for r in conn.execute("SELECT id FROM work WHERE poster_asset IS NOT NULL")]
    if not ids:
        return None
    w = conn.execute("SELECT * FROM work WHERE id = ?", (rng.choice(ids),)).fetchone()
    p = conn.execute("SELECT * FROM progress WHERE target_kind = 'work' AND target_id = ?",
                     (w["id"],)).fetchone()
    d = _tv_summary(w, p and p["position"], p and p["duration"], bool(p and p["watched"]))
    c = conn.execute("SELECT c.name FROM collection c JOIN work_collection wc"
                     " ON wc.collection_id = c.id WHERE wc.work_id = ? ORDER BY c.name",
                     (w["id"],)).fetchone()
    d.update(title_vo=w["title_vo"], directors=json.loads(w["directors_json"]),
             duration_min=w["duration_min"], collection_name=c and c["name"])
    return d


def _marks(n: int) -> str:
    return ",".join("?" * n)


def _availability(conn: sqlite3.Connection, work_ids: list[int]) -> dict[int, tuple[bool, str | None]]:
    """Par œuvre : (jouable, libellé du premier disque absent qui porte ses fichiers)."""
    out: dict[int, tuple[bool, str | None]] = {w: (False, None) for w in work_ids}
    if not work_ids:
        return out
    rows = conn.execute(
        "SELECT COALESCE(mf.work_id, s.work_id) AS wid, mf.present, v.label, v.present AS vp"
        " FROM media_file mf LEFT JOIN episode e ON e.id = mf.episode_id"
        " LEFT JOIN season s ON s.id = e.season_id"
        " LEFT JOIN volume v ON v.uuid = mf.volume_uuid"
        f" WHERE COALESCE(mf.work_id, s.work_id) IN ({_marks(len(work_ids))})"
        " ORDER BY mf.id", work_ids).fetchall()
    playable = {r["wid"] for r in rows if r["present"]}
    absent: dict[int, str | None] = {}
    for r in rows:
        if r["wid"] not in playable and r["vp"] == 0 and r["wid"] not in absent:
            absent[r["wid"]] = r["label"]
    for w in work_ids:
        out[w] = (w in playable, None if w in playable else absent.get(w))
    return out


def _latest_progress(conn: sqlite3.Connection, work_ids: list[int]) -> dict[int, sqlite3.Row]:
    """Par œuvre : la progression la plus récente (la sienne ou celle d'un épisode)."""
    rows = conn.execute(
        "SELECT p.*, CASE p.target_kind WHEN 'work' THEN p.target_id ELSE s.work_id END"
        " AS wid FROM progress p LEFT JOIN episode e ON p.target_kind = 'episode'"
        " AND e.id = p.target_id LEFT JOIN season s ON s.id = e.season_id"
        f" WHERE wid IN ({_marks(len(work_ids))})"
        " ORDER BY p.updated_at IS NOT NULL, p.updated_at", work_ids).fetchall()
    return {r["wid"]: r for r in rows}  # le plus récent écrase


def _enrich_works(conn: sqlite3.Connection, ids: list[int],
                  collection_id: int | None) -> dict[int, dict]:
    works = conn.execute(f"SELECT * FROM work WHERE id IN ({_marks(len(ids))})", ids).fetchall()
    wids = [w["id"] for w in works]
    avail = _availability(conn, wids)
    prog = _latest_progress(conn, wids) if wids else {}
    ranks: dict[int, str | None] = {}
    if collection_id is not None and wids:
        ranks = {r["work_id"]: r["rank"] for r in conn.execute(
            "SELECT work_id, rank FROM work_collection WHERE collection_id = ?"
            f" AND work_id IN ({_marks(len(wids))})", [collection_id, *wids])}
    out: dict[int, dict] = {}
    for w in works:
        p = prog.get(w["id"])
        pos, dur, watched = (p["position"], p["duration"], bool(p["watched"])) if p \
            else (None, None, False)
        playable, absent = avail[w["id"]]
        out[w["id"]] = {
            "kind": "work", "id": w["id"], "type": w["type"], "title_fr": w["title_fr"],
            "title_vo": w["title_vo"], "title_alt": w["title_alt"],
            "directors": json.loads(w["directors_json"]), "year": w["year"],
            "poster": w["poster_asset"],
            "playable": playable, "watched": watched,
            "progress_ratio": _ratio(pos, dur, watched),
            "remaining_min": _remaining_min(pos, dur, watched),
            "absent_volume": absent, "medium": w["medium"], "rank": ranks.get(w["id"])}
    return out


def _enrich_episodes(conn: sqlite3.Connection, ids: list[int]) -> dict[int, dict]:
    rows = conn.execute(
        "SELECT e.id, e.number, e.title, s.id AS season_id, s.number AS season_number,"
        " w.id AS work_id, w.title_fr, w.poster_asset, p.position, p.duration, p.watched"
        " FROM episode e JOIN season s ON s.id = e.season_id JOIN work w ON w.id = s.work_id"
        " LEFT JOIN progress p ON p.target_kind = 'episode' AND p.target_id = e.id"
        f" WHERE e.id IN ({_marks(len(ids))})", ids).fetchall()
    return {r["id"]: {
        "kind": "episode", "id": r["id"], "work_id": r["work_id"],
        "season_id": r["season_id"], "work_title": r["title_fr"],
        "season_number": r["season_number"], "number": r["number"], "title": r["title"],
        "poster": r["poster_asset"], "watched": bool(r["watched"]),
        "progress_ratio": _ratio(r["position"], r["duration"], bool(r["watched"])),
        "remaining_min": _remaining_min(r["position"], r["duration"], bool(r["watched"]))}
        for r in rows}


def _enrich_collections(conn: sqlite3.Connection, ids: list[int]) -> dict[int, dict]:
    colls = conn.execute(
        f"SELECT id, name FROM collection WHERE id IN ({_marks(len(ids))})", ids).fetchall()
    members: dict[int, list[sqlite3.Row]] = {c["id"]: [] for c in colls}
    rows = conn.execute(
        "SELECT wc.collection_id, w.id, w.poster_asset FROM work_collection wc"
        " JOIN work w ON w.id = wc.work_id"
        f" WHERE wc.collection_id IN ({_marks(len(ids))})"
        " ORDER BY wc.rank IS NULL, CAST(wc.rank AS INTEGER), w.id", ids)
    for r in rows:
        members[r["collection_id"]].append(r)
    avail = _availability(conn, sorted({m["id"] for ms in members.values() for m in ms}))
    return {c["id"]: {
        "kind": "collection", "id": c["id"], "name": c["name"],
        "count": len(members[c["id"]]),
        "playable_count": sum(avail[m["id"]][0] for m in members[c["id"]]),
        "posters": [m["poster_asset"] for m in members[c["id"]]
                    if m["poster_asset"] is not None][:3]} for c in colls}


def enrich(conn: sqlite3.Connection, refs: list[dict],
           collection_id: int | None) -> list[dict]:
    """Éléments de vue enrichis (arbitrage 16), dans l'ordre reçu, inconnus omis.

    Au plus une requête principale par sorte, avec IN (…)."""
    builders = {"work": lambda ids: _enrich_works(conn, ids, collection_id),
                "episode": lambda ids: _enrich_episodes(conn, ids),
                "collection": lambda ids: _enrich_collections(conn, ids)}
    found: dict[str, dict[int, dict]] = {}
    for kind, build in builders.items():
        ids = sorted({r["id"] for r in refs if r["kind"] == kind})
        found[kind] = build(ids) if ids else {}
    return [found[r["kind"]][r["id"]] for r in refs
            if r["kind"] in found and r["id"] in found[r["kind"]]]
