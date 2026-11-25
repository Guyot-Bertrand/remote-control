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
    assert env.tv.state.message == "Disque « DISK1 » non branché."
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
    assert exc.value.code == "player"
    assert env.tv.state.message == "Le lecteur vidéo ne répond pas."
    assert env.svc.current is None
    assert progress(env.conn) is None


async def test_missing_executable_is_player_missing(env):
    env.player.start_error = FileNotFoundError(2, "No such file or directory", "mpv")
    with pytest.raises(PlayError) as exc:
        await env.svc.play(PlayRequest("work", 1, "start"))
    assert exc.value.code == "player_missing"
    assert env.tv.state.screen == "message"
    assert env.tv.state.message == "Le lecteur vidéo (mpv) n'est pas installé sur le PC."
    assert env.svc.current is None


async def test_unavailable_player_refused_before_start(env):
    svc = PlaybackService(env.conn, env.player, env.tv, env.bus, lambda: env.indexes,
                          clock=env.clock, player_available=lambda: False)
    with pytest.raises(PlayError) as exc:
        await svc.play(PlayRequest("work", 1, "start"))
    assert exc.value.code == "player_missing"
    assert env.player.calls == []
    assert env.tv.state.message == "Le lecteur vidéo (mpv) n'est pas installé sur le PC."


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
    env.player.on_state(PlayerState(position=5900, duration=6000, running=True, ended=True))
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
                                    "message": None, "view": None, "pause": None}} in msgs
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
    await env.svc.play(PlayRequest("work", 1, "start"))  # même média : retour au début
    assert env.player.calls[-1] == ("seek", 0, False)


async def test_premature_eof_not_watched(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.player.on_state(PlayerState(position=2400, duration=6000, running=True, ended=True))
    row = progress(env.conn)
    assert (row["watched"], row["position"]) == (0, 2400)
    assert env.svc.current is None


async def test_eof_without_duration_marks_watched(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.player.on_state(PlayerState(position=2400, running=True, ended=True))
    row = progress(env.conn)
    assert (row["watched"], row["position"]) == (1, 0)


def drain(q):
    msgs = []
    while not q.empty():
        msgs.append(q.get_nowait())
    return [m for m in msgs if m["type"] == "player"]


async def test_position_only_events_at_most_once_per_second(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.clock.t = 0.0
    emit(env, position=0, duration=6000)
    q = env.bus.subscribe()
    for i in range(1, 31):  # 30 images en 0,9 s
        env.clock.t = i * 0.03
        emit(env, position=i * 0.03, duration=6000)
    assert drain(q) == []
    env.clock.t = 1.0
    emit(env, position=1.0, duration=6000)
    (msg,) = drain(q)
    assert msg["state"]["position"] == 1.0
    env.clock.t = 1.5
    emit(env, position=1.5, duration=6000)
    assert drain(q) == []


@pytest.mark.parametrize("change", [dict(paused=True), dict(volume=50), dict(audio=2),
                                    dict(subtitle={"kind": "embedded", "index": 1}),
                                    dict(duration=5000)])
async def test_non_position_change_published_immediately(env, change):
    await env.svc.play(PlayRequest("work", 1, "start"))
    emit(env, position=0, duration=6000)
    q = env.bus.subscribe()
    env.clock.t = 0.1
    emit(env, **{"position": 0.1, "duration": 6000, **change})
    (msg,) = drain(q)
    for k, v in change.items():
        assert msg["state"][k] == v


async def test_failed_start_releases_lock(env):
    from rc.player.mpv import MpvTimeout

    async def frozen(*a, **k):
        raise MpvTimeout("mpv ne répond pas")

    env.player.start = frozen
    with pytest.raises(PlayError):
        await env.svc.play(PlayRequest("work", 1, "start"))
    assert not env.svc._lock.locked()


async def test_finish_publishes_catalog_after_save(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    q = env.bus.subscribe()
    env.clock.t = 3
    emit(env, position=300, duration=6000)
    await env.svc.stop()
    msgs = []
    while not q.empty():
        msgs.append(q.get_nowait())
    catalog = [m for m in msgs if m["type"] == "catalog"]
    assert catalog == [{"type": "catalog", "revision": current_revision(env.conn)}]
    assert progress(env.conn)["rev"] == current_revision(env.conn)


async def test_sheet_requested_during_playback_shown_after_stop(env):
    env.tv.show("work", 1)
    await env.svc.play(PlayRequest("work", 1, "start"))
    assert env.tv.show("season", 5, 1) is False
    assert env.tv.state.screen == "playing"
    await env.svc.stop()
    assert (env.tv.state.screen, env.tv.state.work_id, env.tv.state.season_id) == (
        "season", 5, 1)


async def test_seek_while_paused_published_immediately(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.clock.t = 5.0
    emit(env, position=5.0, duration=6000, paused=True)
    q = env.bus.subscribe()
    env.clock.t = 5.3
    emit(env, position=90.0, duration=6000, paused=True)  # saut en pause : plus de time-pos
    (msg,) = drain(q)
    assert msg["state"]["position"] == 90.0


async def test_last_dropped_position_published_by_trailing_call(env, monkeypatch):
    monkeypatch.setattr(session_mod, "POSITION_PUBLISH_INTERVAL", 0.1)
    await env.svc.play(PlayRequest("work", 1, "start"))
    emit(env, position=0, duration=6000)
    q = env.bus.subscribe()
    env.clock.t = 0.02
    emit(env, position=300.0, duration=6000)  # deux sauts rapprochés
    env.clock.t = 0.03
    emit(env, position=600.0, duration=6000)
    assert drain(q) == []
    await asyncio.sleep(0.25)
    (msg,) = drain(q)  # un seul appel différé, avec la dernière position
    assert msg["state"]["position"] == 600.0


async def test_trailing_publish_cancelled_at_finish(env, monkeypatch):
    monkeypatch.setattr(session_mod, "POSITION_PUBLISH_INTERVAL", 0.1)
    await env.svc.play(PlayRequest("work", 1, "start"))
    emit(env, position=0, duration=6000)
    env.clock.t = 0.02
    emit(env, position=300.0, duration=6000)
    await env.svc.stop()
    q = env.bus.subscribe()
    await asyncio.sleep(0.25)
    assert drain(q) == []


async def _video_with_sub(env):
    """Chemin de la version lue par défaut, et un sous-titre externe voisin."""
    await env.svc.play(PlayRequest("work", 1, "start"))
    video = env.player.starts()[-1]["path"]
    sub = video.with_name(video.stem + ".fr.srt")
    sub.touch()
    await env.svc.stop()
    env.player.calls.clear()
    return sub


async def test_play_rejects_foreign_external_sub(env, tmp_path):
    foreign = tmp_path / "secret.srt"
    foreign.touch()
    with pytest.raises(PlayError) as exc:
        await env.svc.play(PlayRequest("work", 1, "start",
                                       subtitle={"kind": "external", "path": str(foreign)}))
    assert exc.value.code == "invalid"
    assert env.player.starts() == []


async def test_play_rejects_unknown_embedded_sub_and_audio(env):
    for kw in ({"subtitle": {"kind": "embedded", "index": 99}}, {"audio": 99}):
        with pytest.raises(PlayError) as exc:
            await env.svc.play(PlayRequest("work", 1, "start", **kw))
        assert exc.value.code == "invalid"
    assert env.player.starts() == []


async def test_play_accepts_known_external_sub(env):
    sub = await _video_with_sub(env)
    await env.svc.play(PlayRequest("work", 1, "start",
                                   subtitle={"kind": "external", "path": str(sub)}))
    assert env.player.starts()[-1]["subtitle"] == {"kind": "external", "path": str(sub)}


async def test_set_subtitle_and_audio_reject_unknown(env, tmp_path):
    foreign = tmp_path / "secret.srt"
    foreign.touch()
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.player.calls.clear()
    for sel in ({"kind": "external", "path": str(foreign)},
                {"kind": "embedded", "index": 99}, {"kind": "bogus"}):
        with pytest.raises(PlayError) as exc:
            await env.svc.set_subtitle(sel)
        assert exc.value.code == "invalid"
    with pytest.raises(PlayError):
        await env.svc.set_audio(99)
    assert env.player.calls == []


async def test_set_subtitle_accepts_known_external_and_none(env):
    sub = await _video_with_sub(env)
    await env.svc.play(PlayRequest("work", 1, "start"))
    await env.svc.set_subtitle({"kind": "external", "path": str(sub)})
    await env.svc.set_subtitle({"kind": "none"})
    await env.svc.set_audio(None)
    assert env.player.calls[-3:] == [
        ("set_subtitle", {"kind": "external", "path": str(sub)}),
        ("set_subtitle", {"kind": "none"}), ("set_audio", None)]


async def test_replay_same_media_resume_does_not_restart(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    mid = env.svc.current["media_file_id"]
    env.clock.t = 3
    emit(env, position=300, duration=6000, paused=True)
    env.player.calls.clear()
    await env.svc.play(PlayRequest("work", 1, "resume", media_file_id=mid))
    assert env.player.calls == [("set_pause", False)]  # pas de stop/start, pas de saut
    assert env.svc.current["media_file_id"] == mid
    assert env.tv.state.screen == "playing"


async def test_replay_same_media_start_seeks_to_zero_and_applies_tracks(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    emit(env, position=300, duration=6000)
    env.player.calls.clear()
    await env.svc.play(PlayRequest("work", 1, "start", audio=2,
                                   subtitle={"kind": "embedded", "index": 1}))
    assert env.player.calls == [("seek", 0, False), ("set_audio", 2),
                                ("set_subtitle", {"kind": "embedded", "index": 1})]
    await env.svc.stop()
    row = progress(env.conn)
    assert row["audio"] == "2" and row["subtitle"] == '{"kind": "embedded", "index": 1}'


async def test_replay_same_media_rejects_unknown_tracks(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.player.calls.clear()
    with pytest.raises(PlayError) as exc:
        await env.svc.play(PlayRequest("work", 1, "resume", audio=9))
    assert exc.value.code == "invalid"
    assert env.player.calls == []
    assert env.svc.current is not None


async def test_replay_same_media_brings_tv_back_to_playing(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.tv.show("work", 1, force=True)
    await env.svc.play(PlayRequest("work", 1, "resume"))
    assert env.tv.state.screen == "playing"
    assert [c[0] for c in env.player.calls] == ["start"]


async def test_replay_other_version_restarts(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    await env.svc.play(PlayRequest("work", 1, "start", media_file_id=media_id(env.conn, "HD")))
    assert [c[0] for c in env.player.calls] == ["start", "stop", "start"]


async def test_replay_falls_back_to_restart_when_player_frozen(env):
    from rc.player.mpv import MpvTimeout

    await env.svc.play(PlayRequest("work", 1, "start"))
    env.player.command_error = MpvTimeout("mpv ne répond pas")
    await env.svc.play(PlayRequest("work", 1, "start"))
    assert [c[0] for c in env.player.calls] == ["start", "seek", "stop", "start"]
    assert env.svc.current is not None


async def test_replay_when_session_ends_meanwhile_restarts(env):
    await env.svc.play(PlayRequest("work", 1, "start"))
    real_seek = env.player.seek

    async def seek_then_eof(seconds, relative):
        await real_seek(seconds, relative)
        env.player.on_state(PlayerState(position=6000, duration=6000, running=True, ended=True))

    env.player.seek = seek_then_eof
    await env.svc.play(PlayRequest("work", 1, "start"))
    assert [c[0] for c in env.player.calls] == ["start", "seek", "start"]
    assert env.svc.current is not None and env.tv.state.screen == "playing"


async def test_player_check_skipped_while_playing(env):
    available = [True]
    svc = PlaybackService(env.conn, env.player, env.tv, env.bus, lambda: env.indexes,
                          clock=env.clock, player_available=lambda: available[0])
    await svc.play(PlayRequest("work", 1, "start"))
    available[0] = False  # mpv désinstallé pendant la lecture
    with pytest.raises(PlayError) as exc:
        await svc.play(PlayRequest("work", 3, "start"))
    assert exc.value.code == "player_missing"
    assert svc.current is None  # session précédente arrêtée et enregistrée
    assert [c[0] for c in env.player.calls] == ["start", "stop"]


VIEW = {"kind": "search", "items": []}
PAUSE = {"image": "/api/player/pause.jpg?v=3", "title": "Heroes", "position": 10.0,
         "duration": 100.0, "work_id": 1}


def last_tv(bus_queue):
    last = None
    while not bus_queue.empty():
        m = bus_queue.get_nowait()
        if m["type"] == "tv":
            last = m
    return last


def test_show_view_publishes_view_and_pause(env):
    q = env.bus.subscribe()
    env.tv.show_view(VIEW)
    assert last_tv(q)["state"] == {"screen": "view", "work_id": None, "season_id": None,
                                   "message": None, "view": VIEW, "pause": None}
    env.tv.show_view(VIEW, PAUSE)
    assert last_tv(q)["state"]["pause"] == PAUSE


def test_show_paused_publishes_pause(env):
    q = env.bus.subscribe()
    env.tv.show_paused(PAUSE)
    st = last_tv(q)["state"]
    assert st["screen"] == "paused" and st["pause"] == PAUSE and st["view"] is None


async def test_playback_end_returns_to_last_view_without_pause(env):
    env.tv.show_view(VIEW, PAUSE)
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.player.on_state(PlayerState(position=5900, duration=6000, running=True, ended=True))
    assert env.tv.state.screen == "view"
    assert env.tv.state.view == VIEW and env.tv.state.pause is None


async def test_tv_show_replaces_remembered_view(env):
    env.tv.show_view(VIEW)
    env.tv.show("work", 1)
    await env.svc.play(PlayRequest("work", 3, "start"))
    env.player.on_state(PlayerState(position=5900, duration=6000, running=True, ended=True))
    assert env.tv.state.screen == "work" and env.tv.state.work_id == 1


def test_remember_view_does_not_publish(env):
    q = env.bus.subscribe()
    env.tv.remember_view(VIEW)
    assert q.empty() and env.tv.state.screen == "home"
    env.tv.playback_ended(None)
    assert env.tv.state.screen == "view" and env.tv.state.view == VIEW


def test_update_pause_only_on_view_or_paused(env):
    q = env.bus.subscribe()
    env.tv.update_pause(PAUSE)
    assert q.empty() and env.tv.state.pause is None
    env.tv.show_view(VIEW)
    last_tv(q)
    env.tv.update_pause(PAUSE)
    assert last_tv(q)["state"]["pause"] == PAUSE and env.tv.state.view == VIEW
    env.tv.show_paused(PAUSE)
    last_tv(q)
    other = {**PAUSE, "image": None}
    env.tv.update_pause(other)
    st = last_tv(q)["state"]
    assert st["screen"] == "paused" and st["pause"] == other
