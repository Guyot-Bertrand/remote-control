import json
import os
import subprocess
import unicodedata
from pathlib import Path

import pytest

from rc.catalog.importer import import_catalog
from rc.catalog.parser import parse_catalog
from rc.db import open_db
from rc.fake_tree import make_fake_tree
from rc.volumes import (Volume, build_index, find_catalog_source, list_volumes,
                        locate_files, resolve_media_path)

FIXTURE = Path(__file__).parent / "fixtures" / "medias_sample.txt"


@pytest.fixture
def conn(tmp_path):
    c = open_db(tmp_path / "rc.db")
    yield c
    c.close()


def test_list_volumes_filters_roots():
    payload = {"filesystems": [
        {"target": "/media/x/DISK1", "uuid": "U1", "label": "DISK1"},
        {"target": "/", "uuid": "U0", "label": None},
        {"target": "/mnt/nouuid", "uuid": None, "label": None},
    ]}

    def runner(cmd, **kw):
        assert cmd[0] == "findmnt"
        return subprocess.CompletedProcess(cmd, 0, json.dumps(payload), "")

    vols = list_volumes([Path("/media"), Path("/mnt")], runner=runner)
    assert vols == [Volume("U1", "DISK1", Path("/media/x/DISK1"))]


def setup_disk(conn, tmp_path):
    result = parse_catalog(FIXTURE.read_text(encoding="utf-8"))
    import_catalog(conn, result, {})
    disk = tmp_path / "DISK1"
    disk.mkdir()
    make_fake_tree(result, disk)
    return result, Volume("U1", "DISK1", disk)


def ava_rows(conn):
    return conn.execute(
        "SELECT * FROM media_file WHERE relpath LIKE '%Ava [2017]%'").fetchall()


def test_locate_marks_present_then_absent(conn, tmp_path):
    _, vol = setup_disk(conn, tmp_path)
    assert locate_files(conn, [vol], {"U1": build_index(vol.mount)})
    rows = ava_rows(conn)
    assert rows and all(r["present"] == 1 and r["volume_uuid"] == "U1" for r in rows)
    assert not locate_files(conn, [vol], {"U1": build_index(vol.mount)})
    before = {r["id"]: r["rev"] for r in rows}
    assert locate_files(conn, [], {})
    rows = ava_rows(conn)
    assert all(r["present"] == 0 and r["volume_uuid"] == "U1" for r in rows)
    assert all(r["rev"] > before[r["id"]] for r in rows)
    vrow = conn.execute("SELECT present FROM volume WHERE uuid = 'U1'").fetchone()
    assert vrow["present"] == 0


def test_nfd_and_case_on_disk(conn, tmp_path):
    _, vol = setup_disk(conn, tmp_path)
    dirs = [p for p in vol.mount.iterdir() if p.name.lower() == "theatre"]
    assert dirs
    for f in list(dirs[0].rglob("*")):
        if f.is_file():
            new_name = unicodedata.normalize("NFD", f.name.lower())
            f.rename(f.with_name(new_name))
    dirs[0].rename(vol.mount / "theatre")
    index = build_index(vol.mount)
    locate_files(conn, [vol], {"U1": index})
    row = conn.execute(
        "SELECT id, present FROM media_file WHERE relpath LIKE 'Theatre/%'").fetchone()
    assert row["present"] == 1
    assert resolve_media_path(conn, row["id"], {"U1": index}).is_file()


def test_find_catalog_source_newest(tmp_path):
    a, b = tmp_path / "a", tmp_path / "b"
    for d in (a, b):
        d.mkdir()
        (d / "medias.txt").write_text("x")
    os.utime(a / "medias.txt", (1000, 1000))
    os.utime(b / "medias.txt", (2000, 2000))
    vols = [Volume("A", None, a), Volume("B", None, b)]
    assert find_catalog_source(vols, None) == b / "medias.txt"
    assert find_catalog_source(vols, tmp_path / "o.txt") == tmp_path / "o.txt"
    assert find_catalog_source([], None) is None
