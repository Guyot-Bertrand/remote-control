from pathlib import Path

import pytest

from rc.catalog.importer import ImportRefused, import_catalog, load_aliases
from rc.catalog.parser import parse_catalog
from rc.db import current_revision, open_db

FIXTURE = Path(__file__).parent / "fixtures" / "medias_sample.txt"
TEXT = FIXTURE.read_text(encoding="utf-8")
AGATHA_OLD = "Agatha Cristie - Dix petits negres [2015]"
AGATHA_NEW = "Agatha Christie - Dix petits negres [2015]"


@pytest.fixture
def conn(tmp_path):
    c = open_db(tmp_path / "rc.db")
    yield c
    c.close()


def parse(text=TEXT):
    return parse_catalog(text)


def work_id(conn, key, type_="film"):
    row = conn.execute(
        "SELECT id FROM work_identity WHERE type = ? AND key = ?", (type_, key)
    ).fetchone()
    return row["id"] if row else None


def season_ids(conn, wid):
    rows = conn.execute(
        "SELECT number, id FROM season WHERE work_id = ? ORDER BY number", (wid,)
    )
    return {r["number"]: r["id"] for r in rows}


def count(conn, table):
    return conn.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]


def test_first_import(conn):
    assert current_revision(conn) == 0
    report = import_catalog(conn, parse(), {})
    assert report.added == 7
    assert report.revision == 1
    assert current_revision(conn) == 1
    ava_id = work_id(conn, "Ava [2017]")
    row = conn.execute("SELECT * FROM work WHERE id = ?", (ava_id,)).fetchone()
    assert row["title_fr"] == "Ava"
    assert row["poster_relpath"] == "Films/A/Ava [2017] [0h01].jpg"
    files = conn.execute(
        "SELECT quality FROM media_file WHERE work_id = ? ORDER BY quality", (ava_id,)
    ).fetchall()
    assert [f["quality"] for f in files] == ["FHD", "HD"]


def test_reimport_same_is_noop(conn):
    import_catalog(conn, parse(), {})
    report = import_catalog(conn, parse(), {})
    assert (report.added, report.updated, report.deleted) == (0, 0, 0)
    assert report.revision == 1
    assert current_revision(conn) == 1


def test_ids_stable_across_reimport(conn):
    import_catalog(conn, parse(), {})
    ava_id = work_id(conn, "Ava [2017]")
    btl_id = work_id(conn, "Back to Life [2019]", "serie")
    seasons = season_ids(conn, btl_id)
    files = count(conn, "media_file")
    modified = TEXT.replace("Ridley Scott", "Ridley Scott, Autre")
    report = import_catalog(conn, parse(modified), {})
    assert (report.added, report.updated, report.deleted) == (0, 1, 0)
    assert report.revision == 2
    assert work_id(conn, "Ava [2017]") == ava_id
    assert season_ids(conn, btl_id) == seasons
    assert count(conn, "media_file") == files
    ava_rev = conn.execute("SELECT rev FROM work WHERE id = ?", (ava_id,)).fetchone()[0]
    assert ava_rev == 1


def test_duplicate_key_two_ids(conn):
    import_catalog(conn, parse(), {})
    film = work_id(conn, "Le prenom [2012]", "film")
    theatre = work_id(conn, "Le prenom [2012]", "theatre")
    assert film is not None and theatre is not None
    assert film != theatre


def test_removed_work_tombstoned_progress_kept(conn):
    import_catalog(conn, parse(), {})
    ava_id = work_id(conn, "Ava [2017]")
    conn.execute(
        "INSERT INTO progress (target_kind, target_id, position, duration, watched, "
        "updated_at, rev) VALUES ('work', ?, 120.0, 5940.0, 0, 0, 1)",
        (ava_id,),
    )
    result = parse()
    result.works = [w for w in result.works if w.key != "Ava [2017]"]
    report = import_catalog(conn, result, {})
    assert report.deleted == 1
    assert report.revision == 2
    tomb = conn.execute(
        "SELECT rev FROM tombstone WHERE kind = 'work' AND id = ?", (ava_id,)
    ).fetchone()
    assert tomb["rev"] == 2
    assert conn.execute("SELECT 1 FROM work WHERE id = ?", (ava_id,)).fetchone() is None
    assert count(conn, "progress") == 1
    # Collection only referenced by Ava disappears too.
    assert (
        conn.execute(
            "SELECT 1 FROM collection WHERE name LIKE 'Les 1000%'"
        ).fetchone()
        is None
    )


def test_alias_keeps_id(conn, tmp_path):
    import_catalog(conn, parse(), {})
    old_id = work_id(conn, AGATHA_OLD, "serie")
    alias_file = tmp_path / "aliases.txt"
    alias_file.write_text(
        f"# renommages\n\n{AGATHA_OLD} => {AGATHA_NEW}\n", encoding="utf-8"
    )
    aliases = load_aliases(alias_file)
    assert aliases == {AGATHA_OLD: AGATHA_NEW}
    renamed = TEXT.replace(AGATHA_OLD, AGATHA_NEW)
    import_catalog(conn, parse(renamed), aliases)
    row = conn.execute(
        "SELECT key FROM work WHERE id = ?", (old_id,)
    ).fetchone()
    assert row["key"] == AGATHA_NEW


def test_without_alias_new_id(conn):
    import_catalog(conn, parse(), {})
    old_id = work_id(conn, AGATHA_OLD, "serie")
    report = import_catalog(conn, parse(TEXT.replace(AGATHA_OLD, AGATHA_NEW)), {})
    assert (report.added, report.deleted) == (1, 1)
    new_id = work_id(conn, AGATHA_NEW, "serie")
    assert new_id is not None and new_id != old_id


def test_refuses_empty(conn):
    import_catalog(conn, parse(), {})
    with pytest.raises(ImportRefused, match="catalogue vide"):
        import_catalog(conn, parse(""), {}, force=True)
    assert current_revision(conn) == 1
    assert count(conn, "work") == 7


def test_refuses_mass_deletion(conn):
    import_catalog(conn, parse(), {})
    result = parse()
    result.works = result.works[:3]
    with pytest.raises(ImportRefused, match="suppression massive"):
        import_catalog(conn, result, {})
    assert current_revision(conn) == 1
    assert count(conn, "work") == 7
    report = import_catalog(conn, result, {}, force=True)
    assert report.deleted == 4
    assert count(conn, "work") == 3


def test_media_file_keeps_id_and_locator_state(conn):
    import_catalog(conn, parse(), {})
    ava_id = work_id(conn, "Ava [2017]")
    conn.execute("UPDATE work SET poster_asset = 'abc' WHERE id = ?", (ava_id,))
    conn.execute(
        "UPDATE media_file SET volume_uuid = 'vol-1', present = 1 WHERE work_id = ?",
        (ava_id,),
    )
    before = conn.execute(
        "SELECT id, relpath FROM media_file WHERE work_id = ? ORDER BY id", (ava_id,)
    ).fetchall()
    modified = TEXT.replace("; 3.8G ;", "; 3.9G ;")
    report = import_catalog(conn, parse(modified), {})
    assert report.updated == 1
    rows = conn.execute(
        "SELECT id, relpath, size, volume_uuid, present, rev FROM media_file "
        "WHERE work_id = ? ORDER BY id",
        (ava_id,),
    ).fetchall()
    assert [(r["id"], r["relpath"]) for r in rows] == [tuple(b) for b in before]
    assert all(r["volume_uuid"] == "vol-1" and r["present"] == 1 for r in rows)
    assert sorted((r["size"], r["rev"]) for r in rows) == [("1.6G", 1), ("3.9G", 2)]
    poster = conn.execute(
        "SELECT poster_asset FROM work WHERE id = ?", (ava_id,)
    ).fetchone()[0]
    assert poster == "abc"


def test_removed_season_tombstoned(conn):
    import_catalog(conn, parse(), {})
    btl_id = work_id(conn, "Back to Life [2019]", "serie")
    s2 = season_ids(conn, btl_id)[2]
    ep = conn.execute("SELECT id FROM episode WHERE season_id = ?", (s2,)).fetchone()[0]
    text = TEXT.replace(
        "MD ; 2 ; Saison 02 ; Saison 2\n"
        "MF ; VI ; FHD ; 384M ; S02E01 [0h27,1920x1080,16x9,EN].mp4 ; *.1.fr.vtt\n",
        "",
    )
    report = import_catalog(conn, parse(text), {})
    assert report.updated == 1
    kinds = {
        (r["kind"], r["id"])
        for r in conn.execute("SELECT kind, id FROM tombstone WHERE rev = 2")
    }
    assert ("season", s2) in kinds
    assert ("episode", ep) in kinds
    assert sum(k == "media_file" for k, _ in kinds) == 1
    assert list(season_ids(conn, btl_id)) == [1]
