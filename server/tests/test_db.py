import argparse
import sqlite3
from pathlib import Path

import pytest

from rc import cli
from rc.db import SCHEMA_VERSION, DatabaseTooNew, open_db


def user_version(path) -> int:
    c = sqlite3.connect(path)
    try:
        return c.execute("PRAGMA user_version").fetchone()[0]
    finally:
        c.close()


def test_fresh_db_gets_schema_version(tmp_path):
    open_db(tmp_path / "rc.db").close()
    assert SCHEMA_VERSION == 4
    assert user_version(tmp_path / "rc.db") == 4
    open_db(tmp_path / "rc.db").close()  # réouverture sans changement
    assert user_version(tmp_path / "rc.db") == 4


def make_too_new(path):
    open_db(path).close()
    c = sqlite3.connect(path)
    c.execute(f"PRAGMA user_version = {SCHEMA_VERSION + 1}")
    c.close()


def test_newer_db_refused(tmp_path):
    make_too_new(tmp_path / "rc.db")
    with pytest.raises(DatabaseTooNew, match="plus récente"):
        open_db(tmp_path / "rc.db")


@pytest.mark.parametrize("command", ["serve", "import"])
def test_cli_refuses_newer_db_with_french_message(tmp_path, monkeypatch, capsys, command):
    data = tmp_path / "data"
    data.mkdir()
    make_too_new(data / "rc.db")
    cfg = tmp_path / "c.toml"
    cfg.write_text(f'data_dir = "{data}"\nmdns = false\n')
    monkeypatch.setenv("RC_CONFIG", str(cfg))
    import uvicorn

    monkeypatch.setattr(uvicorn, "run", lambda *a, **k: pytest.fail("serveur démarré"))
    src = tmp_path / "medias.txt"
    src.write_text("", encoding="utf-8")
    args = argparse.Namespace(chemin=src, force=False)
    func = cli._serve if command == "serve" else cli._import
    assert func(args) == 1
    err = capsys.readouterr().err
    assert "base de données" in err and "plus récente" in err


FIXTURES = Path(__file__).parent / "fixtures"


def make_v1(path) -> None:
    c = sqlite3.connect(path)
    c.executescript((FIXTURES / "schema_v1.sql").read_text(encoding="utf-8"))
    c.execute("INSERT INTO work_identity (id, type, key) VALUES (1, 'film', 'a')")
    c.execute("INSERT INTO work (id, type, key, title_fr, directors_json, genres_json,"
              " cast_json, tags_json, episodic, content_hash, rev)"
              " VALUES (1, 'film', 'a', 'A', '[]', '[]', '[]', '[]', 0, 'h', 1)")
    c.commit()
    c.execute("PRAGMA user_version = 1")
    c.close()


def test_fresh_database_is_v4(tmp_path):
    conn = open_db(tmp_path / "rc.db")
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 4
    assert {"ratings_json", "tmdb_url", "poster_source"} <= {
        r[1] for r in conn.execute("PRAGMA table_info(work)")}
    cols = {r[1] for r in conn.execute("PRAGMA table_info(media_file)")}
    assert {"audio_langs_json", "subtitle_langs_json", "width", "height", "duration_s"} <= cols
    assert "medium" in {r[1] for r in conn.execute("PRAGMA table_info(work)")}
    assert "duration_min" in {r[1] for r in conn.execute("PRAGMA table_info(episode)")}
    assert conn.execute("SELECT value FROM meta WHERE key='needs_reimport'").fetchone() is None
    conn.close()


def test_v1_database_migrates(tmp_path):
    make_v1(tmp_path / "rc.db")
    conn = open_db(tmp_path / "rc.db")
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 4
    assert conn.execute("SELECT medium FROM work").fetchone()[0] is None
    assert conn.execute("SELECT ratings_json FROM work").fetchone()[0] == "[]"
    assert conn.execute("SELECT value FROM meta WHERE key='needs_reimport'").fetchone()[0] == "1"
    conn.close()
    conn = open_db(tmp_path / "rc.db")  # réouverture : pas de nouvelle migration
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 4
    conn.close()


def test_row_keeps_null_json_columns_null(tmp_path):
    from rc.api.serialize import row
    conn = open_db(tmp_path / "rc.db")
    conn.execute("INSERT INTO media_file (quality, relpath, subtitles_json, rev)"
                 " VALUES ('HD', 'x.mkv', '[]', 1)")
    d = row(conn.execute("SELECT * FROM media_file").fetchone())
    assert d["audio_langs"] is None
    assert d["subtitle_langs"] is None
    assert d["subtitles"] == []
    conn.close()


def test_schema_v3_and_indexes_on_existing_db(tmp_path):
    open_db(tmp_path / "rc.db").close()
    c = sqlite3.connect(tmp_path / "rc.db")
    c.execute("DROP INDEX media_file_work")
    c.execute("PRAGMA user_version = 2")  # base v2 sans l'index de la v3
    c.close()
    conn = open_db(tmp_path / "rc.db")
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 4
    assert "media_file_work" in {r[1] for r in conn.execute("PRAGMA index_list(media_file)")}
    conn.close()


def make_v3(path) -> None:
    """Base v3 : schéma actuel sans les colonnes de la v4."""
    open_db(path).close()
    c = sqlite3.connect(path)
    for col in ("ratings_json", "tmdb_url", "poster_source"):
        c.execute(f"ALTER TABLE work DROP COLUMN {col}")
    for i, (key, asset, source) in enumerate(
            [("a", "h1", "Films/A.jpg"), ("b", "h2", "tmdb"), ("c", None, None)], 1):
        c.execute("INSERT INTO work_identity (id, type, key) VALUES (?, 'film', ?)", (i, key))
        c.execute("INSERT INTO work (id, type, key, title_fr, directors_json, genres_json,"
                  " cast_json, tags_json, episodic, poster_asset, content_hash, rev)"
                  " VALUES (?, 'film', ?, ?, '[]', '[]', '[]', '[]', 0, ?, 'h', 1)",
                  (i, key, key.upper(), asset))
        if asset:
            c.execute("INSERT INTO asset (hash, source) VALUES (?, ?)", (asset, source))
    c.commit()
    c.execute("PRAGMA user_version = 3")
    c.close()


def test_v3_database_migrates_to_v4(tmp_path):
    make_v3(tmp_path / "rc.db")
    conn = open_db(tmp_path / "rc.db")
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 4
    rows = {r["key"]: r for r in conn.execute("SELECT * FROM work")}
    assert rows["a"]["poster_source"] == "local"
    assert rows["b"]["poster_source"] == "tmdb"
    assert rows["c"]["poster_source"] is None
    assert all(r["ratings_json"] == "[]" and r["tmdb_url"] is None for r in rows.values())
    assert conn.execute("SELECT value FROM meta WHERE key='needs_reimport'").fetchone()[0] == "1"
    conn.close()
