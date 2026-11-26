CREATE TABLE IF NOT EXISTS meta (
    key TEXT PRIMARY KEY,
    value TEXT
);
INSERT OR IGNORE INTO meta (key, value) VALUES ('revision', '0');

-- Identités : jamais supprimées, un identifiant n'est jamais réattribué.
CREATE TABLE IF NOT EXISTS work_identity (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    type TEXT NOT NULL,
    key TEXT NOT NULL,
    UNIQUE (type, key)
);

CREATE TABLE IF NOT EXISTS work (
    id INTEGER PRIMARY KEY REFERENCES work_identity (id),
    type TEXT NOT NULL,
    key TEXT NOT NULL,
    title_fr TEXT NOT NULL,
    title_vo TEXT,
    title_alt TEXT,
    year INTEGER,
    duration_min INTEGER,
    episode_count TEXT,
    directors_json TEXT NOT NULL,
    genres_json TEXT NOT NULL,
    cast_json TEXT NOT NULL,
    synopsis TEXT,
    imdb_id TEXT,
    tags_json TEXT NOT NULL,
    episodic INTEGER NOT NULL,
    poster_relpath TEXT,
    poster_asset TEXT NULL,
    medium TEXT,
    content_hash TEXT NOT NULL,
    rev INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS season_identity (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    work_id INTEGER NOT NULL REFERENCES work_identity (id),
    number INTEGER NOT NULL,
    UNIQUE (work_id, number)
);

CREATE TABLE IF NOT EXISTS season (
    id INTEGER PRIMARY KEY REFERENCES season_identity (id),
    work_id INTEGER NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    number INTEGER NOT NULL,
    label TEXT,
    rev INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS season_work ON season (work_id);

CREATE TABLE IF NOT EXISTS episode_identity (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    season_id INTEGER NOT NULL REFERENCES season_identity (id),
    number INTEGER NOT NULL,
    UNIQUE (season_id, number)
);

CREATE TABLE IF NOT EXISTS episode (
    id INTEGER PRIMARY KEY REFERENCES episode_identity (id),
    season_id INTEGER NOT NULL REFERENCES season (id) ON DELETE CASCADE,
    number INTEGER NOT NULL,
    title TEXT,
    duration_min INTEGER,
    rev INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS episode_season ON episode (season_id);

CREATE TABLE IF NOT EXISTS media_file (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    work_id INTEGER NULL REFERENCES work (id) ON DELETE CASCADE,
    episode_id INTEGER NULL REFERENCES episode (id) ON DELETE CASCADE,
    quality TEXT NOT NULL,
    size TEXT,
    relpath TEXT NOT NULL,
    subtitles_json TEXT NOT NULL,
    volume_uuid TEXT NULL,
    present INTEGER NOT NULL DEFAULT 0,
    audio_langs_json TEXT,
    subtitle_langs_json TEXT,
    width INTEGER,
    height INTEGER,
    duration_s REAL,
    rev INTEGER NOT NULL,
    UNIQUE (work_id, episode_id, relpath)
);
CREATE INDEX IF NOT EXISTS media_file_episode ON media_file (episode_id);
CREATE INDEX IF NOT EXISTS media_file_work ON media_file (work_id);

CREATE TABLE IF NOT EXISTS collection (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    description TEXT,
    link TEXT,
    rev INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS work_collection (
    work_id INTEGER NOT NULL REFERENCES work (id) ON DELETE CASCADE,
    collection_id INTEGER NOT NULL REFERENCES collection (id) ON DELETE CASCADE,
    rank TEXT,
    PRIMARY KEY (work_id, collection_id)
);
CREATE INDEX IF NOT EXISTS work_collection_coll ON work_collection (collection_id);

CREATE TABLE IF NOT EXISTS volume (
    uuid TEXT PRIMARY KEY,
    label TEXT,
    mount TEXT,
    present INTEGER NOT NULL DEFAULT 0,
    rev INTEGER NOT NULL
);

-- Pas de clé étrangère : la progression survit à la suppression d'une œuvre.
CREATE TABLE IF NOT EXISTS progress (
    target_kind TEXT NOT NULL,
    target_id INTEGER NOT NULL,
    media_file_id INTEGER,
    position REAL,
    duration REAL,
    watched INTEGER NOT NULL DEFAULT 0,
    audio TEXT,
    subtitle TEXT,
    updated_at REAL,
    rev INTEGER NOT NULL,
    PRIMARY KEY (target_kind, target_id)
);

CREATE TABLE IF NOT EXISTS asset (
    hash TEXT PRIMARY KEY,
    source TEXT
);

CREATE TABLE IF NOT EXISTS probe_cache (
    media_file_id INTEGER PRIMARY KEY REFERENCES media_file (id) ON DELETE CASCADE,
    mtime REAL,
    size INTEGER,
    tracks_json TEXT
);

CREATE TABLE IF NOT EXISTS tombstone (
    kind TEXT NOT NULL,
    id INTEGER NOT NULL,
    rev INTEGER NOT NULL,
    PRIMARY KEY (kind, id)
);
