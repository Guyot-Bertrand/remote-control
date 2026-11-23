import io
import logging
from pathlib import Path

import httpx
import pytest
from PIL import Image

from rc.assets import asset_path
from rc.catalog.importer import import_catalog
from rc.catalog.parser import parse_catalog
from rc.db import open_db
from rc.tmdb import fetch_missing_posters

FIXTURE = Path(__file__).parent / "fixtures" / "medias_sample.txt"
KEY = "secretkey123"


def jpeg_bytes():
    buf = io.BytesIO()
    Image.new("RGB", (800, 1200), "red").save(buf, "JPEG")
    return buf.getvalue()


@pytest.fixture
def conn(tmp_path):
    c = open_db(tmp_path / "rc.db")
    import_catalog(c, parse_catalog(FIXTURE.read_text(encoding="utf-8")), {})
    c.execute("UPDATE work SET imdb_id = NULL, poster_asset = NULL")
    c.execute("UPDATE work SET imdb_id = 'tt0000001' WHERE id = "
              "(SELECT MIN(id) FROM work)")
    yield c
    c.close()


def client_for(handler):
    return httpx.AsyncClient(transport=httpx.MockTransport(handler), timeout=10)


class Recorder:
    def __init__(self, find=None, image=None, status=200):
        self.requests = []
        self.find = find if find is not None else {
            "movie_results": [{"poster_path": "/abc.jpg", "title": "Autre titre"}],
            "tv_results": []}
        self.image = image if image is not None else jpeg_bytes()
        self.status = status

    def __call__(self, request):
        self.requests.append(request)
        if request.url.host == "api.themoviedb.org":
            if self.status != 200:
                return httpx.Response(self.status)
            return httpx.Response(200, json=self.find)
        return httpx.Response(200, content=self.image)


def work(conn):
    return conn.execute("SELECT * FROM work WHERE imdb_id = 'tt0000001'").fetchone()


async def test_disabled_without_key(conn, tmp_path):
    rec = Recorder()
    async with client_for(rec) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", "", c) == 0
    assert rec.requests == []


async def test_fetches_and_sets_asset(conn, tmp_path):
    rec = Recorder()
    before = work(conn)
    rev0 = conn.execute("SELECT value FROM meta WHERE key='revision'").fetchone()[0]
    async with client_for(rec) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 1
    after = work(conn)
    assert after["poster_asset"] and after["rev"] > before["rev"]
    assert asset_path(tmp_path / "assets", after["poster_asset"], "thumb").is_file()
    find, image = rec.requests
    assert find.url.path == "/3/find/tt0000001"
    assert find.url.params["external_source"] == "imdb_id"
    assert find.url.params["language"] == "fr-FR"
    assert find.url.params["api_key"] == KEY
    assert str(image.url) == "https://image.tmdb.org/t/p/w780/abc.jpg"
    assert int(conn.execute("SELECT value FROM meta WHERE key='revision'")
               .fetchone()[0]) == int(rev0) + 1
    assert not list((tmp_path / "tmp").iterdir())


async def test_v4_token_uses_bearer(conn, tmp_path):
    rec = Recorder()
    token = "eyJabc.def"
    async with client_for(rec) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", token, c)
    assert rec.requests[0].headers["authorization"] == f"Bearer {token}"
    assert "api_key" not in rec.requests[0].url.params


async def test_never_retries_same_work(conn, tmp_path):
    rec = Recorder(find={"movie_results": [], "tv_results": []})
    async with client_for(rec) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
    assert len(rec.requests) == 1
    assert work(conn)["poster_asset"] is None


async def test_http_error_logged_without_key_and_marked(conn, tmp_path, caplog):
    rec = Recorder(status=500)
    with caplog.at_level(logging.WARNING):
        async with client_for(rec) as c:
            assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
            await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
    assert len(rec.requests) == 1
    assert caplog.records and KEY not in caplog.text


async def test_rate_limit_stops_without_marking(conn, tmp_path):
    conn.execute("UPDATE work SET imdb_id = 'tt0000002' WHERE id = "
                 "(SELECT MAX(id) FROM work)")
    rec = Recorder(status=429)
    async with client_for(rec) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
    assert len(rec.requests) == 1
    assert conn.execute("SELECT COUNT(*) FROM meta WHERE key LIKE 'tmdb_tried:%'"
                        ).fetchone()[0] == 0


@pytest.mark.parametrize("bad", ["/../x.jpg", "//evil.com/x.jpg", "http://evil/x.jpg",
                                 "/a/b.jpg", "x.jpg"])
async def test_rejects_suspicious_poster_path(conn, tmp_path, bad):
    rec = Recorder(find={"movie_results": [{"poster_path": bad}]})
    async with client_for(rec) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
    assert len(rec.requests) == 1


async def test_oversized_download_aborted(conn, tmp_path, monkeypatch):
    monkeypatch.setattr("rc.tmdb.MAX_POSTER_BYTES", 100)
    rec = Recorder()
    async with client_for(rec) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
    assert work(conn)["poster_asset"] is None


async def test_does_not_touch_text_fields(conn, tmp_path):
    cols = [r[1] for r in conn.execute("PRAGMA table_info(work)")
            if r[1] not in ("poster_asset", "rev")]
    sql = f"SELECT {', '.join(cols)} FROM work ORDER BY id"
    before = [tuple(r) for r in conn.execute(sql)]
    async with client_for(Recorder()) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
    assert [tuple(r) for r in conn.execute(sql)] == before
