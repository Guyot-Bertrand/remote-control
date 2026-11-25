# Itération 2 — Serveur et écran télé — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Le serveur fournit les données manquantes (langues, résolution, durées, support, total de synchro), reçoit la vue du téléphone et la fait afficher par la télé, et met la vidéo en pause de côté (capture + mpv en arrière-plan) puis la ramène à la reprise.

**Architecture:**
- `rc/analysis.py` : analyse ffprobe en tâche de fond.
- `rc/mirror.py` : `MirrorService` reçoit les vues, décide de ce qu'affiche la télé et déclenche la mise de côté.
- `PlaybackService` gagne `park()` et l'état « mis de côté ».
- `TvController` gagne les états `view` et `paused`.
- `serialize.enrich()` complète les identifiants envoyés par le téléphone.
- L'écran télé (`tv-ui/`, sans framework) est découpé en `app.js` (état, WebSocket), `views.js` (vues miroir) et `pause.js` (coin d'état et plein écran en pause).

**Tech Stack:** Python 3.12, uv, FastAPI, sqlite3, pytest/pytest-asyncio ; mpv (IPC JSON), ffprobe ; HTML/CSS/JS sans framework.

**Spec:** `docs/superpowers/specs/2026-11-24-iteration-2-design.md` — **contrat :** `docs/superpowers/plans/2026-11-22-api-contract-rulings.md`, arbitrages 13 à 19 (ils priment sur toute forme JSON écrite ici). **Maquettes (font foi pour les écrans) :** `docs/design/Remote-Control - 4a - Télé, vues miroir.html`, `… 4b - Saison, pause, composants.html`, et pour l'identité `… 1 - Identité & composants.html`, `… 2 - Ecran télé.html`. Ce sont des fichiers Claude Design autonomes : les servir avec `python3 -m http.server` depuis `docs/design/` et les ouvrir avec Playwright (ils se décompressent dans la page).

## Global Constraints

- Textes visibles en **français**. Messages d'erreur `{error, volume_label, message}` (arbitrage 9).
- `medias.txt` en lecture seule. Données personnelles `docs/data/` jamais commitées.
- Écran télé : 1920×1080, **pas de flou ni de `backdrop-filter`**, animations limitées à `transform`/`opacity`, polices locales Fraunces/Inter, variables CSS de l'identité existante.
- La télé est passive : aucun focus, aucun curseur.
- **Anti-objectifs :**
  - la télé ne change jamais d'écran pendant une lecture non en pause ;
  - pas de clignotement à chaque envoi ;
  - la vignette de pause ne masque rien (elle remplace l'horloge du coin d'état, environ 448×252, en haut à droite).
- Vue du téléphone : `items` ≤ 40, `sections` ≤ 6 × 40. Retour à la veille après **10 min** sans vue (hors pause).
- `pause_background` ∈ `"minimize"`, `"restart"` ; défaut `"restart"` jusqu'à l'essai de la Task 14.
- Analyse : un fichier à la fois, `nice -n 19` si disponible, **suspendue pendant une lecture** ; annonce `catalog` au plus toutes les 30 s.
- Serveur : tous les tests passent (`cd server && uv run pytest -q`), y compris les 264 existants.

## Review Focus

1. **Base de l'itération 1 déjà remplie** (schéma v1, catalogue importé, copie inchangée) → au démarrage, migration vers v2 puis réimport depuis la copie, pour que `medium` apparaisse sans modifier `medias.txt`. Test : Task 1 `test_v1_database_migrates_and_reimports`.
2. **Disque débranché pendant que la vidéo est mise de côté (`restart`)** → à la reprise, pas de plantage. La lecture se termine, la position est gardée et la télé affiche « Disque « X » non branché ». Test : Task 7 `test_unpark_with_volume_absent_ends_with_message`.
3. **Deux téléphones, ou réponses réordonnées** → une vue plus ancienne n'écrase pas la plus récente du même téléphone. Le dernier téléphone qui a envoyé pilote. Test : Task 9 `test_stale_seq_ignored_per_phone`.
4. **Pause depuis le mini-lecteur alors que le téléphone est déjà sur une autre vue** (aucune vue envoyée ensuite) → la vidéo est mise de côté dès la pause. Test : Task 9 `test_pause_while_phone_elsewhere_parks`.
5. **Ancien cache d'analyse (format v1, sans `width`/`duration`)** → relu comme périmé et ré-analysé, sans `KeyError`. Test : Task 3 `test_old_cache_format_is_reprobed`.

---

## Structure des fichiers

```
server/rc/
  schema.sql            # + colonnes v2
  db.py                 # SCHEMA_VERSION 2, migration 1→2, drapeau de réimport
  catalog/model.py      # ParsedWork.media
  catalog/parser.py     # garde les codes physiques
  catalog/importer.py   # work.medium
  probe.py              # width, duration ; media_summary()
  analysis.py           # NOUVEAU : analyse de fond
  config.py             # pause_background
  player/base.py        # Player.screenshot, Player.set_minimized
  player/mpv.py         # idem
  player/session.py     # TvController (view/paused), PlaybackService.park()
  mirror.py             # NOUVEAU : MirrorService
  api/serialize.py      # total_works, enrich()
  api/routes.py         # POST /api/tv/view, GET /api/player/pause.jpg
  app.py                # câblage, tâche d'analyse, réimport après migration
server/tests/
  fake_player.py        # screenshot, set_minimized
  test_db.py test_parser_works.py test_importer.py test_probe.py
  test_analysis.py (nouveau) test_mpv.py test_session.py test_park.py (nouveau)
  test_enrich.py (nouveau) test_mirror.py (nouveau) test_api.py
tv-ui/
  index.html app.js views.js (nouveau) pause.js (nouveau) style.css
  dev/preview.json (nouveau)   # états d'exemple pour ?preview=<nom>
```

---

### Task 1: Schéma v2, migration et réimport

**Files:**
- Modify: `server/rc/schema.sql`, `server/rc/db.py`, `server/rc/app.py` (`restore_from_copy`)
- Test: `server/tests/test_db.py`, `server/tests/test_app.py`

**Interfaces:**
- Produces:
  - nouvelles colonnes `work.medium TEXT`, `episode.duration_min INTEGER` ;
  - colonnes `media_file.audio_langs_json TEXT`, `subtitle_langs_json TEXT`, `width INTEGER`, `height INTEGER`, `duration_s REAL` (toutes NULL par défaut) ;
  - `SCHEMA_VERSION = 2` ;
  - clé `meta` `needs_reimport` = `'1'` après une migration 1→2 ;
  - `serialize.row()` décode `audio_langs_json` → `audio_langs` sans autre changement (règle du suffixe existante). Un `NULL` doit rester `null` : adapter `row()` pour ne pas appeler `json.loads(None)`.

- [ ] **Step 1: Tests**

```python
def test_fresh_database_is_v2(tmp_path):
    conn = open_db(tmp_path / "rc.db")
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 2
    cols = {r[1] for r in conn.execute("PRAGMA table_info(media_file)")}
    assert {"audio_langs_json", "subtitle_langs_json", "width", "height", "duration_s"} <= cols
    assert "medium" in {r[1] for r in conn.execute("PRAGMA table_info(work)")}
    assert conn.execute("SELECT value FROM meta WHERE key='needs_reimport'").fetchone() is None

def test_v1_database_migrates(tmp_path):
    # base v1 : schéma de l'itération 1 (copie figée dans tests/fixtures/schema_v1.sql)
    ...créer la base avec schema_v1.sql et user_version=1, une œuvre insérée...
    conn = open_db(tmp_path / "rc.db")
    assert conn.execute("PRAGMA user_version").fetchone()[0] == 2
    assert conn.execute("SELECT medium FROM work").fetchone()[0] is None
    assert conn.execute("SELECT value FROM meta WHERE key='needs_reimport'").fetchone()[0] == "1"

def test_row_keeps_null_json_columns_null(): ...  # media_file sans analyse → audio_langs is None
```

Dans `test_app.py`, ajouter `test_v1_database_migrates_and_reimports` :
- base v1 avec une œuvre DVD et la copie `catalog/medias.txt` en place ;
- `create_app` est appelé ;
- résultat attendu : `work.medium == "DVD"`, et `needs_reimport` est supprimé.

- [ ] **Step 2: Run** `cd server && uv run pytest tests/test_db.py tests/test_app.py -q` — Expected: FAIL.
- [ ] **Step 3: Implement**
  - Copier le `schema.sql` actuel dans `tests/fixtures/schema_v1.sql`.
  - Ajouter les colonnes à `schema.sql`.
  - Dans `open_db`, quand `version == 1`, lancer les `ALTER TABLE … ADD COLUMN` puis poser `needs_reimport`. Ordre à respecter : `executescript(schema)` d'abord. Il ne recrée pas les tables existantes, et un `ALTER` est sans effet si la colonne existe déjà : vérifier avec `PRAGMA table_info`.
  - `restore_from_copy` réimporte aussi quand `needs_reimport` est posé (`force=True`), puis efface la clé.
- [ ] **Step 4: Run** the same — Expected: PASS ; puis `uv run pytest -q` complet : PASS.
- [ ] **Step 5: Commit** `feat(serveur): schéma v2 et réimport après migration`

### Task 2: Support physique (`medium`) et `total_works`

**Files:**
- Modify: `server/rc/catalog/model.py`, `parser.py`, `importer.py`, `server/rc/api/serialize.py`
- Test: `server/tests/test_parser_works.py`, `test_importer.py`, `test_api.py`

**Interfaces:**
- Consumes: colonne `work.medium` (Task 1).
- Produces:
  - `ParsedWork.media: list[str]` : codes physiques vus, dans l'ordre du fichier, parmi `BR DVD DVR DVT DVS` ;
  - `catalog/paths.py` : `MEDIUM_LABELS = {"BR": "Blu-ray", "DVD": "DVD", "DVR": "DVD (copie)", "DVT": "DVD (TNT)", "DVS": "DVD (satellite)"}` (l'ordre du dict donne la priorité) et `medium_of(codes: list[str], playable: bool) -> str | None` ;
  - `_payload()` ajoute `"medium"`, donc le `content_hash` change ;
  - `serialize.sync()` ajoute `"total_works": len(works)` quand `full`.

- [ ] **Step 1: Tests**

```python
def test_medium_priority():
    assert medium_of(["DVD", "BR"], playable=False) == "Blu-ray"
    assert medium_of(["DVS"], playable=False) == "DVD (satellite)"
    assert medium_of(["DVD"], playable=True) is None
    assert medium_of([], playable=False) is None

def test_parser_keeps_physical_codes():  # MF ; VI ; DVD ; ; (sans fichier)
    w = parse_catalog(SAMPLE_WITH_DVD).works[0]
    assert w.media == ["DVD"]

def test_import_stores_medium(conn):  # œuvre DVD seule → "DVD" ; œuvre DVD + FHD lisible → None
def test_full_sync_has_total_works(client):
    assert client.get("/api/sync").json()["total_works"] == <nombre d'œuvres de la fixture>
    assert "total_works" not in client.get("/api/sync?since=0").json()
```

- [ ] **Step 2: Run** `uv run pytest tests/test_parser_works.py tests/test_importer.py tests/test_api.py -q` — Expected: FAIL.
- [ ] **Step 3: Implement.**
  - `_on_video` ajoute `quality` à `st.work.media` quand le code est dans `MEDIUM_LABELS`.
  - `playable` = l'œuvre ou l'un de ses épisodes a au moins une vidéo lisible.
  - `_write_work` écrit `medium`.
- [ ] **Step 4: Run** — Expected: PASS ; suite complète PASS.
- [ ] **Step 5: Commit** `feat(serveur): support physique des œuvres non numérisées`

### Task 3: Analyse étendue (résolution, durée, langues)

**Files:**
- Modify: `server/rc/probe.py`
- Test: `server/tests/test_probe.py`

**Interfaces:**
- Produces:
  - `MediaTracks` gagne `width: int | None = None` et `duration: float | None = None`, en derniers champs avec défaut pour que les constructions positionnelles existantes restent valides.
  - `probe_tracks` lance `ffprobe -v error -show_streams -show_format -of json`. `duration` vient de `format.duration` (réel), `width` du premier flux vidéo. La commande est préfixée par `nice -n 19` quand `shutil.which("nice")` répond.
  - `_from_json` : un ancien cache sans `width`/`duration` lève `KeyError`. `cached_tracks` le traite comme absent et ré-analyse.
  - `media_summary(t: MediaTracks) -> dict` renvoie `{"audio_langs": list[str], "subtitle_langs": list[str], "width", "height", "duration_s"}`. Langues sans doublon, dans l'ordre ; `None` omis. `subtitle_langs` = pistes intégrées puis `t.external`.

- [ ] **Step 1: Tests**

```python
def test_probe_reads_width_and_duration():  # runner factice : streams vidéo 624x352, format.duration "2490.5"
    t = probe_tracks(Path("x.mkv"), runner=fake)
    assert (t.width, t.height, t.duration) == (624, 352, 2490.5)

def test_old_cache_format_is_reprobed(conn, tmp_path):
    # probe_cache avec un tracks_json v1 (sans width/duration), mtime/size identiques
    t = cached_tracks(conn, 1, video, runner=fake)
    assert t.width == 624 and fake.calls == 1

def test_media_summary_dedupes_and_orders():
    t = MediaTracks([Track(1,"en",None,None,True), Track(2,"en",None,None,False), Track(3,None,None,None,False)],
                    [Track(1,"fr",None,None,False)], [ExternalSub(Path("a.fr.srt"),"fr"), ExternalSub(Path("a.de.srt"),"de")],
                    "h264", 352, width=624, duration=2490.5)
    assert media_summary(t) == {"audio_langs": ["en"], "subtitle_langs": ["fr", "de"],
                                "width": 624, "height": 352, "duration_s": 2490.5}
```

- [ ] **Step 2: Run** `uv run pytest tests/test_probe.py -q` — Expected: FAIL.
- [ ] **Step 3: Implement** as specified.
- [ ] **Step 4: Run** — PASS ; suite complète PASS.
- [ ] **Step 5: Commit** `feat(serveur): résolution, durée et langues dans l'analyse`

### Task 4: Analyse en tâche de fond

**Files:**
- Create: `server/rc/analysis.py`
- Modify: `server/rc/app.py` (lancement de la tâche dans `lifespan`)
- Test: `server/tests/test_analysis.py`

**Interfaces:**
- Consumes: `cached_tracks`, `media_summary` (Task 3) ; `load_tracks` (session.py, pour les sous-titres externes) ; `resolve_media_path` ; colonnes de la Task 1 ; `publish_catalog(state, rev)`.
- Produces:
  - `pending_files(conn: sqlite3.Connection, indexes, recheck: bool) -> list[tuple[int, Path]]` : fichiers `present` résolus, sans analyse (`width IS NULL AND duration_s IS NULL`), ou tous si `recheck` (le cache décide alors).
  - `analyze_file(conn, media_file_id: int, path: Path) -> bool` : bloquant. Il écrit les colonnes du fichier, plus `episode.duration_min` si le fichier est le mieux classé de son épisode, avec `next_revision` dans une transaction `BEGIN IMMEDIATE`. Il renvoie vrai si une valeur a changé. Sans changement : aucune révision.
  - `async analysis_loop(state: AppState, sleep=asyncio.sleep) -> None`. Elle tourne sans fin :
    - pendant une lecture (`state.playback.current is not None`), elle attend `poll_interval` ;
    - sinon, elle prend le fichier suivant et l'analyse avec `asyncio.to_thread`, sur sa propre connexion comme `_posters_in_thread` ;
    - elle appelle `publish_catalog` si 30 s se sont écoulées depuis la dernière annonce, ou quand la file se vide ;
    - quand la file est vide, elle dort `poll_interval` ;
    - `recheck` est vrai au premier passage qui suit chaque nouvel index complet (`state.last_full_index` changé).
  - Une erreur ffprobe sur un fichier est journalisée une fois. Le fichier est alors marqué analysé avec `width = 0`, ce qui évite de recommencer à chaque tour ; `serialize` renvoie `null` pour `width = 0`.

- [ ] **Step 1: Tests** (avec `cached_tracks` remplacé par une fonction de test, et l'horloge et `sleep` injectés)

```python
def test_analyze_file_writes_columns_and_bumps_revision(conn): ...
    assert row["audio_langs_json"] == '["en"]' and row["width"] == 624 and current_revision(conn) == rev0 + 1
def test_analyze_unchanged_file_keeps_revision(conn): ...
def test_episode_duration_from_best_file(conn):  # 2490.5 s → duration_min 42
async def test_loop_waits_during_playback(state): ...  # aucun appel d'analyse tant que playback.current
async def test_loop_announces_at_most_every_30s(state): ...  # 3 fichiers en 10 s d'horloge → une seule annonce, à la fin de file
def test_probe_failure_marks_file_and_is_not_retried(conn): ...
```

- [ ] **Step 2: Run** `uv run pytest tests/test_analysis.py -q` — Expected: FAIL.
- [ ] **Step 3: Implement**, puis ajouter `asyncio.create_task(analysis_loop(state))` dans `lifespan`.
- [ ] **Step 4: Run** — PASS ; suite complète PASS.
- [ ] **Step 5: Commit** `feat(serveur): analyse des fichiers en tâche de fond`

### Task 5: Capture et réduction de fenêtre dans le lecteur

**Files:**
- Modify: `server/rc/player/base.py`, `server/rc/player/mpv.py`, `server/tests/fake_player.py`, `server/tests/fake_mpv.py` (si une commande manque)
- Test: `server/tests/test_mpv.py`

**Interfaces:**
- Produces, dans le `Protocol` `Player` :
  - `async def screenshot(self, dest: Path) -> None`, qui envoie `screenshot-to-file <dest> video` (sans sous-titres ni OSD) ;
  - `async def set_minimized(self, minimized: bool) -> None`, qui pose `window-minimized`. Pour `False`, il pose aussi `fullscreen` à `True`.
- `MpvError` est levée si mpv refuse.
- `FakePlayer` :
  - `screenshot` enregistre l'appel et écrit `b"\xff\xd8fake"` dans `dest`, sauf si `screenshot_error` est posé ;
  - `set_minimized` enregistre l'appel et lève `minimize_error` s'il est posé.

- [ ] **Step 1: Tests**

```python
async def test_screenshot_sends_video_flag(mpv_env):
    await player.screenshot(tmp / "p.jpg")
    assert ["screenshot-to-file", str(tmp / "p.jpg"), "video"] in fake.commands
async def test_unminimize_restores_fullscreen(mpv_env):
    await player.set_minimized(False)
    assert ["set_property", "window-minimized", False] in fake.commands
    assert ["set_property", "fullscreen", True] in fake.commands
```

- [ ] **Step 2–4:** run `uv run pytest tests/test_mpv.py -q` (FAIL → implement → PASS), puis la suite complète.
- [ ] **Step 5: Commit** `feat(lecteur): capture d'image et réduction de la fenêtre`

### Task 6: États télé `view` et `paused`

**Files:**
- Modify: `server/rc/player/session.py` (`TvState`, `TvController`)
- Test: `server/tests/test_session.py`

**Interfaces:**
- Produces :
  - `Screen` ajoute `"view"` et `"paused"`.
  - `TvState` gagne `view: dict | None = None` et `pause: dict | None = None` (arbitrage 15).
  - `TvController.show_view(view: dict, pause: dict | None = None) -> None` : affiche, et retient comme dernier écran hors lecture.
  - `TvController.show_paused(pause: dict) -> None`.
  - `TvController.remember_view(view: dict) -> None` : retient sans afficher.
  - `TvController.update_pause(pause: dict) -> None` : republie l'état courant avec la nouvelle `pause` si l'écran est `view` ou `paused`.
  - `playback_ended(None)` affiche le dernier écran retenu, vue ou fiche, **sans** `pause`.
  - `show()` (`tv/show`) retient lui aussi la fiche comme dernier écran.

- [ ] **Step 1: Tests**

```python
def test_show_view_publishes_view_and_pause(bus): ...
    assert last_event(bus)["state"] == {"screen": "view", "work_id": None, "season_id": None,
                                        "message": None, "view": V, "pause": None}
def test_playback_end_returns_to_last_view_without_pause(bus): ...
def test_remember_view_does_not_publish(bus): ...
def test_update_pause_only_on_view_or_paused(bus): ...
```

- [ ] **Step 2–4:** `uv run pytest tests/test_session.py -q` FAIL → implement → PASS ; suite complète.
- [ ] **Step 5: Commit** `feat(télé): états vue et pause`

### Task 7: Mettre la vidéo de côté (`park`) et la ramener

**Files:**
- Modify: `server/rc/player/session.py` (`PlaybackService`, `_Session`), `server/rc/config.py`
- Test: `server/tests/test_park.py` (nouveau ; fixture `env` copiée de `test_session.py`)

**Interfaces:**
- Consumes: `Player.screenshot`, `Player.set_minimized` (Task 5) ; `TvController.update_pause` et `set_playing` (Task 6).
- Produces:
  - `Config.pause_background: str = "restart"` ;
  - `PlaybackService(..., pause_image: Path, pause_mode: Callable[[], str])` ;
  - `async park() -> dict | None` : met de côté si la vidéo est en pause et pas déjà de côté. Il renvoie `pause_info()`, ou `None` s'il n'y a rien à faire ;
  - `parked: bool` ;
  - `pause_info() -> dict | None`, au format `pause` de l'arbitrage 15. `image` vaut `f"/api/player/pause.jpg?v={n}"`, où `n` augmente à chaque capture réussie, ou `None` ;
  - `on_pause_change: Callable[[bool], None]`, attribut appelé quand `paused` change pendant une session. La Task 9 s'y abonne.
- Comportement :
  - **park :**
    - `screenshot(pause_image)`. En cas d'échec, l'image vaut `None` et la suite continue.
    - Mode `minimize` : `set_minimized(True)`. En cas d'échec, journaliser et passer en mode `restart` pour cette pause.
    - Mode `restart` :
      - enregistrer la progression (`_save`) ;
      - garder `path`, `position`, `duration`, `volume`, `audio`, `subtitle` dans la session (`_Session` gagne `path: Path` et `parked: str | None`) ;
      - arrêter le lecteur sans terminer la session : ignorer les `_on_state` pendant cet arrêt et tant que la session est `parked == "restart"`.
    - `self._state` vaut `running=True, paused=True`, position de la pause. Publier.
  - **Commandes de côté, mode `restart` :**
    - `seek` met à jour la position gardée, bornée à `[0, duration]`, puis publie `player` et appelle `tv.update_pause` ;
    - `set_volume`, `set_audio` et `set_subtitle` valident comme aujourd'hui puis mémorisent ;
    - `pause(True)` ne fait rien ;
    - `pause(False)` ramène la vidéo.
  - **Commandes de côté, mode `minimize` :** les commandes vont à mpv comme d'habitude, et `pause(False)` ramène la vidéo.
  - **Ramener (`_unpark`) :**
    - mode `minimize` : `set_minimized(False)`, puis `set_pause(False)` ;
    - mode `restart` : re-résoudre la version, puis `player.start(path, position, audio, subtitle, external)` et `set_volume` si différent de 100 ;
    - dans les deux cas : `tv.set_playing(work_id)` et publier.
    - Si la re-résolution échoue (`volume_absent` ou `not_found`) : terminer la session (`_finish` avec `message(code, label)`), sans exception vers l'appelant.
  - **`play()` du même média pendant la mise de côté :** appliquer `start`/pistes à l'état gardé, puis ramener. **`play()` d'un autre média ou `stop()` :** `_finish` normal. La position gardée est enregistrée et `parked` est remis à `None`.

- [ ] **Step 1: Tests**

```python
async def test_park_restart_captures_saves_and_stops(env):  # en pause à 1394 s
    info = await svc.park()
    assert ("stop",) in player.calls and info["position"] == 1394 and info["image"].endswith("v=1")
    assert svc.state.running and svc.state.paused and svc.current is not None
    assert progress_row()["position"] == 1394
async def test_park_ignored_when_playing_not_paused(env): assert await svc.park() is None
async def test_seek_while_parked_updates_kept_position(env): ...  # +60 → 1454, tv.update_pause appelé
async def test_resume_restarts_at_kept_position_with_tracks(env):
    await svc.pause(False)
    assert player.starts()[-1]["start_at"] == 1454 and player.starts()[-1]["audio"] == 2
    assert tv.state.screen == "playing" and not svc.parked
async def test_volume_reapplied_after_restart(env): ...
async def test_stop_while_parked_finishes_session(env): ...
async def test_play_same_media_while_parked_unparks(env): ...  # start="start" → start_at 0
async def test_unpark_with_volume_absent_ends_with_message(env):
    # index vidé avant la reprise
    await svc.pause(False)
    assert svc.current is None and tv.state.screen == "message" and "non branché" in tv.state.message
async def test_park_minimize_keeps_player_running(env): ...  # set_minimized(True), aucun stop
async def test_minimize_failure_falls_back_to_restart(env): ...
async def test_screenshot_failure_gives_null_image(env): ...
async def test_on_pause_change_called(env): ...
```

- [ ] **Step 2: Run** `uv run pytest tests/test_park.py -q` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS ; suite complète PASS (`test_session.py` inchangé).
- [ ] **Step 5: Commit** `feat(lecture): mise de côté de la vidéo en pause`

### Task 8: Enrichissement des éléments de vue

**Files:**
- Modify: `server/rc/api/serialize.py`
- Test: `server/tests/test_enrich.py`

**Interfaces:**
- Produces: `enrich(conn, refs: list[dict], collection_id: int | None) -> list[dict]`.
  - Il suit la forme de l'arbitrage 16, dans l'ordre reçu, et omet les identifiants inconnus.
  - Il fait au plus une requête SQL par `kind`, avec `IN (…)`.
- Règles de calcul :
  - `playable` : un `media_file` `present` existe pour l'œuvre ou l'un de ses épisodes.
  - `absent_volume` : l'œuvre n'est pas `playable`, et un de ses fichiers est sur un volume `present = 0`. Prendre le libellé du premier.
  - `progress_ratio` et `remaining_min` :
    - pour une œuvre, d'après sa progression ;
    - pour une œuvre à épisodes, d'après l'épisode le plus récemment touché ;
    - `remaining_min = round((duration - position) / 60)` si non vue et si `duration` est connue.
  - `collection` : `count` = nombre d'œuvres ; `playable_count` = celles qui sont `playable` ; `posters` = 3 premières affiches non nulles par rang (`CAST(rank AS INTEGER)`, `NULL` en dernier).

- [ ] **Step 1: Tests**

```python
def test_enrich_keeps_order_and_drops_unknown(conn):
    out = enrich(conn, [{"kind":"work","id":B}, {"kind":"work","id":999}, {"kind":"work","id":A}], None)
    assert [o["id"] for o in out] == [B, A]
def test_enrich_work_fields(conn): ...      # playable, watched, progress_ratio 0.5, remaining_min 45, medium
def test_enrich_absent_volume(conn): ...    # disque débranché → "VIDEOS"
def test_enrich_rank_only_with_collection(conn): ...
def test_enrich_episode(conn): ...          # work_title "Heroes", season_number 1, number 5, poster de la série
def test_enrich_collection(conn): ...       # count, playable_count, posters ≤ 3
```

- [ ] **Step 2–4:** `uv run pytest tests/test_enrich.py -q` FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(télé): enrichissement des éléments de vue`

### Task 9: `MirrorService`

**Files:**
- Create: `server/rc/mirror.py`
- Test: `server/tests/test_mirror.py`

**Interfaces:**
- Consumes: `enrich` (Task 8) ; `PlaybackService.park`, `parked`, `pause_info`, `on_pause_change`, `current`, `state` (Task 7) ; `TvController.show_view`, `show_paused`, `remember_view`, `show` (Task 6).
- Produces:
  - `MirrorService(conn, tv: TvController, playback: PlaybackService, clock=time.monotonic, idle_after: float = 600)`.
  - `async accept(view: dict) -> bool` : `view` est le corps validé de l'arbitrage 14, déjà en `dict`. Il renvoie `False` si la vue est périmée.
  - `on_pause_change(paused: bool)` : branché sur `playback.on_pause_change`. Si `True` et que la dernière vue acceptée n'est pas `remote`, il programme `park()` puis `show_view` (tâche asyncio).
  - `async idle_loop(sleep=asyncio.sleep)` : toutes les 30 s, vérifie l'inactivité.
- Décision dans `accept`, une fois la vue enrichie (`items` et `sections[].items` passent par `enrich`) :
  1. Lecture en cours et non en pause → `remember_view`.
  2. En pause :
     - si `screen == "remote"` : `show_paused(pause_info())` si `parked`, sinon rien ;
     - sinon : `park()` si besoin, puis `show_view(view, pause_info())`.
  3. Aucune lecture :
     - si `screen == "remote"` : rien ;
     - sinon : `show_view(view)`.
  4. Inactivité : plus de `idle_after` secondes depuis la dernière vue acceptée, écran `view` et `pause is None` → `tv.show("home")`.
- Péremption : la vue est rejetée si `seq <= dernier seq connu pour ce phone_id`. Garder au plus 16 téléphones, en oubliant le plus ancien.

- [ ] **Step 1: Tests** (FakePlayer, horloge injectée)

```python
async def test_view_shown_when_idle(m): assert await m.accept(V_BROWSE) and tv.state.screen == "view"
async def test_stale_seq_ignored_per_phone(m):
    await m.accept({**V_BROWSE, "phone_id": "a", "seq": 5})
    assert not await m.accept({**V_SEARCH, "phone_id": "a", "seq": 4})
    assert await m.accept({**V_SEARCH, "phone_id": "b", "seq": 1})  # autre téléphone : accepté, il pilote
    assert tv.state.view["screen"] == "search"
async def test_view_during_playback_is_remembered_not_shown(m): ...  # écran "playing" inchangé ; à l'arrêt → la vue
async def test_view_while_paused_parks_and_shows_corner(m):
    ...; assert player has ("stop",) and tv.state.screen == "view" and tv.state.pause["position"] == 1394
async def test_remote_view_while_parked_shows_fullscreen_pause(m): ... # screen "paused"
async def test_remote_view_while_paused_not_parked_does_nothing(m): ...
async def test_pause_while_phone_elsewhere_parks(m):
    await m.accept(V_REMOTE); start playback; await m.accept(V_BROWSE)  # mémorisée
    player emits paused=True → await asyncio.sleep(0) ...
    assert svc.parked and tv.state.screen == "view" and tv.state.view["screen"] == "browse"
async def test_idle_returns_to_standby_after_10_min(m): ...  # clock += 601 → screen "home"
async def test_idle_does_not_leave_pause(m): ...
async def test_items_are_enriched(m): ...  # tv.state.view["items"][0]["title_fr"]
```

- [ ] **Step 2–4:** `uv run pytest tests/test_mirror.py -q` FAIL → implement → PASS ; suite complète.
- [ ] **Step 5: Commit** `feat(télé): la télé suit la vue du téléphone`

### Task 10: Routes et câblage

**Files:**
- Modify: `server/rc/api/routes.py`, `server/rc/app.py`
- Test: `server/tests/test_api.py`, `server/tests/test_app.py`

**Interfaces:**
- Consumes: `MirrorService` (Task 9), `PlaybackService(pause_image=…, pause_mode=…)` (Task 7).
- Produces:
  - **`POST /api/tv/view`** : modèles pydantic `ItemRef{kind: Literal["work","episode","collection"], id: int}`, `Person{name, role, count}`, `Section{label: str, people: list[Person] (max 40) = [], items: list[ItemRef] (max 40) = []}` et `TvViewBody`. `TvViewBody` reprend tous les champs de l'arbitrage 14 : `phone_id` `constr(min_length=1, max_length=64)`, `seq` `int ≥ 0`, `items` max 40, `sections` max 6. Le champ `subtitle_track` réutilise `SubSelModel`. La route répond `{"accepted": bool}`.
  - **`GET /api/player/pause.jpg`** : lit `config.data_dir / "pause" / "pause.jpg"` ; `FileResponse` avec `Cache-Control: no-store` ; sinon `not_found()`.
  - `Services` gagne `mirror` ; `create_app` crée le dossier `pause/`, passe `pause_mode=lambda: config.pause_background`, branche `playback.on_pause_change = mirror.on_pause_change` et lance `mirror.idle_loop()` dans `lifespan`.
  - Une valeur inconnue de `pause_background` est journalisée au démarrage et traitée comme `restart`.
  - `hello` (ws.py) publie l'état télé élargi via `asdict(tv.state)`. Vérifier que `view` et `pause` y figurent.

- [ ] **Step 1: Tests**

```python
def test_tv_view_accepts_and_publishes(client): r = client.post("/api/tv/view", json=V); assert r.json() == {"accepted": True}
    assert client.get("/api/tv/state").json()["view"]["items"][0]["title_fr"] == "Bonjour l'angoisse"
def test_tv_view_too_many_items_is_422(client): ...  # 41 items → 422, corps {"error":"invalid",...}
def test_tv_view_stale_seq(client): ...              # {"accepted": False}
def test_pause_image_404_then_served(client, tmp_path): ...  # no-store
def test_hello_includes_view_and_pause_keys(client): ...
```

- [ ] **Step 2–4:** `uv run pytest tests/test_api.py tests/test_app.py -q` FAIL → implement → PASS ; suite complète PASS.
- [ ] **Step 5: Commit** `feat(api): vue du téléphone et image de pause`

### Task 11: Écran télé — socle, coin d'état en pause, plein écran en pause

**Files:**
- Modify: `tv-ui/index.html`, `tv-ui/app.js`, `tv-ui/style.css`
- Create: `tv-ui/pause.js`, `tv-ui/dev/preview.json`

**Interfaces:**
- Consumes: état télé de l'arbitrage 15 (événement `tv`, `hello.tv`), `player` pour faire avancer le temps affiché.
- Produces (fonctions globales, scripts classiques chargés dans l'ordre `pause.js`, `views.js`, `app.js`) :
  - `renderStateCorner(pause) -> string` : HTML du coin d'état en haut à droite. Sans pause, c'est l'horloge actuelle. Avec pause : image, icône pause, titre, « 23:14 / 41:30 », fine barre.
  - `renderPausedFull(pause) -> string` : plein écran en pause. Il reprend la maquette 4b, avec une dérive très lente : `transform: translate`, 120 s aller-retour, pour ne pas marquer l'écran.
  - Dans `app.js` : `show(state)` aiguille vers `view` → `renderView(state.view, state.pause)` (Task 12), et vers `paused` → `renderPausedFull`.
  - Transition entre deux états de même écran (`view` → `view`) : remplacer le contenu par un fondu d'opacité de 200 ms ; ne pas recharger les affiches déjà présentes.
  - **Aperçu de développement :** `?preview=<nom>` charge `dev/preview.json` (`{nom: état}`), affiche l'état sans WebSocket et expose `window.rcShow(state)`.
  - Le fichier d'aperçu contient les données d'exemple du brief, sections 5 et 7 : Heroes S01E05 en pause à 23:14 / 41:30, Bonjour l'angoisse, recherche « tchernia », parcours M · 1 240 / 2 552.

- [ ] **Step 1: Préparer les captures de référence.** Servir `docs/design/` (`python3 -m http.server 8790`) et ouvrir 4b dans Playwright. Capturer le coin d'état en pause et le plein écran en pause dans `/tmp/claude-1000/iter2-ref/`, pour les comparer.
- [ ] **Step 2: Implement** `pause.js`, l'aiguillage dans `app.js` et le CSS, à partir des variables existantes de `style.css`.
- [ ] **Step 3: Vérifier.**
  - Lancer `cd server && uv run rc serve` avec une config de test (comme `~/rc-test/config.toml`, port 8766).
  - Ouvrir `http://localhost:8766/tv/?preview=paused-full` et `?preview=browse-paused` en 1920×1080 avec Playwright, et les comparer aux références.
  - La console ne doit montrer aucune erreur. Contrôler aussi qu'aucun `backdrop-filter` ni `filter: blur` n'apparaît : `grep -n "blur\|backdrop" tv-ui/*.css` ne doit rien renvoyer.
- [ ] **Step 4: Commit** `feat(télé): coin d'état en pause et plein écran en pause`

### Task 12: Écran télé — vues miroir (accueil, parcourir, collection, recherche)

**Files:**
- Create: `tv-ui/views.js`
- Modify: `tv-ui/style.css`, `tv-ui/dev/preview.json`

**Interfaces:**
- Consumes: `view` enrichi (arbitrages 14 et 16), `renderStateCorner` (Task 11).
- Produces: `renderView(view, pause) -> string`, qui aiguille sur `view.screen` vers les fonctions suivantes. Chacune intègre `renderStateCorner(pause)`.
  - **`renderHomeView`.** Salutation selon l'heure : « Bonjour » avant 18 h, « Bonsoir » ensuite. Puis les sections : « Reprendre » avec la barre et « reste N min », « Récemment ajoutés », « Collections » avec « N œuvres · M lisibles ».
  - **`renderBrowseView`.**
    - En-tête `title`, `subtitle`, filtre « Œuvres lisibles » ou « Tout le catalogue ».
    - Grille centrée sur `anchor`.
    - États de vignette : vu, entamé, disque absent avec `absent_volume`, non numérisé avec `medium`, sans affiche.
    - Repère de position : grande lettre laiton, « M · 1 240 / 2 552 » à partir de `position`.
  - **`renderCollectionView`.** Liste par rang, repère « n° 412 · 408–419 sur 1000 » : rang de l'ancre, rangs extrêmes des `items`, `position.total`.
  - **`renderSearchView`.**
    - La requête en grand.
    - Sections Personnes, Œuvres, Aussi connu sous.
    - Surlignage laiton insensible aux accents : `highlight(text, query)`. Il normalise en NFD et retire les diacritiques pour chercher, puis surligne le texte original aux mêmes positions.
    - États « Aucun résultat » (sections vides) et « Tapez au moins deux lettres » (requête de moins de 2 caractères).
  - Une vue d'écran inconnu se replie sur l'accueil miroir.

- [ ] **Step 1: Captures de référence** de chaque vue de 4a, sans et avec pause.
- [ ] **Step 2: Implement** les fonctions et le CSS, plus les états d'aperçu `home`, `browse`, `browse-paused`, `collection`, `search`, `search-empty`, `search-short`.
- [ ] **Step 3: Vérifier.**
  - Capture Playwright de chaque aperçu en 1920×1080, comparée aux références.
  - Aucune erreur console.
  - Passer de `browse` à `browse` (ancre décalée) avec `rcShow` ne doit pas faire clignoter : les affiches déjà présentes ne se rechargent pas. À vérifier dans l'onglet réseau de Playwright.
- [ ] **Step 4: Commit** `feat(télé): vues miroir du catalogue et de la recherche`

### Task 13: Écran télé — fiches enrichies, épisode sélectionné, pistes

**Files:**
- Modify: `tv-ui/app.js` (`renderWork`, `renderSeason`), `tv-ui/views.js`, `tv-ui/style.css`, `tv-ui/dev/preview.json`

**Interfaces:**
- Consumes:
  - `/api/works/{id}` et `/api/seasons/{id}`, qui portent maintenant `audio_langs`, `subtitle_langs`, `width`, `height` sur les `media_files`, `duration_min` sur les épisodes et `medium` sur l'œuvre ;
  - `view.episode_id`, `view.media_file_id`, `view.audio_track`, `view.subtitle_track`.
- Produces :
  - **`renderView` pour `work` :** charge la fiche, puis appelle `renderWork`. Il ajoute :
    - les pastilles de langue par version (« FR · VO », « ST français ») ;
    - la résolution (« 1920 × 1080 » à côté de « FHD ») ;
    - le support des œuvres non numérisées (« DVD »).
  - **Pour `season` :** `renderSeason` sur trois colonnes, comme dans 4b. Il montre la durée de chaque épisode, et les marques « Sélectionné » (`view.episode_id`), « À suivre » (`highlight_episode_id`) et « En cours » (épisode en pause ou en lecture). Il faut pouvoir afficher les deux marques ensemble.
  - **Pour `preplay` :** la vue « Version et pistes » de 4b. Elle montre la version choisie, la piste audio et les sous-titres, à partir de `GET /api/media/{media_file_id}/tracks`, avec les badges « Par défaut » et « Dernier choix ».
  - Libellés de langue communs : `fr` Français, `en` Anglais, `de` Allemand, `es` Espagnol, `it` Italien, `ja` Japonais, `nl` Néerlandais, `pt` Portugais. Une autre langue s'affiche en code majuscule. Pour les sous-titres : « ST français ».

- [ ] **Step 1: Captures de référence** (4b : saison, version et pistes ; 2 : fiche œuvre).
- [ ] **Step 2: Implement**, plus les aperçus `work`, `work-dvd`, `season-selected`, `season-paused`, `preplay`. Les données de fiche de ces aperçus sont injectées dans `preview.json` : `fetchOverride` remplace `getJson` en mode aperçu.
- [ ] **Step 3: Vérifier**
  - Faire des captures et les comparer.
  - Avec un vrai serveur (`~/rc-test`), aller sur la fiche Heroes S1 : les durées doivent s'afficher une fois l'analyse terminée, soit environ 30 s pour 23 fichiers.
- [ ] **Step 4: Commit** `feat(télé): fiches enrichies, épisode sélectionné, choix des pistes`

### Task 14: Essai du mécanisme de mise de côté sur la machine de l'utilisateur

**Files:**
- Modify: `server/rc/config.py` (valeur par défaut, si l'essai le justifie), `~/rc-test/config.toml`, `~/rc-test/LISEZMOI.txt`

Cette tâche demande l'utilisateur (bureau GNOME Wayland). Le contrôleur la mène lui-même ; elle n'est pas confiée à un sous-agent.

- [ ] **Step 1:** Avec `~/rc-test`, régler `pause_background = "minimize"`, puis lancer une vidéo depuis le téléphone, la mettre en pause et revenir à la fiche. Contrôler deux choses :
  - la fenêtre mpv disparaît et la télé montre la fiche avec l'image en pause ;
  - à la reprise, mpv revient **au premier plan, en plein écran**.
- [ ] **Step 2:** Même essai avec `restart`.
- [ ] **Step 3:** Garder `restart` par défaut, sauf si `minimize` réussit les deux contrôles. Noter le résultat dans la conception (§ 8) et dans `LISEZMOI.txt` (« Pause : … »).
- [ ] **Step 4: Commit** `docs: mécanisme de pause retenu après essai`
