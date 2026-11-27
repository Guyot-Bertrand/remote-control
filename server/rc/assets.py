"""Affiches : copie depuis les disques et redimensionnement."""

from __future__ import annotations

import hashlib
import io
import logging
import os
import sqlite3
import stat
import tempfile
from collections.abc import Callable
from pathlib import Path

from PIL import Image

from rc.db import next_revision
from rc.volumes import normalize

log = logging.getLogger(__name__)

SIZES = {"thumb": 300, "tv": 600}
QUALITY = 85
MAX_POSTER_BYTES = 50 * 1024 * 1024
MAX_PIXELS = 40_000_000
ALLOWED_FORMATS = ["JPEG", "PNG", "WEBP"]


def asset_path(assets_dir: Path, hash: str, size: str) -> Path:
    return assets_dir / hash / f"{size}.jpg"


def _write_atomic(dest: Path, write: Callable[[Path], None]) -> None:
    """Écrit dans un fichier temporaire du même dossier puis le renomme : un lecteur ne
    voit jamais une vignette à moitié écrite, et une écriture interrompue ne laisse rien."""
    fd, name = tempfile.mkstemp(dir=dest.parent, prefix=f".{dest.stem}-", suffix=".part")
    os.close(fd)
    tmp = Path(name)
    try:
        write(tmp)
        os.chmod(tmp, 0o644)
        os.replace(tmp, dest)
    except BaseException:
        tmp.unlink(missing_ok=True)
        raise


def process_image(src: Path, assets_dir: Path) -> str:
    fd = os.open(src, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK)
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode):
            raise ValueError(f"pas un fichier régulier : {src}")
        if st.st_size > MAX_POSTER_BYTES:
            raise ValueError(f"fichier trop gros : {src}")
        with os.fdopen(fd, "rb", closefd=False) as f:
            data = f.read(MAX_POSTER_BYTES + 1)
    finally:
        os.close(fd)
    if len(data) > MAX_POSTER_BYTES:
        raise ValueError(f"fichier trop gros : {src}")
    h = hashlib.sha256(data).hexdigest()[:16]
    if all(asset_path(assets_dir, h, s).is_file() for s in ("orig", *SIZES)):
        return h
    with Image.open(io.BytesIO(data), formats=ALLOWED_FORMATS) as im:
        if im.width * im.height > MAX_PIXELS:
            raise ValueError(f"image trop grande : {src}")
        im.load()
        is_jpeg = im.format == "JPEG" and im.mode == "RGB"
        rgb = im.convert("RGB")
    (assets_dir / h).mkdir(parents=True, exist_ok=True)
    if is_jpeg:
        _write_atomic(asset_path(assets_dir, h, "orig"), lambda p: p.write_bytes(data))
    else:
        _write_atomic(asset_path(assets_dir, h, "orig"),
                      lambda p: rgb.save(p, "JPEG", quality=QUALITY))
    for name, width in SIZES.items():
        img = rgb
        if rgb.width > width:
            img = rgb.resize((width, max(1, round(rgb.height * width / rgb.width))),
                             Image.LANCZOS)
        _write_atomic(asset_path(assets_dir, h, name),
                      lambda p, img=img: img.save(p, "JPEG", quality=QUALITY))
    return h


Failure = tuple[str, int, int]  # (chemin relatif, taille, mtime en ns) d'une affiche illisible


def ingest_posters(conn: sqlite3.Connection, assets_dir: Path,
                   indexes: dict[str, dict[str, Path]],
                   failed: set[Failure] | None = None) -> int:
    """Ingère les affiches locales manquantes. `failed` mémorise les échecs d'un tour à
    l'autre : une affiche illisible n'est relue qu'une fois le fichier modifié."""
    if failed is None:
        failed = set()
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
            st = src.stat()
            sig: Failure | None = (row["poster_relpath"], st.st_size, st.st_mtime_ns)
        except OSError:
            sig = None
        if sig in failed:
            continue
        try:
            done.append((row["id"], row["poster_relpath"], process_image(src, assets_dir)))
        except Exception as exc:  # image illisible : on passe à la suivante
            log.warning("affiche illisible, ignorée : %s (%s)", src, exc)
            if sig is not None:
                failed.add(sig)
    if not done:
        return 0
    conn.execute("BEGIN IMMEDIATE")
    try:
        rev = next_revision(conn)
        for work_id, relpath, h in done:
            conn.execute("INSERT OR IGNORE INTO asset (hash, source) VALUES (?, ?)",
                         (h, relpath))
            conn.execute("UPDATE work SET poster_asset = ?, poster_source = 'local', rev = ? "
                         "WHERE id = ?", (h, rev, work_id))
        conn.execute("COMMIT")
    except BaseException:
        conn.execute("ROLLBACK")
        raise
    return len(done)
