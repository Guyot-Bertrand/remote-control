# Étape 3 — Appli Android — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:**
- les sections publiées sur la télé portent leur code (`kind`) ;
- la barre de la première synchro suit toute l'écriture ;
- les touches de volume du téléphone règlent le volume de la télé quand la télécommande est ouverte.

**Architecture:**
- **`kind`** : un champ de plus dans `SectionDto`, rempli par les constructeurs purs de `mirror/TvViews.kt`.
- **Avancement** : `SyncWriter.apply` compte toutes les lignes écrites et donne une fraction. `SyncProgress.Writing` la transporte jusqu'à la barre.
- **Volume** :
  - un aiguillage `VolumeKeys`, porté par `AppContainer`, reçoit les touches de `MainActivity` ;
  - l'écran Télécommande y inscrit son gestionnaire tant qu'il est `RESUMED` et qu'une lecture est connue ;
  - `RemoteViewModel.volumeStep` déplace le curseur tout de suite et regroupe les envois.

**Tech Stack:** Kotlin, Jetpack Compose M3, Room, OkHttp, kotlinx.serialization, coroutines-test ; aucun service Google.

**Spec:**
- **Conception :** `docs/superpowers/specs/2026-11-26-iteration-3-design.md`, § 5.2, 5.5 et 5.7.
- **Contrat :** `docs/superpowers/plans/2026-11-22-api-contract-rulings.md`, arbitrage 21. Il fait foi.

## Global Constraints

- **Textes et dépendances :**
  - tout texte visible est en français et passe par `ui/common/Texts.kt` ;
  - aucune dépendance Google : `./gradlew checkNoGms` doit passer ;
  - compileSdk 37, target 36, min 29.
- **Room :** pas de changement de schéma (version 2).
- **Volume :**
  - pas de 5, borné entre 0 et 100 ;
  - au plus 4 envois par seconde (un toutes les 250 ms), seule la dernière valeur est envoyée ;
  - touches interceptées seulement si l'écran Télécommande est `RESUMED` et qu'une lecture est en cours ; ailleurs, effet normal sur le volume du téléphone.
- **Texte de synchro inchangé :** « 1 250 / 3 992 œuvres » (`Texts.firstSyncWriting`), et son test.
- **Tests :** `cd android && ./gradlew testDebugUnitTest assembleDebug checkNoGms` passe en entier, tests existants compris, sans avertissement Kotlin.
- **Git :** ne jamais ajouter `.superpowers/`, `.playwright-mcp/` ni `docs/data/` ; donner le vrai SHA de chaque commit dans le compte rendu.

## Review Focus

1. **Appui maintenu sur volume +** (répétition du système, environ 20 événements par seconde) :
   - le curseur monte de 5 à chaque répétition, jusqu'à 100 sans dépasser ;
   - au plus 4 envois par seconde partent, et le dernier envoi est la valeur finale.

   Test : Task 3 `volume_keys_coalesced_last_value_wins` et `volume_step_clamps`.
2. **Retour au premier plan, ou passage à une autre page, alors que la télécommande était ouverte** : les touches règlent de nouveau le téléphone dès que l'écran n'est plus `RESUMED`. Test : Task 3 `registered_only_while_resumed_and_running`.
3. **Fin de la lecture, télécommande toujours ouverte** : le gestionnaire est retiré, et les touches règlent le téléphone. Test : même test, avec `running` qui passe à faux.
4. **Échec d'envoi du volume** (serveur injoignable) : même message que le curseur, et le curseur revient à la valeur du serveur au plus tard après `HOLD_MS`. Test : Task 3 `volume_key_failure_shows_message`.
5. **Synchro complète d'un catalogue sans œuvres, ou réponse vide** : la barre ne divise pas par zéro et finit à 1. Test : Task 2 `fraction_with_no_rows_is_one`.

---

## Structure des fichiers (sous `android/app/src/main/java/fr/remotecontrol/`)

```
net/ApiDtos.kt              # SectionDto.kind
mirror/TvViews.kt           # kind des sections d'accueil et de recherche
data/sync/SyncWriter.kt     # onProgress(done, total, fraction)
data/sync/SyncRepository.kt # SyncProgress.Writing(done, total, fraction)
ui/home/HomeViewModel.kt    # syncFraction = fraction
ui/remote/VolumeKeys.kt     # nouveau : aiguillage des touches + inscription tant que RESUMED
ui/remote/RemoteViewModel.kt  # volumeStep, envois regroupés
AppContainer.kt, MainActivity.kt
```

Les trois tâches sont indépendantes.

---

### Task 1: `kind` des sections publiées (arbitrage 21)

**Files:**
- Modify: `net/ApiDtos.kt:255-259` (`SectionDto`), `mirror/TvViews.kt:70-96` (`homeView`, `searchView`)
- Test: `src/test/java/fr/remotecontrol/mirror/TvViewsTest.kt`, `src/test/java/fr/remotecontrol/net/ApiDtosTest.kt`

**Interfaces:**
- Produces : `SectionDto(label: String, people: List<PersonDto> = emptyList(), items: List<ItemRefDto> = emptyList(), kind: String? = null)`. `kind` est placé en dernier, pour que les appels positionnels restent valides. Les valeurs sont des constantes de `TvViews.kt` : `"resume"`, `"recent"`, `"collections"`, `"people"`, `"works"`, `"aka"`.

- [ ] **Step 1: Tests en échec**

```kotlin
@Test fun home_sections_carry_kind()     // homeView : kinds == ["resume", "recent", "collections"] (sections non vides)
@Test fun search_sections_carry_kind()   // searchView : kinds == ["people", "works", "aka"]
@Test fun empty_sections_dropped_with_their_kind() // accueil sans reprise : kinds == ["recent", "collections"]
@Test fun section_kind_serialized()      // RcJson.encodeToString(SectionDto("Reprendre", kind = "resume")) contient "\"kind\":\"resume\""
```

- [ ] **Step 2: Lancer les tests**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*TvViewsTest*' --tests '*ApiDtosTest*'`
Expected: FAIL, paramètre `kind` inconnu.

- [ ] **Step 3: Implémenter**

Ajouter le champ et remplir `kind` dans les six `SectionDto` de `homeView` et de `searchView`.

- [ ] **Step 4: Suite complète et commit**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug checkNoGms`
Expected: BUILD SUCCESSFUL.

```bash
git add android/app/src/main/java/fr/remotecontrol/net/ApiDtos.kt android/app/src/main/java/fr/remotecontrol/mirror/TvViews.kt android/app/src/test/
git commit -m "feat(android): code kind sur les sections publiées à la télé"
```

---

### Task 2: Barre de première synchro fidèle à toute l'écriture

**Files:**
- Modify: `data/sync/SyncWriter.kt:27-75`, `data/sync/SyncRepository.kt:25-29, 66-68`, `ui/home/HomeViewModel.kt:99-107`
- Test: les tests existants de `SyncWriter` (`grep -rln "SyncWriter" android/app/src/test`), `data/sync/SyncRepositoryTest.kt:118`, `ui/home/HomeViewModelTest.kt:87`

**Interfaces:**
- Produces :
  - `SyncWriter.apply(dto: SyncDto, onProgress: (done: Int, total: Int, fraction: Float) -> Unit = { _, _, _ -> })` ;
  - `SyncProgress.Writing(val done: Int, val total: Int, val fraction: Float)`.
  - `done` et `total` gardent leur sens actuel, celui des œuvres.

- [ ] **Step 1: Tests en échec**

```kotlin
@Test fun fraction_covers_all_rows_and_ends_at_one()
// réponse complète : 1 200 œuvres, 3 000 épisodes, 3 500 fichiers, saisons, collections,
// liens, progressions → fractions reçues croissantes au sens large ;
// la fraction reçue avec done == 1200 (fin des œuvres) est < 0.5 ; la dernière == 1f
@Test fun fraction_with_no_rows_is_one()   // réponse complète vide : au moins un appel, fraction 1f
```

Mettre à jour `SyncRepositoryTest` pour qu'il compare `done` et `total` comme avant et vérifie que la dernière fraction vaut `1f`. `HomeViewModelTest` : `Writing(1250, 3992, 0.2f)` donne `syncFraction == 0.2f` et `syncCount` inchangé.

- [ ] **Step 2: Lancer les tests**

Expected: FAIL, la signature d'`onProgress` ne correspond pas.

- [ ] **Step 3: Implémenter**

**Total des lignes** : œuvres, plus saisons, épisodes, fichiers, collections, `workCollections` et progressions de la réponse. Les volumes, peu nombreux, sont exclus.

**Écriture** : chaque liste est écrite par paquets de 500, comme les œuvres. Renommer `WORK_BATCH` en `ROW_BATCH`. Après chaque paquet, appeler `onProgress(worksDone, total, written / totalRows)`, ou `1f` si `totalRows == 0`.

**Fin** : un dernier appel avec `1f`, après `recomputePlayable`.

`SyncRepository` transmet la fraction, et `HomeViewModel` utilise `syncFraction = p.fraction.coerceIn(0f, 1f)`.

- [ ] **Step 4: Suite complète et commit**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug checkNoGms`
Expected: BUILD SUCCESSFUL.

```bash
git add android/app/src/main/java/fr/remotecontrol/data/sync/ android/app/src/main/java/fr/remotecontrol/ui/home/HomeViewModel.kt android/app/src/test/
git commit -m "feat(android): barre de première synchro sur toutes les lignes écrites"
```

---

### Task 3: Touches de volume sur la télécommande

**Files:**
- Create: `ui/remote/VolumeKeys.kt`
- Modify: `ui/remote/RemoteViewModel.kt` (à côté de `onVolumeRelease`, l. 183), `AppContainer.kt`, `MainActivity.kt` (bloc `composable<RemoteRoute>`, l. 317, plus `onKeyDown` et `onKeyUp`)
- Test: `src/test/java/fr/remotecontrol/ui/remote/VolumeKeysTest.kt` (nouveau), `src/test/java/fr/remotecontrol/ui/remote/RemoteViewModelTest.kt`

**Interfaces:**
- Produces :
  - `class VolumeKeys`, avec :
    - `var handler: ((up: Boolean) -> Unit)?` (`@Volatile`) ;
    - `fun onKeyDown(keyCode: Int): Boolean` : vrai et appel du gestionnaire si un gestionnaire est inscrit et que la touche est `KeyEvent.KEYCODE_VOLUME_UP` ou `KEYCODE_VOLUME_DOWN`. Les répétitions d'un appui maintenu arrivent comme de nouveaux `onKeyDown`, traités de la même façon ;
    - `fun onKeyUp(keyCode: Int): Boolean` : vrai, sans appel, dans les mêmes conditions. L'appui est consommé en entier, et le téléphone n'affiche pas son curseur.
  - `suspend fun registerVolumeKeysWhileResumed(lifecycle: Lifecycle, running: Flow<Boolean>, keys: VolumeKeys, onStep: (up: Boolean) -> Unit)`. Le gestionnaire est inscrit tant que `lifecycle` est `RESUMED` et que `running` vaut vrai, et retiré sinon. À la sortie, il n'est retiré que s'il est encore le sien. Même forme que `publishWhileResumed` (`mirror/PublishTvView.kt`).
  - `@Composable fun VolumeKeysWhileResumed(keys: VolumeKeys, running: Boolean, onStep: (Boolean) -> Unit)`, qui appelle la fonction ci-dessus.
  - `RemoteViewModel(…, volumeSendIntervalMs: Long = 250L)` et `fun volumeStep(up: Boolean)`.
  - `AppContainer.volumeKeys: VolumeKeys`.

- [ ] **Step 1: Tests en échec**

```kotlin
// VolumeKeysTest
@Test fun keys_pass_through_without_handler()    // onKeyDown(KEYCODE_VOLUME_UP) == false
@Test fun keys_consumed_with_handler()           // UP → handler(true), DOWN → handler(false), onKeyUp == true, KEYCODE_A → false
@Test fun registered_only_while_resumed_and_running()
// LifecycleRegistry (comme PublishTvViewTest) : RESUMED + running → handler non nul ;
// STARTED → nul ; RESUMED de nouveau → non nul ; running = false → nul

// RemoteViewModelTest
@Test fun volume_step_moves_slider_immediately()   // serveur à 50 : volumeStep(true) → state.volume == 55 avant tout événement
@Test fun volume_step_clamps()                     // 98 + → 100 ; 3 − → 0
@Test fun volume_keys_coalesced_last_value_wins()
// volumeSendIntervalMs = 200, serveur à 50 : 6 appuis + d'affilée → appels api == ["volume(55)", "volume(80)"]
@Test fun volume_key_failure_shows_message()       // volumeResult = Err(...) → message == même texte que le curseur
```

- [ ] **Step 2: Lancer les tests**

Run: `cd android && ./gradlew testDebugUnitTest --tests '*VolumeKeysTest*' --tests '*RemoteViewModelTest*'`
Expected: FAIL, symboles introuvables.

- [ ] **Step 3: Implémenter**

**`volumeStep`** :
- La base est `local.volume`, à défaut le volume affiché (`state.value.volume`). La cible est `(base ± 5).coerceIn(0, 100)`, posée tout de suite dans `local.volume`.
- La cible est confiée à un `MutableStateFlow<Int?>`. Un seul collecteur, lancé dans `viewModelScope`, envoie `api.volume(v)` puis attend `volumeSendIntervalMs`. La dernière valeur gagne, au plus un envoi par intervalle.
- Après un envoi réussi, le curseur est relâché comme dans `onVolumeRelease` (`holdUntilServer`, `stillHeld = { it.volume == v }`).
- En cas d'échec, `fail()` affiche le même message que le curseur.

**`MainActivity`** :
- `onKeyDown` et `onKeyUp` s'adressent d'abord à `container.volumeKeys`, puis à `super` ;
- dans `composable<RemoteRoute>`, appeler `VolumeKeysWhileResumed(c.volumeKeys, state.running, vm::volumeStep)`.

- [ ] **Step 4: Suite complète et commit**

Run: `cd android && ./gradlew testDebugUnitTest assembleDebug checkNoGms`
Expected: BUILD SUCCESSFUL.

```bash
git add android/app/src/main/java/fr/remotecontrol/ui/remote/ android/app/src/main/java/fr/remotecontrol/AppContainer.kt android/app/src/main/java/fr/remotecontrol/MainActivity.kt android/app/src/test/
git commit -m "feat(android): touches de volume du téléphone sur la télécommande"
```

- [ ] **Step 5: Essai manuel, noté pour l'utilisateur**

Sur un vrai téléphone (risque 3 de la conception), vérifier :
- l'appui sur volume + pendant une lecture, télécommande ouverte : le volume de la télé monte, sans curseur du téléphone ;
- les mêmes touches sur l'accueil : le volume du téléphone change.

Cet essai se fait à l'installation de l'APK par l'utilisateur. Le signaler dans le compte rendu, sans le simuler.
