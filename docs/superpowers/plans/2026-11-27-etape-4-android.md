# Étape 4 — Appli Android — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** La fiche de l'œuvre du téléphone affiche les notes (dont la note personnelle et la note technique) et des boutons vers Sens Critique, IMDb, Wikipédia et TMDB (maquette D).

**Architecture:** Les notes (`ratings`) et `tmdb_url` arrivent par `/api/sync` (arbitrages 24 et 25), sont rangés dans `WorkEntity` (Room v3, migration destructive existante), puis une fonction pure `notesUi(ratings, tmdbUrl)` prépare la section affichée par `WorkScreen`.

**Tech Stack:** Kotlin, Jetpack Compose M3, Room, kotlinx.serialization ; aucun service Google.

**Spec:**
- **Conception :** `docs/superpowers/specs/2026-11-27-etape-4-design.md` (§ 4.3 maquette D, § 5.2, § 5.7).
- **Contrat :** `docs/superpowers/plans/2026-11-22-api-contract-rulings.md`, arbitrages 24 à 26. Ils font foi.

## Global Constraints

- Tout texte visible en français, via `ui/common/Texts.kt`.
- Aucune dépendance Google : `./gradlew checkNoGms` doit passer. compileSdk 37, target 36, min 29.
- Room : version 3, migration destructive existante (`fallbackToDestructiveMigration(dropAllTables = true)` seulement, sans `OnDowngrade`) ; le premier lancement refait une synchro complète.
- Échelle `NP`/`NT` : 1 « bof », 3 « pas terrible », 5 « bien », 7 « très bien », 9 « excellent » ; autre valeur en chiffre sans décimale inutile.
- Valeurs : format français, une décimale pour `IM` et `SC` (« 7,0 »), sans décimale inutile pour les critiques (« 8 », « 7,5 »).
- Tests : `cd android && ./gradlew testDebugUnitTest assembleDebug checkNoGms` passe en entier (381 au départ), sans avertissement Kotlin.
- Git : ne jamais ajouter `.superpowers/`, `.playwright-mcp/`, `docs/data/` ; donner le vrai SHA de chaque commit.

## Review Focus

1. **Serveur de l'étape 3** (pas de `ratings` ni `tmdb_url`) : la synchro réussit, la section est absente. Test : Task 1 `old_server_work_without_ratings_decodes`.
2. **Œuvre avec des liens mais sans aucune note** (Wikipédia seul) : pas de ligne de notes ni de pastilles, seulement les boutons. Test : Task 2 `links_only`.
3. **Note `NP` sans `NT`** (cas le plus courant) : « Ma note très bien » seul, sans « · Technique ». Test : Task 2 `personal_note_alone`.
4. **Critique sans valeur** (`value` nul) : ni pastille ni bouton s'il n'a pas de lien. Test : Task 2 `critic_without_value_is_hidden`.
5. **Lien sans application de navigateur** : l'appui ne plante pas (message d'erreur existant ou rien). Test : Task 2, revue de code (pas de test JVM possible).

---

### Task 1: Notes et lien TMDB dans la synchro et la base locale

**Files:**
- Modify: `net/ApiDtos.kt` (`WorkDto` l. 42), `data/db/Entities.kt` (`WorkEntity` l. 14), `data/db/Converters.kt`, `data/db/AppDatabase.kt` (`version = 2` → 3), `data/Search.kt` (`WorkDto.toEntity` l. 29)
- Test: `src/test/java/fr/remotecontrol/net/ApiDtosTest.kt`, tests de synchro existants (`grep -rln SyncWriter android/app/src/test`), `data/db/DatabaseBuildTest.kt`

**Interfaces:**
- Produces :
  - `@Serializable data class RatingDto(val code: String, val name: String, val value: Double? = null, val url: String? = null)` ;
  - `WorkDto.ratings: List<RatingDto> = emptyList()`, `WorkDto.tmdbUrl: String? = null` (`@SerialName("tmdb_url")`) ;
  - `WorkEntity.ratings: List<RatingDto>` (défaut vide) et `WorkEntity.tmdbUrl: String?`, avec convertisseur Room JSON pour `List<RatingDto>` ;
  - base Room version 3.

- [ ] **Step 1: Tests en échec**

```kotlin
@Test fun work_ratings_and_tmdb_url_decode()      // JSON avec ratings (IM 7.0 + url, SC@ES 8.0 sans url, WK value null) et tmdb_url → valeurs égales
@Test fun old_server_work_without_ratings_decodes() // JSON sans les deux champs → ratings vide, tmdbUrl nul
@Test fun sync_writes_ratings_to_entity()         // SyncWriter.apply puis lecture WorkEntity → ratings et tmdbUrl conservés
// DatabaseBuildTest : version 3, une base v2 est effacée et reconstruite (test existant adapté)
```

- [ ] **Step 2: Lancer les tests** — Expected: FAIL (champs inconnus).

- [ ] **Step 3: Implémenter** selon les Interfaces.

- [ ] **Step 4: Suite complète** — Run: `cd android && ./gradlew testDebugUnitTest assembleDebug checkNoGms` — Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/fr/remotecontrol/net/ApiDtos.kt android/app/src/main/java/fr/remotecontrol/data/ android/app/src/test/
git commit -m "feat(android): notes et lien TMDB des œuvres dans la synchro (Room v3)"
```

---

### Task 2: Section « notes et liens » de la fiche (maquette D)

**Files:**
- Create: `ui/work/WorkNotes.kt`
- Modify: `ui/work/WorkScreen.kt` (après `Synopsis`, avant `Seasons`), `ui/common/Texts.kt`
- Test: `src/test/java/fr/remotecontrol/ui/work/WorkNotesTest.kt`

**Interfaces:**
- Consumes : `WorkEntity.ratings`, `WorkEntity.tmdbUrl` (Task 1).
- Produces :
  - `data class NotesUi(val personal: List<Pair<String, String>>, val pills: List<RatingPill>, val links: List<NoteLink>)` ;
  - `data class RatingPill(val label: String, val value: String, val accent: Boolean)` ;
  - `data class NoteLink(val label: String, val url: String)` ;
  - `fun notesUi(ratings: List<RatingDto>, tmdbUrl: String?): NotesUi?` — nul si rien à afficher ;
  - `@Composable fun WorkNotes(ui: NotesUi, onOpen: (String) -> Unit)`.

Règles de `notesUi` :
- `personal` : paires (libellé, mot) dans l'ordre `NP` (« Ma note ») puis `NT` (« Technique »), pour celles qui ont une valeur ; liste vide sinon. L'affichage les réunit par « · », mot en gras.
- `pills` : `IM` (« IMDb », accent), `SC` (« Sens Critique », accent), puis chaque `SC@…` avec une valeur, nom abrégé en initiale du prénom + nom (« Éric Schwald » → « É. Schwald » ; un seul mot : inchangé), sans accent.
- `links` dans l'ordre : « Sens Critique » (`SC`), « IMDb » (`IM`), « Wikipédia » (`WK`), « TMDB » (`tmdbUrl`), seulement ceux qui ont une URL. Les liens des critiques (`SC@…`) n'ont pas de bouton (le bouton Sens Critique suffit).

- [ ] **Step 1: Tests en échec**

```kotlin
@Test fun full_example()  // IM 7.0, SC 6.2, SC@ES "Éric Schwald" 8, SC@AG "Antoine Goya" 7, NP 7, NT 5, WK url, tmdbUrl
// → personal [("Ma note","très bien"),("Technique","bien")]
// → pills [("IMDb","7,0",true),("Sens Critique","6,2",true),("É. Schwald","8",false),("A. Goya","7",false)]
// → links [Sens Critique, IMDb, Wikipédia, TMDB]
@Test fun personal_note_alone()            // NP 9 → [("Ma note","excellent")]
@Test fun even_personal_value_in_digits()  // NP 6 → [("Ma note","6")]
@Test fun critic_without_value_is_hidden()  // SC@ZZ value null → pas de pastille
@Test fun critic_half_point()              // SC@ES 7.5 → "7,5"
@Test fun links_only()                     // WK seul → personal vide, pills vides, links [Wikipédia]
@Test fun nothing_gives_null()             // ratings vide, tmdbUrl nul → null
```

- [ ] **Step 2: Lancer les tests** — Expected: FAIL.

- [ ] **Step 3: Implémenter**

`WorkNotes` reprend la maquette D : ligne `personal` (texte atténué, mots en gras), rangée de pastilles à bord fin (valeur en couleur d'accent si `accent`), rangée de boutons « libellé ↗ » sur fond foncé, texte d'accent. Couleurs et polices de `RcColors`/`Inter` existants. `onOpen` passe par `LocalUriHandler.current.openUri(url)` dans `WorkScreen`, entouré d'un `runCatching` (aucun navigateur : rien ne se passe). Section absente si `notesUi` est nul. Textes (« Ma note », « Technique », noms d'échelle) dans `Texts.kt`.

- [ ] **Step 4: Suite complète** — Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Vérification à l'émulateur** — faite par le contrôleur après la fusion des deux branches (le serveur de l'étape 4 est sur l'autre branche) : rien à faire ici.

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/fr/remotecontrol/ui/ android/app/src/test/
git commit -m "feat(android): notes et liens sur la fiche de l'œuvre"
```
