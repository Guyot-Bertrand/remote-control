import asyncio
import logging
import os
import shutil
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from rc import app as app_mod
from rc.app import create_app, mdns_announce, refresh_once
from rc.catalog.parser import parse_catalog
from rc.config import Config
from rc.db import current_revision, next_revision, open_db
from rc.player.session import PlayRequest
from rc.fake_tree import make_fake_tree

from fake_player import FakePlayer

FIXTURE = Path(__file__).parent / "fixtures" / "medias_sample.txt"


@pytest.fixture
def setup(tmp_path):
    root = tmp_path / "media"
    disk = root / "DISK1"
    disk.mkdir(parents=True)
    make_fake_tree(parse_catalog(FIXTURE.read_text(encoding="utf-8")), disk)
    config = Config(data_dir=tmp_path / "data", scan_roots=[root], dev_dir_volumes=True,
                    mdns=False, poll_interval=3600)
    app = create_app(config, player=FakePlayer())
    state = app.state.rc
    yield state, disk
    state.conn.close()


def works(state) -> int:
    return state.conn.execute("SELECT COUNT(*) FROM work").fetchone()[0]


def put_catalog(disk: Path, text: str, mtime: int) -> None:
    f = disk / "medias.txt"
    f.write_text(text, encoding="utf-8")
    os.utime(f, (mtime, mtime))


async def import_now(state, disk) -> None:
    shutil.copy2(FIXTURE, disk / "medias.txt")
    await refresh_once(state)
    await refresh_once(state)
    assert works(state) > 0


async def test_refresh_imports_only_when_stable(setup):
    state, disk = setup
    text = FIXTURE.read_text(encoding="utf-8")
    put_catalog(disk, text, 1_700_000_000)
    await refresh_once(state)  # vu une première fois
    assert works(state) == 0
    put_catalog(disk, text + "\n", 1_700_000_100)  # modifié avant le second tour
    await refresh_once(state)
    assert works(state) == 0
    await refresh_once(state)  # stable sur deux tours
    assert works(state) > 0
    copy = state.config.data_dir / "catalog" / "medias.txt"
    assert copy.read_text(encoding="utf-8") == text + "\n"
    # Fichiers localisés sur le disque simulé, affiches ingérées.
    present = state.conn.execute(
        "SELECT COUNT(*) FROM media_file WHERE present = 1").fetchone()[0]
    assert present > 0
    assert state.conn.execute(
        "SELECT COUNT(*) FROM work WHERE poster_asset IS NOT NULL").fetchone()[0] > 0


async def test_refresh_refused_keeps_db(setup, caplog):
    state, disk = setup
    await import_now(state, disk)
    before = works(state)
    put_catalog(disk, "", 1_700_000_500)
    with caplog.at_level(logging.WARNING, logger="rc.app"):
        await refresh_once(state)
        await refresh_once(state)
    assert works(state) == before
    assert any(r.levelno == logging.WARNING and "catalogue vide" in r.getMessage()
               for r in caplog.records)
    copy = state.config.data_dir / "catalog" / "medias.txt"
    assert copy.read_text(encoding="utf-8") == FIXTURE.read_text(encoding="utf-8")


async def test_refresh_publishes_catalog_event(setup):
    state, disk = setup
    queue = state.bus.subscribe()
    await import_now(state, disk)
    events = []
    while not queue.empty():
        events.append(queue.get_nowait())
    catalog = [e for e in events if e["type"] == "catalog"]
    assert catalog[-1] == {"type": "catalog", "revision": current_revision(state.conn)}
    await refresh_once(state)  # rien de neuf : pas d'événement
    assert queue.empty()


def catalog_events(queue) -> list[dict]:
    events = []
    while not queue.empty():
        events.append(queue.get_nowait())
    return [e for e in events if e["type"] == "catalog"]


async def test_revision_change_between_rounds_is_published(setup):
    state, disk = setup
    await import_now(state, disk)
    queue = state.bus.subscribe()
    state.conn.execute("BEGIN IMMEDIATE")
    rev = next_revision(state.conn)  # une progression enregistrée pendant la lecture
    state.conn.execute("COMMIT")
    await refresh_once(state)
    assert catalog_events(queue) == [{"type": "catalog", "revision": rev}]
    await refresh_once(state)
    assert catalog_events(queue) == []


async def test_external_import_is_published(setup):
    state, disk = setup
    await import_now(state, disk)
    queue = state.bus.subscribe()
    edited = disk / "medias.txt"  # catalogue modifié puis `rc import` sans argument
    put_catalog(disk, FIXTURE.read_text(encoding="utf-8").replace(
        "MP ; Ava, 13 ans", "MP ; Ava, treize ans"), 1_700_000_900)
    other = open_db(Path(app_mod._db_file(state.conn)))  # `rc import` dans un autre processus
    try:
        assert app_mod.import_file(other, state.config, edited).updated == 1
    finally:
        other.close()
    await refresh_once(state)
    assert catalog_events(queue) == [{"type": "catalog",
                                      "revision": current_revision(state.conn)}]
    await refresh_once(state)  # copie à jour : pas de réimport ni de doublon
    assert catalog_events(queue) == []


async def test_playback_end_publishes_catalog(setup):
    state, disk = setup
    await import_now(state, disk)
    playback = state.playback
    queue = state.bus.subscribe()
    await playback.play(PlayRequest("work", 1, "start"))
    await playback.stop()
    rev = current_revision(state.conn)
    assert catalog_events(queue) == [{"type": "catalog", "revision": rev}]
    await refresh_once(state)  # déjà publiée : pas de doublon
    assert catalog_events(queue) == []


async def test_refresh_survives_findmnt_failure(tmp_path, caplog):
    def runner(cmd, **kw):
        raise FileNotFoundError("findmnt")

    config = Config(data_dir=tmp_path / "data", scan_roots=[tmp_path], mdns=False)
    app = create_app(config, player=FakePlayer(), runner=runner)
    state = app.state.rc
    with caplog.at_level(logging.WARNING, logger="rc.app"):
        await refresh_once(state)
    assert any("disques" in r.getMessage() for r in caplog.records)
    state.conn.close()


def test_tv_static_served(tmp_path):
    config = Config(data_dir=tmp_path / "data", scan_roots=[tmp_path / "vide"],
                    dev_dir_volumes=True, mdns=False, poll_interval=3600,
                    allowed_hosts=["testserver"])
    app = create_app(config, player=FakePlayer())
    with TestClient(app) as client:
        r = client.get("/tv/")
        assert r.status_code == 200
        assert r.headers["content-type"].startswith("text/html")
        assert client.get("/api/info").json()["name"] == "Remote-Control"
    with pytest.raises(Exception):  # connexion fermée à l'arrêt
        app.state.rc.conn.execute("SELECT 1")


class Clock:
    def __init__(self):
        self.t = 1000.0

    def __call__(self):
        return self.t


def deep_key(name: str) -> str:
    return f"films/{name}"


async def test_full_reindex_every_interval(setup):
    state, disk = setup
    state.clock = clock = Clock()
    (disk / "films").mkdir()
    await refresh_once(state)
    (disk / "films" / "nouveau.mkv").touch()  # la racine du disque ne change pas
    clock.t += 599
    await refresh_once(state)
    assert deep_key("nouveau.mkv") not in state.indexes["DISK1"]
    clock.t += 1
    await refresh_once(state)
    assert deep_key("nouveau.mkv") in state.indexes["DISK1"]


async def test_reindex_after_import(setup, tmp_path):
    state, disk = setup
    state.clock = Clock()
    src = tmp_path / "ailleurs" / "medias.txt"
    src.parent.mkdir()
    state.config.catalog_path = src
    (disk / "films").mkdir()
    await refresh_once(state)
    (disk / "films" / "nouveau.mkv").touch()
    shutil.copy2(FIXTURE, src)
    await refresh_once(state)
    assert deep_key("nouveau.mkv") not in state.indexes["DISK1"]
    await refresh_once(state)  # import : réindexation complète dans le même tour
    assert works(state) > 0
    assert deep_key("nouveau.mkv") in state.indexes["DISK1"]


async def test_undecodable_catalog_refused_once(setup, caplog):
    state, disk = setup
    await import_now(state, disk)
    before = works(state)
    f = disk / "medias.txt"
    f.write_bytes(b"MN ; \xff\xfe illisible\n")
    os.utime(f, (1_700_000_900, 1_700_000_900))
    with caplog.at_level(logging.WARNING, logger="rc.app"):
        for _ in range(4):
            await refresh_once(state)
    warnings = [r for r in caplog.records if "illisible" in r.getMessage()]
    assert len(warnings) == 1
    assert works(state) == before
    assert not (state.config.data_dir / "catalog" / "medias.tmp").exists()
    copy = state.config.data_dir / "catalog" / "medias.txt"
    assert copy.read_text(encoding="utf-8") == FIXTURE.read_text(encoding="utf-8")


async def test_catalog_changed_during_copy_skipped(setup, monkeypatch):
    state, disk = setup
    real_copy = app_mod._copy

    def racing_copy(src, tmp):
        real_copy(src, tmp)
        return (0, 0)  # la copie ne correspond plus à l'observation stable

    monkeypatch.setattr(app_mod, "_copy", racing_copy)
    shutil.copy2(FIXTURE, disk / "medias.txt")
    await refresh_once(state)
    await refresh_once(state)
    assert works(state) == 0
    assert not (state.config.data_dir / "catalog" / "medias.tmp").exists()
    monkeypatch.setattr(app_mod, "_copy", real_copy)
    await refresh_once(state)  # pas marqué comme refusé : nouvel essai
    assert works(state) > 0


class FakeZeroconf:
    def __init__(self):
        self.calls = []

    async def _done(self, name):
        await asyncio.sleep(0)
        self.calls.append(name)

    async def async_register_service(self, info):
        self.calls.append(("register", info.type, info.name, info.port, info.properties))
        return asyncio.ensure_future(self._done("registered"))

    async def async_unregister_service(self, info):
        return asyncio.ensure_future(self._done("unregistered"))

    async def async_close(self):
        self.calls.append("closed")


async def test_mdns_register_and_unregister_awaited():
    zc = FakeZeroconf()
    task = asyncio.create_task(mdns_announce(8765, zeroconf_factory=lambda: zc,
                                             addresses=lambda: [bytes([192, 168, 1, 23])]))
    for _ in range(5):
        await asyncio.sleep(0)
    assert zc.calls == [("register", "_rc._tcp.local.", "Remote-Control._rc._tcp.local.",
                         8765, {b"api": b"1"}), "registered"]
    task.cancel()
    await asyncio.gather(task, return_exceptions=True)
    assert zc.calls[2:] == ["unregistered", "closed"]


async def test_mdns_failure_logged(caplog):
    def broken():
        raise OSError("pas de réseau")

    with caplog.at_level(logging.ERROR, logger="rc.app"):
        await mdns_announce(8765, zeroconf_factory=broken, addresses=lambda: [])
    assert any("mDNS" in r.getMessage() for r in caplog.records)


def test_local_addresses_skip_bridges(monkeypatch):
    import ifaddr

    def adapter(name, *ips):
        return ifaddr.Adapter(name, name, [ifaddr.IP(ip, 24, name) for ip in ips])

    monkeypatch.setattr(ifaddr, "get_adapters", lambda: [
        adapter("lo", "127.0.0.1"), adapter("wlp0s20f3", "192.168.1.23", ("fe80::1", 0, 3)),
        adapter("docker0", "172.17.0.1"), adapter("br-4d8f", "172.23.0.1"),
        adapter("veth12", "10.0.0.2"), adapter("virbr0", "192.168.122.1")])
    assert app_mod.local_addresses() == [bytes([192, 168, 1, 23])]


def test_tv_refused_for_unknown_host(tmp_path):
    config = Config(data_dir=tmp_path / "data", scan_roots=[tmp_path / "vide"],
                    dev_dir_volumes=True, mdns=False, poll_interval=3600)
    app = create_app(config, player=FakePlayer())
    with TestClient(app, base_url="http://192.168.1.23:8765") as client:
        assert client.get("/tv/").status_code == 200
        r = client.get("/tv/", headers={"Host": "evil.example:8765"})
        assert r.status_code == 403


async def test_mdns_shutdown_failures_logged_debug(caplog):
    class Broken(FakeZeroconf):
        async def async_unregister_service(self, info):
            raise OSError("socket fermée")

        async def async_close(self):
            raise OSError("déjà fermé")

    task = asyncio.create_task(mdns_announce(8765, zeroconf_factory=Broken,
                                             addresses=lambda: []))
    for _ in range(5):
        await asyncio.sleep(0)
    with caplog.at_level(logging.DEBUG, logger="rc.app"):
        task.cancel()
        await asyncio.gather(task, return_exceptions=True)
    debug = [r.getMessage() for r in caplog.records if r.levelno == logging.DEBUG]
    assert any("retrait" in m for m in debug) and any("fermeture" in m for m in debug)


async def test_tmdb_failure_does_not_block_publish(setup, monkeypatch):
    state, disk = setup
    state.config.tmdb_api_key = "k"

    async def boom(*a, **k):
        raise RuntimeError("tmdb")

    monkeypatch.setattr(app_mod, "fetch_missing_posters", boom)
    events = []
    state.bus.publish = events.append
    await import_now(state, disk)
    assert any(e["type"] == "catalog" for e in events)
