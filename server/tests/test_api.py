import socket
import subprocess
import logging
import threading
from pathlib import Path

import pytest
from fastapi import FastAPI, WebSocketDisconnect
from fastapi.testclient import TestClient

from rc.api import Services, install_api
from rc.assets import ingest_posters
from rc.catalog.importer import import_catalog
from rc.catalog.parser import parse_catalog
from rc.config import Config
from rc.db import current_revision, open_db
from rc.events import EventBus
from rc.fake_tree import make_fake_tree
from rc.player import session as session_mod
from rc.player.session import PlaybackService, TvController
from rc.probe import MediaTracks, Track, cached_tracks
from rc.volumes import Volume, build_index, locate_files

from test_probe import fake_runner
from fake_player import FakePlayer

FIXTURE = Path(__file__).parent / "fixtures" / "medias_sample.txt"
AVA_SYNOPSIS = "MP ; Ava, 13 ans, est en vacances au bord de l'océan..."


def tracks():
    return MediaTracks(
        audio=[Track(1, "en", None, "aac", True), Track(2, "fr", "Français", "aac", False)],
        subtitles=[Track(1, "en", None, "subrip", False)],
        external=[], video_codec="h264", height=1080,
    )


@pytest.fixture
def env(tmp_path, monkeypatch):
    monkeypatch.setattr(session_mod, "cached_tracks", lambda conn, mid, path: tracks())
    config = Config(data_dir=tmp_path / "data", allowed_hosts=["testserver"])
    conn = open_db(tmp_path / "rc.db", check_same_thread=False)
    text = FIXTURE.read_text(encoding="utf-8")
    result = parse_catalog(text)
    import_catalog(conn, result, {})
    disk = tmp_path / "DISK1"
    disk.mkdir()
    make_fake_tree(result, disk)
    vol = Volume("U1", "DISK1", disk)
    indexes = {"U1": build_index(disk)}
    locate_files(conn, [vol], indexes)
    ingest_posters(conn, config.data_dir / "assets", indexes)
    bus = EventBus()
    tv = TvController(bus)
    player = FakePlayer()
    playback = PlaybackService(conn, player, tv, bus, lambda: indexes)
    services = Services(conn, config, bus, tv, playback, lambda: indexes)
    app = FastAPI()
    install_api(app, services)

    class Env:
        pass

    e = Env()
    e.conn, e.text, e.disk, e.vol, e.indexes = conn, text, disk, vol, indexes
    e.player, e.playback, e.tv, e.config = player, playback, tv, config
    with TestClient(app) as client:
        e.client = client
        yield e
    conn.close()


def reimport(env, text):
    import_catalog(env.conn, parse_catalog(text), {})


def media_id(conn, like):
    return conn.execute("SELECT id FROM media_file WHERE relpath LIKE ?", (like,)).fetchone()[0]


def test_info(env):
    assert env.client.get("/api/info").json() == {
        "name": "Remote-Control", "api": 1, "revision": current_revision(env.conn)}


def test_sync_full_then_incremental(env):
    r = env.client.get("/api/sync", headers={"Accept-Encoding": "gzip"})
    assert r.headers["content-encoding"] == "gzip"
    full = r.json()
    assert full["full"] is True and "deleted" not in full
    assert full["revision"] == current_revision(env.conn)
    assert len(full["works"]) == 7
    ava = next(w for w in full["works"] if w["id"] == 1)
    assert ava["directors"] == ["Léa Mysius"] and ava["episodic"] is False
    assert len(ava["poster"]) == 16
    assert not {"content_hash", "directors_json", "poster_asset", "rev"} & ava.keys()
    mf = next(m for m in full["media_files"] if m["work_id"] == 1)
    assert mf["present"] is True and mf["volume_uuid"] == "U1"
    assert isinstance(mf["subtitles"], list) and "subtitles_json" not in mf
    assert full["volumes"] == [{"uuid": "U1", "label": "DISK1",
                                "mount": str(env.disk), "present": True}]
    assert {"work_id": 1, "collection_id": 2, "rank": "2017"} in full["work_collections"]
    assert len(full["seasons"]) == 4 and len(full["episodes"]) == 7

    rev = full["revision"]
    reimport(env, env.text.replace(AVA_SYNOPSIS, "MP ; Nouveau résumé"))
    inc = env.client.get(f"/api/sync?since={rev}").json()
    assert inc["full"] is False
    assert [w["id"] for w in inc["works"]] == [1]
    assert inc["works"][0]["synopsis"] == "Nouveau résumé"
    assert inc["media_files"] == [] and inc["seasons"] == [] and inc["progress"] == []
    assert inc["work_collections"] == [{"work_id": 1, "collection_id": 2, "rank": "2017"}]
    assert len(inc["volumes"]) == 1
    assert inc["deleted"] == {"work": [], "season": [], "episode": [],
                              "media_file": [], "collection": []}


def test_sync_reports_deleted(env):
    rev = current_revision(env.conn)
    prenom = env.conn.execute("SELECT id FROM work WHERE type = 'film' AND key LIKE "
                              "'Le prenom%'").fetchone()[0]
    files = [r[0] for r in env.conn.execute(
        "SELECT id FROM media_file WHERE work_id = ?", (prenom,))]
    block = "MN ; film ; Le prenom [2012] ; Le Prénom ; *\n"
    start = env.text.index(block)
    end = env.text.index("MD ; 0 ; Theatre")
    reimport(env, env.text[:start] + env.text[end:])
    deleted = env.client.get(f"/api/sync?since={rev}").json()["deleted"]
    assert deleted["work"] == [prenom]
    assert deleted["media_file"] == files


def test_sync_reports_availability_change(env):
    rev = current_revision(env.conn)
    locate_files(env.conn, [], {})
    inc = env.client.get(f"/api/sync?since={rev}").json()
    assert inc["media_files"] and all(m["present"] is False for m in inc["media_files"])
    assert inc["volumes"][0]["present"] is False


def test_sync_progress_decoded(env):
    env.conn.execute("INSERT INTO progress (target_kind, target_id, media_file_id, position,"
                     " duration, watched, audio, subtitle, updated_at, rev)"
                     " VALUES ('work', 1, 1, 60, 6000, 1, '2',"
                     " '{\"kind\": \"embedded\", \"index\": 1}', 5.0, 999)")
    (p,) = env.client.get("/api/sync?since=998").json()["progress"]
    assert p == {"target_kind": "work", "target_id": 1, "media_file_id": 1, "position": 60,
                 "duration": 6000, "watched": True, "audio": 2,
                 "subtitle": {"kind": "embedded", "index": 1}, "updated_at": 5.0}


def test_work_detail(env):
    w = env.client.get("/api/works/1").json()
    assert w["title_fr"] == "Ava" and w["progress"] is None and w["seasons"] == []
    assert [m["quality"] for m in w["media_files"]] == ["FHD", "HD"]
    assert all(m["present"] is True and m["volume_label"] == "DISK1"
               for m in w["media_files"])
    assert w["collections"] == [{"id": 2, "name": "Les 1000 plus grands films de tous les"
                                 " temps [2020]", "description": None, "link": None,
                                 "rank": "2017"}]
    serie = env.client.get("/api/works/5").json()
    assert [s["number"] for s in serie["seasons"]] == [1, 2]
    assert env.client.get("/api/works/999").status_code == 404
    assert env.client.get("/api/works/999").json() == {"error": "not_found"}


def test_season_highlight(env):
    for ep, at in ((1, 10.0), (2, 20.0)):
        env.conn.execute("INSERT INTO progress (target_kind, target_id, position, watched,"
                         " updated_at, rev) VALUES ('episode', ?, 300, 0, ?, 1)", (ep, at))
    s = env.client.get("/api/seasons/1").json()
    assert s["number"] == 1 and s["work"]["id"] == 5 and s["work"]["title_fr"] == "Back to Life"
    assert [e["number"] for e in s["episodes"]] == [1, 2]
    assert s["episodes"][1]["progress"]["position"] == 300
    assert s["episodes"][0]["media_files"][0]["present"] is True
    assert s["highlight_episode_id"] == 2
    assert env.client.get("/api/seasons/2").json()["highlight_episode_id"] == 3
    assert env.client.get("/api/seasons/999").status_code == 404


def test_asset_immutable_header(env):
    poster = env.conn.execute("SELECT poster_asset FROM work WHERE id = 1").fetchone()[0]
    r = env.client.get(f"/api/assets/{poster}/thumb.jpg")
    assert r.status_code == 200
    assert r.headers["content-type"] == "image/jpeg"
    assert r.headers["cache-control"] == "public, max-age=31536000, immutable"
    assert env.client.get(f"/api/assets/{poster}/huge.jpg").status_code == 404
    assert env.client.get("/api/assets/0123456789abcdef/tv.jpg").status_code == 404
    assert env.client.get("/api/assets/..%2F..%2Fetc/tv.jpg").status_code == 404


def test_tracks_default_and_last_choice(env):
    ava = media_id(env.conn, "%1920x1038%")
    t = env.client.get(f"/api/media/{ava}/tracks").json()
    assert [a["lang"] for a in t["audio"]] == ["en", "fr"]
    sub = str(env.disk / "Films" / "A" / "Ava [2017] [1h45,1920x1038,16x9,FR,sEN].1.fr.srt")
    assert t["external"] == [{"path": sub, "lang": "fr"}]
    assert (t["video_codec"], t["height"]) == ("h264", 1080)
    assert t["default"] == {"audio": 2, "subtitle": {"kind": "none"}}
    env.conn.execute("INSERT INTO progress (target_kind, target_id, position, watched, audio,"
                     " subtitle, rev) VALUES ('work', 1, 10, 0, '1', ?, 1)",
                     ('{"kind": "external", "path": "%s"}' % sub,))
    t = env.client.get(f"/api/media/{ava}/tracks").json()
    assert t["default"] == {"audio": 1, "subtitle": {"kind": "external", "path": sub}}
    env.conn.execute("UPDATE progress SET audio = '9', subtitle = '{\"kind\": \"embedded\","
                     " \"index\": 7}'")
    t = env.client.get(f"/api/media/{ava}/tracks").json()
    assert t["default"] == {"audio": 2, "subtitle": {"kind": "none"}}
    assert env.client.get("/api/media/999/tracks").json() == {"error": "not_found"}


def test_tracks_probe_failure_is_empty(env, monkeypatch):
    def boom(conn, mid, path):
        raise subprocess.CalledProcessError(1, "ffprobe")

    monkeypatch.setattr(session_mod, "cached_tracks", boom)
    r = env.client.get(f"/api/media/{media_id(env.conn, '%1920x1038%')}/tracks")
    assert r.status_code == 200
    t = r.json()
    assert t["audio"] == [] and t["subtitles"] == [] and len(t["external"]) == 1
    assert t["default"] == {"audio": None, "subtitle": {"kind": "none"}}


def test_tracks_probed_off_loop_and_cached(env, monkeypatch):
    threads = []

    def real(conn, mid, path):
        threads.append(threading.get_ident())
        return cached_tracks(conn, mid, path, runner=fake_runner())

    monkeypatch.setattr(session_mod, "cached_tracks", real)
    ava = media_id(env.conn, "%1920x1038%")
    t = env.client.get(f"/api/media/{ava}/tracks").json()
    assert t["audio"] and t["video_codec"]
    assert threads[0] != env.client.portal.call(threading.get_ident)
    row = env.conn.execute("SELECT 1 FROM probe_cache WHERE media_file_id = ?", (ava,))
    assert row.fetchone() is not None


def test_tracks_volume_absent_409(env):
    locate_files(env.conn, [], {})
    env.indexes.clear()
    r = env.client.get(f"/api/media/{media_id(env.conn, '%1920x1038%')}/tracks")
    assert r.status_code == 409
    assert r.json() == {"error": "volume_absent", "volume_label": "DISK1"}


def test_play_and_player_commands(env):
    r = env.client.post("/api/play", json={"target_kind": "work", "target_id": 1,
                                           "start": "start", "audio": 2,
                                           "subtitle": {"kind": "embedded", "index": 1}})
    assert r.json() == {"ok": True}
    (call,) = env.player.starts()
    assert call["audio"] == 2 and call["subtitle"] == {"kind": "embedded", "index": 1}
    state = env.client.get("/api/player/state").json()
    assert state["state"]["running"] is True and state["current"]["work_id"] == 1
    for path, body in (("pause", {"paused": True}), ("seek", {"seconds": -10, "relative": True}),
                       ("volume", {"level": 80}), ("audio", {"index": None}),
                       ("subtitle", {"kind": "none"}), ("stop", {})):
        assert env.client.post(f"/api/player/{path}", json=body).json() == {"ok": True}
    assert env.player.calls[1:] == [("set_pause", True), ("seek", -10, True),
                                    ("set_volume", 80), ("set_audio", None),
                                    ("set_subtitle", {"kind": "none"}), ("stop",)]
    assert env.client.post("/api/player/volume", json={"level": 101}).status_code == 422
    r = env.client.post("/api/player/subtitle", json={"kind": "bogus"})
    assert r.status_code == 422 and r.json() == {"error": "invalid", "volume_label": None}


def test_play_volume_absent_409(env):
    locate_files(env.conn, [], {})
    env.indexes.clear()
    r = env.client.post("/api/play", json={"target_kind": "work", "target_id": 1,
                                           "start": "resume"})
    assert r.status_code == 409
    assert r.json() == {"error": "volume_absent", "volume_label": "DISK1"}
    r = env.client.post("/api/play", json={"target_kind": "work", "target_id": 2,
                                           "start": "start"})
    assert r.json() == {"error": "not_playable", "volume_label": None}


def test_player_idle_409(env):
    for path, body in (("pause", {"paused": True}), ("seek", {"seconds": 1, "relative": False}),
                       ("volume", {"level": 5}), ("audio", {"index": 1}),
                       ("subtitle", {"kind": "none"}), ("stop", {})):
        r = env.client.post(f"/api/player/{path}", json=body)
        assert r.status_code == 409
        assert r.json() == {"error": "idle", "volume_label": None}
    assert env.player.calls == []
    assert env.client.get("/api/player/state").json()["current"] is None


def test_tv_show_and_state(env):
    assert env.client.post("/api/tv/show", json={"screen": "season", "id": 1}).json() == {
        "shown": True}
    assert env.client.get("/api/tv/state").json() == {
        "screen": "season", "work_id": 5, "season_id": 1, "message": None}
    assert env.client.post("/api/tv/show", json={"screen": "work", "id": 999}).status_code == 404
    r = env.client.post("/api/tv/show", json={"screen": "work"})
    assert r.status_code == 422 and r.json() == {"error": "invalid", "volume_label": None}
    env.client.post("/api/play", json={"target_kind": "work", "target_id": 1, "start": "start"})
    assert env.client.post("/api/tv/show", json={"screen": "home"}).json() == {"shown": False}
    assert env.client.post("/api/tv/show", json={"screen": "home", "force": True}).json() == {
        "shown": True}


def test_ws_hello_then_tv_event(env):
    with env.client.websocket_connect("/api/events") as ws:
        hello = ws.receive_json()
        assert hello == {"type": "hello", "revision": current_revision(env.conn),
                         "tv": {"screen": "home", "work_id": None, "season_id": None,
                                "message": None},
                         "player": env.client.get("/api/player/state").json()}
        env.client.post("/api/tv/show", json={"screen": "work", "id": 1})
        msg = ws.receive_json()
        assert msg == {"type": "tv", "state": {"screen": "work", "work_id": 1,
                                               "season_id": None, "message": None}}
    assert env.playback.bus.subscriber_count == 0  # désabonné à la déconnexion


def test_null_season_label_and_progress_fields_are_filled(env):
    env.conn.execute("UPDATE season SET label = NULL, rev = 900 WHERE id IN (2, 3)")
    env.conn.execute("INSERT INTO progress (target_kind, target_id, watched, rev)"
                     " VALUES ('episode', 3, 0, 900)")
    env.conn.execute("INSERT INTO progress (target_kind, target_id, watched, rev)"
                     " VALUES ('work', 1, 0, 900)")
    inc = env.client.get("/api/sync?since=899").json()
    assert {s["id"]: s["label"] for s in inc["seasons"]} == {2: "Saison 2", 3: "Épisodes"}
    assert all((p["position"], p["updated_at"]) == (0.0, 0.0) for p in inc["progress"])
    assert [s["label"] for s in env.client.get("/api/works/5").json()["seasons"]] == [
        "Saison 1", "Saison 2"]
    season = env.client.get("/api/seasons/2").json()
    assert season["label"] == "Saison 2"
    assert season["episodes"][0]["progress"]["position"] == 0.0
    assert season["highlight_episode_id"] == 3
    work = env.client.get("/api/works/1").json()
    assert (work["progress"]["position"], work["progress"]["updated_at"]) == (0.0, 0.0)


def test_ws_unserialisable_event_is_logged_and_skipped(env, caplog):
    with caplog.at_level(logging.ERROR, logger="rc.api.ws"):
        with env.client.websocket_connect("/api/events") as ws:
            ws.receive_json()  # hello
            env.client.portal.call(env.playback.bus.publish, {"type": "x", "bad": object()})
            env.client.post("/api/tv/show", json={"screen": "work", "id": 1})
            assert ws.receive_json()["type"] == "tv"  # connexion toujours active
    assert any("non sérialisable" in r.getMessage() for r in caplog.records)
    assert env.client.get("/api/info").status_code == 200


def test_ws_unexpected_error_is_logged_and_closes(env, caplog, monkeypatch):
    def boom(self):
        raise RuntimeError("état illisible")

    monkeypatch.setattr(PlaybackService, "state", property(boom))
    with caplog.at_level(logging.ERROR, logger="rc.api.ws"):
        with env.client.websocket_connect("/api/events") as ws:
            with pytest.raises(WebSocketDisconnect) as exc:
                ws.receive_json()
    assert exc.value.code == 1011
    assert any(r.exc_info and "état illisible" in str(r.exc_info[1]) for r in caplog.records)
    assert env.playback.bus.subscriber_count == 0


FORBIDDEN = {"error": "forbidden", "volume_label": None}


def test_post_foreign_origin_refused(env):
    r = env.client.post("/api/tv/show", json={"screen": "home"},
                        headers={"Origin": "http://malveillant.example"})
    assert r.status_code == 403
    assert r.json() == FORBIDDEN
    r = env.client.post("/api/tv/show", json={"screen": "home"}, headers={"Origin": "null"})
    assert r.status_code == 403


def test_post_same_origin_or_none_allowed(env):
    # TestClient envoie Host: testserver ; la page /tv a donc l'origine http://testserver.
    r = env.client.post("/api/tv/show", json={"screen": "home"},
                        headers={"Origin": "http://testserver"})
    assert r.status_code == 200
    assert env.client.post("/api/tv/show", json={"screen": "home"}).status_code == 200
    # Lecture seule : pas de vérification.
    r = env.client.get("/api/info", headers={"Origin": "http://malveillant.example"})
    assert r.status_code == 200


def test_ws_foreign_origin_refused(env):
    with pytest.raises(WebSocketDisconnect) as exc:
        with env.client.websocket_connect(
                "/api/events", headers={"Origin": "http://malveillant.example"}) as ws:
            ws.receive_json()
    assert exc.value.code == 1008


def test_ws_same_origin_allowed(env):
    with env.client.websocket_connect("/api/events",
                                      headers={"Origin": "http://testserver"}) as ws:
        assert ws.receive_json()["type"] == "hello"


EVIL = "evil.example:8765"


def test_rebinding_host_refused(env):
    h = {"Host": EVIL, "Origin": f"http://{EVIL}"}
    r = env.client.get("/api/sync", headers={"Host": EVIL})
    assert (r.status_code, r.json()) == (403, FORBIDDEN)
    r = env.client.post("/api/play", headers=h,
                        json={"target_kind": "work", "target_id": 1, "start": "start"})
    assert (r.status_code, r.json()) == (403, FORBIDDEN)
    assert env.player.starts() == []
    with pytest.raises(WebSocketDisconnect) as exc:
        with env.client.websocket_connect("/api/events", headers=h) as ws:
            ws.receive_json()
    assert exc.value.code == 1008


@pytest.mark.parametrize("host", ["192.168.1.23:8765", "[::1]:8765", "localhost:8765",
                                  f"{socket.gethostname()}.local:8765",
                                  socket.gethostname(), "testserver"])
def test_allowed_hosts_pass(env, host):
    assert env.client.get("/api/info", headers={"Host": host}).status_code == 200
    r = env.client.post("/api/tv/show", json={"screen": "home"},
                        headers={"Host": host, "Origin": f"http://{host}"})
    assert r.status_code == 200
    with env.client.websocket_connect("/api/events", headers={"Host": host}) as ws:
        assert ws.receive_json()["type"] == "hello"


def test_origin_host_must_be_allowed(env):
    # Origin identique à Host, mais Host est déjà refusé ; et une Origin d'un autre hôte
    # autorisé reste refusée (même hôte exigé).
    r = env.client.post("/api/tv/show", json={"screen": "home"},
                        headers={"Host": "localhost:8765", "Origin": "http://127.0.0.1:8765"})
    assert r.status_code == 403


def test_host_policy_unit():
    from rc.api.origin import HostPolicy, host_name

    assert host_name("[::1]:8765") == "::1"
    assert host_name("Box.Local:80") == "box.local"
    assert host_name("fe80::1") == "fe80::1"
    p = HostPolicy(["salon.lan"])
    assert p.allowed({b"host": b"salon.lan:8765"}, check_origin=False)
    assert not p.allowed({}, check_origin=False)  # Host absent
    assert not p.allowed({b"host": b"evil.example"}, check_origin=False)
