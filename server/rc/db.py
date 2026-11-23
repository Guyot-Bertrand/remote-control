"""Ouverture de la base SQLite et compteur de révision."""

from __future__ import annotations

import sqlite3
from pathlib import Path

SCHEMA = Path(__file__).with_name("schema.sql")
SCHEMA_VERSION = 1  # PRAGMA user_version de la base créée par schema.sql


class DatabaseTooNew(Exception):
    """Base créée par une version plus récente du serveur."""


def open_db(path: Path, check_same_thread: bool = True) -> sqlite3.Connection:
    # isolation_level=None : transactions explicites (BEGIN/COMMIT) uniquement.
    # check_same_thread=False : pour les tests (TestClient sert l'appli dans un autre thread).
    conn = sqlite3.connect(path, isolation_level=None, check_same_thread=check_same_thread)
    try:
        version = conn.execute("PRAGMA user_version").fetchone()[0]
        if version > SCHEMA_VERSION:
            raise DatabaseTooNew(
                f"la base de données {path} est plus récente que ce serveur (schéma "
                f"{version}, connu : {SCHEMA_VERSION}) : mettez rc à jour")
        conn.row_factory = sqlite3.Row
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA foreign_keys=ON")
        conn.executescript(SCHEMA.read_text(encoding="utf-8"))
        if version < SCHEMA_VERSION:
            conn.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
    except BaseException:
        conn.close()
        raise
    return conn


def current_revision(conn: sqlite3.Connection) -> int:
    row = conn.execute("SELECT value FROM meta WHERE key = 'revision'").fetchone()
    return int(row[0])


def next_revision(conn: sqlite3.Connection) -> int:
    conn.execute(
        "UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'"
    )
    return current_revision(conn)
