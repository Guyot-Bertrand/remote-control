import logging
from pathlib import Path

import pytest
from PIL import Image

from rc.assets import SIZES, asset_path, ingest_posters, process_image
from rc.catalog.importer import import_catalog
from rc.catalog.parser import parse_catalog
from rc.db import open_db
from rc.fake_tree import make_fake_tree
from rc.volumes import build_index, normalize

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


def test_symlink_refused(conn, tmp_path, caplog):
    indexes, disk = setup(conn, tmp_path)
    row = ava(conn)
    real = make_image(tmp_path / "real.jpg", (100, 150))
    link = tmp_path / "link.jpg"
    link.symlink_to(real)
    indexes = {"U1": {normalize(row["poster_relpath"]): link}}
    with caplog.at_level(logging.WARNING, logger="rc.assets"):
        ingest_posters(conn, tmp_path / "assets", indexes)
    assert ava(conn)["poster_asset"] is None
    assert any(r.name == "rc.assets" for r in caplog.records)
    with pytest.raises(OSError):
        process_image(link, tmp_path / "assets")


def test_pixel_cap(tmp_path, monkeypatch):
    src = make_image(tmp_path / "p.jpg", (100, 150))
    monkeypatch.setattr("rc.assets.MAX_PIXELS", 1000)
    with pytest.raises(ValueError):
        process_image(src, tmp_path / "assets")


def test_oversized_refused(tmp_path, monkeypatch):
    src = make_image(tmp_path / "p.jpg", (100, 150))
    monkeypatch.setattr("rc.assets.MAX_POSTER_BYTES", 10)
    with pytest.raises(ValueError):
        process_image(src, tmp_path / "assets")


def test_disallowed_format_skipped(conn, tmp_path, caplog):
    indexes, disk = setup(conn, tmp_path)
    for p in disk.rglob("*"):
        if p.is_file() and "Ava" in str(p) and p.suffix.lower() in (".jpg", ".jpeg", ".png"):
            Image.new("RGB", (10, 10)).save(p, "GIF")
    with caplog.at_level(logging.WARNING, logger="rc.assets"):
        ingest_posters(conn, tmp_path / "assets", indexes)
    assert ava(conn)["poster_asset"] is None
    assert any(r.name == "rc.assets" for r in caplog.records)


def ava_posters(disk):
    return [p for p in disk.rglob("*") if p.is_file() and "Ava" in str(p)
            and p.suffix.lower() in (".jpg", ".jpeg", ".png")]


def test_unreadable_poster_not_reread_until_changed(conn, tmp_path, monkeypatch):
    indexes, disk = setup(conn, tmp_path)
    (poster,) = ava_posters(disk)
    poster.write_bytes(b"not an image")
    calls = []
    real = process_image

    def spy(src, assets_dir):
        calls.append(src)
        return real(src, assets_dir)

    monkeypatch.setattr("rc.assets.process_image", spy)
    failed: set = set()
    ingest_posters(conn, tmp_path / "assets", indexes, failed)
    ingest_posters(conn, tmp_path / "assets", indexes, failed)
    assert calls.count(poster) == 1
    assert ava(conn)["poster_asset"] is None
    make_image(poster, (100, 150))  # fichier corrigé : taille et date changent
    ingest_posters(conn, tmp_path / "assets", indexes, failed)
    assert calls.count(poster) == 2
    assert ava(conn)["poster_asset"] is not None


def test_asset_files_written_atomically(tmp_path, monkeypatch):
    import os

    replaced = []
    real_replace = os.replace

    def spy(src, dst):
        assert Path(src).parent == Path(dst).parent  # même dossier : renommage atomique
        replaced.append(Path(dst).name)
        real_replace(src, dst)

    monkeypatch.setattr("rc.assets.os.replace", spy)
    src = make_image(tmp_path / "p.png", (1000, 1500), fmt="PNG")
    h = process_image(src, tmp_path / "assets")
    assert sorted(replaced) == ["orig.jpg", "thumb.jpg", "tv.jpg"]
    assert sorted(p.name for p in (tmp_path / "assets" / h).iterdir()) == [
        "orig.jpg", "thumb.jpg", "tv.jpg"]


def test_interrupted_write_leaves_no_partial_asset(tmp_path, monkeypatch):
    src = make_image(tmp_path / "p.jpg", (1000, 1500))
    real_save = Image.Image.save

    def broken(self, fp, *a, **k):
        if "tv" in str(fp):
            Path(fp).write_bytes(b"partiel")
            raise OSError("disque plein")
        return real_save(self, fp, *a, **k)

    monkeypatch.setattr(Image.Image, "save", broken)
    with pytest.raises(OSError):
        process_image(src, tmp_path / "assets")
    (d,) = (tmp_path / "assets").iterdir()
    assert "tv.jpg" not in [p.name for p in d.iterdir()]
    assert all(not p.name.startswith(".") and "tmp" not in p.name for p in d.iterdir())


def test_local_poster_sets_source_local(conn, tmp_path):
    indexes, _ = setup(conn, tmp_path)
    assert ava(conn)["poster_source"] is None
    ingest_posters(conn, tmp_path / "assets", indexes)
    assert ava(conn)["poster_source"] == "local"


@pytest.mark.parametrize("source", ["frame", "tmdb"])
def test_local_poster_replaces_frame_and_tmdb(conn, tmp_path, source):
    text = FIXTURE.read_text(encoding="utf-8")
    result = parse_catalog(text)
    disk = tmp_path / "DISK1"
    disk.mkdir()
    make_fake_tree(result, disk)
    indexes = {"U1": build_index(disk)}
    without_af = text.replace("MF ; AF ; Ava [2017] [0h01].jpg\n", "")
    import_catalog(conn, parse_catalog(without_af), {})
    conn.execute("UPDATE work SET poster_asset = 'x', poster_source = ? "
                 "WHERE key = 'Ava [2017]'", (source,))
    import_catalog(conn, result, {})  # la ligne AF apparaît
    row = ava(conn)
    assert row["poster_asset"] is None and row["poster_source"] is None
    ingest_posters(conn, tmp_path / "assets", indexes)
    row = ava(conn)
    assert row["poster_asset"] not in (None, "x") and row["poster_source"] == "local"
