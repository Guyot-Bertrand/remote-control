# Étape 4 — Serveur et page télé — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Le serveur lit notes et liens des œuvres, garde le lien TMDB, extrait une image de la vidéo pour les œuvres sans affiche (TMDB prioritaire), et la fiche télé affiche les notes publiques en pastilles.

**Architecture:** L'analyseur lit `QS` et `MQ`, l'importeur range les notes dans `work.ratings_json` (exposé tel quel en `ratings` par `serialize.row`). La provenance de l'affiche devient une colonne `work.poster_source` (`local` | `tmdb` | `frame`). TMDB garde l'URL de la fiche (`work.tmdb_url`). Un nouveau module `rc/frames.py` extrait une image avec ffmpeg, appelé par la boucle d'analyse quand elle n'a plus de fichier à analyser.

**Tech Stack:** Python 3.12, uv, FastAPI, sqlite3, httpx, Pillow, ffmpeg/ffprobe ; page télé en JavaScript classique ; Playwright (outil MCP) pour les captures.

**Spec:**
- **Conception :** `docs/superpowers/specs/2026-11-27-etape-4-design.md`.
- **Contrat :** `docs/superpowers/plans/2026-11-22-api-contract-rulings.md`, arbitrages 24 à 26 (rédigés). Ils font foi.
- **Maquette télé A :** pastilles sous la ligne « Réalisation » ; rendu de référence fait sur la vraie page (voir Task 5).

## Global Constraints

- Français partout (textes, journaux, commentaires). Aucune dépendance nouvelle (ffmpeg est un exécutable système, comme ffprobe).
- Page télé légère : pas de flou ; animations limitées à `transform` et `opacity` ; scripts classiques.
- La télé n'affiche jamais `NP` ni `NT`.
- Priorité des affiches : locale (`AF`) > TMDB > image extraite.
- Extraction : un fichier à la fois, jamais pendant une lecture ; moment = 10 % de la durée analysée, sinon 300 s, ou 0 si le fichier est plus court.
- Tests : `cd server && uv run pytest -q` passe en entier (406 au départ), sans avertissement nouveau.
- Git : ne jamais ajouter `.superpowers/`, `.playwright-mcp/`, `docs/data/` ; ne jamais lire ni citer `docs/data/` (données privées) ; donner le vrai SHA de chaque commit.

## Review Focus

1. **Œuvre qui reçoit une ligne `AF` après avoir eu une image extraite ou une affiche TMDB** : l'affiche locale la remplace au tour suivant. Test : Task 2 `test_local_poster_replaces_frame_and_tmdb`.
2. **Clé TMDB ajoutée après des images extraites** : les œuvres jamais essayées passent par TMDB et leur image extraite est remplacée ; une œuvre déjà essayée sans succès garde son image. Test : Task 3 `test_tmdb_replaces_frame_only_for_untried_works`.
3. **Fichier vidéo très court ou sans durée analysée** : extraction à 0 s ou à 300 s, jamais au-delà de la durée. Test : Task 4 `test_frame_time_rules`.
4. **ffmpeg absent ou fichier illisible** : journalisé, œuvre écartée jusqu'au prochain index complet, la boucle continue. Test : Task 4 `test_extraction_failure_is_skipped_until_full_index`.
5. **Base de l'étape 3 rouverte** : colonnes ajoutées, provenance des affiches existantes renseignée, réimport demandé. Test : Task 2 `test_v3_database_migrates_to_v4`.

---

## Structure des fichiers

```
server/rc/catalog/model.py, parser.py   # ParsedRating, sources QS, notes MQ
server/rc/catalog/importer.py           # ratings dans le payload et ratings_json
server/rc/schema.sql, db.py             # colonnes v4 + migration
server/rc/assets.py                     # poster_source = 'local'
server/rc/api/serialize.py              # poster_source masqué
server/rc/tmdb.py                       # tmdb_url, remplacement des images extraites, recherche d'identifiant seule
server/rc/frames.py                     # nouveau : choix du fichier, moment, extraction
server/rc/analysis.py                   # appel de l'extraction quand la file est vide
tv-ui/sheets.js, style.css, dev/preview.json
```

---

### Task 1: Lecture des notes et des sources

**Files:**
- Modify: `server/rc/catalog/model.py` (`ParsedWork`, `ParseResult`), `server/rc/catalog/parser.py` (`_on_mq` l. 143, `HANDLERS["QS"]`)
- Test: `server/tests/test_parser.py` (ou le fichier de tests de l'analyseur existant)

**Interfaces:**
- Produces :
  - `@dataclass ParsedRating(code: str, name: str, value: float | None, url: str | None)` ;
  - `ParsedWork.ratings: list[ParsedRating]` (défaut liste vide), dans l'ordre `IM`, `SC`, `SC@xx` (ordre du fichier), `WK`, `NP`, `NT` ;
  - les noms : `IM` → `IMDb`, `SC` → `Sens Critique`, `WK` → `Wikipédia`, `NP` → `Note personnelle`, `NT` → `Note technique`, `SC@xx` → nom du critique tiré de `QS` ;
  - `ParsedWork.imdb_id` inchangé (toujours tiré du lien IMDb).

- [ ] **Step 1: Tests en échec**

```python
SAMPLE = """QS ; SC@ES ; Sens Critique / Éric Schwald (Sergent_Pepper) ; https://www.senscritique.com/Sergent_Pepper/collection
QS ; SC@ZZ ; Sens Critique / Divers
QS ; NP ; Note personnelle
MD ; 0 ; Films
MN ; film ; Ava [2017] ; Ava ; *
MQ ; WK ; ? ; https://fr.wikipedia.org/wiki/Ava
MQ ; SC@ES ; 8.0
MQ ; IM ; 7.0 ; https://www.imdb.com/title/tt5974624/
MQ ; IM ; 9.9 ; https://www.imdb.com/title/tt0000001/
MQ ; SC ; 6.2 ; https://www.senscritique.com/film/ava/123
MQ ; SC@ZZ ; ?
MQ ; NP ; 7.0
MQ ; YT@IC ; ? ; https://www.youtube.com/watch?v=x
"""

def test_ratings_order_names_values_urls():
    w = parse_catalog(SAMPLE).works[0]
    assert [(r.code, r.name, r.value, r.url) for r in w.ratings] == [
        ("IM", "IMDb", 7.0, "https://www.imdb.com/title/tt5974624/"),
        ("SC", "Sens Critique", 6.2, "https://www.senscritique.com/film/ava/123"),
        ("SC@ES", "Éric Schwald", 8.0, None),
        ("SC@ZZ", "Divers", None, None),
        ("WK", "Wikipédia", None, "https://fr.wikipedia.org/wiki/Ava"),
        ("NP", "Note personnelle", 7.0, None),
    ]
    assert w.imdb_id == "tt5974624"

def test_rating_code_without_qs_line_shows_code():  # MQ ; SC@AB ; 5.0 sans QS → name "SC@AB"
def test_label_without_slash_or_parenthesis_kept_whole():  # QS ; SC@XY ; Critique libre → "Critique libre"
def test_malformed_note_is_none():  # "7,5", "", "abc" → value None ; url ignorée si elle ne commence pas par http
def test_mq_before_any_work_is_ignored_with_warning():  # comportement actuel conservé
```

- [ ] **Step 2: Lancer les tests** — Run: `cd server && uv run pytest -q tests/test_parser.py -k rating` — Expected: FAIL (`ratings` inexistant).

- [ ] **Step 3: Implémenter**

`QS` : `_State` garde `sources: dict[str, str]` (code → nom affiché), rempli par un `_on_qs` à la place de `_accept`. Nom d'un critique : partie du libellé après le premier « / » s'il y en a un, coupée avant « ( », espaces retirés ; libellé entier sinon. Les lignes `QS` sont en tête du fichier ; une ligne `MQ` qui précède sa `QS` garde le code comme nom (pas de seconde passe). Noms fixes pour `IM`, `SC`, `WK`, `NP`, `NT` (ceux des Interfaces), même si une ligne `QS` existe. `_on_mq` garde son extraction IMDb et ajoute la note (codes retenus seulement, premier gagnant par code) ; le tri final se fait dans `_finalize` (ou à l'ajout).

- [ ] **Step 4: Lancer les tests** — Expected: PASS ; puis `uv run pytest -q` complet.

- [ ] **Step 5: Commit**

```bash
git add server/rc/catalog/model.py server/rc/catalog/parser.py server/tests/
git commit -m "feat(catalogue): notes et liens des œuvres (lignes MQ et QS)"
```

---

### Task 2: Base v4, import des notes, provenance des affiches, API

**Files:**
- Modify: `server/rc/schema.sql` (table `work`), `server/rc/db.py` (`SCHEMA_VERSION`, migration), `server/rc/catalog/importer.py` (`_payload` l. 63, `_write_work` l. 190), `server/rc/assets.py` (`ingest_posters`, commit l. 119-135), `server/rc/api/serialize.py` (`_HIDDEN` l. 17)
- Test: `server/tests/test_db*.py`, `server/tests/test_import*.py`, `server/tests/test_assets*.py`, `server/tests/test_api.py`

**Interfaces:**
- Consumes : `ParsedWork.ratings` (Task 1).
- Produces :
  - colonnes `work.ratings_json TEXT NOT NULL DEFAULT '[]'`, `work.tmdb_url TEXT`, `work.poster_source TEXT` (`'local'` | `'tmdb'` | `'frame'` | NULL) ;
  - `SCHEMA_VERSION = 4` ;
  - `ratings` (liste de `{"code","name","value","url"}`) et `tmdb_url` dans chaque œuvre de `/api/sync` et `/api/works/{id}` (par `serialize.row`) ; `poster_source` jamais exposé.

- [ ] **Step 1: Tests en échec**

```python
def test_import_writes_ratings_json(conn):  # catalogue avec le SAMPLE de la Task 1 → json.loads(work.ratings_json) == liste attendue (dicts)
def test_rating_change_bumps_work_revision(conn):  # changer une note → nouveau rev, nouveau content_hash
def test_api_exposes_ratings_and_tmdb_url(env):  # /api/works/{id} et /api/sync : "ratings" (liste), "tmdb_url" (None), pas de "poster_source"
def test_local_poster_sets_source_local(...):  # ingest_posters → poster_source == "local"
def test_local_poster_replaces_frame_and_tmdb(...):
    # œuvre avec poster_asset=X, poster_source='frame' (puis 'tmdb') ; une ligne AF apparaît au réimport
    # → poster_asset et poster_source remis à NULL par l'import, puis ingest_posters → 'local'
def test_v3_database_migrates_to_v4(tmp_path):
    # base créée en v3 (sans les 3 colonnes), avec une affiche locale et une TMDB (asset.source = relpath / 'tmdb')
    # rouverte → user_version 4, colonnes présentes, poster_source 'local' / 'tmdb', meta needs_reimport = '1'
```

- [ ] **Step 2: Lancer les tests** — Expected: FAIL.

- [ ] **Step 3: Implémenter**

- `schema.sql` : les trois colonnes dans `CREATE TABLE work`.
- `db.py` : liste `_V4_COLUMNS` et migration v≤3 → v4 sur le modèle de `_migrate_v1_to_v2` (`ALTER TABLE` si absente) ; puis `UPDATE work SET poster_source = CASE WHEN a.source = 'tmdb' THEN 'tmdb' ELSE 'local' END` pour les œuvres ayant `poster_asset` ; `needs_reimport = '1'` (les notes ne sont pas encore lues). Une base v1 passe par v2 puis v4.
- `importer.py` : `"ratings": [dataclass → dict]` dans `_payload` (donc dans l'empreinte) ; `_write_work` écrit `ratings_json` ; la règle existante qui remet `poster_asset` à NULL quand `poster_relpath` change remet aussi `poster_source` à NULL.
- `assets.py` : le commit d'`ingest_posters` écrit `poster_source = 'local'`.
- `serialize.py` : `poster_source` dans `_HIDDEN`.

- [ ] **Step 4: Suite complète** — Run: `cd server && uv run pytest -q` — Expected: tout passe (adapter les tests qui comparent un dict d'œuvre complet).

- [ ] **Step 5: Commit**

```bash
git add server/rc/schema.sql server/rc/db.py server/rc/catalog/importer.py server/rc/assets.py server/rc/api/serialize.py server/tests/
git commit -m "feat(serveur): notes des œuvres dans l'API, provenance des affiches ; schéma v4"
```

---

### Task 3: TMDB — lien de la fiche, remplacement des images extraites, recherche d'identifiant seule

**Files:**
- Modify: `server/rc/tmdb.py` (`_find_poster_path` l. 37, `fetch_missing_posters` l. 95, `_commit`), `server/rc/app.py` (`_tmdb_step` l. 254)
- Test: `server/tests/test_tmdb.py`

**Interfaces:**
- Consumes : colonnes `tmdb_url`, `poster_source` (Task 2).
- Produces :
  - `_find(client, imdb_id, api_key) -> tuple[str | None, str | None]` : (chemin de l'affiche, URL de la fiche TMDB) ; URL `https://www.themoviedb.org/movie/<id>` (`movie_results`) ou `https://www.themoviedb.org/tv/<id>` (`tv_results`), du résultat retenu ;
  - `fetch_missing_posters` : candidates = `imdb_id` non nul, non marquée `tmdb_tried:<id>`, et (`poster_asset IS NULL` ou `poster_source = 'frame'`) ; succès → `poster_asset`, `poster_source = 'tmdb'`, `tmdb_url` ; une recherche aboutie sans affiche écrit quand même `tmdb_url` ;
  - `fetch_missing_tmdb_links(conn, api_key, client, limit=20) -> int` : œuvres avec `imdb_id`, `tmdb_url IS NULL`, sans repère `tmdb_link_tried:<id>` → `/find` seul, écrit `tmdb_url` ; repère posé sur issue définitive (même règle que les affiches) ;
  - `_tmdb_step` appelle `fetch_missing_posters` puis `fetch_missing_tmdb_links`.

- [ ] **Step 1: Tests en échec** (transport httpx factice, comme les tests TMDB existants)

```python
async def test_poster_found_stores_tmdb_url_and_source(...):  # movie_results id 4242 → tmdb_url ".../movie/4242", poster_source "tmdb"
async def test_tv_result_gives_tv_url(...):  # tv_results id 77 → ".../tv/77"
async def test_tmdb_replaces_frame_only_for_untried_works(...):
    # A : poster_source 'frame', jamais essayée → affiche TMDB, source 'tmdb'
    # B : poster_source 'frame', repère tmdb_tried posé → inchangée, aucun appel pour B
async def test_local_poster_never_replaced_by_tmdb(...):  # poster_source 'local' → pas candidate
async def test_link_lookup_for_work_with_local_poster(...):  # source 'local', tmdb_url NULL → fetch_missing_tmdb_links écrit l'URL, pas de téléchargement d'image
async def test_link_lookup_tried_once(...):  # 404 → repère tmdb_link_tried, second passage sans appel
```

- [ ] **Step 2: Lancer les tests** — Expected: FAIL.

- [ ] **Step 3: Implémenter** — renommer `_find_poster_path` en `_find` (ses appelants et tests suivent) ; le reste selon les Interfaces. Une nouvelle révision dès qu'une œuvre change (`poster_asset` ou `tmdb_url`).

- [ ] **Step 4: Suite complète** — Expected: tout passe.

- [ ] **Step 5: Commit**

```bash
git add server/rc/tmdb.py server/rc/app.py server/tests/
git commit -m "feat(tmdb): lien de la fiche TMDB ; l'affiche TMDB remplace l'image extraite"
```

---

### Task 4: Image extraite de la vidéo

**Files:**
- Create: `server/rc/frames.py`
- Modify: `server/rc/analysis.py` (`analysis_loop` l. 157)
- Test: `server/tests/test_frames.py`

**Interfaces:**
- Consumes : `process_image(src, assets_dir) -> str` (`rc/assets.py`), `resolve_media_path` (`rc/volumes.py`), `QUALITY_ORDER`, colonnes de la Task 2.
- Produces :
  - `frame_time(duration_s: float | None) -> float` : `0.10 * duration` si durée > 0 ; sinon 300.0 ;
  - `frame_candidates(conn, tmdb_enabled: bool) -> list[int]` : œuvres jouables (au moins un fichier présent) avec `poster_asset IS NULL`, et dont TMDB ne peut plus rien donner : `not tmdb_enabled` ou `imdb_id IS NULL` ou repère `tmdb_tried:<id>` ; et dont le fichier choisi est analysé (`width IS NOT NULL OR duration_s IS NOT NULL`) ;
  - `frame_source(conn, work_id, indexes) -> tuple[Path, float | None] | None` : film → meilleure version présente (ordre `QUALITY_ORDER`, puis id) ; œuvre épisodique → premier épisode (saison puis numéro) ayant un fichier présent, sa meilleure version ; renvoie le chemin et `duration_s` ;
  - `extract_frame(path: Path, at: float, dest: Path, runner=subprocess.run) -> None` : `ffmpeg -v error -y -ss <at> -i <path> -frames:v 1 <dest>` (dest en `.jpg`), délai 60 s ;
  - `extract_one(conn, assets_dir, indexes, failed: set[int], tmdb_enabled: bool) -> bool` (bloquant, appelé en fil auxiliaire) : prend la première candidate non écartée, extrait dans un fichier temporaire (sans durée connue : à 300 s, puis à 0 s si ffmpeg n'a produit aucune image, cas d'un fichier plus court), `process_image`, écrit `poster_asset`, `poster_source = 'frame'`, nouvelle révision ; en cas d'échec (ffmpeg absent, code d'erreur, image illisible) journalise et ajoute l'œuvre à `failed` ; vrai si une affiche a été écrite.
  - `analysis_loop` : quand la file d'analyse est vide (avant de dormir), appelle `extract_one` via `asyncio.to_thread` avec une connexion propre au fil (comme `_analyze_in_thread`), une œuvre par tour ; `failed` est vidé avec celui de l'analyse (nouvel index complet) ; même suspension pendant une lecture ; annonce `catalog` comme après une analyse.

- [ ] **Step 1: Tests en échec**

```python
def test_frame_time_rules():
    assert frame_time(5400.0) == 540.0
    assert frame_time(None) == 300.0
    assert frame_time(0) == 300.0
    assert frame_time(120.0) == 12.0

def test_candidates_respect_priority(conn):
    # local/tmdb présentes → exclues ; sans imdb → candidate ; imdb + clé + pas essayée → exclue ;
    # imdb + clé + tmdb_tried → candidate ; imdb sans clé → candidate ; fichier non analysé → exclue ; non jouable → exclue
def test_frame_source_film_best_present_version(...):  # FHD absent, HD présent → HD
def test_frame_source_series_first_present_episode(...):  # S01E01 absent, S01E02 présent → S01E02
def test_short_file_without_duration_retries_at_zero(...):  # runner : rien écrit à -ss 300, image à -ss 0 → affiche écrite, deux appels
def test_extract_one_writes_frame_poster(tmp_path, ...):  # runner factice qui écrit un JPEG valide → poster_source 'frame', nouvelle révision
def test_extraction_failure_is_skipped_until_full_index(...):  # runner qui lève FileNotFoundError → œuvre dans failed, pas d'exception, prochain appel passe à la suivante
async def test_loop_extracts_only_when_queue_empty_and_not_playing(...):  # comme les tests de boucle existants
```

- [ ] **Step 2: Lancer les tests** — Expected: FAIL (module absent).

- [ ] **Step 3: Implémenter** selon les Interfaces.

- [ ] **Step 4: Essai réel** — sur un fichier de `~/rc-test/disques/VIDEOS`, appeler `extract_frame` puis `process_image` (script jetable hors dépôt, `uv run python`), joindre le chemin de l'image produite au compte rendu.

- [ ] **Step 5: Suite complète et commit**

```bash
git add server/rc/frames.py server/rc/analysis.py server/tests/
git commit -m "feat(serveur): image extraite de la vidéo pour les œuvres sans affiche"
```

---

### Task 5: Pastilles de notes sur la fiche télé (maquette A)

**Files:**
- Modify: `tv-ui/sheets.js` (`renderWork`, entre la ligne « Réalisation » et `${status}${progress}`), `tv-ui/style.css`, `tv-ui/dev/preview.json`

**Interfaces:**
- Consumes : `ratings` de `/api/works/{id}` (arbitrage 24).
- Produces : classe `.ratings` (rangée) et `.rating` (pastille), fonction `ratingPills(ratings) -> string` dans `sheets.js`.

- [ ] **Step 1: Implémenter**

Pastilles pour `IM`, `SC` et `SC@…` ayant `value` non nul, dans l'ordre reçu ; jamais `NP`, `NT`, `WK`. Texte : nom en couleur atténuée, valeur en gras, au format français à une décimale pour `IM` et `SC` (« 7,0 », « 6,2 ») et sans décimale inutile pour les critiques (« 8 », « 7,5 »). Valeur en couleur d'accent pour `IM` et `SC`, blanche pour les critiques. Style de référence (rendu validé par l'utilisateur) : rangée `display:flex; flex-wrap:wrap; gap:14px; margin-top:18px; font-size:26px` ; pastille `padding:6px 16px; border-radius:999px; background:rgba(255,255,255,.07); border:1px solid rgba(255,255,255,.12)`, nom et valeur séparés de 12 px — à exprimer avec les variables de couleur existantes de `style.css`. Rien si aucune pastille.

Aperçus : ajouter `ratings` à l'œuvre de l'aperçu `work` (IMDb 7.0, Sens Critique 6.2, Éric Schwald 8, Antoine Goya 7, plus `NP` 7 et `WK` sans valeur, qui ne doivent pas s'afficher) ; nouvel aperçu `work-notes-many` (six critiques : la rangée passe à la ligne) ; l'aperçu `work-dvd` reste sans notes.

- [ ] **Step 2: Vérification visuelle** — Playwright 1920×1080, heure figée, animations coupées (protocole de l'étape 3) : capturer `work`, `work-notes-many`, `work-dvd`. Attendu : `work` conforme au rendu de référence (quatre pastilles sur une ligne, sans NP ni WK) ; `work-dvd` identique au pixel à sa capture d'avant la modification (prendre la capture avant). Joindre les chemins des captures.

- [ ] **Step 3: Commit**

```bash
git add tv-ui/sheets.js tv-ui/style.css tv-ui/dev/preview.json
git commit -m "feat(tv): notes en pastilles sur la fiche de l'œuvre"
```
