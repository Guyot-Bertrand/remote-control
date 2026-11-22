"""Ouverture de la base SQLite et compteur de révision."""

from __future__ import annotations

import sqlite3
from pathlib import Path

SCHEMA = Path(__file__).with_name("schema.sql")


def open_db(path: Path) -> sqlite3.Connection:
    # isolation_level=None : transactions explicites (BEGIN/COMMIT) uniquement.
    conn = sqlite3.connect(path, isolation_level=None)
    conn.row_factory = sqlite3.Row
    conn.execute("PRAGMA journal_mode=WAL")
    conn.execute("PRAGMA foreign_keys=ON")
    conn.executescript(SCHEMA.read_text(encoding="utf-8"))
    return conn


def current_revision(conn: sqlite3.Connection) -> int:
    row = conn.execute("SELECT value FROM meta WHERE key = 'revision'").fetchone()
    return int(row[0])


def next_revision(conn: sqlite3.Connection) -> int:
    conn.execute(
        "UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'"
    )
    return current_revision(conn)
