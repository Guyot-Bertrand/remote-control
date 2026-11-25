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
    media_summary,
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
    cmd = r.calls[0]
    i = cmd.index("ffprobe")
    assert cmd[i : i + 7] == ["ffprobe", "-v", "error", "-show_streams", "-show_format", "-of", "json"]
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


V2 = json.dumps(
    {
        "streams": [{"codec_type": "video", "codec_name": "h264", "width": 624, "height": 352}],
        "format": {"duration": "2490.5"},
    }
)


def test_probe_reads_width_and_duration():
    t = probe_tracks(Path("x.mkv"), runner=fake_runner(V2))
    assert (t.width, t.height, t.duration) == (624, 352, 2490.5)


def test_probe_command_is_niced_when_available(monkeypatch):
    monkeypatch.setattr("rc.probe.shutil.which", lambda n: "/usr/bin/nice")
    r = fake_runner(V2)
    probe_tracks(Path("x.mkv"), runner=r)
    assert r.calls[0][:3] == ["nice", "-n", "19"]
    monkeypatch.setattr("rc.probe.shutil.which", lambda n: None)
    r = fake_runner(V2)
    probe_tracks(Path("x.mkv"), runner=r)
    assert r.calls[0][0] == "ffprobe"


def test_old_cache_format_is_reprobed(tmp_path):
    conn = open_db(tmp_path / "rc.db")
    conn.execute(
        "INSERT INTO media_file (id, quality, relpath, subtitles_json, rev)"
        " VALUES (1, 'HD', 'f.mp4', '[]', 0)"
    )
    f = tmp_path / "f.mp4"
    f.write_bytes(b"abc")
    st = os.stat(f)
    old = json.dumps(
        {"audio": [], "subtitles": [], "external": [], "video_codec": "h264", "height": 352}
    )
    conn.execute(
        "INSERT INTO probe_cache (media_file_id, mtime, size, tracks_json) VALUES (1, ?, ?, ?)",
        (st.st_mtime, st.st_size, old),
    )
    r = fake_runner(V2)
    t = cached_tracks(conn, 1, f, runner=r)
    assert t.width == 624 and len(r.calls) == 1
    conn.close()


def test_media_summary_dedupes_and_orders():
    t = MediaTracks(
        [Track(1, "en", None, None, True), Track(2, "en", None, None, False), Track(3, None, None, None, False)],
        [Track(1, "fr", None, None, False)],
        [ExternalSub(Path("a.fr.srt"), "fr"), ExternalSub(Path("a.de.srt"), "de")],
        "h264",
        352,
        width=624,
        duration=2490.5,
    )
    assert media_summary(t) == {
        "audio_langs": ["en"],
        "subtitle_langs": ["fr", "de"],
        "width": 624,
        "height": 352,
        "duration_s": 2490.5,
    }


@pytest.mark.parametrize("raw", ["nan", "inf", "-1", "0", "N/A"])
def test_probe_ignores_unusable_duration(raw):
    out = json.dumps({"streams": [], "format": {"duration": raw}})
    t = probe_tracks(Path("x.mkv"), runner=fake_runner(out))
    assert t.duration is None
