import json
import os
import subprocess
from pathlib import Path

import pytest

from rc.db import open_db
from rc.probe import (
    ExternalSub,
    MediaTracks,
    Track,
    cached_tracks,
    default_selection,
    find_external_subs,
    probe_tracks,
)

SAMPLE = (Path(__file__).parent / "fixtures" / "ffprobe_sample.json").read_text()


def fake_runner(stdout=SAMPLE):
    calls = []

    def run(cmd, **kwargs):
        calls.append(cmd)
        return subprocess.CompletedProcess(cmd, 0, stdout=stdout, stderr="")

    run.calls = calls
    return run


def test_parse_ffprobe_json():
    r = fake_runner()
    t = probe_tracks(Path("/x/film.mp4"), runner=r)
    assert r.calls[0][:6] == ["ffprobe", "-v", "error", "-show_streams", "-of", "json"]
    assert [a.lang for a in t.audio] == ["en", "fr"]
    assert t.audio[1].lang == "fr"
    assert t.audio[1].index == 2
    assert t.audio[1].title == "Français"
    assert t.subtitles[0].index == 1 and t.subtitles[0].lang == "fr"
    assert t.video_codec == "h264"
    assert t.height == 1080


def test_external_subs_declared_and_discovered(tmp_path):
    (tmp_path / "X.mp4").touch()
    (tmp_path / "X.1.fr.srt").touch()
    (tmp_path / "X.en.vtt").touch()
    (tmp_path / "Y.srt").touch()
    subs = find_external_subs(tmp_path / "X.mp4", [str(tmp_path / "X.1.fr.srt")])
    assert len(subs) == 2
    assert sorted(s.lang for s in subs) == ["en", "fr"]


def tracks(audio_langs, sub_langs=(), external=()):
    return MediaTracks(
        audio=[Track(i + 1, lg, None, "aac", False) for i, lg in enumerate(audio_langs)],
        subtitles=[Track(i + 1, lg, None, "subrip", False) for i, lg in enumerate(sub_langs)],
        external=[ExternalSub(Path(p), lg) for p, lg in external],
        video_codec="h264",
        height=1080,
    )


def test_default_fr_audio_no_subs():
    assert default_selection(tracks(["en", "fr"], ["fr"])) == (2, {"kind": "none"})


def test_default_vo_with_fr_external_sub():
    t = tracks(["en"], ["fr"], [("/a/x.fr.srt", "fr")])
    assert default_selection(t) == (1, {"kind": "external", "path": "/a/x.fr.srt"})
    t2 = tracks(["en"], ["en", "fr"])
    assert default_selection(t2) == (1, {"kind": "embedded", "index": 2})


def test_default_vo_no_fr_sub_none():
    assert default_selection(tracks(["en"], ["en"])) == (1, {"kind": "none"})
    assert default_selection(tracks([])) == (None, {"kind": "none"})


def test_cache_invalidated_on_mtime(tmp_path):
    conn = open_db(tmp_path / "rc.db")
    conn.execute(
        "INSERT INTO media_file (id, quality, relpath, subtitles_json, rev)"
        " VALUES (1, 'HD', 'f.mp4', '[]', 0)"
    )
    f = tmp_path / "f.mp4"
    f.write_bytes(b"abc")
    r = fake_runner()
    a = cached_tracks(conn, 1, f, runner=r)
    b = cached_tracks(conn, 1, f, runner=r)
    assert len(r.calls) == 1
    assert a == b
    os.utime(f, (1_000_000, 1_000_000))
    cached_tracks(conn, 1, f, runner=r)
    assert len(r.calls) == 2
    conn.close()
