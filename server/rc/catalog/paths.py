"""Règles de nommage du catalogue (spec §6.3) : seul endroit où elles vivent."""

from __future__ import annotations

import re

QUALITY_ORDER = ["UHD", "2HD", "FHD", "HD", "SD", "BD"]
# Supports physiques non numérisés ; l'ordre donne la priorité.
MEDIUM_LABELS = {
    "BR": "Blu-ray", "DVD": "DVD", "DVR": "DVD (copie)",
    "DVT": "DVD (TNT)", "DVS": "DVD (satellite)",
}

_SEASON = re.compile(r"Saison\s*0*(\d+)")
_EPISODE = re.compile(r"S(\d{2})E(\d{2,3})(?: - (.+?))?\s*\[")


def medium_of(codes: list[str], playable: bool) -> str | None:
    """Libellé du support physique d'une œuvre non lisible, sinon None."""
    if playable:
        return None
    for code, label in MEDIUM_LABELS.items():
        if code in codes:
            return label
    return None


def resolve_dir(name: str, work_key: str | None) -> str:
    if name == "*" and work_key is not None:
        return work_key
    return name


def season_number(dir_name: str, label: str | None) -> int | None:
    for text in (label, dir_name):
        if text and (m := _SEASON.search(text)):
            return int(m.group(1))
    return None


def episode_info(filename: str) -> tuple[int | None, int | None, str | None]:
    m = _EPISODE.search(filename)
    if not m:
        return None, None, None
    return int(m.group(1)), int(m.group(2)), m.group(3)


def base_name(filename: str) -> str:
    return filename.split(" [", 1)[0]


def subtitle_filename(video_filename: str, ref: str) -> str:
    stem = video_filename.rsplit(".", 1)[0] if "." in video_filename else video_filename
    return ref.replace("*", stem)


def subtitle_lang(filename: str) -> str | None:
    parts = filename.split(".")
    if len(parts) < 3:
        return None
    lang = parts[-2]
    return "fr" if lang in ("fre", "fra") else lang


def relpath(rel_dir: tuple[str, ...], filename: str) -> str:
    return "/".join((*rel_dir, filename))
