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
    assert SCHEMA_VERSION == 2
    assert user_version(tmp_path / "rc.db") == 2
    open_db(tmp_path / "rc.db").close()  # réouverture sans changement
    assert user_version(tmp_path / "rc.db") == 2


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


def test_fresh_database_is_v2(tmp_path):
    conn = open_db(tmp_path / "rc.db")
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 2
    cols = {r[1] for r in conn.execute("PRAGMA table_info(media_file)")}
    assert {"audio_langs_json", "subtitle_langs_json", "width", "height", "duration_s"} <= cols
    assert "medium" in {r[1] for r in conn.execute("PRAGMA table_info(work)")}
    assert "duration_min" in {r[1] for r in conn.execute("PRAGMA table_info(episode)")}
    assert conn.execute("SELECT value FROM meta WHERE key='needs_reimport'").fetchone() is None
    conn.close()


def test_v1_database_migrates(tmp_path):
    make_v1(tmp_path / "rc.db")
    conn = open_db(tmp_path / "rc.db")
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 2
    assert conn.execute("SELECT medium FROM work").fetchone()[0] is None
    assert conn.execute("SELECT value FROM meta WHERE key='needs_reimport'").fetchone()[0] == "1"
    conn.close()
    conn = open_db(tmp_path / "rc.db")  # réouverture : pas de nouvelle migration
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 2
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
