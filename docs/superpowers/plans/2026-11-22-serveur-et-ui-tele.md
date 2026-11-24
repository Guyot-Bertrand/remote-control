# Serveur `rc-server` + UI télé provisoire — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Un service Python qui importe `medias.txt`, retrouve les fichiers sur les disques externes, pilote mpv, expose l'API de synchronisation et de commande, et sert une UI télé provisoire. Le tout doit être testable sur un PC Linux ordinaire, sans le mini-PC.

**Architecture:** Un package `rc` (FastAPI + SQLite stdlib) découpé par responsabilité : `catalog/` (analyse et import), `volumes.py` (disques), `assets.py` (affiches), `probe.py` (pistes), `player/` (mpv et session de lecture), `api/` (HTTP et WebSocket). L'UI télé est un client web passif dans `tv-ui/`, servi par le même processus. L'appli Android et l'installation sur le mini-PC (kiosque) font l'objet de plans séparés.

**Tech Stack:** Python ≥ 3.11, uv, FastAPI, uvicorn, sqlite3 (stdlib), Pillow, zeroconf, httpx, pytest, pytest-asyncio. Outils système : mpv, ffprobe, findmnt, vainfo.

**Spec:** `docs/superpowers/specs/2026-11-22-remote-control-design.md`

## Global Constraints

- Tous les textes visibles par l'utilisateur sont **en français**.
- `medias.txt` est en **lecture seule**. Le système ne l'écrit jamais.
- Identifiant stable d'une œuvre = entier attribué une seule fois au couple **(type, clé `MN`)**, jamais réattribué.
- QR-codes : `rc://w/<id>` (œuvre), `rc://s/<id>` (saison).
- Qualités lisibles, de la meilleure à la moins bonne : `UHD > 2HD > FHD > HD > SD > BD`. Non lisibles : `TS ?? NO DVD BR DVR DVT DVS AD`, et toute ligne `MF ; VI` sans nom de fichier.
- Position de lecture enregistrée **toutes les 10 s**, ainsi qu'à la pause et à l'arrêt. **Vu** = position ≥ **90 %** de la durée ; la position de reprise est alors remise à 0.
- Vignettes : **300 px** de large (`thumb`), **600 px** (`tv`), et l'original (`orig`).
- mpv : `--hwdec=auto-safe --alang=fr,fre,fra --slang=fr,fre,fra`, plein écran.
- UI télé conçue pour **1920×1080**, sans `backdrop-filter`, animations limitées à `transform`/`opacity`.
- Port par défaut **8765**. Service mDNS **`_rc._tcp.local.`**.
- Réseau local, **sans authentification**.
- TMDB est **désactivé par défaut** : il ne fournit jamais de texte, seulement une affiche manquante.

## Review Focus

1. **Disque débranché pendant la lecture** → mpv s'arrête en erreur. La dernière position doit être enregistrée et la télé affiche « Lecture interrompue : fichier inaccessible ». Testé en Task 9.
2. **`medias.txt` copié à moitié ou vidé au moment de l'import** → la base existante ne doit pas être effacée : refus si 0 œuvre, ou si plus de 50 % des œuvres disparaîtraient. Testé en Task 4. L'attente d'un fichier stable est testée en Task 11.
3. **Noms de fichiers avec accents, apostrophes, `[ ] , ?`, en Unicode décomposé (NFD) ou dans une autre casse sur disque exFAT/NTFS** → le fichier doit être retrouvé. Testé en Task 5.
4. **Réimport après modification du catalogue** (œuvre supprimée, clé corrigée avec ou sans alias) → les identifiants des œuvres restantes ne bougent pas, la suppression apparaît dans `/api/sync`, la progression est conservée via l'alias. Testé en Task 4.
5. **Demande de lecture alors qu'une lecture est en cours** (depuis un autre téléphone, ou un double appui) → la lecture en cours est arrêtée proprement, sa position enregistrée, puis la nouvelle démarre. Testé en Task 9.

---

## Structure des fichiers

```
server/
  pyproject.toml
  rc/
    __init__.py
    config.py            # Config (TOML + valeurs par défaut)
    db.py                # connexion, schéma, révisions
    schema.sql
    catalog/
      model.py           # dataclasses du catalogue analysé
      parser.py          # medias.txt → ParseResult
      paths.py           # règle chemin relatif / épisodes (module isolé, spec §6.3)
      importer.py        # ParseResult → SQLite (ids stables, alias, tombstones)
    volumes.py           # disques montés, index des fichiers, localisation
    fake_tree.py         # arborescence factice pour dev/tests
    assets.py            # affiches redimensionnées
    tmdb.py              # secours affiche (désactivé par défaut)
    probe.py             # ffprobe + sous-titres externes + sélection par défaut
    events.py            # EventBus
    player/
      base.py            # Player (Protocol), PlayerState, SubSel
      mpv.py             # MpvClient (IPC) + MpvPlayer
      session.py         # PlaybackService + TvController
    api/
      routes.py          # REST
      ws.py              # WebSocket /api/events
    app.py               # create_app(), tâches de fond, mDNS
    doctor.py            # rc doctor
    cli.py               # rc serve | import | fake-tree | doctor
  tests/
    fixtures/medias_sample.txt
    fake_mpv.py
    test_*.py
tv-ui/
  index.html  app.js  style.css
```

---

### Task 1: Squelette du projet et configuration

**Files:**
- Create: `server/pyproject.toml`, `server/rc/__init__.py`, `server/rc/config.py`
- Test: `server/tests/test_config.py`

**Interfaces:**
- Produces: `@dataclass Config(data_dir: Path, port: int = 8765, scan_roots: list[Path] = [/media, /mnt, /run/media], catalog_path: Path | None = None, mpv_path: str = "mpv", mpv_extra_args: list[str] = [], tmdb_api_key: str = "", poll_interval: float = 30.0)`. Également `load_config(path: Path | None) -> Config`. Le fichier de configuration est lu dans cet ordre : l'argument `path`, puis la variable `RC_CONFIG`, puis `~/.config/rc/config.toml`. Un fichier absent donne les valeurs par défaut. `data_dir` vaut `~/.local/share/rc` par défaut.

- [ ] **Step 1:** créer `pyproject.toml`. Il contient le projet `rc-server`, le script `rc = "rc.cli:main"`, les dépendances `fastapi`, `uvicorn[standard]`, `pillow`, `zeroconf` et `httpx`, et le groupe dev `pytest` et `pytest-asyncio`, avec `asyncio_mode = "auto"`. Lancer `cd server && uv sync`.
- [ ] **Step 2: Test qui échoue.** `test_defaults_when_no_file` : `load_config(tmp/"absent.toml").port == 8765` et `scan_roots == [Path("/media"), Path("/mnt"), Path("/run/media")]`. Puis `test_toml_overrides` : un fichier avec `port = 9000` et `tmdb_api_key = "k"` est bien pris en compte. Puis `test_rc_config_env` : `RC_CONFIG` est utilisée quand `path` vaut None.
- [ ] **Step 3:** `uv run pytest tests/test_config.py -v` → échec (ImportError).
- [ ] **Step 4:** implémenter `rc/config.py` avec `tomllib`.
- [ ] **Step 5:** relancer → PASS.
- [ ] **Step 6:** commit `feat(server): squelette et configuration`. Ajouter `server/.venv/` et `__pycache__/` au `.gitignore` racine.

---

### Task 2: Analyseur — œuvres, métadonnées, collections

**Files:**
- Create: `server/rc/catalog/__init__.py`, `server/rc/catalog/model.py`, `server/rc/catalog/parser.py`, `server/tests/fixtures/medias_sample.txt`
- Test: `server/tests/test_parser_works.py`

**Interfaces:**
- Produces (dans `model.py`) :
  - `SubtitleRef(filename: str, lang: str | None)`
  - `VideoFile(quality: str, size: str | None, filename: str | None, rel_dir: tuple[str, ...], subtitles: list[SubtitleRef], playable: bool)`
  - `PosterRef(filename: str, rel_dir: tuple[str, ...])`
  - `ParsedEpisode(season: int, number: int, title: str | None, videos: list[VideoFile])`
  - `ParsedSeason(number: int, label: str)`
  - `CollectionRef(name: str, rank: str | None)`
  - `ParsedWork(type: str, key: str, title_fr: str, title_vo: str | None, title_alt: str | None, year: int | None, duration_min: int | None, episode_count: str | None, directors: list[str], genres: list[str], cast: list[str], synopsis: str | None, imdb_id: str | None, tags: list[str], collections: list[CollectionRef], poster: PosterRef | None, videos: list[VideoFile], seasons: list[ParsedSeason], episodes: list[ParsedEpisode], episodic: bool, line_no: int)`
  - `ParsedGroup(name: str, description: str | None, link: str | None)`
  - `ParseResult(works: list[ParsedWork], groups: list[ParsedGroup], warnings: list[str])`
  - Dans `parser.py` : `parse_catalog(text: str) -> ParseResult`

Règles de cette tâche :
- Les champs sont séparés par `" ; "`, puis débarrassés de leurs espaces. Les lignes vides et les lignes commençant par `#` sont ignorées.
- Codes connus : `GB TG GN GD GL GM GC GS MN MI MC MP MD MF MQ MG`. Tout autre code produit un avertissement `"ligne N : code inconnu XX"` et ne bloque rien.
- **`MN`** : le type est mis en minuscules (`Film` → `film`). Un titre VO valant `*` donne `None`. Le titre alternatif est le 6ᵉ champ, s'il existe.
- **`MI`** : `1h39` → `duration_min = 99`. Une valeur `a/b` (par exemple `12/12`) va dans `episode_count`. L'année est convertie en entier, sinon reprise du motif `[YYYY]` de la clé. Réalisateurs et genres sont séparés par des virgules. Les champs peuvent manquer (MI à 4 champs).
- **`MC`** : liste séparée par des virgules. **`MP`** : `synopsis`.
- **`MQ ; IM`** ou toute URL `imdb.com/title/(tt\d+)` d'une ligne `MQ` → `imdb_id`.
- **`MF ; TG`** : les jetons `[XX]` vont dans `tags`.
- **`MG ; a ; b`** → `CollectionRef(name=b, rank=a)`. **`MG ; b`** → `CollectionRef(name=b, rank=None)`.
- Un bloc **`GN`** ouvre un `ParsedGroup`. `GD` remplit la description (la valeur `...` est ignorée), `GL` remplit le lien. `GM` est ignoré : l'appartenance aux collections vient de `MG`.
- `MD` et `MF ; VI/AF` sont traités en Task 3. Dans cette tâche, ils sont seulement acceptés sans avertissement.

- [ ] **Step 1: écrire la fixture** `tests/fixtures/medias_sample.txt` (contenu exact ci-dessous, utilisé aussi par les Tasks 3 à 5) :

```
# commentaire
GB ; VER ; 1.11 ; 2024-08-29 ; Tag '[FR]' désormais obligatoire
GN ; Alien [1979-]
GD ; ...
GL ; https://fr.wikipedia.org/wiki/Alien_(s%C3%A9rie_de_films)
GM ; 1979 ; Alien, le huitième passager

MD ; 0 ; Films
MD ; 1 ; A
MN ; film ; Ava [2017] ; Ava ; *
MI ; 1h39 ; 2017 ; Léa Mysius ; comédie dramatique
MC ; Juan Cano, Noée Abita, Laure Calamy
MP ; Ava, 13 ans, est en vacances au bord de l'océan...
MF ; AF ; Ava [2017] [0h01].jpg
MF ; VI ; FHD ; 3.8G ; Ava [2017] [1h45,1920x1038,16x9,FR,sEN].mp4 ; *.1.fr.srt
MF ; VI ; HD ; 1.6G ; Ava [2017] [1h39,1280x720,16x9,FR].mp4 ; *.1.fr.vtt
MF ; TG ; [FR] [ST*] [RU]
MQ ; IM ; 6.7 ; https://www.imdb.com/title/tt6194530/
MG ; 2017 ; Les 1000 plus grands films de tous les temps [2020]
MN ; film ; Alien, le huitieme passager [1979] ; Alien, le huitième passager ; Alien ; L'Étranger
MI ; 1h57 ; 1979 ; Ridley Scott ; Science-fiction, Horreur
MF ; VI ; DVD
MG ; Alien [1979-]
MD ; 1 ; L
MN ; film ; Le prenom [2012] ; Le Prénom ; *
MI ; 1h49 ; 2012 ; Alexandre de La Patellière, Matthieu Delaporte ; comédie
MF ; VI ; HD ; 1.1G ; Le prenom [2012] [1h49,1280x720,16x9,FR].mp4
MD ; 0 ; Theatre
MN ; theatre ; Le prenom [2012] ; Le Prénom ; *
MI ; 1h50 ; 2012 ; Bernard Murat ;
MF ; VI ; SD ; 700M ; Le prenom [2012] [1h50,720x576,4x3,FR].mp4
MD ; 0 ; Series
MN ; serie ; Back to Life [2019] ; Back to Life ; *
MI ; 12/12 ; 2019 ; Laura Solon, Daisy Haggard ; comédie
MD ; 1 ; *
MD ; 2 ; Saison 01 ; Saison 1
MF ; VI ; FHD ; 383M ; S01E01 [0h27,1920x1080,16x9,EN].mp4 ; *.1.fr.vtt
MF ; VI ; FHD ; 429M ; S01E02 [0h24,1920x1080,16x9,EN].mp4 ; *.1.fr.vtt
MD ; 2 ; Saison 02 ; Saison 2
MF ; VI ; FHD ; 384M ; S02E01 [0h27,1920x1080,16x9,EN].mp4 ; *.1.fr.vtt
MD ; 1 ;
MF ; TG ; [FR-] [VO]
MN ; serie ; Agatha Cristie - Dix petits negres [2015] ; Agatha Christie : Dix Petits Nègres ; And Then There Were None
MD ; 1 ; *
MF ; VI ; HD ; ? ; Dix petits negres - 1 [0h51,1280x720,16x9,FR].mp4
MF ; VI ; HD ; ? ; Dix petits negres - 2 [0h54,1280x720,16x9,FR].mp4
MD ; 1 ;
MD ; 0 ; Docs
MD ; 1 ; Inventions
MN ; doc ; Le Fabuleux Destin des Inventions [2002] ; Le Fabuleux Destin des Inventions ; *
MI ; 16/16 ; 2002 ; Axel Engstfeld ; technologie
MD ; 2 ; *
MD ; 3 ; Saison 01 ; Saison 1
MF ; VI ; BD ; 202M ; S01E01 - Le Telegraphe Transatlantique [0h52,610x360,16x9,FR].mp4
MF ; VI ; BD ; 171M ; S01E02 - Et la longitude fut [0h51,640x360,16x9,FR].mp4
MD ; 2 ;
ZZ ; ligne inconnue
```

- [ ] **Step 2: tests qui échouent** (`test_parser_works.py`) :
  - `test_counts` : 7 œuvres, et exactement 1 avertissement contenant `"ZZ"`.
  - `test_ava_metadata` : `type == "film"`, `title_vo is None`, `year == 2017`, `duration_min == 99`, `directors == ["Léa Mysius"]`, `cast[1] == "Noée Abita"`, `imdb_id == "tt6194530"`, `tags == ["[FR]", "[ST*]", "[RU]"]`, `collections == [CollectionRef("Les 1000 plus grands films de tous les temps [2020]", "2017")]`.
  - `test_alien_alt_title_and_rankless_group` : `title_vo == "Alien"`, `title_alt == "L'Étranger"`, `collections == [CollectionRef("Alien [1979-]", None)]`.
  - `test_duplicate_key_distinct_types` : il existe deux œuvres de clé `"Le prenom [2012]"`, de types `{"film", "theatre"}`.
  - `test_series_episode_count` : pour Back to Life, `episode_count == "12/12"` et `duration_min is None`.
  - `test_groups` : `groups == [ParsedGroup("Alien [1979-]", None, "https://fr.wikipedia.org/wiki/Alien_(s%C3%A9rie_de_films)")]`.
- [ ] **Step 3:** `uv run pytest tests/test_parser_works.py -v` → FAIL.
- [ ] **Step 4:** implémenter `model.py` et `parser.py` (une boucle sur les lignes, une œuvre courante, un groupe courant).
- [ ] **Step 5:** relancer → PASS.
- [ ] **Step 6:** commit `feat(catalog): analyse des œuvres et collections`.

---

### Task 3: Analyseur — arborescence, fichiers, affiches, épisodes

**Files:**
- Create: `server/rc/catalog/paths.py`
- Modify: `server/rc/catalog/parser.py`
- Test: `server/tests/test_parser_files.py`

**Interfaces:**
- Consumes: le modèle de la Task 2.
- Produces (`paths.py`, **le seul endroit** où vivent les règles de nommage, spec §6.3) :
  - `resolve_dir(name: str, work_key: str | None) -> str` : renvoie la clé de l'œuvre si `name == "*"`, sinon `name`.
  - `season_number(dir_name: str, label: str | None) -> int | None` : motif `Saison\s*0*(\d+)` cherché dans le libellé, puis dans le nom de dossier.
  - `episode_info(filename: str) -> tuple[int | None, int | None, str | None]` : motif `S(\d{2})E(\d{2,3})(?: - (.+?))?\s*\[` → (saison, épisode, titre).
  - `base_name(filename: str) -> str` : la partie avant le premier `" ["`.
  - `subtitle_filename(video_filename: str, ref: str) -> str` : `*` est remplacé par le nom du fichier vidéo sans son extension.
  - `subtitle_lang(filename: str) -> str | None` : avant-dernier jeton séparé par des points, `fre`/`fra` normalisés en `fr`.
  - `relpath(rel_dir: tuple[str, ...], filename: str) -> str` : jointure par `/`.
  - `QUALITY_ORDER = ["UHD", "2HD", "FHD", "HD", "SD", "BD"]`

Règles :
- **Pile de dossiers.** `MD ; n ; nom` remplace la pile par `pile[:n] + [resolve_dir(nom)]`. `MD ; n ;` (champ vide) la tronque à `pile[:n]`. Le 4ᵉ champ éventuel est le libellé.
- Un `MD` dont `season_number` n'est pas `None` ouvre une saison de l'œuvre courante, avec ce libellé (`ParsedSeason`). Un `MD` de niveau inférieur ou égal au niveau de cette saison la referme.
- Chaque `MF ; VI` et chaque `MF ; AF` mémorise une copie de la pile courante dans `rel_dir`.
- `playable` vaut vrai si le nom de fichier est présent et que la qualité appartient à `QUALITY_ORDER`.
- **Œuvre épisodique** si l'une de ces conditions est vraie : `type == "serie"`, une saison a été ouverte, un fichier correspond au motif `SxxEyy`, ou au moins 2 vidéos lisibles ont des `base_name` différents.
- **Épisodes.** Pour une œuvre épisodique, chaque vidéo lisible devient un épisode `(saison, numéro)`, où la saison est la saison courante, sinon celle du motif `SxxEyy`, sinon 0 avec le libellé `"Épisodes"`. Sans motif, le numéro suit l'ordre d'apparition dans la saison. Deux vidéos qui donnent le même couple `(saison, numéro)` sont deux versions du même épisode. Pour une œuvre non épisodique, les vidéos restent dans `ParsedWork.videos`.

- [ ] **Step 1: tests qui échouent** :
  - `test_ava_two_versions_not_episodic` : `episodic is False`, 2 vidéos, `videos[0].rel_dir == ("Films", "A")`, `videos[0].subtitles == [SubtitleRef("Ava [2017] [1h45,1920x1038,16x9,FR,sEN].1.fr.srt", "fr")]`, `poster == PosterRef("Ava [2017] [0h01].jpg", ("Films", "A"))`.
  - `test_alien_dvd_not_playable` : 1 vidéo, `playable is False`, `filename is None`.
  - `test_theatre_dir` : la vidéo du `Le prenom [2012]` de type theatre a `rel_dir == ("Theatre",)`.
  - `test_series_seasons` : Back to Life a `seasons == [ParsedSeason(1, "Saison 1"), ParsedSeason(2, "Saison 2")]` et 3 épisodes. Le `rel_dir` de l'épisode (2, 1) vaut `("Series", "Back to Life [2019]", "Saison 02")`.
  - `test_parts_without_pattern` : Dix petits nègres est épisodique, avec des épisodes `(0, 1)` et `(0, 2)`, une saison `ParsedSeason(0, "Épisodes")`, et `rel_dir == ("Series", "Agatha Cristie - Dix petits negres [2015]")`.
  - `test_doc_level3_season` : l'épisode (1, 2) a le titre `"Et la longitude fut"` et `rel_dir == ("Docs", "Inventions", "Le Fabuleux Destin des Inventions [2002]", "Saison 01")`.
  - Tests unitaires de `paths.py` : `episode_info("S01E02 - Et la longitude fut [0h51,…].mp4") == (1, 2, "Et la longitude fut")`, `subtitle_lang("x.9.de.vtt") == "de"`, `base_name("Ava [2017] [1h45,…].mp4") == "Ava"`.
- [ ] **Step 2:** `uv run pytest tests/test_parser_files.py -v` → FAIL.
- [ ] **Step 3:** implémenter `paths.py` et compléter `parser.py`.
- [ ] **Step 4:** relancer, ainsi que `tests/test_parser_works.py` → PASS.
- [ ] **Step 5: vérification sur les données réelles.** Lancer `uv run python -c "from rc.catalog.parser import parse_catalog; r=parse_catalog(open('../docs/data/medias.txt').read()); print(len(r.works), len(r.warnings)); print(r.warnings[:20])"`. Attendu : environ 3 990 œuvres. Examiner les avertissements : s'ils révèlent une règle manquante, l'ajouter avec un test.
- [ ] **Step 6:** commit `feat(catalog): arborescence, fichiers et épisodes`.

---

### Task 4: Base SQLite et import idempotent

**Files:**
- Create: `server/rc/schema.sql`, `server/rc/db.py`, `server/rc/catalog/importer.py`
- Test: `server/tests/test_importer.py`

**Interfaces:**
- Consumes: `ParseResult` (Tasks 2 et 3).
- Produces :
  - `open_db(path: Path) -> sqlite3.Connection` : applique `schema.sql`, active `PRAGMA journal_mode=WAL` et `foreign_keys=ON`, `row_factory = sqlite3.Row`.
  - `next_revision(conn) -> int` : incrémente `meta.revision` et renvoie la nouvelle valeur.
  - `current_revision(conn) -> int`
  - `load_aliases(path: Path) -> dict[str, str]` : lignes `ancienne clé => nouvelle clé`, `#` pour les commentaires.
  - `import_catalog(conn, result: ParseResult, aliases: dict[str, str], force: bool = False) -> ImportReport(added: int, updated: int, deleted: int, revision: int)`
  - `class ImportRefused(Exception)`

**Schéma** (`schema.sql`). Chaque table synchronisée a une colonne `rev INTEGER NOT NULL` :
- `meta(key TEXT PK, value TEXT)`
- `work_identity(id INTEGER PK, type TEXT, key TEXT, UNIQUE(type, key))` : jamais supprimée.
- `work(id PK → work_identity, type, key, title_fr, title_vo, title_alt, year, duration_min, episode_count, directors_json, genres_json, cast_json, synopsis, imdb_id, tags_json, episodic INT, poster_relpath, poster_asset TEXT NULL, content_hash, rev)`
- `season_identity(id PK, work_id, number, UNIQUE(work_id, number))` et `season(id PK, work_id, number, label, rev)`
- `episode_identity(id PK, season_id, number, UNIQUE)` et `episode(id PK, season_id, number, title, rev)`
- `media_file(id PK, work_id NULL, episode_id NULL, quality, size, relpath, subtitles_json, volume_uuid NULL, present INT DEFAULT 0, rev, UNIQUE(work_id, episode_id, relpath))`
- `collection(id PK, name UNIQUE, description, link, rev)` et `work_collection(work_id, collection_id, rank, PRIMARY KEY(work_id, collection_id))`
- `volume(uuid PK, label, mount, present INT, rev)`
- `progress(target_kind TEXT, target_id INT, media_file_id, position REAL, duration REAL, watched INT, audio TEXT, subtitle TEXT, updated_at REAL, rev, PRIMARY KEY(target_kind, target_id))`
- `asset(hash PK, source TEXT)`
- `probe_cache(media_file_id PK, mtime, size, tracks_json)`
- `tombstone(kind TEXT, id INT, rev INT, PRIMARY KEY(kind, id))`

**Règles d'import** (une seule transaction, annulée en cas d'erreur) :
- Les alias sont appliqués aux clés **avant** la recherche d'identité. L'identité est trouvée, ou créée, par `(type, clé)`.
- Le `content_hash` est un sha256 du JSON trié des champs de l'œuvre, avec ses saisons, épisodes, fichiers et collections. Si le hash est inchangé, la ligne ne bouge pas, et son `rev` non plus. Si le hash change, le `rev` prend la nouvelle révision.
- Les œuvres absentes du nouveau catalogue sont supprimées de `work` (en cascade sur leurs saisons, épisodes, fichiers et collections) et une ligne `tombstone('work', id, rev)` est ajoutée. **La progression est conservée.**
- **Garde-fous** : `ImportRefused("catalogue vide")` si `result.works` est vide alors que la base contient des œuvres. `ImportRefused("suppression massive")` si plus de 50 % des œuvres existantes disparaîtraient et que `force` est faux.
- Les collections sont créées à partir des `CollectionRef` des œuvres (nom exact). Leur `description` et leur `link` viennent du `ParsedGroup` de même nom, s'il existe.
- Une seule révision est consommée par import, et seulement s'il y a au moins un changement.
- Cette synchronisation par colonne `rev` et `tombstone` remplace le `change_log` évoqué dans la spec §6.2, avec le même effet et plus simplement.

- [ ] **Step 1: tests qui échouent** :
  - `test_first_import` : `added == 7`, `revision == 1`, et `rc://w/<id>` donne Ava.
  - `test_reimport_same_is_noop` : `added == updated == deleted == 0` et la révision ne change pas.
  - `test_ids_stable_across_reimport` : les identifiants d'Ava et des saisons de Back to Life sont identiques après réimport d'un texte où une autre œuvre a été modifiée.
  - `test_duplicate_key_two_ids` : les deux « Le prenom [2012] » ont des identifiants différents.
  - `test_removed_work_tombstoned_progress_kept` : réimport sans Ava → `deleted == 1`, `tombstone('work', ava_id)` est présent, et la ligne `progress` d'Ava (insérée à la main avant le réimport) est toujours là.
  - `test_alias_keeps_id` : la clé est renommée `Agatha Christie - Dix petits negres [2015]` avec l'alias `Agatha Cristie - Dix petits negres [2015] => Agatha Christie - Dix petits negres [2015]` → même identifiant. Sans l'alias, l'identifiant est nouveau.
  - `test_refuses_empty` et `test_refuses_mass_deletion` : la base n'est pas modifiée (même révision, même nombre de `work`). `force=True` permet de passer le garde-fou de suppression massive.
- [ ] **Step 2:** `uv run pytest tests/test_importer.py -v` → FAIL.
- [ ] **Step 3:** implémenter `schema.sql`, `db.py` et `importer.py`.
- [ ] **Step 4:** relancer → PASS. Puis importer le vrai `../docs/data/medias.txt` dans une base temporaire et vérifier qu'il n'y a pas d'erreur et qu'un réimport ne produit aucun changement.
- [ ] **Step 5:** commit `feat(db): schéma et import idempotent`.

---

### Task 5: Disques, localisation des fichiers, source du catalogue, arborescence factice

**Files:**
- Create: `server/rc/volumes.py`, `server/rc/fake_tree.py`
- Test: `server/tests/test_volumes.py`

**Interfaces:**
- Consumes: `open_db`, `next_revision`, les tables `media_file` et `volume`, et `ParseResult` (pour l'arborescence factice).
- Produces :
  - `Volume(uuid: str, label: str | None, mount: Path)`
  - `list_volumes(scan_roots: list[Path], runner=subprocess.run) -> list[Volume]` : lance `findmnt --json --list -o TARGET,UUID,LABEL` et garde les points de montage situés sous `scan_roots` qui ont un UUID.
  - `build_index(mount: Path) -> dict[str, Path]` : la clé est `unicodedata.normalize("NFC", relpath).casefold()`. Ignore les dossiers cachés, `lost+found` et `System Volume Information`. Profondeur maximale de 6.
  - `locate_files(conn, volumes: list[Volume], indexes: dict[str, dict[str, Path]]) -> bool` : met à jour `volume` (présent ou absent, dernier point de montage) et `media_file.volume_uuid/present`, en ne bumpant le `rev` que des lignes qui changent. Renvoie vrai s'il y a eu un changement.
  - `resolve_media_path(conn, media_file_id: int, indexes) -> Path | None`
  - `find_catalog_source(volumes: list[Volume], override: Path | None) -> Path | None` : renvoie `override` s'il est fourni, sinon le `medias.txt` le plus récent (par mtime) trouvé à la racine d'un volume.
  - `make_fake_tree(result: ParseResult, root: Path) -> None` : crée des vidéos vides, les sous-titres déclarés et les affiches (JPEG 2×3 px générés avec Pillow, de couleur différente pour chaque œuvre).
- Un fichier introuvable garde `volume_uuid` (dernier disque connu) avec `present = 0`. Le libellé du disque attendu reste ainsi connu pour le message « disque non branché ».

- [ ] **Step 1: tests qui échouent** :
  - `test_list_volumes_filters_roots` : un faux `runner` renvoie le JSON de `findmnt` avec `/media/x/DISK1` (avec UUID), `/` et `/mnt/nouuid` (sans UUID) → seul DISK1 est gardé.
  - `test_locate_marks_present_then_absent` : fixture importée, arborescence factice dans `tmp/DISK1` → les fichiers d'Ava sont présents. Si on retire DISK1 de la liste, ils sont absents mais gardent le même `volume_uuid`, et leur `rev` augmente.
  - `test_nfd_and_case_on_disk` : le fichier de la pièce de théâtre est renommé sur disque en NFD et en casse différente (`theatre/le prenom [2012] …`), en reprenant les mêmes caractères → il est quand même présent.
  - `test_find_catalog_source_newest` : deux disques contiennent un `medias.txt` → le plus récent est choisi. `override` est prioritaire.
- [ ] **Step 2:** `uv run pytest tests/test_volumes.py -v` → FAIL.
- [ ] **Step 3:** implémenter.
- [ ] **Step 4:** relancer → PASS.
- [ ] **Step 5:** commit `feat(volumes): disques, localisation et arborescence factice`.

---

### Task 6: Affiches

**Files:**
- Create: `server/rc/assets.py`
- Test: `server/tests/test_assets.py`

**Interfaces:**
- Consumes: `work.poster_relpath`, `work.poster_asset`, les index de la Task 5.
- Produces :
  - `SIZES = {"thumb": 300, "tv": 600}`
  - `process_image(src: Path, assets_dir: Path) -> str` : renvoie le hash (sha256 du contenu, 16 caractères hexadécimaux) et écrit `assets_dir/<hash>/{orig,thumb,tv}.jpg`. Redimensionnement en conservant les proportions, jamais d'agrandissement, JPEG qualité 85. Idempotent.
  - `asset_path(assets_dir: Path, hash: str, size: str) -> Path`
  - `ingest_posters(conn, assets_dir: Path, indexes) -> int` : traite les œuvres dont l'affiche est trouvée mais qui n'ont pas encore de `poster_asset`. Affecte `poster_asset` et bumpe `work.rev`. Renvoie le nombre d'affiches traitées.

- [ ] **Step 1: tests qui échouent** : `test_sizes` (image source 1000×1500 → `thumb` 300×450, `tv` 600×900), `test_small_image_not_upscaled` (source 200×300 → `tv` reste en 200×300), `test_ingest_sets_asset_and_bumps_rev` (fixture et arborescence factice → Ava a un `poster_asset` et un `rev` qui a augmenté ; un second appel renvoie 0).
- [ ] **Step 2:** FAIL → implémenter → PASS.
- [ ] **Step 3:** commit `feat(assets): affiches redimensionnées`.

---

### Task 7: Pistes audio et sous-titres

**Files:**
- Create: `server/rc/probe.py`
- Test: `server/tests/test_probe.py`

**Interfaces:**
- Produces :
  - `Track(index: int, lang: str | None, title: str | None, codec: str | None, default: bool)` : `index` est 1-based par type, dans l'ordre des flux, ce qui correspond à `aid`/`sid` de mpv.
  - `ExternalSub(path: Path, lang: str | None)`
  - `MediaTracks(audio: list[Track], subtitles: list[Track], external: list[ExternalSub], video_codec: str | None, height: int | None)`
  - `SubSel` (défini dans `rc/player/base.py`, voir la Task 8) : `{"kind": "none"} | {"kind": "embedded", "index": n} | {"kind": "external", "path": str}`
  - `probe_tracks(path: Path, runner=subprocess.run) -> MediaTracks` : lance `ffprobe -v error -show_streams -of json <path>`. La langue est lue dans `tags.language` et normalisée (`fre`, `fra`, `french` → `fr`).
  - `find_external_subs(video: Path, declared: list[str]) -> list[ExternalSub]` : réunit les fichiers déclarés et ceux du même dossier qui commencent par le nom de la vidéo sans extension et finissent par `.srt`, `.vtt`, `.ass`, `.ssa` ou `.sub`, sans doublon.
  - `cached_tracks(conn, media_file_id: int, path: Path, runner=...) -> MediaTracks` : utilise `probe_cache`, invalidé si le mtime ou la taille changent.
  - `default_selection(t: MediaTracks) -> tuple[int | None, SubSel]` : audio français s'il existe, sinon la première piste. Si l'audio choisi est français, pas de sous-titres. Sinon, sous-titres français, externes d'abord puis intégrés, et à défaut aucun.

- [ ] **Step 1: tests qui échouent** :
  - `test_parse_ffprobe_json` : JSON enregistré avec 1 vidéo h264 1080, audio `eng`, audio `fre`, sous-titre `fre` → `audio[1].lang == "fr"`, `audio[1].index == 2`, `video_codec == "h264"`, `height == 1080`.
  - `test_external_subs_declared_and_discovered` : vidéo `X.mp4` avec `X.1.fr.srt` déclaré, `X.en.vtt` présent mais non déclaré, et `Y.srt` → 2 sous-titres externes, de langues `fr` et `en`.
  - `test_default_fr_audio_no_subs`, `test_default_vo_with_fr_external_sub`, `test_default_vo_no_fr_sub_none`.
  - `test_cache_invalidated_on_mtime` : le `runner` n'est appelé qu'une fois pour deux appels, puis de nouveau après un `os.utime`.
- [ ] **Step 2:** FAIL → implémenter → PASS.
- [ ] **Step 3:** commit `feat(probe): pistes et sous-titres`.

---

### Task 8: Pilotage de mpv

**Files:**
- Create: `server/rc/player/__init__.py`, `server/rc/player/base.py`, `server/rc/player/mpv.py`, `server/tests/fake_mpv.py`
- Test: `server/tests/test_mpv.py`

**Interfaces:**
- Produces (`base.py`) :
  - `SubSel = dict`, avec la forme décrite en Task 7.
  - `@dataclass PlayerState(position: float = 0, duration: float | None = None, paused: bool = False, volume: int = 100, audio: int | None = None, subtitle: SubSel = {"kind": "none"}, ended: bool = False, error: str | None = None, running: bool = False)`
  - `class Player(Protocol)` : `async start(path: Path, start_at: float, audio: int | None, subtitle: SubSel, external_subs: list[Path]) -> None`, `async set_pause(paused: bool)`, `async seek(seconds: float, relative: bool)`, `async set_volume(level: int)`, `async set_audio(index: int | None)`, `async set_subtitle(sel: SubSel)`, `async stop()`, ainsi que l'attribut `on_state: Callable[[PlayerState], None]`.
- Produces (`mpv.py`) :
  - `MpvClient(socket_path: Path)` : `async connect(retries=50, delay=0.1)`, `async command(*args) -> Any` (JSON `{"command": [...], "request_id": n}` par ligne, réponse appariée par `request_id`, `MpvError` si `error != "success"`), `async set_property(name, value)`, `async observe(name)`, `events() -> AsyncIterator[dict]`, `async close()`.
  - `MpvPlayer(mpv_path: str, extra_args: list[str], runtime_dir: Path)` implémente `Player`. Chaque lecture lance un processus mpv : `[mpv_path, "--fs", "--no-terminal", "--idle=no", f"--input-ipc-server={sock}", "--hwdec=auto-safe", "--alang=fr,fre,fra", "--slang=fr,fre,fra", f"--start={start_at}", *("--sub-file=" + p), *extra_args, path]`. Il observe `time-pos`, `duration`, `pause`, `volume`, `aid`, `sid`, `eof-reached`. À `file-loaded`, il applique `aid` et `sid`. Les sous-titres externes sont numérotés après les pistes intégrées, et `sid` est calculé à partir de `SubSel`. Si le processus se termine sans `eof-reached`, l'état final porte `error="process_exit"`. `stop()` envoie `quit` puis termine le processus après 3 s s'il tourne encore.
- `fake_mpv.py` : serveur asyncio sur socket Unix qui répond `success` aux commandes, enregistre les commandes reçues, et peut émettre des événements `property-change`, `file-loaded` et `end-file` à la demande du test.

- [ ] **Step 1: tests qui échouent** (contre `fake_mpv`) : `test_command_roundtrip_and_request_id`, `test_error_raises_mpverror`, `test_observe_property_change_yields_event`. Pour `MpvPlayer`, avec un `mpv_path` pointant vers un petit script Python qui démarre `fake_mpv` sur le socket passé en argument : `test_start_sends_tracks_after_file_loaded` (`set_property aid 2` puis `sid` reçus), `test_state_updates_position`, `test_process_exit_without_eof_sets_error`.
- [ ] **Step 2:** FAIL → implémenter → PASS.
- [ ] **Step 3 (manuel, si mpv est installé : `sudo apt install mpv`)** : `uv run python -m rc.player.mpv <fichier.mp4>` (petit `__main__` de démonstration qui lit 5 s, met en pause, fait un seek de +10 s puis s'arrête). Vérifier visuellement.
- [ ] **Step 4:** commit `feat(player): pilotage mpv par IPC`.

---

### Task 9: Session de lecture, suivi, état de la télé

**Files:**
- Create: `server/rc/events.py`, `server/rc/player/session.py`
- Test: `server/tests/test_session.py`

**Interfaces:**
- Consumes: `Player`, `PlayerState`, `SubSel` (Task 8). `cached_tracks`, `find_external_subs`, `default_selection` (Task 7). `resolve_media_path` (Task 5). `next_revision`.
- Produces :
  - `EventBus` : `subscribe() -> asyncio.Queue[dict]`, `unsubscribe(q)`, `publish(msg: dict)` (non bloquant, une file pleine perd ses vieux messages).
  - `@dataclass TvState(screen: Literal["home", "work", "season", "playing", "message"], work_id: int | None = None, season_id: int | None = None, message: str | None = None)`
  - `TvController(bus)` : `state`, `show(screen, work_id=None, season_id=None, force=False) -> bool` (ignoré et renvoie faux si `screen == "playing"` est actif et `force` est faux), `set_playing(work_id)`, `playback_ended(message: str | None)` (revient à la dernière fiche, ou affiche `message`). Publie `{"type": "tv", "state": asdict(state)}`.
  - `PlayRequest(target_kind: Literal["work", "episode"], target_id: int, start: Literal["start", "resume"], media_file_id: int | None = None, audio: int | None = None, subtitle: SubSel | None = None)`
  - `class PlayError(Exception)`, avec `code: Literal["not_playable", "volume_absent", "not_found", "player"]` et `volume_label: str | None`
  - `PlaybackService(conn, player: Player, tv: TvController, bus: EventBus, indexes_getter: Callable[[], dict], clock=time.monotonic)` : `async play(req)`, `async pause(bool)`, `async seek(seconds, relative)`, `async set_volume(level)`, `async set_audio(index)`, `async set_subtitle(sel)`, `async stop()`, `state -> PlayerState`, `current -> dict | None` (cible, `media_file_id`, titre).

Règles :
- **Choix de la version** : `media_file_id` s'il est fourni, sinon la version mémorisée dans `progress` (pour `resume`), sinon la meilleure qualité présente selon `QUALITY_ORDER`.
- **Erreurs** : aucun fichier lisible → `not_playable`. Fichier connu mais disque absent → `volume_absent` avec le libellé du disque, et la télé affiche `f"Disque « {label} » non branché"`. Chemin introuvable → `not_found`. Échec de démarrage du lecteur → `player` (409 `{"error":"player"}`), message télé « Le lecteur ne répond pas ».
- **Position de départ** : 0 pour `start`. Pour `resume`, la position de `progress` si `watched == 0`, sinon 0.
- **Pistes** : celles de la requête, sinon le dernier choix de `progress`, sinon `default_selection`.
- **Lecture déjà en cours** : elle est arrêtée (avec enregistrement de sa position) avant de démarrer la nouvelle.
- **Enregistrement** : à chaque `on_state`, si `clock() - dernier >= 10` ; à chaque changement de `paused` ; à l'arrêt ou à la fin. `position >= 0.9 * duration` → `watched = 1` et `position = 0`. Chaque écriture bumpe le `rev` de la ligne `progress`. Les changements de piste sont enregistrés dans `progress.audio/subtitle`.
- **Fin** : `ended` → `tv.playback_ended(None)`. `error` → enregistrement, puis `tv.playback_ended("Lecture interrompue : fichier inaccessible")`.
- Chaque `on_state` publie `{"type": "player", "state": ..., "current": ...}`.

- [ ] **Step 1: tests qui échouent** (avec un `FakePlayer` qui enregistre les appels et un `clock` contrôlé, base de la fixture et arborescence factice) :
  - `test_play_best_quality_from_start` : Ava → la version FHD est choisie, `start_at == 0`.
  - `test_resume_uses_stored_version_and_position` : la progression mémorise la version HD à 600 s → `start` est appelé sur le fichier HD avec `start_at == 600`.
  - `test_saves_every_10s_not_more` : des états à t = 0, 3, 9, 10, 15, 21 donnent 2 écritures (t = 10 et t = 21).
  - `test_watched_at_90_percent` : position 5400 sur une durée de 6000 → `watched == 1`, `position == 0`.
  - `test_volume_absent_message` : disque retiré → `PlayError("volume_absent", "DISK1")`, avec le message correspondant sur `tv.state`.
  - `test_process_error_saves_and_shows_message` (Review Focus 1) : état `error="process_exit"` à 1234 s → la progression vaut 1234 et le message de la télé vaut `"Lecture interrompue : fichier inaccessible"`.
  - `test_new_play_stops_current_first` (Review Focus 5) : un deuxième `play` → `stop` est appelé avant le second `start`, et la position de la première lecture est enregistrée.
  - `test_tv_show_ignored_while_playing` : `show("work", 2)` pendant la lecture → faux, l'écran reste `playing`. Avec `force=True` → vrai.
  - `test_track_choice_persisted` : `set_audio(1)` puis arrêt → `progress.audio == "1"`.
- [ ] **Step 2:** FAIL → implémenter → PASS.
- [ ] **Step 3:** commit `feat(player): session de lecture, suivi et état télé`.

---

### Task 10: API HTTP et WebSocket

**Files:**
- Create: `server/rc/api/__init__.py`, `server/rc/api/routes.py`, `server/rc/api/ws.py`
- Test: `server/tests/test_api.py`

**Interfaces:**
- Consumes: tout ce qui précède, via un objet `Services(conn, config, bus, tv: TvController, playback: PlaybackService, indexes_getter)` passé à `build_router(services) -> APIRouter` et à `build_ws_router(services)`.
- Produces (contrat utilisé par l'appli Android et l'UI télé) :
  - `GET /api/info` → `{"name": "Remote-Control", "api": 1, "revision": int}`
  - `GET /api/sync?since=<int>` → `{"revision", "full": bool, "works": [...], "seasons": [...], "episodes": [...], "media_files": [...], "collections": [...], "work_collections": [...], "progress": [...], "volumes": [...], "deleted": {"work": [ids], "season": [...], "episode": [...], "media_file": [...]}}`. `full` vaut vrai quand `since` est absent : il renvoie alors tout, sans les `deleted`. Une œuvre est renvoyée avec ses champs JSON décodés et `poster` (= hash ou `null`), sans `content_hash`. La réponse est compressée (`GZipMiddleware`, à partir de 1 000 octets).
  - `GET /api/works/{id}` → l'œuvre, ses `seasons`, `media_files` (avec `present` et `volume_label`), `collections` et `progress`. `GET /api/seasons/{id}` → la saison, son œuvre parente (résumé), ses épisodes (avec leurs `media_files` et leur `progress`), et `highlight_episode_id` (dernier épisode entamé ou vu, sinon le premier non vu).
  - `GET /api/media/{media_file_id}/tracks` → `MediaTracks` + `default: {"audio", "subtitle"}`. Si le disque est absent, `409 {"error": "volume_absent", "volume_label"}`.
  - `GET /api/assets/{hash}/{size}.jpg` (`size` ∈ thumb, tv, orig) → `Cache-Control: public, max-age=31536000, immutable`. 404 si absent.
  - `POST /api/tv/show {"screen": "home"|"work"|"season", "id": int?, "force": bool?}` → `{"shown": bool}`
  - `GET /api/tv/state` → `TvState`
  - `POST /api/play` (corps = `PlayRequest`) → `{"ok": true}`, ou `409 {"error": code, "volume_label"}`
  - `POST /api/player/pause {"paused": bool}`, `/seek {"seconds": float, "relative": bool}`, `/volume {"level": 0..100}`, `/audio {"index": int|null}`, `/subtitle {SubSel}`, `/stop` → `{"ok": true}`. Sans lecture en cours, `409 {"error": "idle"}`.
  - `GET /api/player/state` → `{"state", "current"}`
  - `WS /api/events` : envoie d'abord `{"type": "hello", "revision", "tv", "player"}`, puis relaie les messages du bus (`player`, `tv`, `catalog`).

- [ ] **Step 1: tests qui échouent** (FastAPI `TestClient`, `FakePlayer`) :
  - `test_sync_full_then_incremental` : synchronisation complète, puis modification d'une œuvre et réimport → `since=rev` ne renvoie que cette œuvre.
  - `test_sync_reports_deleted` (Review Focus 4) : après suppression d'une œuvre, `deleted.work == [id]`.
  - `test_sync_reports_availability_change` : le disque est retiré, puis `locate_files` → le `media_file` apparaît avec `present: false`.
  - `test_season_highlight` : l'épisode S01E02 de Back to Life est entamé → `highlight_episode_id` désigne cet épisode.
  - `test_asset_immutable_header`.
  - `test_play_volume_absent_409`.
  - `test_player_idle_409`.
  - `test_ws_hello_then_tv_event` : connexion, puis `POST /api/tv/show` → un message `tv` est reçu.
- [ ] **Step 2:** FAIL → implémenter → PASS.
- [ ] **Step 3:** commit `feat(api): synchronisation, commandes et événements`.

---

### Task 11: Assemblage, tâches de fond, mDNS, CLI

**Files:**
- Create: `server/rc/app.py`, `server/rc/cli.py`
- Test: `server/tests/test_app.py`

**Interfaces:**
- Consumes: tout ce qui précède.
- Produces :
  - `create_app(config: Config, player: Player | None = None, runner=subprocess.run) -> FastAPI` : ouvre `data_dir/rc.db` et monte les routeurs ainsi que `tv-ui/` sur `/tv` (fichiers statiques). Au démarrage, lance la tâche de fond `refresh_loop`, et la tâche mDNS si le `zeroconf` réel est utilisé (désactivable par `config`, ou injecté dans les tests).
  - `async refresh_once(state) -> None` : fait un tour, appelé toutes les `poll_interval` secondes.
    1. `list_volumes` puis reconstruction des index des volumes nouveaux ou modifiés.
    2. `find_catalog_source`. Si `(taille, mtime)` diffère de la dernière copie **et** est identique à l'observation du tour précédent (fichier stable sur deux tours), copier le fichier dans `data_dir/catalog/medias.txt` (copie atomique), puis `parse_catalog` et `import_catalog` avec `data_dir/aliases.txt`. Un `ImportRefused` est journalisé et la copie précédente conservée.
    3. `locate_files`, puis `ingest_posters`.
    4. Si la révision a changé, publier `{"type": "catalog", "revision"}`.
  - mDNS : `ServiceInfo("_rc._tcp.local.", "Remote-Control._rc._tcp.local.", port=config.port, properties={"api": "1"}, addresses=<IP locales>)`.
  - CLI `rc` : `serve` (uvicorn sur `0.0.0.0:port`), `import [chemin] [--force]` (import immédiat, puis affichage de l'`ImportReport`), `fake-tree <catalogue> <dossier>`, `doctor` (Task 13).

- [ ] **Step 1: tests qui échouent** :
  - `test_refresh_imports_only_when_stable` (Review Focus 2) : un `medias.txt` sur le faux disque est vu une première fois, puis il est modifié avant le second tour → pas d'import. Au troisième tour, sans nouvelle modification, l'import a lieu.
  - `test_refresh_refused_keeps_db` : un `medias.txt` vide → les œuvres sont toujours là et l'avertissement est journalisé.
  - `test_refresh_publishes_catalog_event`.
  - `test_tv_static_served` : `GET /tv/` → 200, en `text/html`.
- [ ] **Step 2:** FAIL → implémenter → PASS.
- [ ] **Step 3 (vérification de bout en bout)** :

```bash
cd server
uv run rc fake-tree ../docs/data/medias.txt /tmp/rc-fake/DISK1
cp ../docs/data/medias.txt /tmp/rc-fake/DISK1/
RC_CONFIG=dev.toml uv run rc serve   # dev.toml : catalog_path et scan_roots pointent vers /tmp/rc-fake (volume simulé : un mode dev "dir_volumes" qui traite chaque sous-dossier de scan_roots comme un volume d'UUID = son nom)
curl -s localhost:8765/api/info
curl -s 'localhost:8765/api/sync' | python -c 'import json,sys; d=json.load(sys.stdin); print(len(d["works"]))'
```

Attendu : environ 3 990 œuvres. Pour cela, ajouter à `Config` l'option `dev_dir_volumes: bool = False`, utilisée par `list_volumes`, avec un test `test_dev_dir_volumes`.
- [ ] **Step 4:** commit `feat(server): assemblage, rafraîchissement, mDNS et CLI`.

---

### Task 12: UI télé provisoire

**Files:**
- Create: `tv-ui/index.html`, `tv-ui/app.js`, `tv-ui/style.css`
- Test: `server/tests/test_app.py::test_tv_static_served` (déjà écrit) et une vérification manuelle

**Interfaces:**
- Consumes: `WS /api/events`, `GET /api/tv/state`, `GET /api/works/{id}`, `GET /api/seasons/{id}`, `GET /api/assets/{hash}/tv.jpg`.

Contraintes :
- Aucun framework, aucun build. Un canevas de 1920×1080 mis à l'échelle de la fenêtre (`transform: scale`).
- Cinq écrans, selon `TvState.screen` :
  - `home` : le nom « Remote-Control », l'heure, et « Prêt ».
  - `work` : affiche, titre français, titre VO s'il diffère, année, durée au format « 1 h 39 », réalisateurs, genres, les 6 premiers noms du casting, synopsis, collections, versions disponibles, et état « Disque « X » non branché » s'il y a lieu, ainsi que la progression (« Reprise possible à 1 h 12 »).
  - `season` : en-tête de la série, liste des épisodes, épisode mis en évidence (`highlight_episode_id`), marques « vu » ou « entamé ».
  - `playing` : fond noir avec le titre en cours (mpv est au-dessus).
  - `message` : texte centré.
- Reconnexion automatique du WebSocket toutes les 2 s. Les fiches sont rechargées à chaque événement `catalog`.
- Ces écrans sont **provisoires** : un fichier CSS par écran n'est pas nécessaire, mais chaque écran est rendu par une fonction `render<Screen>(data)` isolée, pour pouvoir y brancher plus tard les maquettes Claude Design.

- [ ] **Step 1:** implémenter les trois fichiers.
- [ ] **Step 2 (manuel)** : serveur de dev de la Task 11, ouvrir `http://localhost:8765/tv/` en 1920×1080, puis enchaîner `curl -XPOST localhost:8765/api/tv/show -d '{"screen":"work","id":1}' -H 'content-type: application/json'`, puis une saison, puis `home`. Vérifier que chaque écran s'affiche sans erreur dans la console et que les accents sont corrects.
- [ ] **Step 3:** commit `feat(tv-ui): écrans provisoires`.

---

### Task 13: `rc doctor` — diagnostic du mini-PC

**Files:**
- Create: `server/rc/doctor.py`
- Modify: `server/rc/cli.py`
- Test: `server/tests/test_doctor.py`

**Interfaces:**
- Consumes: `list_volumes`, `build_index`, `probe_tracks`, `MpvClient`.
- Produces :
  - `parse_vainfo(text: str) -> set[str]` : profils décodables. Ce sont les lignes `VAProfile… : VAEntrypointVLD`, ramenées à `h264`, `hevc`, `hevc10`, `vp9`, `av1` et `mpeg2`.
  - `codec_key(video_codec: str, profile: str | None) -> str` : `hevc` + `Main 10` → `hevc10`.
  - `survey(media_paths: list[Path], runner) -> Counter[str]`
  - `run_doctor(config, samples: int = 3, play_seconds: int = 20) -> str` : produit un rapport en français. Il donne les profils matériels, la répartition des codecs de la collection présente, le nombre de fichiers **sans décodage matériel possible**, puis, si mpv est disponible, la lecture de `samples` fichiers (un par codec majoritaire) pendant `play_seconds`. Pour chacun, il relève `hwdec-current`, `frame-drop-count` et `decoder-frame-drop-count` via IPC.

- [ ] **Step 1: tests qui échouent** : `test_parse_vainfo` (sortie réelle de vainfo collée en fixture, produite par `vainfo` sur la machine de dev), `test_codec_key_hevc10`, `test_report_flags_unsupported` (survey avec 3 fichiers `hevc10` et un vainfo sans HEVC Main10 → le rapport contient « 3 fichiers sans décodage matériel »).
- [ ] **Step 2:** FAIL → implémenter → PASS.
- [ ] **Step 3:** `uv run rc doctor` sur la machine de dev, qui doit produire un rapport lisible.
- [ ] **Step 4:** commit `feat(doctor): diagnostic décodage vidéo`.

---

### Task 14: Secours TMDB pour les affiches manquantes

**Files:**
- Create: `server/rc/tmdb.py`
- Modify: `server/rc/app.py` (étape 3 de `refresh_once`)
- Test: `server/tests/test_tmdb.py`

**Interfaces:**
- Consumes: `process_image`, `work.imdb_id`, `work.poster_asset`.
- Produces : `async fetch_missing_posters(conn, assets_dir, api_key: str, client: httpx.AsyncClient, limit: int = 20) -> int`. Ne fait rien si `api_key == ""`. Pour chaque œuvre avec un `imdb_id` mais sans `poster_asset` ni affiche locale trouvée, appelle `GET https://api.themoviedb.org/3/find/{imdb_id}?external_source=imdb_id&language=fr-FR`, prend le premier `poster_path` de `movie_results` ou `tv_results`, télécharge `https://image.tmdb.org/t/p/w780{poster_path}`, puis appelle `process_image`. Une tentative est mémorisée par œuvre (`meta` : `tmdb_tried:<id>`) pour ne pas recommencer à chaque tour. **Aucun champ texte n'est jamais modifié.**

- [ ] **Step 1: tests qui échouent** (`httpx.MockTransport`) : `test_disabled_without_key` (aucune requête), `test_fetches_and_sets_asset`, `test_never_retries_same_work`, `test_does_not_touch_text_fields`.
- [ ] **Step 2:** FAIL → implémenter → PASS.
- [ ] **Step 3:** commit `feat(tmdb): secours pour affiches manquantes`.

---

## Plans suivants (hors de ce plan)

- **Appli Android** (Kotlin, Compose, Room, Coil, ZXing, NSD) : elle consomme le contrat d'API de la Task 10.
- **Installation sur le mini-PC** : script unique, Debian 13, service systemd, session kiosque (cage + navigateur), passage de mpv au premier plan, `rc doctor` lancé sur place.
- **Fiches PDF imprimables** (phase 2, format à définir).
