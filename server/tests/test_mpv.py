import asyncio
import gc
import json
import warnings
import stat
import sys
from pathlib import Path

import pytest

from rc.player import mpv as mpv_mod
import rc.player.mpv as mpv_mod
from rc.player.mpv import MpvClient, MpvError, MpvPlayer, MpvTimeout, volume_bar_ass
from tests.fake_mpv import FakeMpv

FAKE = Path(__file__).parent / "fake_mpv.py"


@pytest.fixture
def sock(tmp_path):
    return tmp_path / "s.sock"


@pytest.fixture
async def fake(sock):
    f = FakeMpv(sock)
    await f.start()
    yield f
    await f.close()


@pytest.fixture
async def client(fake, sock):
    c = MpvClient(sock)
    await c.connect(retries=5, delay=0.01)
    yield c
    await c.close()


async def test_command_roundtrip_and_request_id(fake, client):
    fake.props.update(slow=1, fast=2)
    # "slow" est répondu après "fast" : chaque appelant doit recevoir sa propre réponse
    a, b = await asyncio.gather(
        client.command("get_property", "slow"), client.command("get_property", "fast")
    )
    assert (a, b) == (1, 2)


async def test_error_raises_mpverror(fake, client):
    fake.fail.add("bogus")
    with pytest.raises(MpvError, match="property unavailable"):
        await client.command("bogus")


async def test_observe_property_change_yields_event(fake, client):
    pid = await client.observe("time-pos")
    await fake.emit({"event": "property-change", "id": pid, "name": "time-pos", "data": 3.5})
    ev = await asyncio.wait_for(anext(client.events()), 1)
    assert ev == {"event": "property-change", "id": pid, "name": "time-pos", "data": 3.5}


async def test_command_times_out_when_mpv_hangs(fake, client, monkeypatch):
    monkeypatch.setattr(mpv_mod, "COMMAND_TIMEOUT", 0.2)
    fake.mute.add("hang")
    async with asyncio.timeout(2):
        with pytest.raises(MpvTimeout):
            await client.command("hang")
    assert issubclass(MpvTimeout, MpvError)
    assert client._pending == {}
    fake.props["x"] = 7
    assert await client.command("get_property", "x") == 7  # le client reste utilisable


# -- MpvPlayer contre un mpv factice (sous-processus) ------------------------


@pytest.fixture
def make_player(tmp_path, monkeypatch):
    wrapper = tmp_path / "mpv"
    wrapper.write_text(f'#!/bin/sh\nexec {sys.executable} {FAKE} "$@"\n')
    wrapper.chmod(wrapper.stat().st_mode | stat.S_IEXEC)
    log = tmp_path / "log.jsonl"
    players = []

    def make(script, props=None, mute=(), fail=()):
        cfg = tmp_path / "cfg.json"
        cfg.write_text(json.dumps({"script": script, "props": props or {}, "log": str(log),
                                   "mute": list(mute), "fail": list(fail)}))
        monkeypatch.setenv("FAKE_MPV_CONFIG", str(cfg))
        p = MpvPlayer(str(wrapper), ["--foo"], tmp_path / "run")
        states = []
        p.on_state = states.append
        players.append(p)
        return p, states, log

    yield make
    # filet de sécurité : rien ne doit rester vivant


async def wait_for(cond, timeout=5):
    async with asyncio.timeout(timeout):
        while not cond():
            await asyncio.sleep(0.01)


def logged(log):
    return [json.loads(l) for l in log.read_text().splitlines()] if log.exists() else []


async def test_start_sends_tracks_after_file_loaded(make_player, tmp_path):
    ext = tmp_path / "x.srt"
    tracks = [
        {"type": "audio", "id": 1},
        {"type": "sub", "id": 1, "external": False},
        {"type": "sub", "id": 2, "external": True},
    ]
    p, states, log = make_player(
        [{"event": {"event": "file-loaded"}}], {"track-list": tracks}
    )
    await p.start(tmp_path / "f.mkv", 12, 2, {"kind": "external", "path": str(ext)}, [ext])
    await wait_for(lambda: ["set_property", "sid", 2] in logged(log))
    cmds = logged(log)
    assert cmds.index(["set_property", "aid", 2]) < cmds.index(["set_property", "sid", 2])
    argv = next(c for c in cmds if c[0] == "argv")
    assert "--start=12" in argv and f"--sub-file={ext}" in argv and "--foo" in argv
    assert argv[-1] == str(tmp_path / "f.mkv")
    await p.stop()
    assert p.state.running is False


async def test_state_updates_position(make_player, tmp_path):
    ev = lambda n, d: {"event": {"event": "property-change", "id": 1, "name": n, "data": d}}
    p, states, log = make_player(
        [ev("time-pos", 12.5), ev("duration", 100.0), ev("pause", True), ev("volume", 80)]
    )
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await wait_for(lambda: states and states[-1].volume == 80)
    s = states[-1]
    assert (s.position, s.duration, s.paused, s.running) == (12.5, 100.0, True, True)
    await p.stop()
    assert states[-1].running is False and states[-1].error is None


async def test_start_against_frozen_mpv_fails_fast(make_player, tmp_path, monkeypatch):
    monkeypatch.setattr(mpv_mod, "COMMAND_TIMEOUT", 0.2)
    p, states, _ = make_player([], mute=["observe_property"])
    async with asyncio.timeout(5):
        with pytest.raises(MpvTimeout):
            await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    assert states[-1].running is False and states[-1].error == "start_failed"
    assert p._proc is None and p._client is None


async def test_unavailable_time_pos_keeps_last_values(make_player, tmp_path):
    ev = lambda n, d: {"event": {"event": "property-change", "id": 1, "name": n, "data": d}}
    p, states, _ = make_player([ev("time-pos", 50.0), ev("duration", 100.0),
                                ev("time-pos", None), ev("duration", None), ev("volume", 80)])
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await wait_for(lambda: states and states[-1].volume == 80)
    assert (states[-1].position, states[-1].duration) == (50.0, 100.0)
    await p.stop()


async def test_process_exit_without_eof_sets_error(make_player, tmp_path):
    p, states, _ = make_player([{"exit": True}])
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await wait_for(lambda: states and not states[-1].running)
    assert states[-1].error == "process_exit" and not states[-1].ended
    await p.stop()


async def test_eof_marks_ended(make_player, tmp_path):
    p, states, _ = make_player(
        [{"event": {"event": "end-file", "reason": "eof"}}, {"exit": True}]
    )
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await wait_for(lambda: states and not states[-1].running)
    assert states[-1].ended and states[-1].error is None
    await p.stop()


async def test_callback_exception_does_not_stop_pump(make_player, tmp_path):
    ev = lambda n, d: {"event": {"event": "property-change", "id": 1, "name": n, "data": d}}
    p, states, _ = make_player([ev("volume", 10), ev("volume", 20), ev("volume", 30)])
    calls = []

    def cb(s):
        calls.append(s)
        if len(calls) == 1:
            raise RuntimeError("boom")

    p.on_state = cb
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await wait_for(lambda: any(c.volume == 30 for c in calls))
    assert all(c.error is None for c in calls)
    await p.stop()


async def test_unexpected_exit_cleans_up_without_stop(make_player, tmp_path):
    p, states, _ = make_player([{"exit": True}])
    with warnings.catch_warnings():
        warnings.simplefilter("error", ResourceWarning)
        await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
        sock = p._sock
        await wait_for(lambda: states and not states[-1].running)
        assert states[-1].error == "process_exit"
        assert not sock.exists()
        pump, proc = p._pump, p._proc
        await pump
        assert proc.returncode is not None
        del p, pump, proc
        gc.collect()


async def test_start_failure_reports_error(tmp_path):
    p = MpvPlayer(str(tmp_path / "absent"), [], tmp_path / "run")
    states = []
    p.on_state = states.append
    with pytest.raises(FileNotFoundError):
        await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    assert states[-1].running is False and states[-1].error == "start_failed"


async def test_screenshot_sends_video_flag(make_player, tmp_path):
    p, states, log = make_player([])
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await p.screenshot(tmp_path / "p.jpg")
    assert ["screenshot-to-file", str(tmp_path / "p.jpg"), "video"] in logged(log)
    await p.stop()


async def test_screenshot_refused_raises_mpverror(make_player, tmp_path):
    p, states, log = make_player([], fail=["screenshot-to-file"])
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    with pytest.raises(MpvError):
        await p.screenshot(tmp_path / "p.jpg")
    await p.stop()


async def test_minimize_sets_window_minimized_only(make_player, tmp_path):
    p, states, log = make_player([])
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await p.set_minimized(True)
    cmds = logged(log)
    assert ["set_property", "window-minimized", True] in cmds
    assert ["set_property", "fullscreen", True] not in cmds
    await p.stop()


async def test_set_video_keeps_and_restores_track(make_player, tmp_path):
    p, states, log = make_player([], props={"vid": 2})
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await p.set_video(False)
    cmds = logged(log)
    assert ["get_property", "vid"] in cmds
    assert cmds[-1] == ["set_property", "vid", "no"]
    await p.set_video(True)
    cmds = logged(log)
    assert cmds[-2:] == [["set_property", "vid", 2], ["set_property", "fullscreen", True]]
    await p.stop()


async def test_set_video_restores_auto_when_unreadable(make_player, tmp_path):
    p, states, log = make_player([], fail=["get_property"])
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await p.set_video(False)
    assert logged(log)[-1] == ["set_property", "vid", "no"]
    await p.set_video(True)
    cmds = logged(log)
    assert cmds[-2:] == [["set_property", "vid", "auto"], ["set_property", "fullscreen", True]]
    await p.stop()


async def test_unminimize_restores_fullscreen(make_player, tmp_path):
    p, states, log = make_player([])
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await p.set_minimized(False)
    cmds = logged(log)
    assert ["set_property", "window-minimized", False] in cmds
    assert ["set_property", "fullscreen", True] in cmds
    await p.stop()



async def test_set_volume_draws_vertical_bar_then_hides_it(make_player, tmp_path, monkeypatch):
    # Retour visuel sur la vidéo : barre verticale dessinée (osd-overlay), retirée après le délai.
    monkeypatch.setattr(mpv_mod, "VOLUME_BAR_SECONDS", 0.2)
    p, states, log = make_player([])
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await p.set_volume(70)
    cmds = logged(log)
    assert ["set_property", "volume", 70] in cmds
    assert cmds[-1] == ["osd-overlay", mpv_mod.VOLUME_OVERLAY_ID, "ass-events", volume_bar_ass(70),
                        1920, 1080]
    argv = next(c for c in cmds if c[0] == "argv")
    assert "--volume-max=100" in argv
    await wait_for(lambda: logged(log)[-1][:3] == ["osd-overlay", mpv_mod.VOLUME_OVERLAY_ID, "none"])
    await p.stop()


async def test_new_volume_restarts_bar_delay(make_player, tmp_path, monkeypatch):
    monkeypatch.setattr(mpv_mod, "VOLUME_BAR_SECONDS", 0.3)
    p, states, log = make_player([])
    await p.start(tmp_path / "f.mkv", 0, None, {"kind": "none"}, [])
    await p.set_volume(70)
    await asyncio.sleep(0.2)
    await p.set_volume(75)
    await asyncio.sleep(0.2)  # 0,4 s après le premier appui, 0,2 s après le second
    assert not any(c[:3] == ["osd-overlay", mpv_mod.VOLUME_OVERLAY_ID, "none"] for c in logged(log))
    await wait_for(lambda: logged(log)[-1][:3] == ["osd-overlay", mpv_mod.VOLUME_OVERLAY_ID, "none"])
    await p.stop()


def test_volume_bar_geometry():
    # Barre verticale à 6 % du bord droit (repère 1920 × 1080), remplie depuis le bas.
    full, half, empty = volume_bar_ass(100), volume_bar_ass(50), volume_bar_ass(0)
    assert full.count("\\p1") == 2 and half.count("\\p1") == 2
    assert empty.count("\\p1") == 1  # piste seule, pas de remplissage
    assert " 1805 " in full  # bord droit de la barre : 1920 × 0,94
    assert half != full
