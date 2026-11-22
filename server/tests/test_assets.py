import logging
from pathlib import Path

import pytest
from PIL import Image

from rc.assets import SIZES, asset_path, ingest_posters, process_image
from rc.catalog.importer import import_catalog
from rc.catalog.parser import parse_catalog
from rc.db import open_db
from rc.fake_tree import make_fake_tree
from rc.volumes import build_index

FIXTURE = Path(__file__).parent / "fixtures" / "medias_sample.txt"


def size_of(path):
    with Image.open(path) as im:
        return im.size


def make_image(path, size, mode="RGB", fmt="JPEG"):
    Image.new(mode, size, "red").save(path, fmt)
    return path


def test_sizes(tmp_path):
    src = make_image(tmp_path / "p.jpg", (1000, 1500))
    h = process_image(src, tmp_path / "assets")
    assert len(h) == 16
    assert size_of(asset_path(tmp_path / "assets", h, "thumb")) == (300, 450)
    assert size_of(asset_path(tmp_path / "assets", h, "tv")) == (600, 900)
    assert size_of(asset_path(tmp_path / "assets", h, "orig")) == (1000, 1500)
    assert SIZES == {"thumb": 300, "tv": 600}
    assert process_image(src, tmp_path / "assets") == h


def test_small_image_not_upscaled(tmp_path):
    src = make_image(tmp_path / "p.jpg", (200, 300))
    h = process_image(src, tmp_path / "assets")
    assert size_of(asset_path(tmp_path / "assets", h, "tv")) == (200, 300)


@pytest.mark.parametrize("mode,fmt", [("RGBA", "PNG"), ("CMYK", "JPEG")])
def test_non_rgb_sources(tmp_path, mode, fmt):
    src = make_image(tmp_path / "p.img", (400, 600), mode, fmt)
    h = process_image(src, tmp_path / "assets")
    for size in ("orig", "thumb", "tv"):
        with Image.open(asset_path(tmp_path / "assets", h, size)) as im:
            assert im.format == "JPEG" and im.mode == "RGB"


@pytest.fixture
def conn(tmp_path):
    c = open_db(tmp_path / "rc.db")
    yield c
    c.close()


def setup(conn, tmp_path):
    result = parse_catalog(FIXTURE.read_text(encoding="utf-8"))
    import_catalog(conn, result, {})
    disk = tmp_path / "DISK1"
    disk.mkdir()
    make_fake_tree(result, disk)
    return {"U1": build_index(disk)}, disk


def ava(conn):
    return conn.execute(
        "SELECT * FROM work WHERE poster_relpath LIKE '%Ava%'").fetchone()


def test_ingest_sets_asset_and_bumps_rev(conn, tmp_path):
    indexes, _ = setup(conn, tmp_path)
    assets = tmp_path / "assets"
    before = ava(conn)
    assert before["poster_asset"] is None
    n = ingest_posters(conn, assets, indexes)
    assert n >= 1
    after = ava(conn)
    assert after["poster_asset"] and after["rev"] > before["rev"]
    assert asset_path(assets, after["poster_asset"], "thumb").is_file()
    assert conn.execute("SELECT source FROM asset WHERE hash = ?",
                        (after["poster_asset"],)).fetchone()
    assert ingest_posters(conn, assets, indexes) == 0


def test_unreadable_poster_skipped(conn, tmp_path, caplog):
    indexes, disk = setup(conn, tmp_path)
    for p in disk.rglob("*"):
        if p.is_file() and "Ava" in str(p) and p.suffix.lower() in (".jpg", ".jpeg", ".png"):
            p.write_bytes(b"not an image")
    with caplog.at_level(logging.WARNING, logger="rc.assets"):
        ingest_posters(conn, tmp_path / "assets", indexes)
    assert ava(conn)["poster_asset"] is None
    assert any("rc.assets" == r.name for r in caplog.records)
