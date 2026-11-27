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
from rc.tmdb import fetch_missing_posters, fetch_missing_tmdb_links

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
    c.execute("UPDATE work SET imdb_id = NULL, poster_asset = NULL, poster_relpath = NULL")
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
    assert not (tmp_path / "tmp").exists()


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


def tried(conn) -> int:
    return conn.execute("SELECT COUNT(*) FROM meta WHERE key LIKE 'tmdb_tried:%'"
                        ).fetchone()[0]


def second_work(conn):
    conn.execute("UPDATE work SET imdb_id = 'tt0000002' WHERE id = "
                 "(SELECT MAX(id) FROM work)")


async def test_not_found_is_definitive_and_marked(conn, tmp_path, caplog):
    rec = Recorder(status=404)
    with caplog.at_level(logging.WARNING):
        async with client_for(rec) as c:
            assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
            await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
    assert len(rec.requests) == 1
    assert tried(conn) == 1
    assert KEY not in caplog.text


async def test_server_error_not_marked(conn, tmp_path, caplog):
    rec = Recorder(status=500)
    with caplog.at_level(logging.WARNING):
        async with client_for(rec) as c:
            assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
            await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
    assert len(rec.requests) == 2  # nouvel essai au tour suivant
    assert tried(conn) == 0
    assert caplog.records and KEY not in caplog.text


@pytest.mark.parametrize("status", [401, 403])
async def test_refused_key_stops_without_marking(conn, tmp_path, status, caplog):
    second_work(conn)
    rec = Recorder(status=status)
    with caplog.at_level(logging.WARNING):
        async with client_for(rec) as c:
            assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
    assert len(rec.requests) == 1  # tour interrompu
    assert tried(conn) == 0
    assert KEY not in caplog.text


async def test_network_error_stops_without_marking(conn, tmp_path):
    second_work(conn)
    requests = []

    def handler(request):
        requests.append(request)
        raise httpx.ConnectError("pas de réseau", request=request)

    async with client_for(handler) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
    assert len(requests) == 1
    assert tried(conn) == 0


async def test_image_download_network_error_not_marked(conn, tmp_path):
    rec = Recorder()

    def handler(request):
        if request.url.host == "image.tmdb.org":
            raise httpx.ReadTimeout("lent", request=request)
        return rec(request)

    async with client_for(handler) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
    assert tried(conn) == 0


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
            if r[1] not in ("poster_asset", "rev", "poster_source", "tmdb_url")]
    sql = f"SELECT {', '.join(cols)} FROM work ORDER BY id"
    before = [tuple(r) for r in conn.execute(sql)]
    async with client_for(Recorder()) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
    assert [tuple(r) for r in conn.execute(sql)] == before


async def test_corrupt_image_marked_tried_and_tmp_cleaned(conn, tmp_path):
    rec = Recorder(image=b"pas une image")
    async with client_for(rec) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
    assert len(rec.requests) == 2  # une seule tentative (find + image)
    assert work(conn)["poster_asset"] is None
    assert not (tmp_path / "tmp").exists()


def with_poster_on_volume(conn, uuid):
    wid = work(conn)["id"]
    conn.execute("UPDATE work SET poster_relpath = 'x/absente.jpg' WHERE id = ?", (wid,))
    conn.execute("INSERT INTO media_file (work_id, quality, relpath, subtitles_json, "
                 "volume_uuid, present, rev) VALUES (?, 'HD', 'f.mkv', '[]', ?, 1, 1)",
                 (wid, uuid))


async def test_unplugged_disk_is_not_missing(conn, tmp_path):
    with_poster_on_volume(conn, "U1")
    rec = Recorder()
    async with client_for(rec) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c,
                                           indexes={}) == 0
    assert rec.requests == []


async def test_present_disk_without_poster_is_missing(conn, tmp_path):
    with_poster_on_volume(conn, "U1")
    async with client_for(Recorder()) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c,
                                           indexes={"U1": {}}) == 1


async def test_api_key_never_logged_at_info(conn, tmp_path, caplog):
    from rc.cli import setup_logging
    setup_logging()
    caplog.set_level(logging.INFO)
    async with client_for(Recorder()) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
    assert KEY not in caplog.text


def test_redact_filter_masks_secrets():
    from rc.cli import RedactFilter
    rec = logging.LogRecord("httpx", logging.INFO, "", 0,
                            "GET https://x/?api_key=%s&a=1 Bearer eyJabc.d", (KEY,), None)
    RedactFilter().filter(rec)
    assert KEY not in rec.msg and "eyJabc" not in rec.msg and "a=1" in rec.msg


async def test_poster_found_stores_tmdb_url_and_source(conn, tmp_path):
    rec = Recorder(find={"movie_results": [{"id": 4242, "poster_path": "/abc.jpg"}]})
    async with client_for(rec) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
    w = work(conn)
    assert w["tmdb_url"] == "https://www.themoviedb.org/movie/4242"
    assert w["poster_source"] == "tmdb"


async def test_tv_result_gives_tv_url(conn, tmp_path):
    rec = Recorder(find={"movie_results": [],
                         "tv_results": [{"id": 77, "poster_path": "/abc.jpg"}]})
    async with client_for(rec) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
    assert work(conn)["tmdb_url"] == "https://www.themoviedb.org/tv/77"


async def test_match_without_poster_still_stores_url(conn, tmp_path):
    rec = Recorder(find={"movie_results": [{"id": 5, "poster_path": None}]})
    rev0 = work(conn)["rev"]
    async with client_for(rec) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
    w = work(conn)
    assert w["tmdb_url"] == "https://www.themoviedb.org/movie/5"
    assert w["poster_asset"] is None and w["rev"] > rev0


async def test_tmdb_replaces_frame_only_for_untried_works(conn, tmp_path):
    second_work(conn)
    conn.execute("UPDATE work SET poster_source = 'frame', poster_asset = 'f' "
                 "WHERE imdb_id IS NOT NULL")
    conn.execute("INSERT INTO asset (hash, source) VALUES ('f', 'frame')")
    conn.execute("INSERT INTO meta (key, value) SELECT 'tmdb_tried:' || id, '1' "
                 "FROM work WHERE imdb_id = 'tt0000002'")
    rec = Recorder()
    async with client_for(rec) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 1
    a = work(conn)
    b = conn.execute("SELECT * FROM work WHERE imdb_id = 'tt0000002'").fetchone()
    assert a["poster_source"] == "tmdb" and a["poster_asset"] != "f"
    assert b["poster_source"] == "frame" and b["poster_asset"] == "f"
    assert all("tt0000002" not in str(r.url) for r in rec.requests)


async def test_local_poster_never_replaced_by_tmdb(conn, tmp_path):
    conn.execute("INSERT INTO asset (hash, source) VALUES ('l', 'local')")
    conn.execute("UPDATE work SET poster_source = 'local', poster_asset = 'l' "
                 "WHERE imdb_id = 'tt0000001'")
    rec = Recorder()
    async with client_for(rec) as c:
        assert await fetch_missing_posters(conn, tmp_path / "assets", KEY, c) == 0
    assert rec.requests == []
    assert work(conn)["poster_asset"] == "l"


async def test_link_lookup_for_work_with_local_poster(conn, tmp_path):
    conn.execute("INSERT INTO asset (hash, source) VALUES ('l', 'local')")
    conn.execute("UPDATE work SET poster_source = 'local', poster_asset = 'l' "
                 "WHERE imdb_id = 'tt0000001'")
    rec = Recorder(find={"movie_results": [{"id": 9, "poster_path": "/abc.jpg"}]})
    rev0 = work(conn)["rev"]
    async with client_for(rec) as c:
        assert await fetch_missing_tmdb_links(conn, KEY, c) == 1
    w = work(conn)
    assert w["tmdb_url"] == "https://www.themoviedb.org/movie/9"
    assert w["poster_asset"] == "l" and w["rev"] > rev0
    assert len(rec.requests) == 1  # aucune image téléchargée


async def test_link_lookup_tried_once(conn, tmp_path):
    conn.execute("UPDATE work SET poster_source = 'local' WHERE imdb_id = 'tt0000001'")
    rec = Recorder(status=404)
    async with client_for(rec) as c:
        await fetch_missing_tmdb_links(conn, KEY, c)
        await fetch_missing_tmdb_links(conn, KEY, c)
    assert len(rec.requests) == 1
    assert conn.execute("SELECT COUNT(*) FROM meta WHERE key LIKE 'tmdb_link_tried:%'"
                        ).fetchone()[0] == 1


async def test_poster_pass_404_means_no_second_find_in_link_pass(conn, tmp_path):
    rec = Recorder(status=404)
    async with client_for(rec) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
        assert await fetch_missing_tmdb_links(conn, KEY, c) == 0
    assert len(rec.requests) == 1


async def test_link_pass_ignores_works_without_poster_history(conn, tmp_path):
    rec = Recorder()
    async with client_for(rec) as c:  # ni locale ni tentée par la passe des affiches
        assert await fetch_missing_tmdb_links(conn, KEY, c) == 0
    assert rec.requests == []


@pytest.mark.parametrize("status", [429, 401])
async def test_abort_is_signalled(conn, tmp_path, status):
    outcome = {}
    async with client_for(Recorder(status=status)) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c, outcome=outcome)
    assert outcome["aborted"] is True


@pytest.mark.parametrize("status,unusable", [(401, True), (403, True), (429, False),
                                             (404, False), (200, False)])
async def test_unusable_signalled_for_refused_key_only(conn, tmp_path, status, unusable):
    outcome = {}
    async with client_for(Recorder(status=status)) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c, outcome=outcome)
    assert outcome.get("unusable", False) is unusable


async def test_unusable_signalled_without_network(conn, tmp_path):
    def handler(request):
        raise httpx.ConnectError("pas de réseau", request=request)

    outcome = {}
    async with client_for(handler) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c, outcome=outcome)
    assert outcome["unusable"] is True and outcome["aborted"] is True


async def test_unchanged_url_takes_no_revision(conn, tmp_path):
    conn.execute("UPDATE work SET tmdb_url = 'https://www.themoviedb.org/movie/5' "
                 "WHERE imdb_id = 'tt0000001'")
    before = work(conn)["rev"]
    rev0 = conn.execute("SELECT value FROM meta WHERE key='revision'").fetchone()[0]
    rec = Recorder(find={"movie_results": [{"id": 5, "poster_path": None}]})
    async with client_for(rec) as c:
        await fetch_missing_posters(conn, tmp_path / "assets", KEY, c)
    assert work(conn)["rev"] == before
    assert conn.execute("SELECT value FROM meta WHERE key='revision'"
                        ).fetchone()[0] == rev0
