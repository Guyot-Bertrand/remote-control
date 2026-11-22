"""Pistes audio et sous-titres d'un fichier vidéo (ffprobe + sous-titres externes)."""

from __future__ import annotations

import json
import os
import sqlite3
import subprocess
from dataclasses import asdict, dataclass
from pathlib import Path

from rc.player.base import SubSel

SUB_EXTENSIONS = (".srt", ".vtt", ".ass", ".ssa", ".sub")
_LANGS = {
    "fre": "fr", "fra": "fr", "french": "fr", "fr": "fr",
    "eng": "en", "english": "en", "en": "en",
}


@dataclass
class Track:
    index: int  # 1-based par type, dans l'ordre des flux (= aid/sid de mpv)
    lang: str | None
    title: str | None
    codec: str | None
    default: bool


@dataclass
class ExternalSub:
    path: Path
    lang: str | None


@dataclass
class MediaTracks:
    audio: list[Track]
    subtitles: list[Track]
    external: list[ExternalSub]
    video_codec: str | None
    height: int | None


def normalize_lang(value: str | None) -> str | None:
    v = (value or "").strip().lower()
    if not v or v == "und":
        return None
    return _LANGS.get(v, v)


def probe_tracks(path: Path, runner=subprocess.run) -> MediaTracks:
    proc = runner(
        ["ffprobe", "-v", "error", "-show_streams", "-of", "json", str(path)],
        capture_output=True,
        text=True,
        check=True,
    )
    data = json.loads(proc.stdout)
    audio: list[Track] = []
    subs: list[Track] = []
    video_codec: str | None = None
    height: int | None = None
    for s in data.get("streams", []):
        kind = s.get("codec_type")
        tags = s.get("tags") or {}
        if kind == "video":
            if video_codec is None:
                video_codec = s.get("codec_name")
                height = s.get("height")
            continue
        target = audio if kind == "audio" else subs if kind == "subtitle" else None
        if target is None:
            continue
        target.append(
            Track(
                index=len(target) + 1,
                lang=normalize_lang(tags.get("language")),
                title=tags.get("title"),
                codec=s.get("codec_name"),
                default=bool((s.get("disposition") or {}).get("default")),
            )
        )
    return MediaTracks(audio, subs, [], video_codec, height)


def _sub_lang(video: Path, sub: Path) -> str | None:
    rest = sub.name[len(video.stem):] if sub.name.startswith(video.stem) else sub.name.split(".", 1)[-1]
    parts = [p for p in rest.split(".") if p]
    # parts : [..., langue?, extension]
    return normalize_lang(parts[-2]) if len(parts) >= 2 else None


def find_external_subs(video: Path, declared: list[str]) -> list[ExternalSub]:
    paths: list[Path] = [Path(d) for d in declared]
    if video.parent.is_dir():
        for p in sorted(video.parent.iterdir()):
            if p.name.startswith(video.stem) and p.suffix.lower() in SUB_EXTENSIONS:
                paths.append(p)
    seen: set[Path] = set()
    result: list[ExternalSub] = []
    for p in paths:
        if p in seen:
            continue
        seen.add(p)
        result.append(ExternalSub(p, _sub_lang(video, p)))
    return result


def _to_json(t: MediaTracks) -> str:
    d = asdict(t)
    d["external"] = [{"path": str(e.path), "lang": e.lang} for e in t.external]
    return json.dumps(d)


def _from_json(text: str) -> MediaTracks:
    d = json.loads(text)
    return MediaTracks(
        audio=[Track(**a) for a in d["audio"]],
        subtitles=[Track(**s) for s in d["subtitles"]],
        external=[ExternalSub(Path(e["path"]), e["lang"]) for e in d["external"]],
        video_codec=d["video_codec"],
        height=d["height"],
    )


def cached_tracks(
    conn: sqlite3.Connection, media_file_id: int, path: Path, runner=subprocess.run
) -> MediaTracks:
    st = os.stat(path)
    row = conn.execute(
        "SELECT mtime, size, tracks_json FROM probe_cache WHERE media_file_id = ?",
        (media_file_id,),
    ).fetchone()
    if row is not None and row[0] == st.st_mtime and row[1] == st.st_size:
        return _from_json(row[2])
    tracks = probe_tracks(path, runner=runner)
    conn.execute(
        "INSERT INTO probe_cache (media_file_id, mtime, size, tracks_json)"
        " VALUES (?, ?, ?, ?) ON CONFLICT(media_file_id) DO UPDATE SET"
        " mtime = excluded.mtime, size = excluded.size, tracks_json = excluded.tracks_json",
        (media_file_id, st.st_mtime, st.st_size, _to_json(tracks)),
    )
    return tracks


def default_selection(t: MediaTracks) -> tuple[int | None, SubSel]:
    if not t.audio:
        return None, {"kind": "none"}
    chosen = next((a for a in t.audio if a.lang == "fr"), t.audio[0])
    if chosen.lang == "fr":
        return chosen.index, {"kind": "none"}
    ext = next((e for e in t.external if e.lang == "fr"), None)
    if ext is not None:
        return chosen.index, {"kind": "external", "path": str(ext.path)}
    emb = next((s for s in t.subtitles if s.lang == "fr"), None)
    if emb is not None:
        return chosen.index, {"kind": "embedded", "index": emb.index}
    return chosen.index, {"kind": "none"}
