# Itération 2 — Appli Android — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** L'appli affiche les nouvelles données du serveur (langues, résolution, durées, support, progression de la première synchro). Elle publie aussi la vue courante pour que la télé la reproduise.

**Architecture:**
- **Vue publiée.** Chaque écran concerné construit un `TvView` : des fonctions pures, testées, dans `mirror/TvViews.kt`. Il le confie à un `TvMirror` unique, porté par `AppContainer`. Le `TvMirror` fusionne les envois, en fait au plus 2 par seconde, les numérote et ne publie qu'au premier plan.
- **Publication.** Un composable `PublishTvView(view)` publie la vue tant que l'entrée de navigation est `RESUMED`.
- **Indicateur « Affichée sur la télé ».** `TvSheet` ne fait plus d'appel `tvShow`. Il se contente de suivre l'état télé élargi.

**Tech Stack:** Kotlin, Jetpack Compose M3, Room, OkHttp, kotlinx.serialization, coroutines-test ; pas de services Google (`checkNoGms`).

**Spec:**
- **Conception :** `docs/superpowers/specs/2026-11-24-iteration-2-design.md`.
- **Contrat :** `docs/superpowers/plans/2026-11-22-api-contract-rulings.md`, arbitrages 13 à 19. Ils font foi.
- **Maquettes :** `docs/design/Remote-Control - 4c - Téléphone.html`, et pour l'existant `… 3 - Appli android.html`. Ce sont des fichiers Claude Design autonomes : les servir avec `python3 -m http.server` depuis `docs/design/` et les ouvrir avec Playwright.

## Global Constraints

- **Textes et dépendances :**
  - tout texte visible est en français et passe par `ui/common/Texts.kt` ;
  - aucune dépendance Google, `./gradlew checkNoGms` doit passer ;
  - compileSdk 37, target 36, min 29.
- **Room :** version 2, avec la migration destructive existante. La synchronisation suivante recharge tout.
- **Envois de vue :**
  - **cadence :** au plus 2 par seconde ; seule la dernière vue en attente est envoyée ;
  - **premier plan seulement :** rien n'est envoyé en arrière-plan, et la dernière vue est renvoyée au retour au premier plan ;
  - **taille :** `items` ≤ 40, `sections` ≤ 6 × 40 ;
  - **numérotation :** `seq` vaut l'heure en ms, forcée strictement croissante ;
  - **identité :** `phone_id` est un UUID aléatoire stocké dans `KeyValueStore` (clé `tv.phone_id`).
- **Échec d'envoi :** silencieux, sans message ni nouvelle tentative. Le prochain changement renvoie la vue.
- **Langues :**
  - noms en français : `fr` Français, `en` Anglais, `de` Allemand, `es` Espagnol, `it` Italien, `ja` Japonais, `nl` Néerlandais, `pt` Portugais ; toute autre langue s'affiche en code majuscule ;
  - sous-titres : « ST français » ;
  - résolution : « 1920 × 1080 », avec une espace insécable fine autour du « × », comme dans la maquette.
- **Tests :** tous passent, soit `cd android && ./gradlew testDebugUnitTest assembleDebug checkNoGms`, y compris les 314 tests existants. Aucun avertissement Kotlin.

## Review Focus

1. **Défilement rapide dans un parcours de 2 500 œuvres** :
   - une vue est envoyée au plus toutes les 500 ms, et toujours la plus récente ;
   - à l'arrêt du doigt, la dernière position part, au plus 500 ms plus tard.

   Test : Task 5 `fast_updates_are_coalesced_and_last_wins`.
2. **Retour au premier plan après une pause du téléphone** : la vue de l'écran affiché est renvoyée, même sans changement. Test : Task 5 `resends_last_view_when_foregrounded`.
3. **Navigation arrière** (fiche saison → fiche œuvre → parcours) : chaque écran qui redevient visible republie sa vue, et non celle de l'écran quitté. Test : Task 7 `publishes_only_while_resumed`, test Compose ou test de l'aide de publication.
4. **Serveur de l'itération 1** (champs absents de `/api/sync`, état télé sans `view`) : ni plantage ni pastille vide. Les champs inconnus sont `null`, et les pastilles sont masquées. Test : Task 2 `old_server_sync_decodes`.
5. **Réinstallation ou redémarrage de l'appli** : le `seq` repart de l'heure courante, toujours plus grande que le dernier envoyé, et le serveur ne rejette donc pas les vues. Test : Task 5 `seq_is_time_based_and_strictly_increasing`.

---

## Structure des fichiers (sous `android/app/src/main/java/fr/remotecontrol/`)

```
net/ApiDtos.kt            # nouveaux champs, TvViewDto, TvStateDto élargi
net/RcApi.kt, OkHttpRcApi.kt   # tvView()
data/db/Entities.kt, AppDatabase.kt (v2), ReadModels.kt, CatalogDao.kt
data/sync/SyncWriter.kt, SyncRepository.kt   # écriture par paquets + progression
ui/common/Format.kt, Texts.kt                 # langues, résolution, support, durées
mirror/TvMirror.kt        # NOUVEAU : envoi limité
mirror/TvViews.kt         # NOUVEAU : construction des vues par écran
mirror/PublishTvView.kt   # NOUVEAU : composable de publication
player/TvSheet.kt         # suit l'état élargi, plus d'appel tvShow
ui/*/…Screen.kt, …ViewModel.kt   # affichage des données, publication des vues
AppContainer.kt, RcApp.kt # TvMirror, run() au premier plan
```

---

### Task 1: Rendre déterministe `BrowseViewModelTest.selection_visible_before_works_load`

**Files:**
- Modify: `android/app/src/test/java/fr/remotecontrol/ui/browse/BrowseViewModelTest.kt`

**Cause :** la liste `seen` est un `mutableListOf` alimenté par une collecte `Dispatchers.Unconfined`, qui reprend sur le thread de Room pendant que le test lit sur un autre thread. De plus, `vm.await` observe `state.value`, et le collecteur peut ne pas avoir encore reçu le dernier état au moment de l'annulation.

- [ ] **Step 1: Réécrire le test.**
  - `seen` devient une `CopyOnWriteArrayList`, et la collecte se fait sur `Dispatchers.Default`.
  - Attendre, avec un délai maximal de 5 s, que `seen` contienne lui-même un état `type == "theatre" && loaded` ; puis `job.cancelAndJoin()`.
  - Les assertions ne changent pas.
- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*BrowseViewModelTest*'` **20 fois de suite**, avec une boucle shell et `--rerun-tasks`. Expected : 20 PASS.
- [ ] **Step 3: Commit** `test(android): test de parcours déterministe`

### Task 2: Nouveaux champs du contrat, cache v2, progression de la première synchro

**Files:**
- Modify: `net/ApiDtos.kt`, `data/db/Entities.kt`, `data/db/AppDatabase.kt`, `data/sync/SyncWriter.kt`, `data/sync/SyncRepository.kt`, `data/db/ReadModels.kt` et `CatalogDao.kt` (si les modèles de lecture doivent exposer les champs)
- Test: `net/ApiDtosTest.kt`, `data/sync/SyncRepositoryTest.kt`, `data/db/CatalogDaoTest.kt`

**Interfaces:**
- Produces:
  - `WorkDto.medium: String? = null` ;
  - `EpisodeDto.durationMin: Int? = null` (`duration_min`) ;
  - `MediaFileDto` : `audioLangs: List<String>? = null`, `subtitleLangs: List<String>? = null`, `width: Int? = null`, `height: Int? = null`, `durationS: Double? = null` ;
  - `SyncDto.totalWorks: Int? = null` (`total_works`) ;
  - mêmes colonnes dans `WorkEntity`, `EpisodeEntity` et `MediaFileEntity`, avec des converters pour les listes si besoin ;
  - `AppDatabase` passe en `version = 2`.
  - `sealed interface SyncProgress { data object Idle; data object Downloading; data class Writing(val done: Int, val total: Int) }`, publié par `SyncRepository.progress: StateFlow<SyncProgress>`.
  - `SyncWriter.apply(dto, onProgress: (done: Int, total: Int) -> Unit = { _, _ -> })` écrit les œuvres par paquets de 500, dans la même transaction. Il appelle `onProgress` après chaque paquet, avec `total = dto.totalWorks ?: dto.works.size`.
- La progression n'est publiée que pour une synchro complète. Une synchro incrémentale reste `Idle` → `Idle`.

- [ ] **Step 1: Tests**

```kotlin
@Test fun old_server_sync_decodes() {  // JSON de l'itération 1, sans aucun nouveau champ
    val dto = RcJson.decodeFromString<SyncDto>(ITER1_SYNC_JSON)
    assertNull(dto.totalWorks); assertNull(dto.mediaFiles[0].audioLangs); assertNull(dto.works[0].medium)
}
@Test fun new_fields_decode_and_are_stored() = runTest { ... }  // audio_langs ["en"], width 624, duration_min 42, medium "DVD"
@Test fun full_sync_reports_downloading_then_writing() = runTest {
    // 1 200 œuvres, total_works 1200 → progressions observées : Downloading, Writing(500,1200), Writing(1000,1200), Writing(1200,1200), Idle
}
@Test fun incremental_sync_reports_no_writing_progress() = runTest { ... }
```

- [ ] **Step 2: Run** `./gradlew testDebugUnitTest --tests '*ApiDtosTest*' --tests '*SyncRepositoryTest*' --tests '*CatalogDaoTest*'` — FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** — PASS ; puis toute la suite.
- [ ] **Step 5: Commit** `feat(android): nouvelles données du serveur et progression de la synchro`

### Task 3: Mise en forme des nouvelles données

**Files:**
- Modify: `ui/common/Format.kt`, `ui/common/Texts.kt`
- Test: `ui/common/FormatTest.kt`

**Interfaces:**
- Produces:
  - `languageName(code: String): String`, par exemple `"fr"` → « Français » et `"xx"` → « XX » ;
  - `languagePills(audio: List<String>?, subtitles: List<String>?): List<String>`, par exemple `["Anglais", "ST français"]`. Les sous-titres sont en minuscules après « ST ». Le résultat est vide si les deux valent `null`.
  - `resolutionLabel(width: Int?, height: Int?): String?`, par exemple « 1920 × 1080 », ou `null` si une valeur est nulle ou vaut 0 ;
  - `episodeDurationLine(durationMin: Int?, remainingMin: Int?): String?`, avec ces sorties :
    - « 41 min » ;
    - « 41 min · reste 18 min » ;
    - « reste 18 min » si la durée est inconnue ;
    - `null` si les deux sont inconnus ;
  - `Texts.notDigitizedOn(medium: String)` : « Cette œuvre n'existe qu'en DVD. » (reprendre le texte exact de 4c s'il diffère) ;
  - `Texts.firstSyncDownloading` : « Téléchargement du catalogue… » ;
  - `Texts.firstSyncWriting(done, total)` : « 1 250 / 3 992 œuvres », avec les milliers séparés par une espace fine insécable, comme les compteurs existants.

- [ ] **Step 1: Tests** reprenant exactement les exemples ci-dessus.
- [ ] **Step 2–4:** `./gradlew testDebugUnitTest --tests '*FormatTest*'` FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(android): mise en forme des langues, résolutions et durées`

### Task 4: Afficher les nouvelles données dans les écrans

**Files:**
- Modify: `ui/work/WorkScreen.kt` et `WorkViewModel.kt`, `ui/season/SeasonScreen.kt` et `SeasonViewModel.kt`, `ui/preplay/PrePlayScreen.kt` et `PrePlayViewModel.kt`, `ui/remote/RemoteScreen.kt` et `RemoteViewModel.kt`, `ui/common/WorkList.kt`, `ui/browse/BrowseScreen.kt`, `ui/common/PosterThumb.kt`, `ui/home/HomeScreen.kt` et `HomeViewModel.kt`, `data/db/ReadModels.kt` et `CatalogDao.kt`
- Test: les `…ViewModelTest.kt` correspondants, `CatalogDaoTest.kt`

**Interfaces:**
- Consumes: Tasks 2 et 3.
- Produces, en suivant 4c (captures de référence à faire d'abord) :
  - **Fiche œuvre :**
    - pastilles de langue et résolution par version ;
    - œuvre non numérisée : la pastille `medium` et `notDigitizedOn`.
  - **Fiche saison :**
    - `EpisodeUi.durationLine` (via `episodeDurationLine`) sur chaque ligne et sur la carte sélectionnée ;
    - pastilles de langue de l'en-tête, d'après les fichiers présents.
  - **Version et pistes :** la résolution sous chaque version.
  - **Télécommande :** la ligne « année · qualité · langue » prend la langue de `audioLangs` quand la piste choisie est inconnue.
  - **Listes et vignettes non numérisées :** `medium` à la place de « Non numérisé » quand il est connu.
  - **Accueil, première synchro :**
    - barre indéterminée avec `firstSyncDownloading` pendant `Downloading` ;
    - barre déterminée avec `firstSyncWriting` pendant `Writing`.

- [ ] **Step 1: Tests de ViewModel**

```kotlin
@Test fun work_versions_carry_language_pills_and_resolution() // ["Anglais","ST français"], "624 × 352"
@Test fun not_digitized_work_shows_medium()                    // "DVD"
@Test fun season_episode_lines_have_duration()                 // "41 min · reste 18 min"
@Test fun home_first_sync_shows_writing_progress()             // Writing(1250, 3992) → texte "1 250 / 3 992 œuvres"
```

- [ ] **Step 2–4:** FAIL → implement → PASS ; toute la suite.
- [ ] **Step 5: Vérifier visuellement.**
  - Construire l'APK et l'installer sur l'émulateur sans GMS, avec le serveur de `~/rc-test` à jour (plan serveur, Tasks 1 à 4).
  - Faire une capture de chaque écran modifié et la comparer à 4c.
- [ ] **Step 6: Commit** `feat(android): langues, résolution, durées, support et progression affichés`

### Task 5: `TvMirror` et appel `tvView`

**Files:**
- Create: `mirror/TvMirror.kt`
- Modify: `net/ApiDtos.kt` (`TvViewDto`, `ItemRefDto`, `PersonDto`, `SectionDto`, `PositionDto`), `net/RcApi.kt`, `net/OkHttpRcApi.kt`, `testing/FakeRcApi.kt`, `AppContainer.kt`, `RcApp.kt`
- Test: `mirror/TvMirrorTest.kt`, `net/OkHttpRcApiTest.kt`

**Interfaces:**
- Produces:
  - `TvViewDto`, au format de l'arbitrage 14. Noms Kotlin en camelCase avec `@SerialName` ; `phoneId` et `seq` sont posés par `TvMirror`. Avec `encodeDefaults = true`, les champs nuls sont envoyés `null`, ce qui est accepté.
  - `RcApi.tvView(view: TvViewDto): ApiResult<Boolean>`, qui renvoie `accepted`.
  - `class TvMirror(api: RcApi, store: KeyValueStore, clock: () -> Long = System::currentTimeMillis, minInterval: Duration = 500.milliseconds)` :
    - `fun publish(view: TvViewDto)` : non suspendante ; remplace la vue en attente ;
    - `suspend fun run()` : appelée dans `repeatOnLifecycle(STARTED)` de `RcApp`. Elle renvoie d'abord la dernière vue connue, puis envoie chaque nouvelle vue, en laissant au moins `minInterval` entre deux envois ;
    - en dehors de `run()`, `publish` retient la vue sans l'envoyer ;
    - `seq = max(clock(), lastSeq + 1)`.
  - `AppContainer.tvMirror`.

- [ ] **Step 1: Tests** (`runTest`, temps virtuel)

```kotlin
@Test fun fast_updates_are_coalesced_and_last_wins()      // 10 publish en 300 ms → 2 envois au plus, le dernier = la 10e vue
@Test fun nothing_sent_outside_run()
@Test fun resends_last_view_when_foregrounded()            // publish, run annulé, run relancé → renvoi de la même vue (seq plus grand)
@Test fun seq_is_time_based_and_strictly_increasing()      // horloge figée → seq +1 à chaque envoi
@Test fun phone_id_is_stable_across_instances()            // même KeyValueStore → même phoneId
@Test fun send_failure_is_silent()
```

- [ ] **Step 2–4:** FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(android): envoi limité de la vue courante`

### Task 6: Construction des vues par écran

**Files:**
- Create: `mirror/TvViews.kt`
- Test: `mirror/TvViewsTest.kt`

**Interfaces:**
- Consumes: `TvViewDto` (Task 5) ; modèles existants (`WorkSummary`, `HomeUi`, `SearchSections`, `SeasonUi`, `PrePlayUi`, `ResumeItem`).
- Produces (fonctions pures) :
  - `windowAround(ids: List<Long>, first: Int, before: Int = 10, max: Int = 40): List<Long>` : fenêtre autour de l'index visible, bornée aux extrémités, avec toujours `max` éléments quand c'est possible.
  - `browseView(type, titleText, subtitleText, playableOnly, works: List<WorkSummary>, firstVisible: Int): TvViewDto`.
    - `screen` vaut `"browse"`.
    - `anchor` est l'œuvre visible en haut.
    - `position = PositionDto(letter = indexLetter(titre de l'ancre), index = firstVisible + 1, total = works.size)`.
    - `filter` vaut `"playable"` ou `"all"`.
  - `collectionView(collectionId, name, subtitleText, works, firstVisible)`, avec `screen` à `"collection"`.
  - `homeView(ui: HomeUi)`, avec `screen` à `"home"`. Les sections, chacune limitée à 40 éléments, sont :
    - « Reprendre » : épisodes (`kind` `episode`) ou œuvres ;
    - « Récemment ajoutés » ;
    - « Collections » (`kind` `collection`).
  - `searchView(query, sections: SearchSections)`, avec `screen` à `"search"`. Il produit « Personnes » (`people`), « Œuvres » et « Aussi connu sous ».
  - `workView(workId)` ; `seasonView(seasonId, workId, selectedEpisodeId)` ; `preplayView(target, mediaFileId, audio, subtitle)`, avec `workId`/`episodeId` selon la cible ; `remoteView()`.
  - `title` et `subtitle` reprennent les textes déjà affichés en en-tête par chaque écran (mêmes fonctions de `Texts`).

- [ ] **Step 1: Tests**

```kotlin
@Test fun window_is_bounded_at_ends()       // windowAround(1..100, first = 2) == 1..40 ; first = 95 → 61..100
@Test fun browse_view_has_anchor_and_position() // M, index 1240, total 2552
@Test fun home_view_sections_use_episode_refs()
@Test fun search_view_sections_and_people()
@Test fun season_view_carries_selected_episode()
@Test fun preplay_view_carries_tracks()
```

- [ ] **Step 2–4:** `./gradlew testDebugUnitTest --tests '*TvViewsTest*'` FAIL → implement → PASS.
- [ ] **Step 5: Commit** `feat(android): vues télé de chaque écran`

### Task 7: Publication depuis les écrans et indicateur « Affichée sur la télé »

**Files:**
- Create: `mirror/PublishTvView.kt`
- Modify: `ui/home/HomeScreen.kt`, `ui/browse/BrowseScreen.kt` et `BrowseViewModel.kt`, `ui/search/SearchScreen.kt`, `ui/work/WorkScreen.kt` et `WorkViewModel.kt`, `ui/season/SeasonScreen.kt` et `SeasonViewModel.kt`, `ui/preplay/PrePlayScreen.kt`, `ui/remote/RemoteScreen.kt`, `player/TvSheet.kt`, `net/ApiDtos.kt` (`TvStateDto` élargi : `view: TvViewStateDto?`, `pause: PauseDto?`)
- Test: `player/TvSheetTest.kt`, `mirror/PublishTvViewTest.kt` (Robolectric ou test de la logique extraite), les tests de ViewModel concernés

**Interfaces:**
- Consumes: `TvMirror.publish` (Task 5), fonctions de `TvViews.kt` (Task 6).
- Produces :
  - **`@Composable fun PublishTvView(view: TvViewDto?)`** : tant que le `LocalLifecycleOwner` est `RESUMED`, publie `view` à chaque changement (`LaunchedEffect` + `repeatOnLifecycle(RESUMED)`). Ne fait rien si `view` vaut `null`, par exemple pendant le chargement. Le `TvMirror` est obtenu par `LocalTvMirror`, un `CompositionLocal` fourni par `MainActivity` depuis `AppContainer`.
  - **Défilement :**
    - Parcours et collection : `snapshotFlow { grid.firstVisibleItemIndex }` donne la première ligne visible. On garde la vue publiée tant que l'ancre ne change pas, puis on la construit avec `browseView` ou `collectionView`.
    - Accueil et recherche : la vue ne dépend pas du défilement.
  - **`TvSheet`** n'appelle plus `api.tvShow`.
    - `onTv` vaut vrai si `tv.screen == "view"` et que `tv.view.screen` et l'identifiant correspondent à la fiche (`workId` ou `seasonId`), ou encore si l'ancien format `screen == "work"/"season"` correspond (serveur de l'itération 1).
    - `onScreenResumed` est supprimé : le renvoi est désormais fait par `PublishTvView`.
  - **Écrans sans vue :** le scanner et les réglages ne publient rien, et la télé garde la dernière vue.

- [ ] **Step 1: Tests**

```kotlin
@Test fun on_tv_follows_view_state()               // TvStateDto(screen="view", view=…work 12) → onTv true pour la fiche 12
@Test fun on_tv_supports_iteration1_state()        // TvStateDto(screen="work", workId=12) → true
@Test fun publishes_only_while_resumed()           // lifecycle STARTED → aucune publication ; RESUMED → publication
@Test fun browse_publishes_on_anchor_change_only() // ViewModel : onFirstVisible(5), (5), (6) → 2 vues
```

- [ ] **Step 2–4:** FAIL → implement → PASS ; toute la suite ; `assembleDebug checkNoGms`.
- [ ] **Step 5: Vérifier de bout en bout.** Lancer le serveur (plan serveur terminé) et l'émulateur, ouvrir la télé dans le navigateur, puis vérifier :
  - parcourir, rechercher « tchernia », ouvrir Heroes S1 et sélectionner E12 : la télé suit ;
  - lancer E05 puis le mettre en pause depuis la télécommande : la télé reste sur la vidéo ;
  - revenir à la fiche : la télé montre la fiche avec l'image dans le coin ;
  - rouvrir la télécommande : la pause s'affiche en plein écran ;
  - reprendre : la vidéo revient.

  Faire des captures des étapes.
- [ ] **Step 6: Commit** `feat(android): la télé suit les écrans du téléphone`
