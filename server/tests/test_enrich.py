import pytest

from rc.api.serialize import enrich
from rc.db import open_db


def _work(conn, wid, title, *, episodic=0, poster=None, medium=None, type_="film"):
    conn.execute("INSERT INTO work_identity (id, type, key) VALUES (?, ?, ?)",
                 (wid, type_, f"k{wid}"))
    conn.execute(
        "INSERT INTO work (id, type, key, title_fr, directors_json, genres_json, cast_json,"
        " tags_json, episodic, poster_asset, medium, content_hash, rev)"
        " VALUES (?, ?, ?, ?, '[]', '[]', '[]', '[]', ?, ?, ?, 'h', 1)",
        (wid, type_, f"k{wid}", title, episodic, poster, medium))


def _file(conn, *, work=None, episode=None, volume="U1", present=1, rel="f.mkv"):
    conn.execute(
        "INSERT INTO media_file (work_id, episode_id, quality, relpath, subtitles_json,"
        " volume_uuid, present, rev) VALUES (?, ?, '1080p', ?, '[]', ?, ?, 1)",
        (work, episode, rel, volume, present))


def _progress(conn, kind, tid, pos, dur, watched=0, at=1.0):
    conn.execute(
        "INSERT INTO progress (target_kind, target_id, position, duration, watched,"
        " updated_at, rev) VALUES (?, ?, ?, ?, ?, ?, 1)", (kind, tid, pos, dur, watched, at))


A, B, C, S1, E5, COLL = 1, 2, 3, 10, 20, 7


@pytest.fixture
def conn(tmp_path):
    c = open_db(tmp_path / "rc.db")
    c.execute("INSERT INTO volume (uuid, label, present, rev) VALUES ('U1', 'DISK1', 1, 1)")
    c.execute("INSERT INTO volume (uuid, label, present, rev) VALUES ('U2', 'VIDEOS', 0, 1)")
    _work(c, A, "Alpha", poster="pa", medium="bluray")
    _file(c, work=A, rel="a.mkv")
    _progress(c, "work", A, 2700.0, 5400.0)
    _work(c, B, "Bravo", poster="pb")
    _file(c, work=B, volume="U2", present=0, rel="b.mkv")
    _work(c, C, "Heroes", episodic=1, poster="pc", type_="serie")
    c.execute("INSERT INTO season_identity (id, work_id, number) VALUES (?, ?, 1)", (S1, C))
    c.execute("INSERT INTO season (id, work_id, number, rev) VALUES (?, ?, 1, 1)", (S1, C))
    c.execute("INSERT INTO episode_identity (id, season_id, number) VALUES (?, ?, 5)", (E5, S1))
    c.execute("INSERT INTO episode (id, season_id, number, title, rev) VALUES (?, ?, 5, 'Five', 1)",
              (E5, S1))
    _file(c, episode=E5, rel="e5.mkv")
    _progress(c, "episode", E5, 600.0, 3000.0)
    c.execute("INSERT INTO collection (id, name, rev) VALUES (?, 'Saga', 1)", (COLL,))
    for w, rank in ((A, "2"), (B, "10"), (C, None)):
        c.execute("INSERT INTO work_collection (work_id, collection_id, rank) VALUES (?, ?, ?)",
                  (w, COLL, rank))
    return c


def test_enrich_keeps_order_and_drops_unknown(conn):
    out = enrich(conn, [{"kind": "work", "id": B}, {"kind": "work", "id": 999},
                        {"kind": "work", "id": A}], None)
    assert [o["id"] for o in out] == [B, A]


def test_enrich_work_fields(conn):
    o = enrich(conn, [{"kind": "work", "id": A}], None)[0]
    assert o["kind"] == "work" and o["title_fr"] == "Alpha" and o["poster"] == "pa"
    assert o["playable"] is True and o["watched"] is False
    assert o["progress_ratio"] == 0.5 and o["remaining_min"] == 45
    assert o["medium"] == "bluray" and o["absent_volume"] is None and o["rank"] is None
    assert o["directors"] == [] and o["title_alt"] is None


def test_enrich_work_directors_and_alt_title(conn):
    conn.execute("UPDATE work SET directors_json = ?, title_alt = ? WHERE id = ?",
                 ('["Ridley Scott", "Jim Doe"]', "Alien", B))
    o = enrich(conn, [{"kind": "work", "id": B}], None)[0]
    assert o["directors"] == ["Ridley Scott", "Jim Doe"] and o["title_alt"] == "Alien"


def test_enrich_absent_volume(conn):
    o = enrich(conn, [{"kind": "work", "id": B}], None)[0]
    assert o["playable"] is False and o["absent_volume"] == "VIDEOS"
    assert o["progress_ratio"] is None and o["remaining_min"] is None


def test_enrich_rank_only_with_collection(conn):
    refs = [{"kind": "work", "id": A}, {"kind": "work", "id": C}]
    assert [o["rank"] for o in enrich(conn, refs, COLL)] == ["2", None]
    assert [o["rank"] for o in enrich(conn, refs, None)] == [None, None]


def test_enrich_episode(conn):
    o = enrich(conn, [{"kind": "episode", "id": E5}], None)[0]
    assert (o["work_title"], o["season_number"], o["number"]) == ("Heroes", 1, 5)
    assert o["poster"] == "pc" and o["work_id"] == C and o["season_id"] == S1
    assert o["progress_ratio"] == 0.2 and o["remaining_min"] == 40


def test_enrich_episodic_work_uses_latest_episode(conn):
    o = enrich(conn, [{"kind": "work", "id": C}], None)[0]
    assert o["playable"] is True and o["progress_ratio"] == 0.2 and o["remaining_min"] == 40


def test_enrich_collection(conn):
    for i in range(4, 7):
        _work(conn, i, f"W{i}", poster=f"p{i}")
        conn.execute("INSERT INTO work_collection (work_id, collection_id, rank) VALUES (?, ?, ?)",
                     (i, COLL, str(i)))
    o = enrich(conn, [{"kind": "collection", "id": COLL}], None)[0]
    assert o["name"] == "Saga" and o["count"] == 6 and o["playable_count"] == 2
    assert o["posters"] == ["pa", "p4", "p5"]
