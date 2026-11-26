import asyncio
import os
import sqlite3
import subprocess
from pathlib import Path

import pytest

from rc import analysis
from rc.analysis import (analysis_loop, analyze_file, pending_files, recheck_candidates,
                         stale_files)
from rc.api.serialize import row
from rc.app import AppState
from rc.config import Config
from rc.db import current_revision, open_db
from rc.events import EventBus
from rc.probe import MediaTracks, Track


def tracks_en(duration=2490.5):
    return MediaTracks([Track(1, "en", None, "aac", True)], [], [], "h264", 252, 624, duration)


def bump_revision(conn):
    conn.execute("UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'")


@pytest.fixture
def conn(tmp_path):
    c = open_db(tmp_path / "rc.db")
    c.execute("INSERT INTO media_file (id, quality, relpath, subtitles_json, present, rev)"
              " VALUES (1, 'HD', 'f.mp4', '[]', 1, 0)")
    yield c
    c.close()


@pytest.fixture
def fake_probe(monkeypatch):
    monkeypatch.setattr(analysis, "cached_tracks", lambda *a, **k: tracks_en())


def test_analyze_file_writes_columns_and_bumps_revision(conn, fake_probe, tmp_path):
    rev0 = current_revision(conn)
    assert analyze_file(conn, 1, tmp_path / "f.mp4") is True
    r = conn.execute("SELECT * FROM media_file WHERE id = 1").fetchone()
    assert r["audio_langs_json"] == '["en"]' and r["width"] == 624
    assert r["height"] == 252 and r["duration_s"] == 2490.5
    assert r["rev"] == rev0 + 1 and current_revision(conn) == rev0 + 1


def test_analyze_unchanged_file_keeps_revision(conn, fake_probe, tmp_path):
    analyze_file(conn, 1, tmp_path / "f.mp4")
    rev = current_revision(conn)
    assert analyze_file(conn, 1, tmp_path / "f.mp4") is False
    assert current_revision(conn) == rev


def test_external_subs_counted(conn, fake_probe, tmp_path):
    (tmp_path / "f.mp4").touch()
    (tmp_path / "f.fr.srt").touch()
    analyze_file(conn, 1, tmp_path / "f.mp4")
    r = conn.execute("SELECT subtitle_langs_json FROM media_file").fetchone()
    assert r[0] == '["fr"]'


def test_episode_duration_from_best_file(conn, monkeypatch, tmp_path):
    conn.execute("PRAGMA foreign_keys=OFF")
    conn.execute("INSERT INTO episode (id, season_id, number, rev) VALUES (5, 1, 1, 0)")
    conn.execute("UPDATE media_file SET episode_id = 5 WHERE id = 1")
    conn.execute("INSERT INTO media_file (id, episode_id, quality, relpath, subtitles_json,"
                 " present, rev) VALUES (2, 5, 'SD', 'g.mp4', '[]', 1, 0)")
    monkeypatch.setattr(analysis, "cached_tracks", lambda *a, **k: tracks_en(2490.5))
    assert analyze_file(conn, 1, tmp_path / "f.mp4")
    ep = conn.execute("SELECT duration_min, rev FROM episode WHERE id = 5").fetchone()
    assert ep["duration_min"] == 42 and ep["rev"] == current_revision(conn)
    # un fichier moins bien classé ne touche pas l'épisode
    monkeypatch.setattr(analysis, "cached_tracks", lambda *a, **k: tracks_en(60.0))
    analyze_file(conn, 2, tmp_path / "g.mp4")
    assert conn.execute("SELECT duration_min FROM episode").fetchone()[0] == 42


def test_probe_failure_marks_file_and_is_not_retried(conn, tmp_path, monkeypatch, caplog):
    def boom(*a, **k):
        raise subprocess.CalledProcessError(1, "ffprobe")

    monkeypatch.setattr(analysis, "cached_tracks", boom)
    assert analyze_file(conn, 1, tmp_path / "f.mp4") is True
    r = conn.execute("SELECT * FROM media_file WHERE id = 1").fetchone()
    assert r["width"] == 0
    assert "f.mp4" in caplog.text
    conn.execute("UPDATE media_file SET volume_uuid = 'u'")
    monkeypatch.setattr(analysis, "resolve_media_path", lambda c, i, ix: tmp_path / "f.mp4")
    assert pending_files(conn, {"u": {}}) == []
    d = row(conn.execute("SELECT * FROM media_file WHERE id = 1").fetchone())
    assert d["width"] is None and d["height"] is None
    assert d["duration_s"] is None and d["audio_langs"] is None


def test_pending_files(conn, tmp_path, monkeypatch):
    conn.execute("UPDATE media_file SET volume_uuid = 'u'")
    p = tmp_path / "f.mp4"
    monkeypatch.setattr(analysis, "resolve_media_path", lambda c, i, ix: p)
    assert pending_files(conn, {"u": {}}) == [(1, p)]
    conn.execute("UPDATE media_file SET width = 10")
    assert pending_files(conn, {"u": {}}) == []
    conn.execute("UPDATE media_file SET width = NULL, present = 0")
    assert pending_files(conn, {"u": {}}) == []


def stat_of(p):
    st = os.stat(p)
    return st.st_mtime, st.st_size


def test_recheck_skips_files_matching_probe_cache(tmp_path):
    p = tmp_path / "f.mp4"
    p.write_bytes(b"abc")
    m, sz = stat_of(p)
    assert stale_files([(1, p, m, sz)]) == []


def test_recheck_keeps_modified_files(tmp_path):
    p = tmp_path / "f.mp4"
    p.write_bytes(b"abc")
    m, sz = stat_of(p)
    assert stale_files([(1, p, m, sz + 1)]) == [(1, p)]
    assert stale_files([(1, p, m + 1, sz)]) == [(1, p)]


def test_recheck_keeps_files_without_cache(tmp_path):
    p = tmp_path / "f.mp4"
    p.write_bytes(b"abc")
    assert stale_files([(1, p, None, None)]) == [(1, p)]


def test_recheck_keeps_files_that_cannot_be_stat(tmp_path):
    p = tmp_path / "gone.mp4"
    assert stale_files([(1, p, 1.0, 3)]) == [(1, p)]


def test_recheck_candidates_reads_cache_without_stat(conn, tmp_path, monkeypatch):
    conn.execute("UPDATE media_file SET volume_uuid = 'u'")
    p = tmp_path / "missing.mp4"
    monkeypatch.setattr(analysis, "resolve_media_path", lambda c, i, ix: p)
    assert recheck_candidates(conn, {"u": {}}) == [(1, p, None, None)]
    conn.execute("INSERT INTO probe_cache (media_file_id, mtime, size, tracks_json)"
                 " VALUES (1, 5.5, 9, '{}')")
    assert recheck_candidates(conn, {"u": {}}) == [(1, p, 5.5, 9)]
    conn.execute("UPDATE media_file SET present = 0")
    assert recheck_candidates(conn, {"u": {}}) == []


# -- boucle ----------------------------------------------------------------

class Stop(BaseException):  # n'est pas avalée par la boucle
    pass


class Clock:
    def __init__(self):
        self.t = 0.0

    def __call__(self):
        return self.t


@pytest.fixture
def state(tmp_path):
    c = open_db(tmp_path / "rc.db")
    cfg = Config(data_dir=tmp_path, poll_interval=7.0)
    st = AppState(config=cfg, conn=c, bus=EventBus(), clock=Clock())
    st.published_revision = current_revision(c)

    class PB:
        current = None

    st.playback = PB()
    yield st
    c.close()


def patch_loop(monkeypatch, state, step):
    """Trois fichiers en attente ; chaque analyse dure `step` s d'horloge et change le catalogue."""
    analyzed = []
    files = [(i, Path(f"/x/f{i}.mp4")) for i in (1, 2, 3)]
    monkeypatch.setattr(analysis, "pending_files",
                        lambda conn, ix: [f for f in files if f[0] not in analyzed])

    def fake(db_file, mf_id, path):
        analyzed.append(mf_id)
        state.clock.t += step
        other = sqlite3.connect(db_file, isolation_level=None)  # le thread a sa connexion
        bump_revision(other)
        other.close()
        return True

    monkeypatch.setattr(analysis, "_analyze_in_thread", fake)
    return analyzed


async def stop_sleep(_):
    raise Stop


async def test_loop_waits_during_playback(state, monkeypatch):
    analyzed = patch_loop(monkeypatch, state, 3.0)
    state.playback.current = {"id": 1}
    sleeps = []

    async def sleep(s):
        sleeps.append(s)
        if len(sleeps) == 3:
            raise Stop

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assert analyzed == [] and sleeps == [7.0, 7.0, 7.0]


async def test_loop_announces_at_most_every_30s(state, monkeypatch):
    analyzed = patch_loop(monkeypatch, state, 3.0)
    events = []
    state.bus.publish = events.append
    with pytest.raises(Stop):
        await analysis_loop(state, sleep=stop_sleep)
    assert analyzed == [1, 2, 3]
    assert events == [{"type": "catalog", "revision": current_revision(state.conn)}]


async def test_loop_announces_again_after_30s(state, monkeypatch):
    analyzed = patch_loop(monkeypatch, state, 20.0)
    events = []
    state.bus.publish = events.append
    with pytest.raises(Stop):
        await analysis_loop(state, sleep=stop_sleep)
    assert analyzed == [1, 2, 3]
    assert len(events) == 2  # après 40 s (2e fichier), puis à la fin de la file


async def test_loop_rechecks_after_new_full_index(state, monkeypatch):
    seen = []
    monkeypatch.setattr(analysis, "pending_files", lambda conn, ix: seen.append(False) or [])
    monkeypatch.setattr(analysis, "recheck_candidates", lambda conn, ix: [])
    monkeypatch.setattr(analysis, "stale_files", lambda c: seen.append(True) or [])
    state.last_full_index = 100.0
    sleeps = []

    async def sleep(s):
        sleeps.append(s)
        if len(sleeps) == 3:
            raise Stop
        if len(sleeps) == 2:
            state.last_full_index = 200.0

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assert seen == [True, False, True]


async def test_loop_recheck_stats_in_thread(state, monkeypatch):
    files = [(i, Path(f"/x/f{i}.mp4")) for i in (1, 2, 3)]
    cands = [(i, p, 1.0, 1) for i, p in files]
    monkeypatch.setattr(analysis, "recheck_candidates", lambda conn, ix: cands)
    def fake_stale(c):
        return [f for f in files if f[0] == 2]

    monkeypatch.setattr(analysis, "stale_files", fake_stale)
    monkeypatch.setattr(analysis, "pending_files", lambda conn, ix: [])
    threaded = []
    real_to_thread = asyncio.to_thread

    async def spy(fn, *a, **k):
        threaded.append(fn)
        return await real_to_thread(fn, *a, **k)

    monkeypatch.setattr(analysis.asyncio, "to_thread", spy)
    analyzed = []

    def fake(db_file, mf_id, path):
        analyzed.append(mf_id)
        return False

    monkeypatch.setattr(analysis, "_analyze_in_thread", fake)
    state.last_full_index = 100.0
    with pytest.raises(Stop):
        await analysis_loop(state, sleep=stop_sleep)
    assert fake_stale in threaded
    assert analyzed == [2]


async def test_loop_survives_transient_errors(state, monkeypatch, caplog):
    analyzed = patch_loop(monkeypatch, state, 3.0)
    real_pending = analysis.pending_files
    fail = {"pending": True}

    def flaky_pending(conn, ix):
        if fail.pop("pending", False):
            raise RuntimeError("liste indisponible")
        return real_pending(conn, ix)

    monkeypatch.setattr(analysis, "pending_files", flaky_pending)
    import rc.app
    real_publish = rc.app.publish_catalog
    boom = {"once": True}

    def flaky_publish(st, rev):
        if boom.pop("once", False):
            raise sqlite3.OperationalError("database is locked")
        real_publish(st, rev)

    monkeypatch.setattr(rc.app, "publish_catalog", flaky_publish)
    sleeps = []

    async def sleep(s):
        sleeps.append(s)
        if len(sleeps) == 3:
            raise Stop

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assert analyzed == [1, 2, 3]  # la boucle a continué après les deux erreurs
    assert sleeps[:2] == [7.0, 7.0]
    assert "tour d'analyse en échec" in caplog.text


# -- fichiers impossibles à terminer : la boucle doit finir par dormir -----

def add_file(state, tmp_path, monkeypatch):
    state.conn.execute("INSERT INTO media_file (id, quality, relpath, subtitles_json, present,"
                       " rev) VALUES (1, 'HD', 'f.mp4', '[]', 1, 0)")
    (tmp_path / "f.mp4").touch()
    monkeypatch.setattr(analysis, "resolve_media_path", lambda c, i, ix: tmp_path / "f.mp4")


def counting_probe(monkeypatch, result):
    calls = []

    def probe(*a, **k):
        calls.append(1)
        if len(calls) > 20:
            raise Stop  # boucle chaude : on arrête le test
        return result()

    monkeypatch.setattr(analysis, "cached_tracks", probe)
    return calls


async def test_loop_sleeps_when_probe_yields_no_width_nor_duration(state, tmp_path, monkeypatch):
    add_file(state, tmp_path, monkeypatch)
    calls = counting_probe(monkeypatch, lambda: MediaTracks(
        [Track(1, "en", None, "aac", True)], [], [], None, None, None, None))
    sleeps = []

    async def sleep(s):
        sleeps.append(s)
        raise Stop

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assert len(calls) == 1 and sleeps == [7.0]
    d = row(state.conn.execute("SELECT * FROM media_file WHERE id = 1").fetchone())
    assert d["audio_langs"] == ["en"] and d["width"] is None and d["duration_s"] is None


async def test_loop_skips_failing_file_until_next_full_index(state, tmp_path, monkeypatch,
                                                             caplog):
    add_file(state, tmp_path, monkeypatch)

    def fail():
        raise ValueError("cannot convert float NaN to integer")

    calls = counting_probe(monkeypatch, fail)
    sleeps = []

    async def sleep(s):
        sleeps.append(s)
        if len(sleeps) == 2:
            state.last_full_index = 100.0  # nouvel index complet : on réessaie
        if len(sleeps) == 3:
            raise Stop

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assert len(calls) == 2 and sleeps == [7.0, 7.0, 7.0]
    assert caplog.text.count("échec de l'analyse") == 2


def test_nan_or_infinite_duration_is_ignored(conn, monkeypatch, tmp_path):
    import json
    conn.execute("PRAGMA foreign_keys=OFF")
    conn.execute("INSERT INTO episode (id, season_id, number, rev) VALUES (5, 1, 1, 0)")
    conn.execute("UPDATE media_file SET episode_id = 5 WHERE id = 1")
    for bad in (float("nan"), float("inf"), -3.0, 0.0):
        monkeypatch.setattr(analysis, "cached_tracks", lambda *a, b=bad, **k: tracks_en(b))
        analyze_file(conn, 1, tmp_path / "f.mp4")
        r = conn.execute("SELECT duration_s, width FROM media_file").fetchone()
        assert r["duration_s"] is None and r["width"] == 624
        assert conn.execute("SELECT duration_min FROM episode").fetchone()[0] is None
        json.dumps(row(r), allow_nan=False)


async def test_loop_rechecks_playback_after_stale_files(state, monkeypatch):
    """Une lecture lancée pendant la vérification des fichiers suspend l'analyse : la
    file est gardée et reprise après la lecture."""
    files = [(i, Path(f"/x/f{i}.mp4")) for i in (1, 2)]
    monkeypatch.setattr(analysis, "recheck_candidates",
                        lambda conn, ix: [(i, p, 1.0, 1) for i, p in files])

    def fake_stale(c):
        state.playback.current = {"id": 1}  # lecture lancée pendant le thread
        return list(files)

    monkeypatch.setattr(analysis, "stale_files", fake_stale)
    monkeypatch.setattr(analysis, "pending_files", lambda conn, ix: [])
    analyzed = []
    monkeypatch.setattr(analysis, "_analyze_in_thread",
                        lambda db, mf_id, path: analyzed.append(mf_id) or False)
    state.last_full_index = 100.0
    sleeps = []
    seen = []  # fichiers analysés à chaque attente

    async def sleep(s):
        sleeps.append(s)
        seen.append(list(analyzed))
        if len(sleeps) == 2:
            state.playback.current = None  # fin de la lecture
        if len(sleeps) == 3:
            raise Stop

    with pytest.raises(Stop):
        await analysis_loop(state, sleep=sleep)
    assert sleeps[:2] == [7.0, 7.0]
    assert seen[:2] == [[], []]  # rien d'analysé pendant la lecture
    assert analyzed == [1, 2]
