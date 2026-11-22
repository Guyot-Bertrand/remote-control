"""Arborescence factice pour le développement et les tests."""

from __future__ import annotations

import hashlib
from pathlib import Path

from PIL import Image

from rc.catalog.model import ParseResult, VideoFile
from rc.catalog.paths import relpath


def _videos(work) -> list[VideoFile]:
    out = list(work.videos)
    for ep in work.episodes:
        out.extend(ep.videos)
    return out


def make_fake_tree(result: ParseResult, root: Path) -> None:
    for work in result.works:
        for v in _videos(work):
            if not (v.playable and v.filename):
                continue
            video = root / relpath(v.rel_dir, v.filename)
            video.parent.mkdir(parents=True, exist_ok=True)
            video.touch()
            for s in v.subtitles:
                (video.parent / s.filename).touch()
        if work.poster:
            poster = root / relpath(work.poster.rel_dir, work.poster.filename)
            poster.parent.mkdir(parents=True, exist_ok=True)
            digest = hashlib.sha1(work.key.encode("utf-8")).digest()
            Image.new("RGB", (2, 3), tuple(digest[:3])).save(poster, "JPEG")
