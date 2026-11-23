import asyncio
import json
import stat
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

from rc.config import Config
from rc.doctor import _play_sample, _survey, codec_key, parse_vainfo, run_doctor, survey

FIX = Path(__file__).parent / "fixtures"
FAKE = Path(__file__).parent / "fake_mpv.py"


def test_parse_vainfo():
    got = parse_vainfo((FIX / "vainfo_sample.txt").read_text())
    assert got == {"mpeg2", "h264", "hevc", "hevc10", "vp9", "vp9_10", "vc1", "vp8"}


def test_codec_key_hevc10():
    assert codec_key("hevc", "Main 10") == "hevc10"
    assert codec_key("hevc", "Main") == "hevc"
    assert codec_key("h264", "High") == "h264"
    assert codec_key("mpeg2video", None) == "mpeg2"


VAINFO_NO_MAIN10 = "VAProfileHEVCMain : VAEntrypointVLD\nVAProfileH264High : VAEntrypointVLD\n"


def make_runner(streams_by_name, vainfo=VAINFO_NO_MAIN10):
    def runner(args, **kw):
        if args[0] == "vainfo":
            return SimpleNamespace(stdout="", stderr=vainfo, returncode=0)
        name = Path(args[-1]).name
        if name not in streams_by_name:
            raise subprocess.CalledProcessError(1, args)
        return SimpleNamespace(stdout=json.dumps({"streams": [streams_by_name[name]]}))
    return runner


def test_survey_counts_and_failures():
    r = make_runner({"a.mkv": {"codec_name": "hevc", "profile": "Main 10"}})
    c = survey([Path("/x/a.mkv"), Path("/x/b.mkv")], r)
    assert c == {"hevc10": 1, "illisible": 1}


def test_survey_is_capped():
    calls = []
    def r(args, **kw):
        calls.append(args)
        return SimpleNamespace(stdout=json.dumps({"streams": [{"codec_name": "h264"}]}))
    c = survey([Path(f"/x/{i}.mkv") for i in range(1000)], r)
    assert sum(c.values()) == 300 and len(calls) == 300


def test_report_flags_unsupported():
    names = {f"{i}.mkv": {"codec_name": "hevc", "profile": "Main 10"} for i in range(3)}
    paths = [Path(f"/x/{n}") for n in names]
    report = run_doctor(Config(), runner=make_runner(names), which=lambda _: None,
                        media_paths=paths)
    assert "3 fichiers sans décodage matériel" in report
    assert "mpv introuvable : test de lecture ignoré" in report


def test_report_playback_with_injected_player():
    names = {"a.mkv": {"codec_name": "h264", "profile": "High"}}
    seen = []
    async def play(path, mpv, extra, secs):
        seen.append((path.name, mpv, secs))
        return {"hwdec-current": "vaapi", "frame-drop-count": 2, "decoder-frame-drop-count": 1}
    report = run_doctor(Config(), samples=2, play_seconds=5, runner=make_runner(names),
                        which=lambda _: "/usr/bin/mpv", play=play,
                        media_paths=[Path("/x/a.mkv")])
    assert seen == [("a.mkv", "/usr/bin/mpv", 5)]
    assert "décodage : vaapi ; images perdues : 2 ; perdues au décodage : 1" in report
    assert "Aucun fichier sondé sans décodage matériel possible" in report


def test_report_playback_failure_is_reported():
    names = {"a.mkv": {"codec_name": "h264"}}
    async def play(*a):
        raise asyncio.TimeoutError()
    report = run_doctor(Config(), runner=make_runner(names), which=lambda _: "mpv",
                        play=play, media_paths=[Path("/x/a.mkv")])
    assert "échec de la lecture" in report


def test_play_sample_reads_counters(tmp_path, monkeypatch):
    wrapper = tmp_path / "mpv"
    wrapper.write_text(f'#!/bin/sh\nexec {sys.executable} {FAKE} "$@"\n')
    wrapper.chmod(wrapper.stat().st_mode | stat.S_IEXEC)
    cfg = tmp_path / "cfg.json"
    cfg.write_text(json.dumps({"props": {"hwdec-current": "vaapi", "frame-drop-count": 4,
                                         "decoder-frame-drop-count": 0}}))
    monkeypatch.setenv("FAKE_MPV_CONFIG", str(cfg))
    res = asyncio.run(_play_sample(tmp_path / "v.mkv", str(wrapper), [], 0))
    assert res == {"hwdec-current": "vaapi", "frame-drop-count": 4,
                   "decoder-frame-drop-count": 0}


def test_parse_vainfo_exact_profiles():
    assert parse_vainfo("VAProfileHEVCMain444 : VAEntrypointVLD\n"
                        "VAProfileHEVCMain12 : VAEntrypointVLD\n"
                        "VAProfileHEVCMain422_10 : VAEntrypointVLD\n") == set()
    assert parse_vainfo("VAProfileVP9Profile2 : VAEntrypointVLD\n") == {"vp9_10"}
    # style i965 : espaces et tabulation différents, entrées d'encodage ignorées
    assert parse_vainfo("      VAProfileH264High               :\tVAEntrypointVLD\n"
                        "      VAProfileAV1Profile0 : VAEntrypointEncSlice\n") == {"h264"}


def test_codec_key_profiles():
    assert codec_key("hevc", "Main 4:4:4 10") == "hevc (Main 4:4:4 10)"
    assert codec_key("vp9", "Profile 2") == "vp9_10"
    assert codec_key("vc1", None) == "vc1"


def run(names, vainfo=VAINFO_NO_MAIN10, **kw):
    return run_doctor(Config(), runner=make_runner(names, vainfo), which=lambda _: None,
                      media_paths=[Path(f"/x/{n}") for n in names], **kw)


def test_unknown_codec_not_counted_unsupported():
    r = run({"a.mkv": {"codec_name": "wmv3"}})
    assert "sans décodage matériel possible (" not in r.replace("Aucun fichier sondé sans", "")
    assert "1 fichier non vérifié" in r


def test_vainfo_unavailable_not_counted():
    r = run({"a.mkv": {"codec_name": "hevc", "profile": "Main 10"}}, vainfo="")
    assert "non vérifiable (vainfo indisponible)" in r
    assert "fichier sans décodage" not in r


def test_probe_timeout_and_budget():
    kws = []
    def r(args, **kw):
        kws.append(kw)
        return SimpleNamespace(stdout=json.dumps({"streams": [{"codec_name": "h264"}]}))
    survey([Path("/x/a.mkv")], r)
    assert kws[0]["timeout"] == 15
    ticks = iter([0, 0, 1000, 1000])
    counts, _, cut = _survey([Path("/x/a.mkv"), Path("/x/b.mkv")], r, budget=600,
                             clock=lambda: next(ticks))
    assert cut and sum(counts.values()) == 1


def test_playback_warns_fullscreen():
    async def play(*a):
        return {}
    r = run_doctor(Config(), runner=make_runner({"a.mkv": {"codec_name": "h264"}}),
                   which=lambda _: "mpv", play=play, media_paths=[Path("/x/a.mkv")])
    assert "plein écran sur la télé" in r
