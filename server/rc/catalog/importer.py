"""Import idempotent d'un ParseResult dans la base (une transaction, une révision)."""

from __future__ import annotations

import hashlib
import json
import sqlite3
from dataclasses import asdict, dataclass, field
from pathlib import Path

from rc.catalog.model import ParsedWork, ParseResult, VideoFile
from rc.catalog.paths import medium_of, relpath
from rc.db import current_revision, next_revision


class ImportRefused(Exception):
    pass


@dataclass
class ImportReport:
    added: int
    updated: int
    deleted: int
    revision: int
    removed: list[tuple[str, str]] = field(default_factory=list)  # (type, clé) disparus


def load_aliases(path: Path) -> dict[str, str]:
    aliases: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=>" not in line:
            continue
        old, new = (part.strip() for part in line.split("=>", 1))
        if old and new:
            aliases[old] = new
    return aliases


def _dumps(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True)


def _files(videos: list[VideoFile]) -> list[dict]:
    return [
        {
            "quality": v.quality,
            "size": v.size,
            "relpath": relpath(v.rel_dir, v.filename),
            "subtitles": [{"filename": s.filename, "lang": s.lang} for s in v.subtitles],
        }
        for v in videos
        if v.playable and v.filename
    ]


def _is_playable(w: ParsedWork) -> bool:
    return any(v.playable for v in w.videos) or any(
        v.playable for e in w.episodes for v in e.videos)


def _payload(w: ParsedWork) -> dict:
    """Tout ce qui est importé pour une œuvre ; son hash est le content_hash."""
    poster = relpath(w.poster.rel_dir, w.poster.filename) if w.poster else None
    season_nums = {s.number for s in w.seasons}
    seasons = [{"number": s.number, "label": s.label} for s in w.seasons]
    # Saison référencée par un épisode mais non déclarée : créée sans libellé.
    for n in sorted({e.season for e in w.episodes} - season_nums):
        seasons.append({"number": n, "label": None})
    return {
        "type": w.type, "key": w.key, "title_fr": w.title_fr, "title_vo": w.title_vo,
        "title_alt": w.title_alt, "year": w.year, "duration_min": w.duration_min,
        "episode_count": w.episode_count, "directors": w.directors, "genres": w.genres,
        "cast": w.cast, "synopsis": w.synopsis, "imdb_id": w.imdb_id, "tags": w.tags,
        "episodic": w.episodic, "poster_relpath": poster,
        "medium": medium_of(w.media, _is_playable(w)),
        "files": _files(w.videos),
        "seasons": seasons,
        "episodes": [
            {"season": e.season, "number": e.number, "title": e.title,
             "files": _files(e.videos)}
            for e in w.episodes
        ],
        "collections": [{"name": c.name, "rank": c.rank} for c in w.collections],
        "ratings": [asdict(r) for r in w.ratings],
    }


def _bury(conn: sqlite3.Connection, kind: str, ids, rev: int) -> None:
    conn.executemany(
        "INSERT OR REPLACE INTO tombstone (kind, id, rev) VALUES (?, ?, ?)",
        [(kind, i, rev) for i in ids],
    )


def _unbury(conn: sqlite3.Connection, kind: str, id_: int) -> None:
    conn.execute("DELETE FROM tombstone WHERE kind = ? AND id = ?", (kind, id_))


def _identity(conn, table: str, cols: tuple[str, str], values: tuple) -> int:
    where = f"{cols[0]} = ? AND {cols[1]} = ?"
    row = conn.execute(f"SELECT id FROM {table} WHERE {where}", values).fetchone()
    if row:
        return row[0]
    cur = conn.execute(f"INSERT INTO {table} ({cols[0]}, {cols[1]}) VALUES (?, ?)", values)
    return cur.lastrowid


def _bury_files(conn, where: str, args: tuple, rev: int) -> None:
    ids = [r[0] for r in conn.execute(f"SELECT id FROM media_file WHERE {where}", args)]
    _bury(conn, "media_file", ids, rev)


def _sync_rows(conn, table: str, parent_col: str, parent_id: int, value_col: str,
               wanted: dict[int, object], identity: tuple[str, str], kind: str,
               rev: int, on_remove) -> dict[int, int]:
    """Synchronise saisons ou épisodes d'un parent ; renvoie numéro -> id."""
    rows = conn.execute(
        f"SELECT id, number, {value_col} FROM {table} WHERE {parent_col} = ?", (parent_id,)
    ).fetchall()
    existing = {r["number"]: r for r in rows}
    ids: dict[int, int] = {}
    for number, value in wanted.items():
        row = existing.get(number)
        if row is None:
            id_ = _identity(conn, identity[0], (identity[1], "number"), (parent_id, number))
            conn.execute(
                f"INSERT INTO {table} (id, {parent_col}, number, {value_col}, rev) "
                "VALUES (?, ?, ?, ?, ?)",
                (id_, parent_id, number, value, rev),
            )
            _unbury(conn, kind, id_)
        else:
            id_ = row["id"]
            if row[value_col] != value:
                conn.execute(
                    f"UPDATE {table} SET {value_col} = ?, rev = ? WHERE id = ?",
                    (value, rev, id_),
                )
        ids[number] = id_
    for number, row in existing.items():
        if number not in wanted:
            on_remove(row["id"])
            _bury(conn, kind, [row["id"]], rev)
            conn.execute(f"DELETE FROM {table} WHERE id = ?", (row["id"],))
    return ids


def _remove_episode(conn, episode_id: int, rev: int) -> None:
    _bury_files(conn, "episode_id = ?", (episode_id,), rev)


def _remove_season(conn, season_id: int, rev: int) -> None:
    ep_ids = [r[0] for r in conn.execute(
        "SELECT id FROM episode WHERE season_id = ?", (season_id,))]
    for ep_id in ep_ids:
        _remove_episode(conn, ep_id, rev)
    _bury(conn, "episode", ep_ids, rev)


def _sync_files(conn, work_id: int, wanted: dict[tuple, dict], rev: int) -> None:
    rows = conn.execute(
        "SELECT id, work_id, episode_id, relpath, quality, size, subtitles_json "
        "FROM media_file WHERE work_id = ? OR episode_id IN ("
        " SELECT e.id FROM episode e JOIN season s ON s.id = e.season_id"
        " WHERE s.work_id = ?)",
        (work_id, work_id),
    ).fetchall()
    existing = {(r["work_id"], r["episode_id"], r["relpath"]): r for r in rows}
    for key, f in wanted.items():
        values = (f["quality"], f["size"], _dumps(f["subtitles"]))
        row = existing.get(key)
        if row is None:
            conn.execute(
                "INSERT INTO media_file (work_id, episode_id, relpath, quality, size, "
                "subtitles_json, rev) VALUES (?, ?, ?, ?, ?, ?, ?)",
                (*key, *values, rev),
            )
        elif (row["quality"], row["size"], row["subtitles_json"]) != values:
            conn.execute(
                "UPDATE media_file SET quality = ?, size = ?, subtitles_json = ?, rev = ? "
                "WHERE id = ?",
                (*values, rev, row["id"]),
            )
    gone = [r["id"] for k, r in existing.items() if k not in wanted]
    _bury(conn, "media_file", gone, rev)
    conn.executemany("DELETE FROM media_file WHERE id = ?", [(i,) for i in gone])


def _forget_tmdb(conn, work_id: int) -> None:
    """IMDb changé : ce que TMDB avait trouvé ne vaut plus, la recherche est refaite.
    Une affiche locale ou extraite reste (l'extraite sera remplacée par TMDB)."""
    conn.execute("DELETE FROM meta WHERE key IN (?, ?)",
                 (f"tmdb_tried:{work_id}", f"tmdb_link_tried:{work_id}"))
    conn.execute("UPDATE work SET tmdb_url = NULL, "
                 "poster_asset = CASE WHEN poster_source = 'tmdb' THEN NULL "
                 "ELSE poster_asset END, "
                 "poster_source = CASE WHEN poster_source = 'tmdb' THEN NULL "
                 "ELSE poster_source END WHERE id = ?", (work_id,))


def _write_work(conn, work_id: int, p: dict, content_hash: str,
                collection_ids: dict[str, int], rev: int) -> None:
    old = conn.execute("SELECT imdb_id FROM work WHERE id = ?", (work_id,)).fetchone()
    conn.execute(
        "INSERT INTO work (id, type, key, title_fr, title_vo, title_alt, year, "
        "duration_min, episode_count, directors_json, genres_json, cast_json, synopsis, "
        "imdb_id, tags_json, episodic, poster_relpath, medium, ratings_json, content_hash, "
        "rev) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
        "ON CONFLICT (id) DO UPDATE SET type = excluded.type, key = excluded.key, "
        "title_fr = excluded.title_fr, title_vo = excluded.title_vo, "
        "title_alt = excluded.title_alt, year = excluded.year, "
        "duration_min = excluded.duration_min, episode_count = excluded.episode_count, "
        "directors_json = excluded.directors_json, genres_json = excluded.genres_json, "
        "cast_json = excluded.cast_json, synopsis = excluded.synopsis, "
        "imdb_id = excluded.imdb_id, tags_json = excluded.tags_json, "
        "episodic = excluded.episodic, poster_asset = CASE WHEN "
        "poster_relpath IS excluded.poster_relpath THEN poster_asset ELSE NULL END, "
        "poster_source = CASE WHEN "
        "poster_relpath IS excluded.poster_relpath THEN poster_source ELSE NULL END, "
        "poster_relpath = excluded.poster_relpath, medium = excluded.medium, "
        "ratings_json = excluded.ratings_json, "
        "content_hash = excluded.content_hash, rev = excluded.rev",
        (work_id, p["type"], p["key"], p["title_fr"], p["title_vo"], p["title_alt"],
         p["year"], p["duration_min"], p["episode_count"], _dumps(p["directors"]),
         _dumps(p["genres"]), _dumps(p["cast"]), p["synopsis"], p["imdb_id"],
         _dumps(p["tags"]), int(p["episodic"]), p["poster_relpath"], p["medium"],
         json.dumps(p["ratings"], ensure_ascii=False), content_hash, rev),
    )
    _unbury(conn, "work", work_id)
    if old is not None and old["imdb_id"] != p["imdb_id"]:
        _forget_tmdb(conn, work_id)

    season_ids = _sync_rows(
        conn, "season", "work_id", work_id, "label",
        {s["number"]: s["label"] for s in p["seasons"]},
        ("season_identity", "work_id"), "season", rev,
        lambda sid: _remove_season(conn, sid, rev),
    )
    files = {(work_id, None, f["relpath"]): f for f in p["files"]}
    for season_number, season_id in season_ids.items():
        episodes = [e for e in p["episodes"] if e["season"] == season_number]
        ep_ids = _sync_rows(
            conn, "episode", "season_id", season_id, "title",
            {e["number"]: e["title"] for e in episodes},
            ("episode_identity", "season_id"), "episode", rev,
            lambda eid: _remove_episode(conn, eid, rev),
        )
        for e in episodes:
            for f in e["files"]:
                files.setdefault((None, ep_ids[e["number"]], f["relpath"]), f)
    _sync_files(conn, work_id, files, rev)

    conn.execute("DELETE FROM work_collection WHERE work_id = ?", (work_id,))
    conn.executemany(
        "INSERT OR IGNORE INTO work_collection (work_id, collection_id, rank) "
        "VALUES (?, ?, ?)",
        [(work_id, collection_ids[c["name"]], c["rank"]) for c in p["collections"]],
    )


def _sync_collections(conn, result: ParseResult, rev: int) -> tuple[dict[str, int], bool]:
    groups = {g.name: g for g in result.groups}
    names = {c.name for w in result.works for c in w.collections}
    existing = {r["name"]: r for r in conn.execute("SELECT * FROM collection")}
    ids: dict[str, int] = {}
    changed = False
    for name in sorted(names):
        g = groups.get(name)
        desc, link = (g.description, g.link) if g else (None, None)
        row = existing.get(name)
        if row is None:
            ids[name] = conn.execute(
                "INSERT INTO collection (name, description, link, rev) VALUES (?, ?, ?, ?)",
                (name, desc, link, rev),
            ).lastrowid
            changed = True
        else:
            ids[name] = row["id"]
            if (row["description"], row["link"]) != (desc, link):
                conn.execute(
                    "UPDATE collection SET description = ?, link = ?, rev = ? WHERE id = ?",
                    (desc, link, rev, row["id"]),
                )
                changed = True
    gone = [r["id"] for name, r in existing.items() if name not in names]
    _bury(conn, "collection", gone, rev)
    conn.executemany("DELETE FROM collection WHERE id = ?", [(i,) for i in gone])
    return ids, changed or bool(gone)


def import_catalog(conn: sqlite3.Connection, result: ParseResult,
                   aliases: dict[str, str], force: bool = False) -> ImportReport:
    # Verrou d'écriture pris avant toute lecture : les identités et le contenu lus
    # ne peuvent pas changer d'ici au COMMIT.
    conn.execute("BEGIN IMMEDIATE")
    try:
        report = _import_locked(conn, result, aliases, force)
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    return report


def _import_locked(conn: sqlite3.Connection, result: ParseResult,
                   aliases: dict[str, str], force: bool) -> ImportReport:
    identities = {(r["type"], r["key"]): r["id"]
                  for r in conn.execute("SELECT id, type, key FROM work_identity")}
    sources: dict[str, list[str]] = {}
    for old, new in aliases.items():
        sources.setdefault(new, []).append(old)

    resolved: list[tuple[int | None, ParsedWork]] = []
    seen: set = set()
    for w in result.works:
        # Une clé renommée retrouve l'identité de son ancienne clé.
        candidates = [*sources.get(w.key, []), w.key]
        id_ = next((identities[(w.type, k)] for k in candidates
                    if (w.type, k) in identities), None)
        marker = id_ if id_ is not None else (w.type, w.key)
        if marker in seen:
            raise ImportRefused(f"clé en double : {w.type} ; {w.key}")
        seen.add(marker)
        resolved.append((id_, w))

    existing = {r["id"]: (r["content_hash"], r["type"], r["key"])
                for r in conn.execute("SELECT id, content_hash, type, key FROM work")}
    kept = {id_ for id_, _ in resolved if id_ is not None}
    removed = [i for i in existing if i not in kept]
    if not result.works and existing:
        raise ImportRefused("catalogue vide")
    if existing and len(removed) * 2 > len(existing) and not force:
        raise ImportRefused("suppression massive")

    revision = current_revision(conn)
    rev = revision + 1
    added = updated = 0
    collection_ids, changed = _sync_collections(conn, result, rev)
    for id_, w in resolved:
        p = _payload(w)
        content_hash = hashlib.sha256(_dumps(p).encode("utf-8")).hexdigest()
        if id_ is None:
            id_ = _identity(conn, "work_identity", ("type", "key"), (w.type, w.key))
        if id_ not in existing:
            added += 1
        elif existing[id_][0] != content_hash:
            updated += 1
        else:
            continue
        _write_work(conn, id_, p, content_hash, collection_ids, rev)
    for work_id in removed:
        for (sid,) in conn.execute(
                "SELECT id FROM season WHERE work_id = ?", (work_id,)).fetchall():
            _remove_season(conn, sid, rev)
            _bury(conn, "season", [sid], rev)
        _bury_files(conn, "work_id = ?", (work_id,), rev)
        _bury(conn, "work", [work_id], rev)
        conn.execute("DELETE FROM work WHERE id = ?", (work_id,))
    if changed or added or updated or removed:
        revision = next_revision(conn)
    return ImportReport(added, updated, len(removed), revision,
                        [existing[i][1:] for i in removed])
