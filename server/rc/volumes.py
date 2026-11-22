"""Disques externes : détection, index des fichiers, localisation."""

from __future__ import annotations

import json
import logging
import os
import sqlite3
import subprocess
import unicodedata
from dataclasses import dataclass
from pathlib import Path

from rc.db import next_revision

log = logging.getLogger(__name__)

MAX_DEPTH = 6
_SKIP_DIRS = {"lost+found", "System Volume Information"}


class VolumeUnreadable(OSError):
    """La racine du disque monté ne peut pas être listée."""


@dataclass
class Volume:
    uuid: str
    label: str | None
    mount: Path


def normalize(relpath: str) -> str:
    return unicodedata.normalize("NFC", relpath).casefold()


def list_volumes(scan_roots: list[Path], runner=subprocess.run) -> list[Volume]:
    proc = runner(
        ["findmnt", "--json", "--list", "-o", "TARGET,UUID,LABEL"],
        capture_output=True, text=True, check=True,
    )
    data = json.loads(proc.stdout)
    roots = [Path(r) for r in scan_roots]
    volumes: list[Volume] = []
    seen: set[str] = set()
    for fs in data.get("filesystems", []):
        uuid, target = fs.get("uuid"), fs.get("target")
        if not uuid or not target:
            continue
        mount = Path(target)
        if not any(mount.is_relative_to(r) for r in roots):
            continue
        if uuid in seen:
            continue
        seen.add(uuid)
        volumes.append(Volume(uuid, fs.get("label") or mount.name, mount))
    return volumes


def build_index(mount: Path) -> dict[str, Path]:
    index: dict[str, Path] = {}

    def walk(directory: Path, depth: int) -> None:
        try:
            with os.scandir(directory) as it:
                entries = list(it)
        except OSError as exc:
            if directory == mount:
                raise VolumeUnreadable(f"{mount}: {exc}") from exc
            log.warning("répertoire illisible, ignoré : %s (%s)", directory, exc)
            return
        for entry in entries:
            if entry.name.startswith(".") or entry.name in _SKIP_DIRS:
                continue
            if entry.is_symlink():
                continue
            path = Path(entry.path)
            if entry.is_dir(follow_symlinks=False):
                if depth < MAX_DEPTH:
                    walk(path, depth + 1)
            else:
                index[normalize(path.relative_to(mount).as_posix())] = path

    walk(mount, 1)
    return index


def locate_files(conn: sqlite3.Connection, volumes: list[Volume],
                 indexes: dict[str, dict[str, Path]]) -> bool:
    present = [v for v in volumes if v.uuid in indexes]
    # Monté mais sans index (illisible) : on ne touche à rien de ce qui s'y trouve.
    unreadable = {v.uuid for v in volumes if v.uuid not in indexes}
    conn.execute("BEGIN IMMEDIATE")
    try:
        rev: int | None = None

        def bump() -> int:
            nonlocal rev
            if rev is None:
                rev = next_revision(conn)
            return rev

        known = {r["uuid"]: r for r in conn.execute("SELECT * FROM volume")}
        for v in present:
            old = known.get(v.uuid)
            if old is None or (old["label"], old["mount"], old["present"]) != (
                    v.label, str(v.mount), 1):
                conn.execute(
                    "INSERT INTO volume (uuid, label, mount, present, rev) "
                    "VALUES (?, ?, ?, 1, ?) ON CONFLICT(uuid) DO UPDATE SET "
                    "label = excluded.label, mount = excluded.mount, "
                    "present = 1, rev = excluded.rev",
                    (v.uuid, v.label, str(v.mount), bump()),
                )
        present_ids = {v.uuid for v in present}
        for uuid, old in known.items():
            if (uuid not in present_ids and uuid not in unreadable
                    and old["present"]):
                conn.execute("UPDATE volume SET present = 0, rev = ? WHERE uuid = ?",
                             (bump(), uuid))

        rows = conn.execute(
            "SELECT id, relpath, volume_uuid, present FROM media_file").fetchall()
        for row in rows:
            key = normalize(row["relpath"])
            found = next((v.uuid for v in present if key in indexes[v.uuid]), None)
            if found is not None:
                new = (found, 1)
            elif row["volume_uuid"] in unreadable:
                continue
            else:
                new = (row["volume_uuid"], 0)
            if new != (row["volume_uuid"], row["present"]):
                conn.execute(
                    "UPDATE media_file SET volume_uuid = ?, present = ?, rev = ? "
                    "WHERE id = ?", (new[0], new[1], bump(), row["id"]))
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    return rev is not None


def resolve_media_path(conn: sqlite3.Connection, media_file_id: int,
                       indexes: dict[str, dict[str, Path]]) -> Path | None:
    row = conn.execute(
        "SELECT relpath, volume_uuid FROM media_file WHERE id = ?", (media_file_id,)
    ).fetchone()
    if row is None or row["volume_uuid"] not in indexes:
        return None
    return indexes[row["volume_uuid"]].get(normalize(row["relpath"]))


def find_catalog_source(volumes: list[Volume], override: Path | None) -> Path | None:
    if override is not None:
        return override
    candidates = [v.mount / "medias.txt" for v in volumes]
    candidates = [p for p in candidates if p.is_file()]
    return max(candidates, key=lambda p: p.stat().st_mtime, default=None)
