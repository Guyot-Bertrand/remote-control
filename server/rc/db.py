"""Ouverture de la base SQLite et compteur de révision."""

from __future__ import annotations

import sqlite3
from pathlib import Path

SCHEMA = Path(__file__).with_name("schema.sql")
SCHEMA_VERSION = 3  # PRAGMA user_version de la base créée par schema.sql


# Colonnes ajoutées par la version 2 (table, colonne, type).
_V2_COLUMNS = (
    ("work", "medium", "TEXT"),
    ("episode", "duration_min", "INTEGER"),
    ("media_file", "audio_langs_json", "TEXT"),
    ("media_file", "subtitle_langs_json", "TEXT"),
    ("media_file", "width", "INTEGER"),
    ("media_file", "height", "INTEGER"),
    ("media_file", "duration_s", "REAL"),
)


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
        if version == 1:
            _migrate_v1_to_v2(conn)
        if version < SCHEMA_VERSION:
            conn.execute(f"PRAGMA user_version = {SCHEMA_VERSION}")
    except BaseException:
        conn.close()
        raise
    return conn


def _migrate_v1_to_v2(conn: sqlite3.Connection) -> None:
    """Ajoute les colonnes de la v2 puis demande un réimport complet du catalogue
    (le médium des œuvres n'existe pas dans une base v1)."""
    for table, column, decl in _V2_COLUMNS:
        existing = {r[1] for r in conn.execute(f"PRAGMA table_info({table})")}
        if column not in existing:
            conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} {decl}")
    conn.execute("INSERT OR REPLACE INTO meta (key, value) VALUES ('needs_reimport', '1')")


def current_revision(conn: sqlite3.Connection) -> int:
    row = conn.execute("SELECT value FROM meta WHERE key = 'revision'").fetchone()
    return int(row[0])


def next_revision(conn: sqlite3.Connection) -> int:
    conn.execute(
        "UPDATE meta SET value = CAST(value AS INTEGER) + 1 WHERE key = 'revision'"
    )
    return current_revision(conn)
