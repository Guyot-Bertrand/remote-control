# Étape 3 — Serveur et page télé — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reprise quasi immédiate après une pause (mode `hide`), ligne de détail sur la pause en plein écran, sections codées, retrait de `tv/show`, page télé découpée sans changement de rendu, et moins de charge pour le mini-PC.

**Architecture:**
- **Mode `hide`.** Un troisième mode de mise de côté dans `PlaybackService`, au côté de `minimize` : mpv reste lancé, en pause. La différence avec `minimize` est le seul appel au lecteur, `Player.set_video(False/True)` au lieu de `set_minimized(True/False)`. Les deux modes « mpv vivant » partagent donc le même chemin : commandes directes, vignette qui suit les sauts, reprise par `_await_video`.
- **Page télé.** On découpe d'abord, à rendu identique prouvé par captures. Ensuite seulement, on touche au rendu (ligne de détail, `kind`).
- **Serveur.** Les changements de performance gardent les résultats identiques : les tests existants font foi.

**Tech Stack:** Python 3.12, uv, FastAPI, sqlite3, mpv IPC JSON, pytest (asyncio) ; page télé en JavaScript classique, sans outil de construction ; Playwright (outil MCP du plugin) et Pillow pour les captures.

**Spec:**
- **Conception :** `docs/superpowers/specs/2026-11-26-iteration-3-design.md`, § 5.1, 5.3, 5.4, 5.5 (côté serveur et télé) et 5.6.
- **Contrat :** `docs/superpowers/plans/2026-11-22-api-contract-rulings.md`, arbitrages 20 à 23 (déjà rédigés). Ils font foi.
- **Maquette de la ligne de détail :** `docs/design/Remote-Control - 4b - Saison, pause, composants.html`, écran de pause en plein écran. C'est un fichier Claude Design autonome : le servir avec `python3 -m http.server` depuis `docs/design/` et l'ouvrir avec Playwright.

## Global Constraints

- **Langue :** tout texte visible, tout message de journal et tout commentaire sont en français.
- **Page télé légère :** pas de flou ; animations limitées à `transform` et `opacity` ; scripts classiques (pas de modules), chargés dans l'ordre par `tv-ui/index.html`.
- **Réseau local, sans authentification.** Aucune dépendance nouvelle côté serveur.
- **Bureau GNOME Wayland :** un programme ne peut ni placer sa fenêtre ni la remettre au premier plan. `hide` marche parce que la fenêtre rouverte est neuve.
- **Résolution :** « 624 × 352 », avec U+202F (espace fine insécable) de chaque côté du « × ».
- **Tests :** `cd server && uv run pytest -q` passe en entier (370 tests au départ), sans avertissement nouveau.
- **Git :** ne jamais ajouter `.superpowers/` ni `.playwright-mcp/` ni `docs/data/` ; ne rien supprimer hors de son périmètre ; donner le vrai SHA de chaque commit dans le compte rendu.

## Review Focus

1. **Reprise `hide` quand mpv refuse de rendre la piste vidéo** (`set_video(True)` lève une exception) : la lecture repart quand même (son), la session continue, un avertissement est journalisé, et la télé finit par passer en « lecture » par le délai de secours. Test : Task 2 `test_restore_video_failure_still_resumes`.
2. **Arrêt demandé pendant que la vidéo est masquée** : mpv est arrêté (un seul `stop`), la session se termine, et la télé montre la dernière vue. Elle ne reste pas sur la capture. Test : Task 2 `test_stop_while_hidden_stops_player`.
3. **Disque débranché entre deux index complets** (fichier présent en base, `stat` impossible) : la revérification ne plante pas. Le fichier est mis en file, et l'analyse existante marque l'échec. Test : Task 5 `test_recheck_keeps_files_that_cannot_be_stat`.
4. **Fiche de pause mise à jour sur place** (saut pendant la pause, `refreshPausedFull`) : la ligne de détail reste juste. Si `detail` apparaît ou disparaît, l'écran est redessiné. Vérification : Task 3, étape Playwright avec `window.rcShow`.
5. **Vue d'un téléphone avec un `kind` qui contredit son titre** (titres dans une autre langue) : la télé suit `kind`, pas le titre. Vérification : Task 4, aperçu `home-kind`.

---

## Structure des fichiers

```
server/rc/player/base.py        # Player.set_video
server/rc/player/mpv.py         # MpvPlayer.set_video (piste gardée)
server/rc/player/session.py     # mode hide ; pause.detail
server/rc/config.py, app.py     # défaut hide, PAUSE_MODES à trois valeurs
server/rc/api/routes.py         # Section.kind ; retrait de TvShowBody et /tv/show
server/rc/analysis.py           # revérification : fichiers modifiés seulement
server/rc/api/serialize.py      # _availability en deux requêtes indexées
server/rc/schema.sql, db.py     # index, SCHEMA_VERSION = 3
server/tests/fake_player.py, test_park.py, test_mpv.py, test_api.py, test_analysis*.py, test_db*.py
tv-ui/common.js, sheets.js      # nouveaux (découpage de app.js)
tv-ui/app.js, views.js, pause.js, style.css, index.html, dev/preview.json
```

Ordre imposé : Task 1 (découpage) avant toute modification du rendu (Tasks 3 et 4).

---

### Task 1: Découpage de la page télé, à rendu identique

**Files:**
- Create: `tv-ui/common.js`, `tv-ui/sheets.js`
- Modify: `tv-ui/app.js` (772 lignes au départ), `tv-ui/index.html`
- Hors dépôt : captures et script de comparaison dans le répertoire temporaire de la session (scratchpad), jamais dans le dépôt.

**Interfaces:**
- Produces : l'ordre de chargement `common.js`, `pause.js`, `sheets.js`, `views.js`, `app.js`, dans `index.html`. Les fonctions gardent leur nom : `esc`, `fmtMinutes`, `joinDot`, `langLabel`, `resolution`, `posterUrl`, `poster`, `ICON`, `NBSP`… passent dans `common.js` ; `renderWork`, `renderSeason`, les pastilles de version, `sheetJson` et `sheetCache` passent dans `sheets.js`. L'état, la WebSocket, `show`, `load`, `mount`, `mountFade`, `keepImages`, la veille et `preview` restent dans `app.js`.

- [ ] **Step 1: Captures de référence, avant toute modification**

Servir le serveur de dev ou `python3 -m http.server 8799` depuis `tv-ui/`, puis, avec Playwright en 1920×1080, capturer chacun des 16 aperçus de `tv-ui/dev/preview.json` (`index.html?preview=<nom>`). Pour un rendu déterministe :
- heure figée avant la navigation : `page.clock.setFixedTime(new Date('2026-11-26T20:30:00'))` ;
- animations et transitions coupées par une feuille injectée après le chargement : `*,*::before,*::after{animation:none!important;transition:none!important}` ;
- attendre `document.fonts.ready`, puis 1,5 s pour le fondu de montage.

Enregistrer `<nom>.png` dans `<scratchpad>/avant/`.

- [ ] **Step 2: Découper `app.js`**

Déplacer les fonctions sans modifier leur corps, sauf si une référence croisée l'impose. Aucune fonction ne doit être dupliquée. Les constantes ne servant qu'à un seul fichier vont dans ce fichier. `views.js` et `pause.js` peuvent utiliser `common.js` : supprimer d'eux tout doublon d'un outil commun s'il en existe. Mettre à jour `index.html`.

- [ ] **Step 3: Vérifier la console**

Pour chacun des 16 aperçus et pour la page normale (`/tv/` sur le serveur de dev), relever la console Playwright. Attendu : aucune erreur, aucune `ReferenceError`.

- [ ] **Step 4: Captures après, puis comparaison**

Reprendre les mêmes captures dans `<scratchpad>/apres/`. Comparer avec un petit script Pillow (`ImageChops.difference(a, b).getbbox()`), lancé par `cd server && uv run python <script>`. Attendu : `getbbox()` vaut `None` pour les 16 aperçus. Joindre au compte rendu la sortie du script (une ligne par aperçu).

- [ ] **Step 5: Commit**

```bash
git add tv-ui/common.js tv-ui/sheets.js tv-ui/app.js tv-ui/views.js tv-ui/pause.js tv-ui/index.html
git commit -m "refactor(tv): découpage de la page télé en common, pause, sheets, views, app"
```

---

### Task 2: Mode de mise de côté `hide`

**Files:**
- Modify: `server/rc/player/base.py`, `server/rc/player/mpv.py` (près de `set_minimized`, l. 297), `server/rc/player/session.py` (`_Session.parked`, `_play_locked`, `_park_locked`, `_unminimize`, `_unpark_locked`, `_on_state`), `server/rc/config.py:39`, `server/rc/app.py:50, 381-405`
- Test: `server/tests/fake_player.py`, `server/tests/test_park.py`, `server/tests/test_mpv.py`, tests de config et d'appli existants (chercher `pause_background`)

**Interfaces:**
- Produces :
  - `Player.set_video(self, enabled: bool) -> None` (async) dans le protocole ;
  - `_Session.parked: Literal["hide", "minimize", "restart"] | None` ;
  - `PAUSE_MODES = ("hide", "minimize", "restart")` ;
  - `Config.pause_background: str = "hide"`.

- [ ] **Step 1: Tests du lecteur factice et de la session (en échec)**

Dans `fake_player.py`, ajouter `self.video_error: Exception | None = None` et `async def set_video(self, enabled)`. Cette méthode ajoute `("set_video", enabled)` à `calls`, puis lève `video_error` s'il est posé.

Dans `test_park.py`, ajouter les tests suivants, sur le modèle des tests `minimize` existants (`env.mode = "hide"`, `paused_at_1394`, `parked`, `video_back`) :

```python
async def test_park_hide_keeps_player_running(env):
    # mise de côté : ("set_video", False) dans calls, pas de ("stop",)
    # état publié : running True, paused True, position 1394
    # reprise pause(False) : appels suivants == [("set_video", True), ("set_pause", False)]
    # télé : "paused" avec l'image jusqu'à video_back(env), puis "playing"

async def test_play_same_media_while_hidden_unparks(env):
    # play du même média : premier appel suivant == ("set_video", True), pas de ("start", …)

async def test_hide_failure_falls_back_to_restart(env):
    # env.player.video_error = RuntimeError("pas de vidéo")
    # mise de côté : ("stop",) appelé, session parked == "restart"
    # reprise : ("start", …) à 1394, pas de ("set_video", True)

async def test_seek_while_hidden_updates_pause_tile(env):
    # seek envoyé à mpv : ("seek", 1500, False) dans calls ; événement de position 1500
    # émis en pause : tv.state.pause["position"] == 1500

async def test_volume_and_tracks_while_hidden_go_to_player(env):
    # set_volume(40) : ("set_volume", 40) dans calls ; set_audio(2) : ("set_audio", 2)

async def test_stop_while_hidden_stops_player(env):
    # stop() : exactement un ("stop",), session terminée, tv.state.screen != "paused"

async def test_restore_video_failure_still_resumes(env, caplog):
    # parked en hide, puis env.player.video_error = RuntimeError(...)
    # pause(False) : ("set_pause", False) envoyé, session toujours là, avertissement journalisé
```

- [ ] **Step 2: Lancer les tests**

Run: `cd server && uv run pytest tests/test_park.py -q`
Expected: les 7 nouveaux tests échouent (`set_video` jamais appelé). Les anciens passent.

- [ ] **Step 3: Implémenter**

- **`base.py`** : `set_video` dans `Player`.
- **`MpvPlayer.set_video`** :
  - À la désactivation : lire `vid` par `get_property`, garder la valeur dans `self._vid_kept`, puis `set_property("vid", "no")`. Une valeur illisible, `False` ou `"no"` donne `None`.
  - À la réactivation : `set_property("vid", self._vid_kept or "auto")`, puis `set_property("fullscreen", True)`.
  - `start()` remet `self._vid_kept = None`.
- **`session.py`** :
  - `_park_locked` : le mode `hide` appelle `set_video(False)` là où `minimize` appelle `set_minimized(True)`, avec le même repli sur `restart` si l'appel échoue (avertissement « masquage de la vidéo impossible, arrêt du lecteur »).
  - `_unminimize` devient `_restore_video()`, qui appelle `set_video(True)` ou `set_minimized(False)` selon le mode. En cas d'échec, il journalise un avertissement et continue.
  - Partout où le code teste `parked == "minimize"`, c'est-à-dire dans `_play_locked` et `_on_state`, il teste désormais `parked in ("hide", "minimize")`. Les commandes passent déjà directement à mpv par `_kept()`, propre à `restart`.
  - Le défaut de `PlaybackService(pause_mode=…)` devient `lambda: "hide"`.
- **`config.py`** : défaut `"hide"`, avec un commentaire décrivant les trois valeurs.
- **`app.py`** :
  - `PAUSE_MODES` à trois valeurs ;
  - avertissement « pause_background inconnu (%r) : « hide » utilisé » ;
  - `pause_mode=lambda: config.pause_background if config.pause_background in PAUSE_MODES else "hide"`.

- [ ] **Step 4: Tests de `MpvPlayer` et de la config**

Dans `test_mpv.py`, avec le faux mpv (`fake_mpv.py`) :
- `test_set_video_keeps_and_restores_track` : `vid` valant 2, la désactivation donne `vid = "no"`, la réactivation donne `vid = 2` puis `fullscreen = True` ;
- `test_set_video_restores_auto_when_unreadable` : `get_property vid` en erreur, la réactivation donne `vid = "auto"`.

Étendre `fake_mpv.py` si `get_property vid` n'y est pas géré.

Tests de config et d'appli, là où `pause_background` est déjà testé :
- défaut `"hide"` ;
- `"restart"` explicite reste `"restart"` ;
- `"bidule"` donne `"hide"`, avec l'avertissement.

- [ ] **Step 5: Lancer toute la suite**

Run: `cd server && uv run pytest -q`
Expected: tout passe.

- [ ] **Step 6: Commit**

```bash
git add server/rc/player/base.py server/rc/player/mpv.py server/rc/player/session.py server/rc/config.py server/rc/app.py server/tests/
git commit -m "feat(serveur): mise de côté « hide », piste vidéo retirée pendant la pause (défaut)"
```

---

### Task 3: Ligne de détail de la pause (arbitrage 20)

**Files:**
- Modify: `server/rc/player/session.py` (`pause_info`, `_await_video`), `tv-ui/pause.js` (`renderPausedFull`, `refreshPausedFull`), `tv-ui/style.css` (après `.paused-title`, l. 366), `tv-ui/dev/preview.json`
- Test: `server/tests/test_park.py`, `server/tests/test_api.py` (assertions d'objets `pause` existantes)

**Interfaces:**
- Consumes : `_Session.current["media_file_id"]`, `target_kind`, `target_id`.
- Produces :
  - `pause_detail(conn: sqlite3.Connection, current: dict) -> str | None`, au niveau module dans `session.py` ;
  - la clé `detail` dans tout objet `pause` (`pause_info()` et l'objet montré par `_await_video`).
  - `player.current` ne change pas : arbitrage 5.

- [ ] **Step 1: Tests en échec**

```python
def test_pause_detail_episode(conn):  # épisode titré « Hiros », fichier SD 624×352
    assert pause_detail(conn, current) == "Hiros · SD 624 × 352"

def test_pause_detail_film_without_analysis(conn):  # width NULL
    assert pause_detail(conn, current) == "FHD"

def test_pause_detail_omits_empty_episode_title_and_zero_width(conn):  # title "", width 0
    assert pause_detail(conn, current) == "SD"

def test_pause_detail_none_when_nothing_known(conn):  # quality "" , width NULL, film
    assert pause_detail(conn, current) is None

async def test_pause_info_carries_detail(env):
    # paused_at_1394 puis park : tv.state.pause["detail"] == pause_detail(env.conn, current)
    # et l'objet de _await_video à la reprise le porte aussi
```

Les tests utilisent la fixture `env` de `test_park.py` (`env.conn`, `current` = `env.playback.current` après `paused_at_1394`, ou un dict construit à la main pour un film). Les valeurs se posent par `UPDATE` sur la base de test.

- [ ] **Step 2: Lancer les tests**

Run: `cd server && uv run pytest tests/test_park.py -q -k detail`
Expected: FAIL, `pause_detail` est introuvable.

- [ ] **Step 3: Implémenter `pause_detail`**

Le texte se compose ainsi :
- il réunit par « · » le titre d'épisode (s'il existe, non vide) et un groupe « qualité résolution » ;
- ce groupe réunit par une espace la qualité (si non vide) et la résolution (`f"{w} × {h}"` si `width` et `height` sont tous deux > 0) ;
- un groupe vide est omis, et le tout vide donne `None`.

Le calcul se fait à chaque appel de `pause_info`, pour que la résolution d'une analyse arrivée en cours de pause apparaisse. Un seul objet `pause` est construit, par une aide commune à `pause_info` et à `_await_video`, `_pause_dict(s, image)`. Mettre à jour les assertions existantes d'objets `pause` dans `test_api.py` et `test_park.py`.

- [ ] **Step 4: Page télé**

Dans `renderPausedFull`, ajouter `<span class="paused-detail">…</span>` sous `.paused-title` dans `.paused-text`, seulement si `pause.detail`. Style d'après la maquette 4b, mesuré avec Playwright : police, taille, couleur et écart. À défaut de mesure, Inter 500, 30 px, `var(--rc-text-2)`, 12 px sous le titre.

`refreshPausedFull` renvoie `false` si la présence de `detail` diffère de l'écran monté. Sinon, il met à jour le texte. Rien ne change dans le coin d'état.

Dans `dev/preview.json` :
- `paused-full` reçoit `"detail": "Hiros · SD 624 × 352"`, avec U+202F ;
- `paused-full-sd` reçoit `"detail": "SD 704 × 576"` ;
- nouvel aperçu `paused-full-nodetail`, copie de `paused-full` avec `"detail": null`.

- [ ] **Step 5: Vérification visuelle**

Avec Playwright, en 1920×1080 :
- capturer `paused-full`, `paused-full-sd` et `paused-full-nodetail`, puis les comparer à 4b ;
- sur `paused-full`, appeler `window.rcShow` avec le même état mais une position différente : la ligne de détail reste et la dérive n'est pas relancée ;
- appeler `window.rcShow` avec `detail: null` : la ligne disparaît.

Joindre les captures au compte rendu, par leur chemin dans le scratchpad.

- [ ] **Step 6: Suite complète et commit**

Run: `cd server && uv run pytest -q`
Expected: tout passe.

```bash
git add server/rc/player/session.py server/tests/ tv-ui/pause.js tv-ui/style.css tv-ui/dev/preview.json
git commit -m "feat: ligne de détail (épisode, qualité, résolution) sur la pause en plein écran"
```

---

### Task 4: Contrat — `sections[].kind` et retrait de `tv/show` (arbitrages 21 et 22)

**Files:**
- Modify: `server/rc/api/routes.py:80-85` (`TvShowBody`), `:97-99` (`Section`), `:257` (route `tv/show`) ; `tv-ui/views.js` (`homeSectionKind` l. 206, rangées de recherche l. 399, largeurs l. 434) ; `tv-ui/dev/preview.json`
- Test: `server/tests/test_api.py`

**Interfaces:**
- Produces :
  - `Section.kind: Literal["resume", "recent", "collections", "people", "works", "aka"] | None = None` ;
  - `kind` est présent dans chaque `view.sections[]` de l'état télé ;
  - côté télé, `sectionKind(s)` renvoie `s.kind`, ou une valeur déduite par la règle actuelle si `kind` est absent.

- [ ] **Step 1: Tests serveur en échec**

```python
def test_tv_view_section_kind_passes_through(env):
    # VIEW accueil avec sections kind "resume" puis "collections" :
    # tv/state view.sections[i]["kind"] == envoyé
def test_tv_view_section_kind_null_or_absent_accepted(env):  # → kind None dans l'état
def test_tv_view_unknown_section_kind_is_422(env):  # kind "bidule" → 422, error_body("invalid", None)
def test_tv_show_route_removed(env):  # POST /api/tv/show → 404
```

Supprimer `test_tv_show_and_state`. Les tests WebSocket qui provoquaient un événement `tv` par `tv/show` (`test_ws_hello_then_tv_event`, `test_ws_unserialisable_event_is_logged_and_skipped` et d'autres trouvés par `grep -n "tv/show" server/tests`) le provoquent désormais par `POST /api/tv/view` (`VIEW`). Ils attendent un événement `tv` d'écran `view`.

- [ ] **Step 2: Lancer les tests**

Run: `cd server && uv run pytest tests/test_api.py -q`
Expected: les 4 nouveaux tests échouent.

- [ ] **Step 3: Implémenter côté serveur**

- **`Section`** : ajouter `kind`, déclaré dans le modèle. `MirrorService._enriched` recopie déjà les clés de section.
- **`TvShowBody` et la route** : les supprimer, avec les imports devenus inutiles. `TvController.show()` reste, car la veille l'appelle.
- **Vérifier** que `grep -rn "tv/show" server tv-ui android/app/src` ne trouve plus que des commentaires historiques ; les corriger s'ils décrivent la route comme existante.

- [ ] **Step 4: Page télé**

`sectionKind(s)` vit dans `views.js`. `homeSectionKind` et la recherche s'en servent :
- à l'accueil, `collections` donne `'coll'`, `resume` donne `'l'` et `recent` donne `'s'` ;
- dans la recherche, `aka` utilise `akaCard` et sa largeur, `people` utilise `personRow` et `works` utilise `foundRow` ;
- sans `kind`, la règle actuelle s'applique, sans changement.

Nouveaux aperçus :
- `home-kind`, copie de `home` où chaque section porte son `kind` et un titre en anglais (`"Continue"`, `"Recently added"`, `"Collections"`) ;
- `search-kind`, même principe à partir de `search` (`"Also known as"` pour `aka`).

- [ ] **Step 5: Vérification visuelle**

Avec le protocole de la Task 1 (heure figée, animations coupées), capturer `home`, `home-kind`, `search` et `search-kind`.

Attendu :
- même disposition entre `home` et `home-kind`, et entre `search` et `search-kind` : seuls les titres de section diffèrent ;
- les 16 aperçus d'origine sont identiques au pixel aux captures « après » de la Task 1, sauf `paused-full` et `paused-full-sd`, modifiés par la Task 3.

- [ ] **Step 6: Suite complète et commit**

Run: `cd server && uv run pytest -q`
Expected: tout passe.

```bash
git add server/rc/api/routes.py server/tests/ tv-ui/views.js tv-ui/dev/preview.json
git commit -m "feat: sections codées (kind) ; retrait de POST /api/tv/show"
```

---

### Task 5: Revérification de l'analyse limitée aux fichiers modifiés

**Files:**
- Modify: `server/rc/analysis.py` (`pending_files` l. 29, `analysis_loop`)
- Test: le fichier de tests d'analyse existant (`grep -ln pending_files server/tests`)

**Interfaces:**
- Produces :
  - `recheck_candidates(conn, indexes) -> list[tuple[int, Path, float | None, int | None]]` : fichiers présents et résolus, avec `probe_cache.mtime` et `size`, ou `None` sans cache. Il ne fait aucun `stat` et lit la base dans le fil principal.
  - `stale_files(candidates) -> list[tuple[int, Path]]` : fonction pure sur le système de fichiers, sans base. Elle garde les fichiers sans cache, ceux dont `os.stat` donne une `st_mtime` ou une `st_size` différente (comparaison exacte, comme `cached_tracks`), et ceux dont le `stat` lève `OSError`.
  - `pending_files(conn, indexes, recheck)` ne sert plus qu'aux fichiers non analysés. Supprimer son paramètre `recheck` si plus rien ne l'utilise.

- [ ] **Step 1: Tests en échec**

```python
def test_recheck_skips_files_matching_probe_cache(...):  # cache = stat réel → stale_files == []
def test_recheck_keeps_modified_files(...):              # size différente → gardé ; mtime différente → gardé
def test_recheck_keeps_files_without_cache(...):
def test_recheck_keeps_files_that_cannot_be_stat(...):   # fichier supprimé après résolution → gardé
async def test_loop_recheck_stats_in_thread(...):
    # analysis_loop avec last_full_index changé : stale_files appelé via asyncio.to_thread
    # (monkeypatch de asyncio.to_thread dans rc.analysis enregistrant la fonction reçue),
    # seuls les fichiers périmés sont analysés
```

- [ ] **Step 2: Lancer les tests**

Expected: FAIL, fonctions introuvables.

- [ ] **Step 3: Implémenter**

Dans `analysis_loop`, en cas de `recheck`, utiliser `queue = await asyncio.to_thread(stale_files, recheck_candidates(state.conn, state.indexes))` ; sinon, `pending_files(...)` comme avant. La gestion de `handled_index` et de `failed` ne change pas.

- [ ] **Step 4: Suite complète et commit**

Run: `cd server && uv run pytest -q`
Expected: tout passe.

```bash
git add server/rc/analysis.py server/tests/
git commit -m "perf(analyse): revérification limitée aux fichiers modifiés depuis l'analyse"
```

---

### Task 6: Disponibilité indexée, schéma v3

**Files:**
- Modify: `server/rc/api/serialize.py:234-253` (`_availability`), `server/rc/schema.sql`, `server/rc/db.py:9`
- Test: les tests existants d'`enrich` et de vue (`test_mirror*.py`, `test_api.py`), plus les tests de base (`grep -ln SCHEMA_VERSION server/tests`)

**Interfaces:**
- Produces : `SCHEMA_VERSION = 3`. Le résultat de `_availability(conn, work_ids)` est identique, y compris le choix du disque absent, qui est celui du premier fichier par `mf.id` croissant.

- [ ] **Step 1: Test d'équivalence et test de plan de requête (en échec pour le second)**

```python
def test_availability_same_as_before(conn):
    # base de test avec : film présent ; film absent sur disque débranché ; série dont un
    # épisode est présent ; série toute absente sur deux disques (libellé = celui du plus
    # petit mf.id) ; œuvre sans fichier → comparer à la valeur attendue écrite en dur
def test_availability_uses_indexes(conn):
    # EXPLAIN QUERY PLAN des requêtes de _availability (exposées par une constante de module)
    # ne contient pas "SCAN media_file" (ni "SCAN mf")
def test_schema_v3_and_indexes_on_existing_db(tmp_path):
    # base v2 créée puis rouverte : user_version == 3, index présents (PRAGMA index_list)
```

- [ ] **Step 2: Lancer les tests**

Expected: `test_availability_uses_indexes` et `test_schema_v3…` échouent.

- [ ] **Step 3: Implémenter**

Deux requêtes, sans `COALESCE` dans le `WHERE` :
- l'une sur `media_file.work_id IN (…)` ;
- l'autre sur `season.work_id IN (…)`, jointe à `episode` puis à `media_file.episode_id`.

Elles sont réunies par `UNION ALL` ou fusionnées en Python, puis triées par `mf.id` avant le calcul existant.

Ajouter dans `schema.sql` `CREATE INDEX IF NOT EXISTS media_file_work ON media_file (work_id);`. `season_work` et `media_file_episode` existent déjà. Ajouter d'autres index seulement si le plan de requête en montre le besoin. Passer `SCHEMA_VERSION` à 3 ; aucune migration de données n'est nécessaire, car `schema.sql` est rejoué à chaque ouverture.

- [ ] **Step 4: Suite complète et commit**

Run: `cd server && uv run pytest -q`
Expected: tout passe.

```bash
git add server/rc/api/serialize.py server/rc/schema.sql server/rc/db.py server/tests/
git commit -m "perf(serveur): disponibilité par requêtes indexées ; schéma v3"
```
