import io
import sqlite3
import subprocess
from pathlib import Path

import pytest
from PIL import Image

from rc import analysis, frames
from rc.analysis import analysis_loop
from rc.app import AppState
from rc.config import Config
from rc.db import current_revision, open_db
from rc.events import EventBus
from rc.frames import extract_frame, extract_one, frame_candidates, frame_source, frame_time


def jpeg_bytes(color=(200, 30, 30)):
    buf = io.BytesIO()
    Image.new("RGB", (64, 36), color).save(buf, "JPEG")
    return buf.getvalue()


@pytest.fixture(autouse=True)
def ffmpeg_found(monkeypatch):
    """ffmpeg et nice « installés » : les tests ne dépendent pas de la machine."""
    monkeypatch.setattr(frames.shutil, "which", lambda name: f"/usr/bin/{name}")


@pytest.fixture
def conn(tmp_path):
    c = open_db(tmp_path / "rc.db")
    yield c
    c.close()


def add_work(conn, wid, *, imdb=None, episodic=False, poster_relpath=None,
             poster_asset=None, poster_source=None):
    conn.execute("INSERT INTO work_identity (id, type, key) VALUES (?, 'film', ?)",
                 (wid, f"k{wid}"))
    conn.execute(
        "INSERT INTO work (id, type, key, title_fr, directors_json, genres_json, cast_json,"
        " imdb_id, tags_json, episodic, poster_relpath, poster_asset, poster_source,"
        " content_hash, rev) VALUES (?, 'film', ?, ?, '[]', '[]', '[]', ?, '[]', ?, ?, ?, ?,"
        " 'h', 0)",
        (wid, f"k{wid}", f"Œuvre {wid}", imdb, int(episodic), poster_relpath,
         poster_asset, poster_source))


def add_file(conn, fid, *, work_id=None, episode_id=None, quality="HD", present=1,
             duration=None, width=None):
    conn.execute(
        "INSERT INTO media_file (id, work_id, episode_id, quality, relpath, subtitles_json,"
        " volume_uuid, present, width, duration_s, rev) VALUES (?, ?, ?, ?, ?, '[]', 'u', ?,"
        " ?, ?, 0)",
        (fid, work_id, episode_id, quality, f"f{fid}.mkv", present, width, duration))


def add_episode(conn, work_id, season_no, ep_no, eid):
    sid = work_id * 100 + season_no
    if conn.execute("SELECT 1 FROM season WHERE id = ?", (sid,)).fetchone() is None:
        conn.execute("INSERT INTO season_identity (id, work_id, number) VALUES (?, ?, ?)",
                     (sid, work_id, season_no))
        conn.execute("INSERT INTO season (id, work_id, number, rev) VALUES (?, ?, ?, 0)",
                     (sid, work_id, season_no))
    conn.execute("INSERT INTO episode_identity (id, season_id, number) VALUES (?, ?, ?)",
                 (eid, sid, ep_no))
    conn.execute("INSERT INTO episode (id, season_id, number, rev) VALUES (?, ?, ?, 0)",
                 (eid, sid, ep_no))


def indexes_for(tmp_path, *fids):
    """Index factice : chaque fichier `f<id>.mkv` existe sous `tmp_path`."""
    idx = {}
    for fid in fids:
        p = tmp_path / f"f{fid}.mkv"
        p.touch()
        idx[f"f{fid}.mkv"] = p
    return {"u": idx}


def test_frame_time_rules():
    assert frame_time(5400.0) == 540.0
    assert frame_time(None) == 300.0
    assert frame_time(0) == 300.0
    assert frame_time(120.0) == 12.0


def test_candidates_respect_priority(conn):
    add_work(conn, 1, poster_asset="a" * 16, poster_source="local")
    add_work(conn, 2, poster_asset="b" * 16, poster_source="tmdb")
    add_work(conn, 3)                                   # sans imdb → candidate
    add_work(conn, 4, imdb="tt0000004")                 # clé, jamais essayée → exclue
    add_work(conn, 5, imdb="tt0000005")                 # clé, essayée → candidate
    conn.execute("INSERT INTO meta (key, value) VALUES ('tmdb_tried:5', '1')")
    add_work(conn, 6)                                   # fichier non analysé → exclue
    add_work(conn, 7)                                   # non jouable → exclue
    add_work(conn, 8, poster_relpath="AF/x.jpg")        # AF déclarée (disque absent)
    for wid in (1, 2, 3, 4, 5, 8):
        add_file(conn, wid, work_id=wid, duration=100.0)
    add_file(conn, 6, work_id=6)
    add_file(conn, 7, work_id=7, present=0, duration=100.0)
    assert frame_candidates(conn, tmdb_enabled=True) == [3, 5]
    assert frame_candidates(conn, tmdb_enabled=False) == [3, 4, 5]  # imdb sans clé


def test_candidate_accepts_failed_analysis(conn):
    add_work(conn, 1)
    add_file(conn, 1, work_id=1, width=0)  # analyse en échec : marquée, sans durée
    assert frame_candidates(conn, tmdb_enabled=False) == [1]


def test_candidates_check_chosen_file_only(conn):
    """Le fichier choisi (meilleure version présente) doit être analysé."""
    add_work(conn, 1)
    add_file(conn, 1, work_id=1, quality="FHD")           # choisi, pas analysé
    add_file(conn, 2, work_id=1, quality="SD", duration=10.0)
    assert frame_candidates(conn, tmdb_enabled=False) == []


def test_frame_source_film_best_present_version(conn, tmp_path):
    add_work(conn, 1)
    add_file(conn, 1, work_id=1, quality="FHD", present=0, duration=1.0)
    add_file(conn, 2, work_id=1, quality="SD", duration=2.0)
    add_file(conn, 3, work_id=1, quality="HD", duration=3.0)
    idx = indexes_for(tmp_path, 1, 2, 3)
    assert frame_source(conn, 1, idx) == (tmp_path / "f3.mkv", 3.0)


def test_frame_source_series_first_present_episode(conn, tmp_path):
    add_work(conn, 1, episodic=True)
    add_episode(conn, 1, 2, 1, 21)   # S02E01, créé avant
    add_episode(conn, 1, 1, 2, 12)   # S01E02
    add_episode(conn, 1, 1, 1, 11)   # S01E01, absent
    add_file(conn, 1, episode_id=11, present=0, duration=1.0)
    add_file(conn, 2, episode_id=21, duration=2.0)
    add_file(conn, 3, episode_id=12, quality="SD", duration=3.0)
    add_file(conn, 4, episode_id=12, quality="HD", duration=4.0)
    idx = indexes_for(tmp_path, 1, 2, 3, 4)
    assert frame_source(conn, 1, idx) == (tmp_path / "f4.mkv", 4.0)
    assert frame_candidates(conn, tmdb_enabled=False) == [1]


def test_frame_source_unresolved_file(conn, tmp_path):
    add_work(conn, 1)
    add_file(conn, 1, work_id=1, duration=1.0)
    assert frame_source(conn, 1, {"u": {}}) is None


def test_extract_frame_command(tmp_path, monkeypatch):
    calls = []

    def runner(cmd, **kw):
        calls.append((cmd, kw))
        return subprocess.CompletedProcess(cmd, 0)

    monkeypatch.setattr(frames.shutil, "which", lambda name: f"/usr/bin/{name}")
    extract_frame(Path("/v/film.mkv"), 540.0, tmp_path / "frame.jpg", runner=runner)
    cmd, kw = calls[0]
    assert cmd == ["nice", "-n", "10", "/usr/bin/ffmpeg", "-v", "error", "-y", "-threads", "1",
                   "-protocol_whitelist", "file",
                   "-format_whitelist", "matroska,webm,mov,mp4,m4a,3gp,3g2,mj2,avi,mpegts,"
                   "mpeg,flv,asf,ogg,rm,dv,nut",
                   "-ss", "540.0", "-i", "file:/v/film.mkv",
                   "-frames:v", "1", str(tmp_path / "frame.jpg")]
    assert kw["timeout"] == 60 and kw["check"] is True
    monkeypatch.setattr(frames.shutil, "which",  # sans nice
                        lambda name: "/opt/ffmpeg" if name == "ffmpeg" else None)
    extract_frame(Path("/v/film.mkv"), 1.0, tmp_path / "frame.jpg", runner=runner)
    assert calls[1][0][0] == "/opt/ffmpeg"


def test_extract_frame_ffmpeg_missing_while_nice_present(tmp_path, monkeypatch):
    # Avec nice, ffmpeg absent fait sortir nice en 127 (CalledProcessError) : jamais
    # FileNotFoundError. L'absence se voit donc avant de lancer quoi que ce soit.
    calls = []

    def runner(cmd, **kw):
        calls.append(cmd)
        raise subprocess.CalledProcessError(127, cmd)

    monkeypatch.setattr(frames.shutil, "which",
                        lambda name: "/usr/bin/nice" if name == "nice" else None)
    with pytest.raises(frames.FfmpegMissing):
        extract_frame(Path("/v/film.mkv"), 1.0, tmp_path / "frame.jpg", runner=runner)
    assert calls == []


def test_extract_frame_relative_input_made_absolute(tmp_path, monkeypatch):
    calls = []
    monkeypatch.chdir(tmp_path)
    extract_frame(Path("-x.mkv"), 1.0, tmp_path / "f.jpg",
                  runner=lambda cmd, **kw: calls.append(cmd))
    assert calls[0][calls[0].index("-i") + 1] == f"file:{tmp_path}/-x.mkv"


def writing_runner(calls, write_when=lambda at: True):
    def runner(cmd, **kw):
        at = float(cmd[cmd.index("-ss") + 1])
        calls.append(at)
        if write_when(at):
            Path(cmd[-1]).write_bytes(jpeg_bytes())
        return subprocess.CompletedProcess(cmd, 0)
    return runner


def test_extract_one_writes_frame_poster(conn, tmp_path, monkeypatch):
    add_work(conn, 1)
    add_file(conn, 1, work_id=1, duration=5400.0)
    calls = []
    monkeypatch.setattr(frames, "_runner", writing_runner(calls))
    rev0 = current_revision(conn)
    assets = tmp_path / "assets"
    assert extract_one(conn, assets, indexes_for(tmp_path, 1), set(), False) is True
    w = conn.execute("SELECT poster_asset, poster_source, rev FROM work").fetchone()
    assert w["poster_source"] == "frame" and w["poster_asset"]
    assert w["rev"] == rev0 + 1 == current_revision(conn)
    assert (assets / w["poster_asset"] / "tv.jpg").is_file()
    assert conn.execute("SELECT source FROM asset").fetchone()[0] == "frame"
    assert calls == [540.0]
    assert not (tmp_path / "tmp").exists() or not any((tmp_path / "tmp").iterdir())
    assert frame_candidates(conn, False) == []
    assert extract_one(conn, assets, indexes_for(tmp_path, 1), set(), False) is False


def test_short_file_without_duration_retries_at_zero(conn, tmp_path, monkeypatch):
    add_work(conn, 1)
    add_file(conn, 1, work_id=1, width=0)
    calls = []
    monkeypatch.setattr(frames, "_runner", writing_runner(calls, lambda at: at == 0))
    assert extract_one(conn, tmp_path / "assets", indexes_for(tmp_path, 1), set(), False)
    assert calls == [300.0, 0.0]
    assert conn.execute("SELECT poster_source FROM work").fetchone()[0] == "frame"


def test_extraction_failure_is_skipped_until_full_index(conn, tmp_path, monkeypatch, caplog):
    add_work(conn, 1)
    add_work(conn, 2)
    add_file(conn, 1, work_id=1, duration=100.0)
    add_file(conn, 2, work_id=2, duration=100.0)
    idx = indexes_for(tmp_path, 1, 2)

    def broken(cmd, **kw):
        raise subprocess.CalledProcessError(1, cmd, stderr=b"x" * 300 + b" moov atom not found")

    monkeypatch.setattr(frames, "_runner", broken)
    failed: set[int] = set()
    rev0 = current_revision(conn)
    assert extract_one(conn, tmp_path / "assets", idx, failed, False) is False
    assert failed == {1} and "moov atom not found" in caplog.text
    assert "x" * 201 not in caplog.text  # fin de stderr seulement (200 caractères)
    assert current_revision(conn) == rev0
    calls = []
    monkeypatch.setattr(frames, "_runner", writing_runner(calls))
    assert extract_one(conn, tmp_path / "assets", idx, failed, False) is True
    rows = conn.execute("SELECT id, poster_source FROM work ORDER BY id").fetchall()
    assert [tuple(r) for r in rows] == [(1, None), (2, "frame")]


def test_missing_ffmpeg_raises_without_marking_works(conn, tmp_path, monkeypatch):
    for wid in (1, 2):
        add_work(conn, wid)
        add_file(conn, wid, work_id=wid, duration=100.0)
    calls = []

    def missing(cmd, **kw):
        calls.append(cmd)
        raise FileNotFoundError("ffmpeg")

    monkeypatch.setattr(frames, "_runner", missing)
    failed: set[int] = set()
    with pytest.raises(frames.FfmpegMissing):
        extract_one(conn, tmp_path / "assets", indexes_for(tmp_path, 1, 2), failed, False)
    assert failed == set() and len(calls) == 1


def test_extract_one_stops_at_first_usable_candidate(conn, tmp_path, monkeypatch):
    for wid in (1, 2, 3):
        add_work(conn, wid)
        add_file(conn, wid, work_id=wid, duration=100.0)
    looked = []
    real = frames._chosen_file
    monkeypatch.setattr(frames, "_chosen_file",
                        lambda c, w, e: looked.append(w) or real(c, w, e))
    monkeypatch.setattr(frames, "_runner", writing_runner([]))
    assert extract_one(conn, tmp_path / "assets", indexes_for(tmp_path, 1, 2, 3), set(),
                       False)
    assert 3 not in looked  # la liste entière n'est pas construite


def test_orphan_frame_removed_when_poster_arrived_meanwhile(conn, tmp_path, monkeypatch):
    add_work(conn, 1)
    add_file(conn, 1, work_id=1, duration=100.0)
    assets = tmp_path / "assets"
    real_grab = frames._grab

    def grab_then_tmdb(path, duration, assets_dir):
        h = real_grab(path, duration, assets_dir)
        conn.execute("UPDATE work SET poster_asset = 'f' || 'ffffffffffffff',"
                     " poster_source = 'tmdb'")  # TMDB est passé pendant l'extraction
        return h

    monkeypatch.setattr(frames, "_grab", grab_then_tmdb)
    monkeypatch.setattr(frames, "_runner", writing_runner([]))
    rev0 = current_revision(conn)
    assert extract_one(conn, assets, indexes_for(tmp_path, 1), set(), False) is False
    assert current_revision(conn) == rev0
    assert conn.execute("SELECT poster_source FROM work").fetchone()[0] == "tmdb"
    assert list(assets.iterdir()) == []  # fichiers de l'image retirés


def test_frame_kept_when_hash_referenced(conn, tmp_path, monkeypatch):
    add_work(conn, 1)
    add_file(conn, 1, work_id=1, duration=100.0)
    assets = tmp_path / "assets"
    real_grab = frames._grab

    def grab_then_local(path, duration, assets_dir):
        h = real_grab(path, duration, assets_dir)
        conn.execute("INSERT INTO asset (hash, source) VALUES (?, 'AF/x.jpg')", (h,))
        conn.execute("UPDATE work SET poster_asset = ?, poster_source = 'local'", (h,))
        return h

    monkeypatch.setattr(frames, "_grab", grab_then_local)
    monkeypatch.setattr(frames, "_runner", writing_runner([]))
    assert extract_one(conn, assets, indexes_for(tmp_path, 1), set(), False) is False
    assert len(list(assets.iterdir())) == 1  # partagée : gardée


def test_unreadable_image_marks_failed(conn, tmp_path, monkeypatch):
    add_work(conn, 1)
    add_file(conn, 1, work_id=1, duration=100.0)

    def garbage(cmd, **kw):
        Path(cmd[-1]).write_bytes(b"pas une image")
        return subprocess.CompletedProcess(cmd, 0)

    monkeypatch.setattr(frames, "_runner", garbage)
    failed: set[int] = set()
    assert extract_one(conn, tmp_path / "assets", indexes_for(tmp_path, 1), failed,
                       False) is False
    assert failed == {1}


def test_ffmpeg_error_code_marks_failed(conn, tmp_path, monkeypatch):
    add_work(conn, 1)
    add_file(conn, 1, work_id=1, duration=100.0)

    def err(cmd, **kw):
        raise subprocess.CalledProcessError(1, cmd)

    monkeypatch.setattr(frames, "_runner", err)
    failed: set[int] = set()
    assert not extract_one(conn, tmp_path / "assets", indexes_for(tmp_path, 1), failed,
                           False)
    assert failed == {1}


# -- boucle ----------------------------------------------------------------

class Stop(BaseException):
    pass


@pytest.fixture
def state(tmp_path):
    c = open_db(tmp_path / "rc.db")
    cfg = Config(data_dir=tmp_path, poll_interval=7.0)
    st = AppState(config=cfg, conn=c, bus=EventBus(), clock=lambda: 0.0)
    st.published_revision = current_revision(c)

    class PB:
        current = None

    st.playback = PB()
    yield st
    c.close()


async def test_loop_extracts_only_when_queue_empty_and_not_playing(state, monkeypatch,
                                                                   tmp_path):
    files = [(1, Path("/x/f1.mp4"))]
    order = []
    monkeypatch.setattr(analysis, "pending_files",
                        lambda conn, ix: [f for f in files if ("analyse", f[0]) not in order])
    monkeypatch.setattr(analysis, "_analyze_in_thread",
                        lambda db, mf_id, path: order.append(("analyse", mf_id)) or False)
    wrote = iter([True, False, False])

    def fake_extract(db_file, assets_dir, indexes, failed, tmdb_enabled):
        order.append(("image", tmdb_enabled, assets_dir))
        other = sqlite3.connect(db_file, isolation_level=None)
        other.execute("UPDATE meta SET value = CAST(value AS INTEGER) + 1"
                      " WHERE key = 'revision'")
        other.close()
        return next(wrote)

    monkeypatch.setattr(analysis, "_extract_in_thread", fake_extract)
    events = []
    state.bus.publish = events.append
    sleeps = []

    async def sleep(s):
        sleeps.append(s)
        if len(sleeps) == 1:
            state.playback.current = {"id": 1}  # lecture : aucune extraction
        if len(sleeps) == 3:
            state.playback.current = None
        if len(sleeps) == 4:
            raise Stop

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assets = tmp_path / "assets"
    # analyse d'abord ; puis une image écrite (tour suivant sans attente), puis rien → attente
    assert order == [("analyse", 1), ("image", False, assets), ("image", False, assets),
                     ("image", False, assets)]
    assert sleeps == [7.0, 7.0, 7.0, 7.0]
    assert events[-1] == {"type": "catalog", "revision": current_revision(state.conn)}


async def test_loop_passes_tmdb_enabled_and_clears_failures(state, monkeypatch):
    state.config.tmdb_api_key = "k"
    monkeypatch.setattr(analysis, "pending_files", lambda conn, ix: [])
    monkeypatch.setattr(analysis, "recheck_candidates", lambda conn, ix: [])
    monkeypatch.setattr(analysis, "stale_files", lambda c: [])
    seen = []

    def fake_extract(db_file, assets_dir, indexes, failed, tmdb_enabled):
        seen.append((tmdb_enabled, set(failed)))
        if not failed:
            failed.add(1)  # un échec : l'œuvre suivante est essayée sans attendre
        return False

    monkeypatch.setattr(analysis, "_extract_in_thread", fake_extract)
    sleeps = []

    async def sleep(s):
        sleeps.append(s)
        if len(sleeps) == 2:
            state.last_full_index = 100.0
        if len(sleeps) == 3:
            raise Stop

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assert seen == [(True, set()), (True, {1}), (True, {1}), (True, set()), (True, {1})]
    assert sleeps == [7.0, 7.0, 7.0]


async def test_loop_many_failures_do_not_starve_later_work(state, monkeypatch, tmp_path):
    """Plus de 20 œuvres en échec permanent : la suivante est atteinte dans le même cycle,
    sans attendre une période après chaque échec."""
    monkeypatch.setattr(analysis, "pending_files", lambda conn, ix: [])
    for wid in range(1, 27):
        add_work(state.conn, wid)
        add_file(state.conn, wid, work_id=wid, duration=100.0)
    state.indexes = indexes_for(tmp_path, *range(1, 27))
    calls = []

    def runner(cmd, **kw):
        src = cmd[cmd.index("-i") + 1]
        calls.append(src)
        if not src.endswith("/f26.mkv"):
            raise subprocess.CalledProcessError(1, cmd)
        Path(cmd[-1]).write_bytes(jpeg_bytes())
        return subprocess.CompletedProcess(cmd, 0)

    monkeypatch.setattr(frames, "_runner", runner)
    sleeps = []

    async def sleep(s):
        sleeps.append(s)
        raise Stop

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assert len(calls) == 26 and sleeps == [7.0]
    rows = state.conn.execute("SELECT id FROM work WHERE poster_source = 'frame'").fetchall()
    assert [r[0] for r in rows] == [26]


async def test_loop_disables_tmdb_wait_when_tmdb_unusable(state, monkeypatch):
    state.config.tmdb_api_key = "k"
    state.tmdb_unusable = True  # clé refusée ou réseau absent au dernier tour TMDB
    monkeypatch.setattr(analysis, "pending_files", lambda conn, ix: [])
    seen = []
    monkeypatch.setattr(analysis, "_extract_in_thread",
                        lambda db, a, ix, failed, tmdb: seen.append(tmdb) or False)

    async def sleep(s):
        state.tmdb_unusable = False
        if len(seen) == 2:
            raise Stop

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assert seen == [False, True]


async def test_loop_no_extraction_if_playback_starts_during_listing(state, monkeypatch):
    def pending(conn, ix):
        state.playback.current = {"id": 1}
        return []

    monkeypatch.setattr(analysis, "pending_files", pending)
    calls = []
    monkeypatch.setattr(analysis, "_extract_in_thread", lambda *a: calls.append(a) or True)

    async def sleep(s):
        raise Stop

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assert calls == []


async def test_loop_missing_ffmpeg_logged_once_until_full_index(state, monkeypatch, caplog):
    monkeypatch.setattr(analysis, "pending_files", lambda conn, ix: [])
    monkeypatch.setattr(analysis, "recheck_candidates", lambda conn, ix: [])
    monkeypatch.setattr(analysis, "stale_files", lambda c: [])
    calls = []

    def missing(*a):
        calls.append(1)
        raise frames.FfmpegMissing("ffmpeg")

    monkeypatch.setattr(analysis, "_extract_in_thread", missing)
    sleeps = []

    async def sleep(s):
        sleeps.append(s)
        if len(sleeps) == 3:
            state.last_full_index = 100.0
        if len(sleeps) == 4:
            raise Stop

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assert len(calls) == 2 and sleeps == [7.0] * 4  # 1er tour, puis après l'index complet
    assert caplog.text.count("ffmpeg introuvable") == 2
