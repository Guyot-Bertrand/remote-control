"""Mise de côté de la vidéo en pause (`park`) et reprise."""

import asyncio
from pathlib import Path

import pytest

from rc.catalog.importer import import_catalog
from rc.catalog.parser import parse_catalog
from rc.db import open_db
from rc.events import EventBus
from rc.fake_tree import make_fake_tree
from rc.player import session as session_mod
from rc.player.base import PlayerState
from rc.player.session import PlaybackService, PlayError, PlayRequest, TvController
from rc.probe import MediaTracks, Track
from rc.volumes import Volume, build_index, locate_files

from fake_player import FakePlayer

FIXTURE = Path(__file__).parent / "fixtures" / "medias_sample.txt"

TRACKS = MediaTracks(
    audio=[Track(1, "fr", None, "aac", True), Track(2, "en", None, "aac", False)],
    subtitles=[Track(1, "en", None, "subrip", False)],
    external=[], video_codec="h264", height=1080,
)


class Clock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setattr(session_mod, "cached_tracks", lambda conn, mid, path: TRACKS)
    conn = open_db(tmp_path / "rc.db")
    result = parse_catalog(FIXTURE.read_text(encoding="utf-8"))
    import_catalog(conn, result, {})
    disk = tmp_path / "DISK1"
    disk.mkdir()
    make_fake_tree(result, disk)
    vol = Volume("U1", "DISK1", disk)
    indexes = {"U1": build_index(disk)}
    locate_files(conn, [vol], indexes)
    bus = EventBus()
    tv = TvController(bus)
    player = FakePlayer()
    clock = Clock()

    class Env:
        pass

    e = Env()
    e.mode = "restart"
    e.image = tmp_path / "pause.jpg"
    svc = PlaybackService(conn, player, tv, bus, lambda: indexes, clock=clock,
                          pause_image=e.image, pause_mode=lambda: e.mode)
    e.conn, e.disk, e.indexes, e.bus, e.tv = conn, disk, indexes, bus, tv
    e.player, e.clock, e.svc = player, clock, svc
    yield e
    conn.close()


def progress_row(conn, kind="work", tid=1):
    return conn.execute("SELECT * FROM progress WHERE target_kind = ? AND target_id = ?",
                        (kind, tid)).fetchone()


def emit(env, **kw):
    env.player.on_state(PlayerState(running=True, **kw))


async def paused_at_1394(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.clock.t = 5
    emit(env, position=1394.0, duration=6000.0, paused=True, audio=1)
    env.player.calls.clear()


async def parked(env):
    await paused_at_1394(env)
    return await env.svc.park()


async def test_park_restart_captures_saves_and_stops(env):
    info = await parked(env)
    assert ("screenshot", env.image) in env.player.calls
    assert ("stop",) in env.player.calls
    assert info["position"] == 1394 and info["image"].endswith("v=1")
    assert info == {"image": "/api/player/pause.jpg?v=1", "title": "Ava",
                    "position": 1394.0, "duration": 6000.0, "work_id": 1}
    assert env.svc.parked
    assert env.svc.state.running and env.svc.state.paused
    assert env.svc.state.position == 1394
    assert env.svc.current is not None
    assert progress_row(env.conn)["position"] == 1394
    assert env.svc.pause_info() == info


async def test_park_stop_event_does_not_end_session(env):
    await paused_at_1394(env)
    env.player.stop_state = PlayerState(position=1394, duration=6000, running=False)
    await env.svc.park()
    assert env.svc.current is not None and env.svc.parked
    emit(env, position=0, ended=True)  # événement tardif : ignoré
    assert env.svc.current is not None and env.svc.state.position == 1394
    assert env.tv.state.screen == "playing"


async def test_image_version_increases(env):
    await parked(env)
    await env.svc.pause(False)
    emit(env, position=1400.0, duration=6000.0, paused=True)
    info = await env.svc.park()
    assert info["image"] == "/api/player/pause.jpg?v=2"


async def test_park_ignored_when_playing_not_paused(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    emit(env, position=10.0, duration=6000.0)
    assert await env.svc.park() is None
    assert env.player.calls[-1][0] == "start"


async def test_park_ignored_without_session_or_already_parked(env):
    assert await env.svc.park() is None
    await parked(env)
    env.player.calls.clear()
    assert await env.svc.park() is None
    assert env.player.calls == []


async def test_seek_while_parked_updates_kept_position(env):
    await parked(env)
    calls = []
    env.tv.update_pause = calls.append
    await env.svc.seek(60, True)
    assert env.svc.state.position == 1454
    assert calls and calls[-1]["position"] == 1454
    assert not [c for c in env.player.calls if c[0] == "seek"]
    await env.svc.seek(-5000, True)
    assert env.svc.state.position == 0
    await env.svc.seek(9000, False)
    assert env.svc.state.position == 6000


async def test_resume_restarts_at_kept_position_with_tracks(env):
    await parked(env)
    await env.svc.seek(60, True)
    await env.svc.set_audio(2)
    await env.svc.set_subtitle({"kind": "embedded", "index": 1})
    await env.svc.pause(True)  # sans effet
    assert env.player.starts() == []
    await env.svc.pause(False)
    start = env.player.starts()[-1]
    assert start["start_at"] == 1454 and start["audio"] == 2
    assert start["subtitle"] == {"kind": "embedded", "index": 1}
    assert "1920x1038" in start["path"].name
    assert env.tv.state.screen == "playing" and not env.svc.parked
    assert env.svc.state.running and not env.svc.state.paused
    assert env.svc.pause_info() is None
    assert not [c for c in env.player.calls if c[0] == "set_volume"]


async def test_invalid_track_while_parked_refused(env):
    await parked(env)
    with pytest.raises(PlayError) as exc:
        await env.svc.set_audio(9)
    assert exc.value.code == "invalid"
    with pytest.raises(PlayError):
        await env.svc.set_subtitle({"kind": "embedded", "index": 7})


async def test_volume_reapplied_after_restart(env):
    await parked(env)
    await env.svc.set_volume(40)
    assert env.svc.state.volume == 40
    assert not [c for c in env.player.calls if c[0] == "set_volume"]
    await env.svc.pause(False)
    names = [c[0] for c in env.player.calls]
    assert names.index("set_volume") > names.index("start")
    assert ("set_volume", 40) in env.player.calls


async def test_events_after_resume_are_followed(env):
    await parked(env)
    await env.svc.pause(False)
    emit(env, position=1500.0, duration=6000.0)
    assert env.svc.state.position == 1500
    emit(env, position=1500.0, duration=6000.0, ended=True)
    assert env.svc.current is None


async def test_stop_while_parked_finishes_session(env):
    await parked(env)
    await env.svc.seek(60, True)
    await env.svc.stop()
    assert env.svc.current is None and not env.svc.parked
    assert not env.svc.state.running
    assert progress_row(env.conn)["position"] == 1454
    assert env.tv.state.screen == "home"


async def test_play_same_media_while_parked_unparks(env):
    await parked(env)
    await env.svc.play(PlayRequest("work", 1, "start", audio=2))
    start = env.player.starts()[-1]
    assert start["start_at"] == 0 and start["audio"] == 2
    assert not env.svc.parked and env.tv.state.screen == "playing"


async def test_play_same_media_resume_keeps_position(env):
    await parked(env)
    await env.svc.play(PlayRequest("work", 1, "resume"))
    assert env.player.starts()[-1]["start_at"] == 1394


async def test_play_other_media_while_parked_saves_and_replaces(env):
    await parked(env)
    await env.svc.seek(60, True)
    await env.svc.play(PlayRequest("work", 3, "start"))
    assert progress_row(env.conn)["position"] == 1454
    assert env.svc.current["target_id"] == 3 and not env.svc.parked


async def test_unpark_with_volume_absent_ends_with_message(env):
    await parked(env)
    env.indexes.clear()
    await env.svc.pause(False)
    assert env.svc.current is None and not env.svc.parked
    assert env.tv.state.screen == "message" and "non branché" in env.tv.state.message
    assert env.tv.state.message == "Disque « DISK1 » non branché."
    assert env.player.starts() == []
    assert progress_row(env.conn)["position"] == 1394


async def test_park_minimize_keeps_player_running(env):
    env.mode = "minimize"
    info = await parked(env)
    assert ("set_minimized", True) in env.player.calls
    assert ("stop",) not in env.player.calls
    assert info["image"] == "/api/player/pause.jpg?v=1"
    assert env.svc.parked and env.svc.state.paused
    await env.svc.seek(60, True)
    assert ("seek", 60, True) in env.player.calls  # commandes envoyées à mpv
    env.player.calls.clear()
    await env.svc.pause(False)
    assert env.player.calls == [("set_minimized", False), ("set_pause", False)]
    assert not env.svc.parked and env.tv.state.screen == "playing"


async def test_play_same_media_while_minimized_unparks(env):
    env.mode = "minimize"
    await parked(env)
    env.player.calls.clear()
    await env.svc.play(PlayRequest("work", 1, "resume"))
    assert env.player.calls[0] == ("set_minimized", False)
    assert ("set_pause", False) in env.player.calls
    assert env.player.starts() == [] and not env.svc.parked


async def test_minimize_failure_falls_back_to_restart(env):
    env.mode = "minimize"
    env.player.minimize_error = RuntimeError("pas de fenêtre")
    await parked(env)
    assert ("stop",) in env.player.calls
    assert env.svc.parked and env.svc.current is not None
    env.player.calls.clear()
    await env.svc.pause(False)
    assert env.player.starts()[-1]["start_at"] == 1394
    assert ("set_minimized", False) not in env.player.calls


async def test_screenshot_failure_gives_null_image(env):
    env.player.screenshot_error = RuntimeError("capture impossible")
    info = await parked(env)
    assert info["image"] is None
    assert ("stop",) in env.player.calls and env.svc.parked


async def test_no_pause_image_path_gives_null_image(env):
    svc = PlaybackService(env.conn, env.player, env.tv, env.bus, lambda: env.indexes,
                          clock=env.clock)
    await svc.play(PlayRequest("work", 1, "start"))
    emit(env, position=100.0, duration=6000.0, paused=True)
    info = await svc.park()
    assert info["image"] is None and svc.parked
    assert not [c for c in env.player.calls if c[0] == "screenshot"]


async def test_on_pause_change_called(env):
    seen = []
    env.svc.on_pause_change = seen.append
    await env.svc.play(PlayRequest("work", 1, "start"))
    emit(env, position=10.0, duration=6000.0)
    emit(env, position=10.0, duration=6000.0, paused=True)
    emit(env, position=10.0, duration=6000.0, paused=True)
    assert seen == [True]
    await env.svc.park()
    await env.svc.pause(False)
    assert seen == [True, False]
    emit(env, position=12.0, duration=6000.0)
    assert seen == [True, False]


async def test_unpause_during_park_waits_and_unparks(env):
    await paused_at_1394(env)
    gate = env.player.stop_gate = asyncio.Event()
    parking = asyncio.create_task(env.svc.park())
    await asyncio.sleep(0)
    resuming = asyncio.create_task(env.svc.pause(False))
    await asyncio.sleep(0)
    assert ("set_pause", False) not in env.player.calls
    gate.set()
    await parking
    await resuming
    assert not env.svc.parked and env.player.starts()[-1]["start_at"] == 1394


async def test_unpark_player_failure_ends_session(env):
    await parked(env)
    env.player.fail_start = True
    with pytest.raises(PlayError) as exc:
        await env.svc.pause(False)
    assert exc.value.code == "player"
    assert env.svc.current is None and not env.svc.state.running
    assert env.tv.state.screen == "message"
    assert progress_row(env.conn)["position"] == 1394


async def test_seek_while_minimized_updates_pause_tile(env):
    env.mode = "minimize"
    await parked(env)
    env.tv.show_view({"screen": "browse"}, env.svc.pause_info())
    await env.svc.seek(60, True)
    emit(env, position=1454.0, duration=6000.0, paused=True, audio=1)  # mpv suit le saut
    assert env.tv.state.screen == "view"
    assert env.tv.state.pause["position"] == 1454
    assert env.tv.state.pause["image"] == "/api/player/pause.jpg?v=1"
