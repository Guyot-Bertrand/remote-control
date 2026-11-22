import asyncio
from pathlib import Path

import pytest

from rc.catalog.importer import import_catalog
from rc.catalog.parser import parse_catalog
from rc.db import current_revision, open_db
from rc.events import EventBus
from rc.fake_tree import make_fake_tree
from rc.player import session as session_mod
from rc.player.base import PlayerState
from rc.player.session import (PlaybackService, PlayError, PlayRequest, TvController,
                               decode_audio, decode_subtitle)
from rc.probe import MediaTracks, Track
from rc.volumes import Volume, build_index, locate_files

FIXTURE = Path(__file__).parent / "fixtures" / "medias_sample.txt"

TRACKS = MediaTracks(
    audio=[Track(1, "fr", None, "aac", True), Track(2, "en", None, "aac", False)],
    subtitles=[Track(1, "en", None, "subrip", False)],
    external=[], video_codec="h264", height=1080,
)


class FakePlayer:
    def __init__(self):
        self.on_state = lambda s: None
        self.calls = []
        self.fail_start = False
        self.start_gate: asyncio.Event | None = None  # start lent
        self.stop_gate: asyncio.Event | None = None  # stop lent
        self.stop_state: PlayerState | None = None  # état final émis par stop (comme mpv)

    async def start(self, path, start_at, audio, subtitle, external_subs):
        self.calls.append(("start", dict(path=path, start_at=start_at, audio=audio,
                                         subtitle=subtitle, external_subs=external_subs)))
        if self.start_gate is not None:
            await self.start_gate.wait()
        if self.fail_start:
            raise OSError("boom")

    async def set_pause(self, paused):
        self.calls.append(("set_pause", paused))

    async def seek(self, seconds, relative):
        self.calls.append(("seek", seconds, relative))

    async def set_volume(self, level):
        self.calls.append(("set_volume", level))

    async def set_audio(self, index):
        self.calls.append(("set_audio", index))

    async def set_subtitle(self, sel):
        self.calls.append(("set_subtitle", sel))

    async def stop(self):
        self.calls.append(("stop",))
        gate, self.stop_gate = self.stop_gate, None  # seul le premier stop est lent
        if gate is not None:
            await gate.wait()
        if self.stop_state is not None:
            self.on_state(self.stop_state)

    def starts(self):
        return [c[1] for c in self.calls if c[0] == "start"]


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
    svc = PlaybackService(conn, player, tv, bus, lambda: indexes, clock=clock)

    class Env:
        pass

    e = Env()
    e.conn, e.disk, e.indexes, e.bus, e.tv = conn, disk, indexes, bus, tv
    e.player, e.clock, e.svc = player, clock, svc
    yield e
    conn.close()


def media_id(conn, quality, like="%Ava [2017]%"):
    return conn.execute("SELECT id FROM media_file WHERE relpath LIKE ? AND quality = ?",
                        (like, quality)).fetchone()[0]


def progress(conn, kind="work", tid=1):
    return conn.execute("SELECT * FROM progress WHERE target_kind = ? AND target_id = ?",
                        (kind, tid)).fetchone()


def emit(env, **kw):
    env.player.on_state(PlayerState(running=True, **kw))


async def test_play_best_quality_from_start(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    (call,) = env.player.starts()
    assert "1920x1038" in call["path"].name
    assert call["start_at"] == 0
    assert env.svc.current == {"target_kind": "work", "target_id": 1,
                               "media_file_id": media_id(env.conn, "FHD"), "work_id": 1,
                               "season_id": None, "title": "Ava"}
    assert env.tv.state.screen == "playing"
    sub = call["path"].parent / "Ava [2017] [1h45,1920x1038,16x9,FR,sEN].1.fr.srt"
    assert call["external_subs"] == [sub]


async def test_resume_uses_stored_version_and_position(env):
    hd = media_id(env.conn, "HD")
    env.conn.execute("INSERT INTO progress (target_kind, target_id, media_file_id, position,"
                     " duration, watched, rev) VALUES ('work', 1, ?, 600, 6000, 0, 1)", (hd,))
    await env.svc.play(PlayRequest("work", 1, "resume"))
    (call,) = env.player.starts()
    assert "1280x720" in call["path"].name
    assert call["start_at"] == 600


async def test_resume_reapplies_stored_tracks(env):
    env.conn.execute("INSERT INTO progress (target_kind, target_id, position, watched, audio,"
                     " subtitle, rev) VALUES ('work', 1, 10, 0, '2',"
                     " '{\"kind\": \"embedded\", \"index\": 1}', 1)")
    await env.svc.play(PlayRequest("work", 1, "resume"))
    (call,) = env.player.starts()
    assert call["audio"] == 2
    assert call["subtitle"] == {"kind": "embedded", "index": 1}
    await env.svc.stop()
    env.conn.execute("UPDATE progress SET audio = '9'")
    await env.svc.play(PlayRequest("work", 1, "resume"))
    assert env.player.starts()[-1]["audio"] == 1  # piste disparue : choix par défaut


async def test_saves_every_10s_not_more(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    rev = current_revision(env.conn)
    for t in (0, 3, 9, 10, 15, 21):
        env.clock.t = t
        emit(env, position=t, duration=6000)
    assert current_revision(env.conn) == rev + 2
    assert progress(env.conn)["position"] == 21


async def test_pause_change_saves(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    rev = current_revision(env.conn)
    env.clock.t = 2
    emit(env, position=2, duration=6000, paused=True)
    assert current_revision(env.conn) == rev + 1


async def test_watched_at_90_percent(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.clock.t = 10
    emit(env, position=5400, duration=6000)
    row = progress(env.conn)
    assert row["watched"] == 1
    assert row["position"] == 0


async def test_volume_absent_message(env):
    locate_files(env.conn, [], {})
    env.indexes.clear()
    with pytest.raises(PlayError) as exc:
        await env.svc.play(PlayRequest("work", 1, "start"))
    assert (exc.value.code, exc.value.volume_label) == ("volume_absent", "DISK1")
    assert env.tv.state.screen == "message"
    assert env.tv.state.message == "Disque « DISK1 » non branché"
    assert env.player.starts() == []


async def test_not_found_and_not_playable(env):
    for p in env.disk.rglob("Ava*.mp4"):
        p.unlink()
    env.indexes["U1"] = build_index(env.disk)
    with pytest.raises(PlayError) as exc:
        await env.svc.play(PlayRequest("work", 1, "start"))
    assert exc.value.code == "not_found"
    with pytest.raises(PlayError) as exc:
        await env.svc.play(PlayRequest("work", 2, "start"))  # Alien : DVD seulement
    assert exc.value.code == "not_playable"


async def test_start_failure_shows_message(env):
    env.player.fail_start = True
    with pytest.raises(PlayError) as exc:
        await env.svc.play(PlayRequest("work", 1, "start"))
    assert exc.value.code == "not_found"
    assert env.tv.state.message == "Lecture interrompue : fichier inaccessible"
    assert env.svc.current is None
    assert progress(env.conn) is None


async def test_process_error_saves_and_shows_message(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.player.on_state(PlayerState(position=1234, duration=6000, running=False,
                                    error="process_exit"))
    assert progress(env.conn)["position"] == 1234
    assert env.tv.state.screen == "message"
    assert env.tv.state.message == "Lecture interrompue : fichier inaccessible"
    assert env.svc.current is None
    assert env.svc.state.running is False


async def test_ended_returns_to_last_sheet(env):
    env.tv.show("work", 1)
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.player.on_state(PlayerState(position=0, duration=6000, running=True, ended=True))
    assert env.tv.state.screen == "work" and env.tv.state.work_id == 1
    assert progress(env.conn)["watched"] == 1
    assert env.svc.current is None


async def test_new_play_stops_current_first(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.clock.t = 3
    emit(env, position=300, duration=6000)
    await env.svc.play(PlayRequest("work", 3, "start"))
    names = [c[0] for c in env.player.calls]
    assert names == ["start", "stop", "start"]
    assert progress(env.conn)["position"] == 300
    assert env.svc.current["target_id"] == 3


async def test_stop_without_playback_is_noop(env):
    await env.svc.stop()
    assert env.player.calls == []


async def test_tv_show_ignored_while_playing(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    assert env.tv.show("work", 2) is False
    assert env.tv.state.screen == "playing"
    assert env.tv.show("work", 2, force=True) is True
    assert env.tv.state.screen == "work" and env.tv.state.work_id == 2


async def test_track_choice_persisted(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    await env.svc.set_audio(1)
    await env.svc.set_subtitle({"kind": "embedded", "index": 1})
    await env.svc.stop()
    row = progress(env.conn)
    assert row["audio"] == "1"
    assert decode_audio(row["audio"]) == 1
    assert decode_subtitle(row["subtitle"]) == {"kind": "embedded", "index": 1}
    assert decode_audio(None) is None and decode_subtitle(None) is None


async def test_episode_title_and_events(env):
    q = env.bus.subscribe()
    await env.svc.play(PlayRequest("episode", 2, "start"))
    cur = env.svc.current
    assert cur["title"] == "Back to Life — S01E02"
    assert (cur["work_id"], cur["season_id"]) == (5, 1)
    emit(env, position=1, duration=100)
    msgs = []
    while not q.empty():
        msgs.append(q.get_nowait())
    assert {"type": "tv", "state": {"screen": "playing", "work_id": 5, "season_id": None,
                                    "message": None}} in msgs
    player = [m for m in msgs if m["type"] == "player"][-1]
    assert player["state"]["position"] == 1 and player["current"] == cur


async def test_bus_drops_oldest_when_full():
    bus = EventBus()
    q = bus.subscribe()
    for i in range(105):
        bus.publish({"i": i})
    assert q.qsize() == 100
    assert q.get_nowait() == {"i": 5}
    bus.unsubscribe(q)
    bus.publish({"i": 999})
    assert q.qsize() == 99


async def test_concurrent_plays_are_serialized(env):
    env.player.start_gate = asyncio.Event()
    first = asyncio.create_task(env.svc.play(PlayRequest("work", 1, "start")))
    second = asyncio.create_task(env.svc.play(PlayRequest("work", 3, "start")))
    await asyncio.sleep(0.01)
    env.player.start_gate.set()
    await asyncio.gather(first, second)
    assert [c[0] for c in env.player.calls] == ["start", "stop", "start"]
    assert env.svc.current["target_id"] == 3
    assert progress(env.conn) is not None  # la première lecture a été enregistrée


async def test_stop_does_not_finish_a_newer_session(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    gate = env.player.stop_gate = asyncio.Event()
    stopping = asyncio.create_task(env.svc.stop())
    await asyncio.sleep(0.01)
    playing = asyncio.create_task(env.svc.play(PlayRequest("work", 3, "start")))
    await asyncio.sleep(0.01)
    gate.set()
    await asyncio.gather(stopping, playing)
    assert env.svc.current["target_id"] == 3
    assert env.tv.state.screen == "playing"
    assert progress(env.conn, "work", 3) is None


async def test_reentrant_stop_saves_first_and_keeps_tv(env):
    env.player.stop_state = PlayerState(position=300, duration=6000, running=False)
    await env.svc.play(PlayRequest("work", 1, "start"))
    q = env.bus.subscribe()
    await env.svc.play(PlayRequest("work", 3, "start"))
    assert progress(env.conn)["position"] == 300
    assert env.svc.current["target_id"] == 3
    assert env.svc.state.running is True
    tv = []
    while not q.empty():
        m = q.get_nowait()
        if m["type"] == "tv":
            tv.append(m["state"]["screen"])
    assert tv == ["playing"]  # pas de retour intermédiaire à la fiche


async def test_finish_clears_session_when_save_fails(env, monkeypatch):
    await env.svc.play(PlayRequest("work", 1, "start"))

    def boom(state):
        raise RuntimeError("disk full")

    monkeypatch.setattr(env.svc, "_save", boom)
    await env.svc.stop()
    assert env.svc.current is None
    assert env.svc.state.running is False


async def test_stored_external_sub_must_belong_to_version(env, tmp_path):
    other = tmp_path / "ailleurs.fr.srt"
    other.touch()
    env.conn.execute("INSERT INTO progress (target_kind, target_id, position, watched,"
                     " subtitle, rev) VALUES ('work', 1, 10, 0, ?, 1)",
                     ('{"kind": "external", "path": "%s"}' % other,))
    await env.svc.play(PlayRequest("work", 1, "resume"))
    assert env.player.starts()[-1]["subtitle"] == {"kind": "none"}  # défaut (audio fr)


async def test_start_failure_publishes_player_state(env):
    env.player.fail_start = True
    q = env.bus.subscribe()
    with pytest.raises(PlayError):
        await env.svc.play(PlayRequest("work", 1, "start"))
    msgs = []
    while not q.empty():
        msgs.append(q.get_nowait())
    player = [m for m in msgs if m["type"] == "player"]
    assert player and player[-1]["state"]["running"] is False
    assert player[-1]["current"] is None


async def test_resume_rewatch_uses_position_even_if_watched(env):
    env.conn.execute("INSERT INTO progress (target_kind, target_id, position, duration,"
                     " watched, rev) VALUES ('work', 1, 1200, 6000, 1, 1)")
    await env.svc.play(PlayRequest("work", 1, "resume"))
    assert env.player.starts()[-1]["start_at"] == 1200
    env.clock.t = 10
    emit(env, position=1500, duration=6000)
    row = progress(env.conn)
    assert (row["position"], row["watched"]) == (1500, 1)
    await env.svc.play(PlayRequest("work", 1, "start"))
    assert env.player.starts()[-1]["start_at"] == 0
