import argparse
import sqlite3

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
    assert SCHEMA_VERSION == 1
    assert user_version(tmp_path / "rc.db") == 1
    open_db(tmp_path / "rc.db").close()  # réouverture sans changement
    assert user_version(tmp_path / "rc.db") == 1


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
