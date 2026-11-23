import logging
import os
import shutil
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from rc.app import create_app, refresh_once
from rc.catalog.parser import parse_catalog
from rc.config import Config
from rc.db import current_revision
from rc.fake_tree import make_fake_tree

from test_session import FakePlayer

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
                    dev_dir_volumes=True, mdns=False, poll_interval=3600)
    app = create_app(config, player=FakePlayer())
    with TestClient(app) as client:
        r = client.get("/tv/")
        assert r.status_code == 200
        assert r.headers["content-type"].startswith("text/html")
        assert client.get("/api/info").json()["name"] == "Remote-Control"
