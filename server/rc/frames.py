"""Image extraite de la vidéo pour les œuvres sans aucune affiche (ni locale, ni TMDB)."""

from __future__ import annotations

import logging
import shutil
import sqlite3
import subprocess
import tempfile
from collections.abc import Iterator
from pathlib import Path

from rc.assets import process_image
from rc.catalog.paths import QUALITY_ORDER
from rc.db import next_revision
from rc.volumes import resolve_media_path

log = logging.getLogger("rc.frames")

DEFAULT_AT = 300.0  # secondes, quand la durée est inconnue
FFMPEG_TIMEOUT = 60
_runner = subprocess.run  # remplacé dans les tests
# Fichiers venus des disques, donc non sûrs : ffmpeg ne lit que des fichiers locaux
# (`file:` seulement, pas de réseau) et seulement des conteneurs vidéo. Les formats
# « liste de lecture » (hls, concat…), qui iraient chercher d'autres fichiers, sont refusés.
FORMAT_WHITELIST = ("matroska,webm,mov,mp4,m4a,3gp,3g2,mj2,avi,mpegts,mpeg,flv,asf,ogg,"
                    "rm,dv,nut")


class FfmpegMissing(Exception):
    """ffmpeg introuvable : inutile d'essayer les autres œuvres."""


def frame_time(duration_s: float | None) -> float:
    """10 % de la durée analysée ; 5 minutes sans durée."""
    if duration_s is not None and duration_s > 0:
        return 0.10 * duration_s
    return DEFAULT_AT


def _rank(quality: str) -> int:
    return QUALITY_ORDER.index(quality) if quality in QUALITY_ORDER else len(QUALITY_ORDER)


def _best(rows: list[sqlite3.Row]) -> sqlite3.Row | None:
    return min(rows, key=lambda r: (_rank(r["quality"]), r["id"]), default=None)


def _chosen_file(conn: sqlite3.Connection, work_id: int, episodic: bool
                 ) -> sqlite3.Row | None:
    """Film : meilleure version présente. Œuvre épisodique : premier épisode (saison puis
    numéro) ayant un fichier présent, sa meilleure version."""
    cols = "mf.id, mf.quality, mf.width, mf.duration_s"
    if not episodic:
        return _best(conn.execute(
            f"SELECT {cols} FROM media_file mf WHERE mf.work_id = ? AND mf.present = 1",
            (work_id,)).fetchall())
    rows = conn.execute(
        f"SELECT {cols}, e.id AS episode_id FROM media_file mf "
        "JOIN episode e ON e.id = mf.episode_id JOIN season s ON s.id = e.season_id "
        "WHERE s.work_id = ? AND mf.present = 1 ORDER BY s.number, e.number, e.id",
        (work_id,)).fetchall()
    if not rows:
        return None
    first = rows[0]["episode_id"]
    return _best([r for r in rows if r["episode_id"] == first])


def iter_candidates(conn: sqlite3.Connection, tmdb_enabled: bool) -> Iterator[int]:
    """Œuvres jouables sans affiche ni affiche locale déclarée (même sur un disque
    débranché), dont TMDB ne peut plus rien donner, et dont le fichier choisi est analysé.
    Paresseux : le fichier choisi n'est cherché qu'au fur et à mesure."""
    rows = conn.execute(
        "SELECT id, episodic FROM work WHERE poster_asset IS NULL "
        "AND poster_relpath IS NULL "
        "AND (? = 0 OR imdb_id IS NULL "
        "OR EXISTS (SELECT 1 FROM meta WHERE key = 'tmdb_tried:' || work.id)) "
        "ORDER BY id", (int(tmdb_enabled),)).fetchall()
    for r in rows:
        f = _chosen_file(conn, r["id"], bool(r["episodic"]))
        if f is not None and (f["width"] is not None or f["duration_s"] is not None):
            yield r["id"]


def frame_candidates(conn: sqlite3.Connection, tmdb_enabled: bool) -> list[int]:
    return list(iter_candidates(conn, tmdb_enabled))


def frame_source(conn: sqlite3.Connection, work_id: int,
                 indexes: dict[str, dict[str, Path]]) -> tuple[Path, float | None] | None:
    """Chemin du fichier choisi et sa durée ; `None` si rien n'est résolu sur un disque."""
    w = conn.execute("SELECT episodic FROM work WHERE id = ?", (work_id,)).fetchone()
    if w is None:
        return None
    f = _chosen_file(conn, work_id, bool(w["episodic"]))
    if f is None:
        return None
    path = resolve_media_path(conn, f["id"], indexes)
    if path is None:
        return None
    return path, f["duration_s"]


def extract_frame(path: Path, at: float, dest: Path, runner=subprocess.run) -> None:
    """Une image à `at` secondes dans `dest` (JPEG), en basse priorité. Lève en cas
    d'échec de ffmpeg ; `FfmpegMissing` s'il est introuvable. L'absence est vérifiée
    avant le lancement : derrière `nice`, un ffmpeg absent ne donnerait qu'un code 127."""
    ff = shutil.which("ffmpeg")
    if ff is None:
        raise FfmpegMissing("ffmpeg introuvable dans le PATH")
    nice = ["nice", "-n", "10"] if shutil.which("nice") else []
    cmd = [*nice, ff, "-v", "error", "-y", "-threads", "1",
           "-protocol_whitelist", "file", "-format_whitelist", FORMAT_WHITELIST,
           "-ss", str(at), "-i", f"file:{path.resolve()}", "-frames:v", "1", str(dest)]
    try:
        runner(cmd, check=True, capture_output=True, timeout=FFMPEG_TIMEOUT)
    except FileNotFoundError as exc:  # ffmpeg retiré entre-temps, lancé sans nice
        raise FfmpegMissing(str(exc)) from exc


def _detail(exc: Exception) -> str:
    """Message d'erreur, avec la fin de la sortie d'erreur de ffmpeg (200 caractères)."""
    err = getattr(exc, "stderr", None)
    if isinstance(err, bytes):
        err = err.decode("utf-8", "replace")
    err = (err or "").strip()
    return f"{exc} — {err[-200:]}" if err else str(exc)


def _has_image(p: Path) -> bool:
    return p.is_file() and p.stat().st_size > 0


def _grab(path: Path, duration: float | None, assets_dir: Path) -> str:
    with tempfile.TemporaryDirectory(prefix="rc-frame-") as tmp:
        dest = Path(tmp) / "frame.jpg"
        try:
            extract_frame(path, frame_time(duration), dest, runner=_runner)
        except subprocess.CalledProcessError:
            if duration is not None and duration > 0:
                raise
        if not _has_image(dest) and not (duration is not None and duration > 0):
            # durée inconnue : fichier peut-être plus court que 5 minutes
            extract_frame(path, 0.0, dest, runner=_runner)
        if not _has_image(dest):
            raise ValueError("ffmpeg n'a produit aucune image")
        return process_image(dest, assets_dir)


def extract_one(conn: sqlite3.Connection, assets_dir: Path,
                indexes: dict[str, dict[str, Path]], failed: set[int],
                tmdb_enabled: bool) -> bool:
    """Extrait l'image de la première œuvre candidate non écartée (bloquant). Vrai si une
    affiche a été écrite ; un échec écarte l'œuvre (`failed`) et rend faux. ffmpeg
    introuvable : `FfmpegMissing` est propagée, aucune œuvre n'est écartée."""
    for work_id in iter_candidates(conn, tmdb_enabled):
        if work_id in failed:
            continue
        src = frame_source(conn, work_id, indexes)
        if src is None:
            continue  # fichier pas encore localisé : on passe à la suivante
        path, duration = src
        try:
            h = _grab(path, duration, assets_dir)
        except FfmpegMissing:
            raise
        except Exception as exc:  # code d'erreur, délai, aucune image, image illisible
            log.warning("image extraite impossible pour %s : %s", path, _detail(exc))
            failed.add(work_id)
            return False
        conn.execute("BEGIN IMMEDIATE")
        try:
            still = conn.execute(
                "SELECT 1 FROM work WHERE id = ? AND poster_asset IS NULL "
                "AND poster_relpath IS NULL", (work_id,)).fetchone()
            if still:  # une affiche a pu arriver entre-temps (locale, TMDB)
                rev = next_revision(conn)
                conn.execute("INSERT OR IGNORE INTO asset (hash, source) VALUES (?, 'frame')",
                             (h,))
                conn.execute("UPDATE work SET poster_asset = ?, poster_source = 'frame', "
                             "rev = ? WHERE id = ?", (h, rev, work_id))
            elif conn.execute("SELECT 1 FROM asset WHERE hash = ?", (h,)).fetchone() is None:
                # Image devenue inutile : ses fichiers ne restent pas orphelins. Fait sous
                # le verrou d'écriture, aucune ligne `asset` ne peut arriver entre-temps.
                # Reste un cas théorique : une affiche locale aux octets identiques, traitée
                # au même instant, trouverait ses fichiers puis les perdrait ici.
                shutil.rmtree(assets_dir / h, ignore_errors=True)
            conn.execute("COMMIT")
        except BaseException:
            conn.execute("ROLLBACK")
            raise
        return bool(still)
    return False
