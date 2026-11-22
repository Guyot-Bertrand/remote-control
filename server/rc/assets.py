"""Affiches : copie depuis les disques et redimensionnement."""

from __future__ import annotations

import hashlib
import logging
import shutil
import sqlite3
from pathlib import Path

from PIL import Image

from rc.db import next_revision
from rc.volumes import normalize

log = logging.getLogger(__name__)

SIZES = {"thumb": 300, "tv": 600}
QUALITY = 85


def asset_path(assets_dir: Path, hash: str, size: str) -> Path:
    return assets_dir / hash / f"{size}.jpg"


def process_image(src: Path, assets_dir: Path) -> str:
    h = hashlib.sha256(src.read_bytes()).hexdigest()[:16]
    out = assets_dir / h
    if all(asset_path(assets_dir, h, s).is_file() for s in ("orig", *SIZES)):
        return h
    out.mkdir(parents=True, exist_ok=True)
    with Image.open(src) as im:
        im.load()
        is_jpeg = im.format == "JPEG" and im.mode == "RGB"
        rgb = im.convert("RGB")
    if is_jpeg:
        shutil.copyfile(src, asset_path(assets_dir, h, "orig"))
    else:
        rgb.save(asset_path(assets_dir, h, "orig"), "JPEG", quality=QUALITY)
    for name, width in SIZES.items():
        img = rgb
        if rgb.width > width:
            img = rgb.resize((width, max(1, round(rgb.height * width / rgb.width))),
                             Image.LANCZOS)
        img.save(asset_path(assets_dir, h, name), "JPEG", quality=QUALITY)
    return h


def ingest_posters(conn: sqlite3.Connection, assets_dir: Path,
                   indexes: dict[str, dict[str, Path]]) -> int:
    rows = conn.execute(
        "SELECT id, poster_relpath FROM work "
        "WHERE poster_relpath IS NOT NULL AND poster_asset IS NULL").fetchall()
    done: list[tuple[int, str, str]] = []
    for row in rows:
        key = normalize(row["poster_relpath"])
        src = next((idx[key] for idx in indexes.values() if key in idx), None)
        if src is None:
            continue
        try:
            done.append((row["id"], row["poster_relpath"], process_image(src, assets_dir)))
        except Exception as exc:  # image illisible : on passe à la suivante
            log.warning("affiche illisible, ignorée : %s (%s)", src, exc)
    if not done:
        return 0
    conn.execute("BEGIN IMMEDIATE")
    try:
        rev = next_revision(conn)
        for work_id, relpath, h in done:
            conn.execute("INSERT OR IGNORE INTO asset (hash, source) VALUES (?, ?)",
                         (h, relpath))
            conn.execute("UPDATE work SET poster_asset = ?, rev = ? WHERE id = ?",
                         (h, rev, work_id))
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    return len(done)
