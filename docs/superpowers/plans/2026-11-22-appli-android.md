# Appli Android (télécommande) — Plan d'implémentation

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Une appli Android en Kotlin/Compose, sans aucun service Google, qui trouve `rc-server` sur le réseau local, garde une copie du catalogue dans Room pour naviguer hors ligne, affiche les fiches sur la télé, et lance puis pilote la lecture.

**Architecture:** Un seul module Gradle `:app` dans `android/`, découpé en paquets par responsabilité : `net/` (DTO, client HTTP, WebSocket), `data/` (Room, synchronisation), `discovery/` (NSD et adresse manuelle), `player/` (commandes et état de lecture), `qr/` (décodage ZXing), et `ui/<écran>/` (un composable et un ViewModel par écran). Tout ce que l'utilisateur voit passe par un `UiState` produit par un ViewModel : les maquettes Claude Design pourront remplacer les composables sans toucher à la logique. Les dépendances sont assemblées à la main dans `AppContainer`, sans framework d'injection.

**Tech Stack:** Gradle 9.9.0 (wrapper), AGP 9.5.1 (Kotlin intégré), Kotlin 2.4.21, KSP 2.3.13, Compose BOM 2026.09.00 (Material 3), Room 2.8.5 (FTS4), OkHttp 5.5.1 + MockWebServer3 5.5.1, kotlinx.serialization 1.12.0, kotlinx.coroutines 1.11.0, Coil 3.6.3, CameraX 1.6.2, ZXing core 3.5.4, Navigation Compose 2.10.2, Lifecycle 2.11.0, Robolectric 4.17, JUnit 4.13.2. JDK 21.

**Spec:** `docs/superpowers/specs/2026-11-22-remote-control-design.md` (§3, §7, §8, §10, §11). **Contrat d'API :** `docs/superpowers/plans/2026-11-22-serveur-et-ui-tele.md`, Task 10 (formes de données : Tasks 4, 7, 8, 9), complété par `docs/superpowers/plans/2026-11-22-api-contract-rulings.md` (arbitrages 1 à 8, qui font foi en cas d'écart).

## Global Constraints

- Tous les textes visibles par l'utilisateur sont **en français**, et **tous** regroupés dans `fr.remotecontrol.ui.common.Texts` (libellés, boutons, titres de section, messages, noms de langue, libellés de type). Aucun composable ni ViewModel n'écrit de chaîne visible en dur.
- **Aucune dépendance aux Google Play Services** : ni `com.google.android.gms`, ni `com.google.firebase`, ni `com.google.mlkit`, ni `com.google.android.play`. La tâche Gradle `checkNoGms` le vérifie à chaque build, sur les classpaths d'exécution debug **et** release.
- `minSdk = 29` (Android 10), `compileSdk = 36`, `targetSdk = 36`. Distribution par APK.
- Paquet et `applicationId` : **`fr.remotecontrol`** (`.debug` en suffixe pour le build debug).
- Port par défaut **8765**. Service mDNS **`_rc._tcp`** (`_rc._tcp.local.` côté serveur). Réseau local en HTTP clair, **sans authentification**.
- QR-codes : `rc://w/<id>` (œuvre), `rc://s/<id>` (saison).
- Qualités lisibles, de la meilleure à la moins bonne : `UHD > 2HD > FHD > HD > SD > BD`.
- La navigation fonctionne **hors ligne** depuis Room. Seules les commandes ont besoin du serveur.
- Filtre par défaut : **œuvres lisibles seulement** (au moins un fichier présent), avec une bascule « Tout le catalogue ».
- Ouvrir une fiche d'œuvre ou de saison l'affiche aussi sur la télé (`POST /api/tv/show`). Le serveur l'ignore pendant une lecture : l'appli n'envoie jamais `force`.
- Les booléens du contrat sont de **vrais booléens JSON** (arbitrage 1) : aucun décodage toléré de 0/1.
- Quand la feuille de choix est affichée, l'appli envoie **toujours** la version, l'audio et les sous-titres affichés (arbitrage 8 : le `default` de `/tracks` reflète déjà le dernier choix). Les boutons directs (« Lire depuis le début », « Reprendre ») n'envoient aucune piste, et le serveur réapplique le dernier choix.
- Les tests tournent sans émulateur : `./gradlew testDebugUnitTest` et `./gradlew assembleDebug`, lancés depuis `android/`.
- Chaque écran : un composable `XxxScreen(state, callbacks)` sans logique, un `XxxViewModel` qui expose `state: StateFlow<XxxUi>`.

## Review Focus

1. **Serveur éteint, ou qui a changé d'adresse IP (DHCP)** → la navigation reste possible depuis le cache, les affiches déjà vues s'affichent depuis le cache disque (clé indépendante de l'hôte), une commande affiche « Serveur injoignable » sans planter, et au retour au premier plan NSD retrouve la nouvelle adresse. Testé en Tasks 3, 7 et 8.
2. **Synchronisation interrompue, ou base du serveur recréée** (révision du serveur inférieure à la révision locale) → le cache n'est jamais laissé à moitié écrit (une transaction, la révision écrite en dernier), et une révision qui recule déclenche une synchronisation complète qui efface les lignes périmées. Testé en Task 6.
3. **Recherche avec accents, casse, mots partiels et caractères spéciaux** (`negres` → « Dix Petits Nègres », `l'etr` → « L'Étranger », `patelliere` → un réalisateur, saisie `"`, `*`, `-`, `OR`) → résultats corrects, jamais d'exception FTS. Testé en Task 5.
4. **Barre de position déplacée pendant que le serveur pousse la position** → le curseur ne saute pas en arrière pendant le glissement, et une seule commande `seek` part au relâchement. Idem pour le volume. Testé en Task 12.
5. **Scan QR en conditions réelles** (images caméra avec `rowStride` > largeur, même code décodé 30 fois par seconde, QR étranger, identifiant absent du cache) → une seule navigation, un message en français pour un QR inconnu, et une resynchronisation avant de déclarer l'œuvre introuvable. Testé en Task 13.

---

## Structure des fichiers

```
android/
  settings.gradle.kts  build.gradle.kts  gradle.properties  gradlew  gradlew.bat
  gradle/libs.versions.toml  gradle/wrapper/{gradle-wrapper.jar,gradle-wrapper.properties}
  app/
    build.gradle.kts  proguard-rules.pro  schemas/            # schéma Room exporté
    src/main/AndroidManifest.xml
    src/main/res/xml/network_security_config.xml
    src/main/java/fr/remotecontrol/
      RcApp.kt                      # Application + ImageLoader Coil
      AppContainer.kt               # assemblage manuel des dépendances
      MainActivity.kt               # NavHost, cycle de vie (locate, sync, events)
      net/
        Json.kt                     # RcJson
        ApiDtos.kt                  # DTO du contrat (Task 10 du plan serveur)
        ServerEvents.kt             # ServerEvent + parseServerEvent
        RcApi.kt                    # interface RcApi, ApiResult, ApiError, ServerProbe
        OkHttpRcApi.kt
        EventsClient.kt             # WebSocket /api/events, reconnexion
      data/
        db/Entities.kt  db/Converters.kt  db/CatalogDao.kt  db/SyncDao.kt
        db/MetaDao.kt   db/AppDatabase.kt  db/ReadModels.kt
        Search.kt                   # fold(), ftsText(), buildFtsQuery()
        KeyValueStore.kt
        sync/SyncWriter.kt  sync/SyncRepository.kt  sync/SyncCoordinator.kt
        CatalogFilter.kt
      discovery/
        ServerAddress.kt  NsdServerDiscovery.kt  ServerLocator.kt
      player/
        PlayCommands.kt             # PlayTarget, StartMode, playRequest()
        PlayLauncher.kt             # lecture partagée : api.play → ToRemote ou message
        Versions.kt                 # QUALITY_ORDER, availabilityOf, preferredVersion
        Tracks.kt                   # languageName, trackOptions, subtitleOptions
        RemoteController.kt
      qr/
        QrPayload.kt  QrDecoder.kt  QrAnalyzer.kt
      ui/
        common/Texts.kt  common/Format.kt  common/NavEvent.kt  common/Images.kt  common/Routes.kt
        common/WorkList.kt          # liste d'œuvres partagée (parcours, recherche)
        home/  browse/  search/  work/  season/  preplay/  remote/  scan/  settings/
          (XxxScreen.kt + XxxViewModel.kt dans chaque dossier)
    src/test/java/fr/remotecontrol/...   # tests JVM (Robolectric pour Room et Android)
    src/test/java/fr/remotecontrol/testing/{Fixtures,TestDb,FakeRcApi,FakeKeyValueStore,MainDispatcherRule}.kt
    src/test/resources/fixtures/*.json   # réponses du serveur (contrat)
    src/test/resources/robolectric.properties
```

**Choix de test :** Room est testé en mémoire sous **Robolectric 4.17** (`sdk=36`). Robolectric embarque un vrai SQLite natif, avec FTS4, ce qui teste les vraies requêtes générées par Room, sans émulateur. Un pilote SQLite « bundled » sur la JVM aurait demandé une seconde configuration de base uniquement pour les tests. Les ViewModels qui lisent Room tournent aussi sous Robolectric. Le reste (DTO, client HTTP, WebSocket, logique pure) tourne en JUnit simple.

---

### Task 1: Squelette Gradle, manifeste, textes et formats

**Files:**
- Create: `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`, `android/gradle/libs.versions.toml`, `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/*`, `android/app/build.gradle.kts`, `android/app/proguard-rules.pro`, `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/res/xml/network_security_config.xml`, `android/app/src/main/java/fr/remotecontrol/{RcApp.kt,MainActivity.kt}`, `android/app/src/main/java/fr/remotecontrol/ui/common/{Texts.kt,Format.kt}`, `android/app/src/test/resources/robolectric.properties`
- Modify: `.gitignore` (racine) : `android/.gradle/`, `android/build/`, `android/app/build/`, `android/local.properties`, `android/.kotlin/`
- Test: `android/app/src/test/java/fr/remotecontrol/ui/common/FormatTest.kt`

**Interfaces:**
- Produces (`Format.kt`) :
  - `fun formatDuration(minutes: Int): String` — `99` → `"1 h 39"`, `45` → `"45 min"`, `120` → `"2 h 00"`.
  - `fun formatClock(seconds: Double): String` — `75.0` → `"1:15"`, `3725.0` → `"1:02:05"`, valeurs négatives ramenées à 0.
  - `fun formatHhMm(seconds: Double): String` — `4320.0` → `"01:12"`, `600.0` → `"00:10"`.
  - `val WORK_TYPES = listOf("film", "serie", "doc", "theatre", "tele", "spectacle")`
- Produces (`Texts.kt`, `object Texts`) — copie exacte. **Fonctions de libellé** :
  - `fun typeLabel(type: String): String` — `film` → `"Films"`, `serie` → `"Séries"`, `doc` → `"Documentaires"`, `theatre` → `"Théâtre"`, `tele` → `"Téléfilms"`, `spectacle` → `"Spectacles"`, sinon `type`.
  - `fun languageName(code: String?): String` — `fr` → `"Français"`, `en` → `"Anglais"`, `de` → `"Allemand"`, `es` → `"Espagnol"`, `it` → `"Italien"`, `ja` → `"Japonais"`, `ru` → `"Russe"`, `pt` → `"Portugais"`, `nl` → `"Néerlandais"`, `null` → `"Langue inconnue"`, sinon le code en majuscules.
  - `fun episodeCode(season: Int, number: Int): String` — `(1, 2)` → `"S01E02"` ; saison 0 → `"Épisode 2"`.
  - `fun episodeTitle(work: String, season: Int, number: Int): String` — `("Back to Life", 1, 2)` → `"Back to Life — S01E02"` ; saison 0 → `"<work> — épisode 2"`.
  - `fun versionLabel(quality: String, size: String?): String` — `("FHD", "3.8G")` → `"FHD · 3.8G"`, `("HD", null)` → `"HD"`.
  - `fun serverDetected(display: String) = "$display (détecté)"`, `fun serverManual(display: String) = "$display (saisi à la main)"`, `fun catalogInfo(revision: Long?, count: Int) = "Révision du catalogue : ${revision ?: "—"} · $count œuvres"`.
  - `fun externalSub(lang: String?) = "${languageName(lang)} (fichier)"`, `fun embeddedSub(lang: String?) = "${languageName(lang)} (intégré)"`.
  - `fun skipLabel(seconds: Int)` — `-60` → `"−60 s"`, `10` → `"+10 s"`.
- **Libellés d'écran** (constantes) : `appName = "Remote-Control"`, `back = "Retour"`, `sectionRecent = "Récemment ajoutés"`, `sectionBrowse = "Parcourir"`, `sectionCollections = "Collections"`, `search = "Rechercher"`, `searchHint = "Titre, réalisateur, acteur…"`, `scan = "Scanner un QR-code"`, `remote = "Télécommande"`, `settings = "Réglages"`, `notAvailable = "Non lisible"`, `settingsServer = "Serveur"`, `manualAddress = "Adresse manuelle (secours)"`, `save = "Enregistrer"`, `rediscover = "Rechercher le serveur"`, `resync = "Resynchroniser"`, `seasons = "Saisons"`, `versions = "Versions"`, `version = "Version"`, `audio = "Audio"`, `subtitles = "Sous-titres"`, `startPlayback = "Lancer la lecture"`, `play = "Lecture"`, `pause = "Pause"`, `restart = "Début"`, `volume = "Volume"`, `stop = "Arrêter"`, `directors = "Réalisation"`, `cast = "Avec"`.
- **Messages** :
  `serverUnreachable = "Serveur injoignable"`, `noServer = "Aucun serveur trouvé"`, `searching = "Recherche du serveur…"`, `notPlayable = "Aucun fichier lisible pour cette œuvre"`, `notFound = "Fichier introuvable"`, `idle = "Aucune lecture en cours"`, `fun volumeAbsent(label: String?) = label?.let { "Disque « $it » non branché" } ?: "Disque non branché"`, `notDigitized = "Non numérisé"`, `fun httpError(status: Int) = "Erreur du serveur ($status)"`, `playFromStart = "Lire depuis le début"`, `fun resumeAt(seconds: Double) = "Reprendre à ${formatHhMm(seconds)}"`, `fromStart = "Depuis le début"`, `resume = "Reprendre"`, `tracksAndVersion = "Version et pistes…"`, `watched = "Vu"`, `fun started(seconds: Double) = "Entamé — ${formatClock(seconds)}"`, `subtitleNone = "Aucun"`, `playableOnly = "Œuvres lisibles"`, `wholeCatalogue = "Tout le catalogue"`, `catalogEmpty = "Catalogue vide : recherche du serveur…"`, `qrUnknown = "QR-code non reconnu"`, `qrNotInCatalog = "Œuvre introuvable dans le catalogue"`, `cameraPermission = "Autorisez l'appareil photo pour scanner les QR-codes"`, `invalidAddress = "Adresse invalide"`, `addressUnreachable = "Serveur injoignable à cette adresse"`, `fun synced(count: Int) = "Catalogue synchronisé ($count œuvres)"`, `nothingPlaying = "Lecture en cours"`.

- [ ] **Step 1: wrapper sans Gradle système.** Générer le wrapper avec la distribution déjà en cache : `~/.gradle/wrapper/dists/gradle-9.9.0-bin/*/gradle-9.9.0/bin/gradle -p android wrapper --gradle-version 9.9.0`. Écrire `android/local.properties` (ignoré par git) avec `sdk.dir=/home/beber/Android/Sdk`.
- [ ] **Step 2: configuration Gradle.** `settings.gradle.kts` : dépôts `google()`, `mavenCentral()`, `gradlePluginPortal()`, `RepositoriesMode.FAIL_ON_PROJECT_REPOS`, plugin `org.gradle.toolchains.foojay-resolver-convention` 1.0.0, `rootProject.name = "RemoteControl"`, `include(":app")`. `gradle.properties` : `org.gradle.jvmargs=-Xmx4g -Dfile.encoding=UTF-8`, `org.gradle.caching=true`, `org.gradle.configuration-cache=true`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true`. `libs.versions.toml` : les versions exactes du Tech Stack, plus `coreKtx = "1.19.1"`, `activityCompose = "1.13.0"`, `androidxTestCore = "1.7.0"`, `androidxTestExtJunit = "1.3.0"`. Bibliothèques : `core-ktx`, `activity-compose`, `compose-bom`, `compose-ui`, `compose-material3`, `compose-ui-tooling-preview`, `lifecycle-viewmodel-compose`, `lifecycle-runtime-compose`, `navigation-compose`, `room-runtime`, `room-ktx`, `room-compiler`, `kotlinx-coroutines-android`, `kotlinx-serialization-json`, `okhttp`, `coil-compose`, `coil-network-okhttp`, `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`, `zxing-core` ; tests : `junit`, `robolectric`, `kotlinx-coroutines-test`, `androidx-test-core`, `androidx-test-ext-junit`, `mockwebserver3` (`com.squareup.okhttp3:mockwebserver3:5.5.1`). Plugins : `com.android.application`, `org.jetbrains.kotlin.plugin.compose`, `org.jetbrains.kotlin.plugin.serialization`, `com.google.devtools.ksp`, `androidx.room`. **Pas** de plugin `kotlin-android` : AGP 9 intègre Kotlin.
- [ ] **Step 3: `app/build.gradle.kts`.** `namespace = applicationId = "fr.remotecontrol"`, `compileSdk = 36`, `minSdk = 29`, `targetSdk = 36`, `versionCode = 1`, `versionName = "0.1.0"`, debug `applicationIdSuffix = ".debug"`, `buildFeatures.compose = true`, `kotlin { jvmToolchain(21) }`, `room { schemaDirectory("$projectDir/schemas") }`, `testOptions.unitTests.isIncludeAndroidResources = true` et `unitTests.all { it.jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED") }`. `robolectric.properties` : `sdk=36`. Ajouter la tâche `checkNoGms`, compatible avec le cache de configuration, et la brancher sur `preBuild` :

```kotlin
val runtimeArtifacts = listOf("debugRuntimeClasspath", "releaseRuntimeClasspath").map { name ->
    configurations.named(name).flatMap { it.incoming.artifacts.resolvedArtifacts }
        .map { set -> set.map { it.id.componentIdentifier.displayName } }
}.reduce { a, b -> a.zip(b) { x, y -> x + y } }
val checkNoGms by tasks.registering {
    inputs.property("artifacts", runtimeArtifacts)
    doLast {
        val banned = listOf("com.google.android.gms", "com.google.firebase", "com.google.mlkit", "com.google.android.play")
        val hits = runtimeArtifacts.get().filter { a -> banned.any { a.startsWith(it) } }
        if (hits.isNotEmpty()) throw GradleException("Dépendances Google interdites : $hits")
    }
}
tasks.named("preBuild") { dependsOn(checkNoGms) }
```

- [ ] **Step 4: manifeste.** Permissions `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CAMERA`. `<uses-feature android:name="android.hardware.camera.any" android:required="false"/>`. `android:networkSecurityConfig="@xml/network_security_config"`, avec `<base-config cleartextTrafficPermitted="true"/>` (HTTP clair sur le réseau local, sinon toutes les requêtes échouent dès l'API 28). `RcApp` déclarée comme `android:name`. `MainActivity` affiche pour l'instant un `Text(Texts.appName)` dans un `MaterialTheme`.
- [ ] **Step 5: test qui échoue** (`FormatTest`, qui couvre aussi `Texts`) : `formatDuration(99) == "1 h 39"`, `formatDuration(45) == "45 min"`, `formatDuration(120) == "2 h 00"`, `formatClock(75.0) == "1:15"`, `formatClock(3725.0) == "1:02:05"`, `formatClock(-3.0) == "0:00"`, `formatHhMm(4320.0) == "01:12"`, `Texts.resumeAt(4320.0) == "Reprendre à 01:12"`, `Texts.volumeAbsent("DISK1") == "Disque « DISK1 » non branché"`, `Texts.volumeAbsent(null) == "Disque non branché"`, `Texts.typeLabel("theatre") == "Théâtre"`, `Texts.languageName(null) == "Langue inconnue"`, `Texts.languageName("sv") == "SV"`, `Texts.episodeCode(0, 2) == "Épisode 2"`, `Texts.episodeTitle("Back to Life", 1, 2) == "Back to Life — S01E02"`, `Texts.versionLabel("HD", null) == "HD"`, `Texts.skipLabel(-60) == "−60 s"`.
- [ ] **Step 6:** `cd android && ./gradlew testDebugUnitTest --tests '*FormatTest'` → FAIL (références non résolues).
- [ ] **Step 7:** implémenter `Format.kt` et `Texts.kt`.
- [ ] **Step 8:** `./gradlew testDebugUnitTest assembleDebug checkNoGms` → BUILD SUCCESSFUL, `app/build/outputs/apk/debug/app-debug.apk` existe.
- [ ] **Step 9:** commit `feat(android): squelette Gradle, textes et formats`.

---

### Task 2: DTO du contrat et fixtures JSON

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/net/{Json.kt,ApiDtos.kt,ServerEvents.kt}`, `android/app/src/test/resources/fixtures/*.json`, `android/app/src/test/java/fr/remotecontrol/testing/Fixtures.kt`
- Test: `android/app/src/test/java/fr/remotecontrol/net/ApiDtosTest.kt`

**Interfaces:**
- Produces (`Json.kt`) :
  - `val RcJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true; classDiscriminator = "kind" }`
- Produces (`ApiDtos.kt`, tous `@Serializable`, noms JSON en `@SerialName` snake_case ; champs nullables avec `= null`, listes avec `= emptyList()`) :
  - `InfoDto(name: String, api: Int, revision: Long)`
  - `SyncDto(revision: Long, full: Boolean, works: List<WorkDto>, seasons: List<SeasonDto>, episodes: List<EpisodeDto>, mediaFiles: List<MediaFileDto> /*media_files*/, collections: List<CollectionDto>, workCollections: List<WorkCollectionDto> /*work_collections*/, progress: List<ProgressDto>, volumes: List<VolumeDto>, deleted: DeletedDto = DeletedDto())`
  - `DeletedDto(work: List<Long>, season: List<Long>, episode: List<Long>, mediaFile: List<Long> /*media_file*/, collection: List<Long>)` (arbitrage 3 : les enfants d'une œuvre supprimée y figurent aussi)
  - `WorkDto(id: Long, type: String, key: String, titleFr: String /*title_fr*/, titleVo: String?, titleAlt: String?, year: Int?, durationMin: Int? /*duration_min*/, episodeCount: String? /*episode_count*/, directors: List<String>, genres: List<String>, cast: List<String>, synopsis: String?, imdbId: String?, tags: List<String>, episodic: Boolean = false, poster: String?)`
  - `SeasonDto(id: Long, workId: Long, number: Int, label: String)`
  - `EpisodeDto(id: Long, seasonId: Long, number: Int, title: String?)`
  - `MediaFileDto(id: Long, workId: Long?, episodeId: Long?, quality: String, size: String?, relpath: String, volumeUuid: String?, present: Boolean = false)`
  - `CollectionDto(id: Long, name: String, description: String?, link: String?)`
  - `WorkCollectionDto(workId: Long, collectionId: Long, rank: String?)`
  - `ProgressDto(targetKind: String, targetId: Long, mediaFileId: Long?, position: Double, duration: Double?, watched: Boolean = false, updatedAt: Double)` (`audio` et `subtitle`, décodés côté serveur, sont ignorés : arbitrage 7)
  - `VolumeDto(uuid: String, label: String?, mount: String?, present: Boolean = false)`
  - `TrackDto(index: Int, lang: String?, title: String?, codec: String?, default: Boolean = false)`
  - `ExternalSubDto(path: String, lang: String?)`
  - `TrackDefaultsDto(audio: Int?, subtitle: SubSel = SubSel.None)`
  - `MediaTracksDto(audio: List<TrackDto>, subtitles: List<TrackDto>, external: List<ExternalSubDto>, videoCodec: String?, height: Int?, default: TrackDefaultsDto)`
  - `@Serializable sealed interface SubSel { @SerialName("none") data object None; @SerialName("embedded") data class Embedded(val index: Int); @SerialName("external") data class External(val path: String) }` → `{"kind": "none"}`, `{"kind": "embedded", "index": n}`, `{"kind": "external", "path": "…"}`.
  - `PlayerStateDto(position: Double = 0.0, duration: Double? = null, paused: Boolean = false, volume: Int = 100, audio: Int? = null, subtitle: SubSel = SubSel.None, ended: Boolean = false, error: String? = null, running: Boolean = false)`
  - `CurrentDto(targetKind: String, targetId: Long, mediaFileId: Long, workId: Long /*work_id*/, seasonId: Long? = null /*season_id*/, title: String? = null)` (arbitrage 5)
  - `PlayerSnapshotDto(state: PlayerStateDto = PlayerStateDto(), current: CurrentDto? = null)`
  - `TvStateDto(screen: String, workId: Long?, seasonId: Long?, message: String?)`
  - `PlayRequestDto(targetKind: String, targetId: Long, start: String, mediaFileId: Long? = null, audio: Int? = null, subtitle: SubSel? = null)`
  - `TvShowRequestDto(screen: String, id: Long? = null)`, `TvShowResultDto(shown: Boolean)`
  - `ErrorDto(error: String, volumeLabel: String? = null)`
- Produces (`ServerEvents.kt`) :
  - `sealed interface ServerEvent { data class Hello(val revision: Long, val tv: TvStateDto, val player: PlayerSnapshotDto); data class Player(val snapshot: PlayerSnapshotDto); data class Tv(val state: TvStateDto); data class Catalog(val revision: Long) }`
  - `fun parseServerEvent(text: String): ServerEvent?` : lit le champ `type`. Un message `player` est décodé tel quel en `PlayerSnapshotDto` (champs `state` et `current` au premier niveau). Renvoie `null` pour un type inconnu ou un JSON invalide, sans exception.
- Produces (test) : `object Fixtures { fun text(name: String): String }`, qui lit `src/test/resources/fixtures/<name>`.

- [ ] **Step 1: écrire les fixtures** (contenu exact, réutilisé par les Tasks 3 à 6). Elles suivent le catalogue d'exemple du plan serveur et les arbitrages du contrat (vrais booléens, toutes les erreurs 409 avec `volume_label`, `progress.audio` entier).

`sync_full.json` :
```json
{"revision": 3, "full": true,
 "works": [
  {"id": 1, "type": "film", "key": "Ava [2017]", "title_fr": "Ava", "title_vo": null, "title_alt": null, "year": 2017, "duration_min": 99, "episode_count": null, "directors": ["Léa Mysius"], "genres": ["comédie dramatique"], "cast": ["Juan Cano", "Noée Abita", "Laure Calamy"], "synopsis": "Ava, 13 ans, est en vacances au bord de l'océan...", "imdb_id": "tt6194530", "tags": ["[FR]", "[ST*]", "[RU]"], "episodic": false, "poster": "a1b2c3d4e5f60718", "rev": 1},
  {"id": 2, "type": "film", "key": "Alien, le huitieme passager [1979]", "title_fr": "Alien, le huitième passager", "title_vo": "Alien", "title_alt": "L'Étranger", "year": 1979, "duration_min": 117, "episode_count": null, "directors": ["Ridley Scott"], "genres": ["Science-fiction", "Horreur"], "cast": [], "synopsis": null, "imdb_id": null, "tags": [], "episodic": false, "poster": null, "rev": 1},
  {"id": 3, "type": "film", "key": "Le prenom [2012]", "title_fr": "Le Prénom", "title_vo": null, "title_alt": null, "year": 2012, "duration_min": 109, "episode_count": null, "directors": ["Alexandre de La Patellière", "Matthieu Delaporte"], "genres": ["comédie"], "cast": [], "synopsis": null, "imdb_id": null, "tags": [], "episodic": false, "poster": null, "rev": 1},
  {"id": 4, "type": "theatre", "key": "Le prenom [2012]", "title_fr": "Le Prénom", "title_vo": null, "title_alt": null, "year": 2012, "duration_min": 110, "episode_count": null, "directors": ["Bernard Murat"], "genres": [], "cast": [], "synopsis": null, "imdb_id": null, "tags": [], "episodic": false, "poster": null, "rev": 1},
  {"id": 5, "type": "serie", "key": "Back to Life [2019]", "title_fr": "Back to Life", "title_vo": null, "title_alt": null, "year": 2019, "duration_min": null, "episode_count": "12/12", "directors": ["Laura Solon", "Daisy Haggard"], "genres": ["comédie"], "cast": [], "synopsis": null, "imdb_id": null, "tags": ["[FR-]", "[VO]"], "episodic": true, "poster": null, "rev": 1}],
 "seasons": [
  {"id": 1, "work_id": 5, "number": 1, "label": "Saison 1", "rev": 1},
  {"id": 2, "work_id": 5, "number": 2, "label": "Saison 2", "rev": 1}],
 "episodes": [
  {"id": 1, "season_id": 1, "number": 1, "title": null, "rev": 1},
  {"id": 2, "season_id": 1, "number": 2, "title": null, "rev": 1},
  {"id": 3, "season_id": 2, "number": 1, "title": null, "rev": 1}],
 "media_files": [
  {"id": 1, "work_id": 1, "episode_id": null, "quality": "FHD", "size": "3.8G", "relpath": "Films/A/Ava [2017] [1h45,1920x1038,16x9,FR,sEN].mp4", "volume_uuid": "DISK1", "present": true, "rev": 2},
  {"id": 2, "work_id": 1, "episode_id": null, "quality": "HD", "size": "1.6G", "relpath": "Films/A/Ava [2017] [1h39,1280x720,16x9,FR].mp4", "volume_uuid": "DISK1", "present": true, "rev": 2},
  {"id": 3, "work_id": 3, "episode_id": null, "quality": "HD", "size": "1.1G", "relpath": "Films/L/Le prenom [2012] [1h49,1280x720,16x9,FR].mp4", "volume_uuid": "DISK1", "present": true, "rev": 2},
  {"id": 4, "work_id": 4, "episode_id": null, "quality": "SD", "size": "700M", "relpath": "Theatre/Le prenom [2012] [1h50,720x576,4x3,FR].mp4", "volume_uuid": "DISK2", "present": false, "rev": 2},
  {"id": 5, "work_id": null, "episode_id": 1, "quality": "FHD", "size": "383M", "relpath": "Series/Back to Life [2019]/Saison 01/S01E01 [0h27,1920x1080,16x9,EN].mp4", "volume_uuid": "DISK1", "present": true, "rev": 2},
  {"id": 6, "work_id": null, "episode_id": 2, "quality": "FHD", "size": "429M", "relpath": "Series/Back to Life [2019]/Saison 01/S01E02 [0h24,1920x1080,16x9,EN].mp4", "volume_uuid": "DISK1", "present": true, "rev": 2},
  {"id": 7, "work_id": null, "episode_id": 3, "quality": "FHD", "size": "384M", "relpath": "Series/Back to Life [2019]/Saison 02/S02E01 [0h27,1920x1080,16x9,EN].mp4", "volume_uuid": "DISK1", "present": true, "rev": 2}],
 "collections": [
  {"id": 1, "name": "Les 1000 plus grands films de tous les temps [2020]", "description": null, "link": null, "rev": 1},
  {"id": 2, "name": "Alien [1979-]", "description": null, "link": "https://fr.wikipedia.org/wiki/Alien_(s%C3%A9rie_de_films)", "rev": 1}],
 "work_collections": [
  {"work_id": 1, "collection_id": 1, "rank": "2017"},
  {"work_id": 2, "collection_id": 2, "rank": null}],
 "progress": [
  {"target_kind": "work", "target_id": 1, "media_file_id": 2, "position": 600.0, "duration": 5940.0, "watched": false, "audio": null, "subtitle": null, "updated_at": 1795000000.0, "rev": 3},
  {"target_kind": "episode", "target_id": 2, "media_file_id": 6, "position": 300.0, "duration": 1440.0, "watched": false, "audio": 1, "subtitle": null, "updated_at": 1795000100.0, "rev": 3}],
 "volumes": [
  {"uuid": "DISK1", "label": "DISK1", "mount": "/media/beber/DISK1", "present": true, "rev": 2},
  {"uuid": "DISK2", "label": "Théâtre", "mount": null, "present": false, "rev": 2}]}
```

`sync_incremental.json` (réponse à `since=3`) :
```json
{"revision": 5, "full": false,
 "works": [{"id": 1, "type": "film", "key": "Ava [2017]", "title_fr": "Ava", "title_vo": null, "title_alt": "Ava la sauvage", "year": 2017, "duration_min": 99, "episode_count": null, "directors": ["Léa Mysius"], "genres": ["comédie dramatique"], "cast": ["Juan Cano", "Noée Abita", "Laure Calamy"], "synopsis": "Ava, 13 ans, est en vacances au bord de l'océan...", "imdb_id": "tt6194530", "tags": ["[FR]"], "episodic": false, "poster": "a1b2c3d4e5f60718", "rev": 5}],
 "seasons": [], "episodes": [],
 "media_files": [{"id": 4, "work_id": 4, "episode_id": null, "quality": "SD", "size": "700M", "relpath": "Theatre/Le prenom [2012] [1h50,720x576,4x3,FR].mp4", "volume_uuid": "DISK2", "present": true, "rev": 4}],
 "collections": [], "work_collections": [],
 "progress": [{"target_kind": "episode", "target_id": 2, "media_file_id": 6, "position": 0.0, "duration": 1440.0, "watched": true, "audio": null, "subtitle": null, "updated_at": 1795000500.0, "rev": 5}],
 "volumes": [{"uuid": "DISK2", "label": "Théâtre", "mount": "/media/beber/Theatre", "present": true, "rev": 4}],
 "deleted": {"work": [3], "season": [], "episode": [3], "media_file": [3, 7], "collection": []}}
```

`tracks.json` :
```json
{"audio": [{"index": 1, "lang": "en", "title": null, "codec": "aac", "default": true},
           {"index": 2, "lang": "fr", "title": "Français", "codec": "ac3", "default": false}],
 "subtitles": [{"index": 1, "lang": "en", "title": null, "codec": "subrip", "default": false}],
 "external": [{"path": "/media/beber/DISK1/Films/A/Ava [2017] [1h45,1920x1038,16x9,FR,sEN].1.fr.srt", "lang": "fr"}],
 "video_codec": "h264", "height": 1038,
 "default": {"audio": 2, "subtitle": {"kind": "none"}}}
```

`info.json` : `{"name": "Remote-Control", "api": 1, "revision": 5}`
`error_volume_absent.json` : `{"error": "volume_absent", "volume_label": "Théâtre"}`
`error_idle.json` : `{"error": "idle", "volume_label": null}`
`player_state.json` :
```json
{"state": {"position": 612.5, "duration": 5940.0, "paused": false, "volume": 80, "audio": 2, "subtitle": {"kind": "external", "path": "/media/beber/DISK1/Films/A/Ava [2017] [1h45,1920x1038,16x9,FR,sEN].1.fr.srt"}, "ended": false, "error": null, "running": true},
 "current": {"target_kind": "work", "target_id": 1, "media_file_id": 2, "work_id": 1, "season_id": null, "title": "Ava"}}
```
`ws_hello.json` : `{"type": "hello", "revision": 5, "tv": {"screen": "home", "work_id": null, "season_id": null, "message": null}, "player": {"state": {"position": 0, "duration": null, "paused": false, "volume": 100, "audio": null, "subtitle": {"kind": "none"}, "ended": false, "error": null, "running": false}, "current": null}}`
`ws_player.json` : le contenu de `player_state.json` avec `"type": "player"` ajouté au premier niveau.
`ws_tv.json` : `{"type": "tv", "state": {"screen": "work", "work_id": 1, "season_id": null, "message": null}}`
`ws_catalog.json` : `{"type": "catalog", "revision": 6}`

- [ ] **Step 2: tests qui échouent** (`ApiDtosTest`) :
  - `sync_full_decodes` : 5 œuvres, `works[1].titleAlt == "L'Étranger"`, `works[1].episodic == false`, `works[4].episodic == true`, `mediaFiles[3].present == false`, `progress[1].watched == false`, `volumes[1].label == "Théâtre"`, `deleted == DeletedDto()`.
  - `sync_incremental_decodes_deleted` : `deleted.work == listOf(3L)`, `deleted.mediaFile == listOf(3L, 7L)`, `deleted.collection == emptyList<Long>()`, `mediaFiles[0].present == true`.
  - `tracks_decode_defaults` : `audio[1].lang == "fr"`, `external[0].lang == "fr"`, `default.audio == 2`, `default.subtitle == SubSel.None`.
  - `subsel_roundtrip` : `RcJson.encodeToString<SubSel>(SubSel.Embedded(2)) == """{"kind":"embedded","index":2}"""`, `SubSel.None` → `{"kind":"none"}`, et le décodage inverse.
  - `play_request_encodes_snake_case` : `PlayRequestDto("work", 1, "resume", mediaFileId = 2, audio = 2, subtitle = SubSel.None)` encodé contient `"target_kind":"work"`, `"target_id":1`, `"start":"resume"`, `"media_file_id":2`, `"subtitle":{"kind":"none"}`.
  - `events_parse` : `ws_hello` → `Hello(revision = 5, …)` avec `player.current == null` ; `ws_player` → `Player` avec `snapshot.current.mediaFileId == 2`, `current.workId == 1`, `current.seasonId == null` et `snapshot.state.subtitle is SubSel.External` ; `ws_tv` → `Tv` avec `state.workId == 1` ; `ws_catalog` → `Catalog(6)` ; `"""{"type":"bogus"}"""` → `null` ; `"pas du json"` → `null`.
- [ ] **Step 3:** `./gradlew testDebugUnitTest --tests '*ApiDtosTest'` → FAIL.
- [ ] **Step 4:** implémenter `Json.kt`, `ApiDtos.kt`, `ServerEvents.kt` (sérialiseurs générés uniquement, aucun sérialiseur sur mesure).
- [ ] **Step 5:** relancer → PASS.
- [ ] **Step 6:** commit `feat(android): DTO du contrat d'API et fixtures`.

---

### Task 3: Client HTTP

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/net/{RcApi.kt,OkHttpRcApi.kt}`
- Test: `android/app/src/test/java/fr/remotecontrol/net/OkHttpRcApiTest.kt`

**Interfaces:**
- Consumes: DTO de la Task 2, `Texts` (Task 1).
- Produces (`RcApi.kt`) :
  - `sealed interface ApiResult<out T> { data class Ok<T>(val value: T) : ApiResult<T>; data class Err(val error: ApiError) : ApiResult<Nothing> }`
  - `sealed interface ApiError { data object NoServer; data object Unreachable; data class Server(val code: String, val volumeLabel: String?); data class Http(val status: Int) }`
  - `fun ApiError.messageFr(): String` : `NoServer` → `Texts.noServer`, `Unreachable` → `Texts.serverUnreachable`, `Server("volume_absent", l)` → `Texts.volumeAbsent(l)`, `"not_playable"` → `Texts.notPlayable`, `"not_found"` → `Texts.notFound`, `"idle"` → `Texts.idle`, autre code ou `Http(s)` → `Texts.httpError(s)` (le statut 409 pour un code inconnu).
  - `interface RcApi` : `suspend fun info(): ApiResult<InfoDto>`, `suspend fun sync(since: Long?): ApiResult<SyncDto>`, `suspend fun tracks(mediaFileId: Long): ApiResult<MediaTracksDto>`, `suspend fun tvShow(screen: String, id: Long?): ApiResult<Boolean>`, `suspend fun play(request: PlayRequestDto): ApiResult<Unit>`, `suspend fun pause(paused: Boolean): ApiResult<Unit>`, `suspend fun seek(seconds: Double, relative: Boolean): ApiResult<Unit>`, `suspend fun volume(level: Int): ApiResult<Unit>`, `suspend fun audio(index: Int?): ApiResult<Unit>`, `suspend fun subtitle(sel: SubSel): ApiResult<Unit>`, `suspend fun stop(): ApiResult<Unit>`, `suspend fun playerState(): ApiResult<PlayerSnapshotDto>`
  - `fun interface ServerProbe { suspend fun probe(base: HttpUrl): InfoDto? }` : `null` si injoignable, si le JSON est invalide ou si `api != 1`.
- Produces (`OkHttpRcApi.kt`) :
  - `fun rcOkHttpClient(): OkHttpClient` : `connectTimeout` 3 s, `readTimeout` 30 s, `pingInterval` 20 s (WebSocket).
  - `class OkHttpRcApi(client: OkHttpClient, baseUrl: () -> HttpUrl?, json: Json = RcJson) : RcApi, ServerProbe`. Le fournisseur `baseUrl` est relu à **chaque** appel (l'adresse peut changer). `null` → `Err(NoServer)` sans requête. `IOException` → `Err(Unreachable)`. Réponse 409 → `Err(Server(code, volumeLabel))` décodée depuis `ErrorDto`. Autre statut non 2xx → `Err(Http(status))`. Chemins : `api/info`, `api/sync` (paramètre `since` **absent** quand `since == null`), `api/media/{id}/tracks`, `api/tv/show`, `api/play`, `api/player/{pause,seek,volume,audio,subtitle,stop}` (POST JSON, `/stop` avec `{}`), `api/player/state`. Corps : `{"paused": b}`, `{"seconds": s, "relative": r}`, `{"level": n}`, `{"index": n|null}` (le `null` est envoyé explicitement), et le `SubSel` tel quel. Exécution dans `Dispatchers.IO`.

- [ ] **Step 1: tests qui échouent** (`MockWebServer` de `mockwebserver3`, une instance par test) :
  - `sync_without_since_omits_param` : `sync(null)` → requête `GET /api/sync` dont `url.queryParameter("since") == null`. Résultat `Ok` avec 5 œuvres.
  - `sync_with_since` : `sync(3)` → `queryParameter("since") == "3"`.
  - `sync_gzip_body_decoded` : réponse `sync_full.json` compressée en gzip avec `Content-Encoding: gzip` → `Ok`, 5 œuvres.
  - `tracks_409_volume_absent` : `error_volume_absent.json` en 409 → `Err(Server("volume_absent", "Théâtre"))`, et `messageFr() == "Disque « Théâtre » non branché"`.
  - `play_posts_contract_body` : `play(PlayRequestDto("episode", 2, "start"))` → `POST /api/play`, corps JSON dont `target_kind == "episode"` et `start == "start"`. `{"ok": true}` → `Ok(Unit)`.
  - `audio_null_sent_explicitly` : `audio(null)` → corps exactement `{"index":null}`.
  - `seek_body` : `seek(-10.0, relative = true)` → `{"seconds":-10.0,"relative":true}`.
  - `player_idle_409` : `pause(true)` avec `error_idle.json` → `Err(Server("idle", null))`, `messageFr() == "Aucune lecture en cours"`.
  - `tv_show_returns_shown` : `{"shown": false}` → `Ok(false)`. Le corps envoyé vaut `{"screen":"work","id":1}`.
  - `no_server_no_request` : `baseUrl = { null }` → `Err(NoServer)` et `server.requestCount == 0`.
  - `unreachable_maps_to_Unreachable` (Review Focus 1) : serveur arrêté (`server.close()`) → `Err(Unreachable)`, sans exception.
  - `base_url_reread_each_call` : deux `MockWebServer`, le fournisseur passe de l'un à l'autre → la 2ᵉ requête arrive sur le 2ᵉ serveur.
  - `probe_rejects_other_api` : `{"name":"x","api":2,"revision":0}` → `probe(...) == null`. `info.json` → non nul.
- [ ] **Step 2:** `./gradlew testDebugUnitTest --tests '*OkHttpRcApiTest'` → FAIL.
- [ ] **Step 3:** implémenter `RcApi.kt` et `OkHttpRcApi.kt`.
- [ ] **Step 4:** relancer → PASS.
- [ ] **Step 5:** commit `feat(android): client HTTP du serveur`.

---

### Task 4: WebSocket des événements

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/net/EventsClient.kt`
- Test: `android/app/src/test/java/fr/remotecontrol/net/EventsClientTest.kt`

**Interfaces:**
- Consumes: `parseServerEvent`, `ServerEvent` (Task 2), `rcOkHttpClient()` (Task 3).
- Produces :
  - `class EventsClient(client: OkHttpClient, baseUrl: () -> HttpUrl?, reconnectDelayMs: Long = 2_000)`
  - `fun events(): Flow<ServerEvent>` : flux froid (`callbackFlow`). À la collecte, ouvre `baseUrl().resolve("api/events")`. Chaque message texte est passé à `parseServerEvent`, et les `null` sont ignorés. Sur `onFailure` ou `onClosed`, ou quand `baseUrl()` vaut `null`, attend `reconnectDelayMs` puis réessaie, en relisant `baseUrl()`. L'annulation de la collecte ferme la socket (`close(1000, null)`).

- [ ] **Step 1: tests qui échouent** (`MockWebServer` avec `MockResponse.Builder().webSocketUpgrade(listener)`, `runTest` avec un délai réel court, `reconnectDelayMs = 50`) :
  - `receives_hello_then_tv` : le listener serveur envoie `ws_hello.json` puis `ws_tv.json` → `events().take(2).toList()` donne `[Hello, Tv]`.
  - `ignores_unknown_messages` : `{"type":"bogus"}` puis `ws_catalog.json` → le premier événement reçu est `Catalog(6)`.
  - `reconnects_after_close` : première connexion fermée par le serveur après `hello`, seconde connexion qui envoie `ws_catalog.json` → les événements `[Hello, Catalog(6)]` arrivent, et `server.requestCount == 2`.
  - `waits_when_no_server` : `baseUrl` renvoie `null` deux fois puis l'URL → un `Hello` finit par arriver.
  - `cancel_closes_socket` : après `take(1)`, le listener serveur reçoit `onClosing` ou `onFailure` dans la seconde.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implémenter `EventsClient.kt`.
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** commit `feat(android): événements WebSocket avec reconnexion`.

---

### Task 5: Base Room et recherche plein texte

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/data/db/{Entities.kt,Converters.kt,ReadModels.kt,CatalogDao.kt,SyncDao.kt,MetaDao.kt,AppDatabase.kt}`, `android/app/src/main/java/fr/remotecontrol/data/{Search.kt,KeyValueStore.kt}`, `android/app/src/test/java/fr/remotecontrol/testing/TestDb.kt`
- Test: `android/app/src/test/java/fr/remotecontrol/data/SearchTest.kt`, `android/app/src/test/java/fr/remotecontrol/data/db/CatalogDaoTest.kt`

**Interfaces:**
- Produces (`Entities.kt`, noms de tables entre parenthèses) :
  - `WorkEntity("work")(@PrimaryKey id: Long, type: String, titleFr: String, titleVo: String?, titleAlt: String?, year: Int?, durationMin: Int?, episodeCount: String?, directors: List<String>, genres: List<String>, cast: List<String>, synopsis: String?, tags: List<String>, episodic: Boolean, poster: String?, sortKey: String, playable: Boolean = false)`, avec des index sur `type`, `sortKey`, `playable`.
  - `SeasonEntity("season")(id, workId, number: Int, label: String)` (index `workId`). `EpisodeEntity("episode")(id, seasonId, number: Int, title: String?)` (index `seasonId`).
  - `MediaFileEntity("media_file")(id, workId: Long?, episodeId: Long?, quality: String, size: String?, relpath: String, volumeUuid: String?, present: Boolean)` (index `workId`, `episodeId`).
  - `VolumeEntity("volume")(@PrimaryKey uuid: String, label: String?, present: Boolean)`.
  - `CollectionEntity("collection")(id, name, description: String?, link: String?)`. `WorkCollectionEntity("work_collection", primaryKeys = [workId, collectionId])(workId, collectionId, rank: String?)` (index `collectionId`).
  - `ProgressEntity("progress", primaryKeys = [targetKind, targetId])(targetKind: String, targetId: Long, mediaFileId: Long?, position: Double, duration: Double?, watched: Boolean, updatedAt: Double)`.
  - `MetaEntity("meta")(@PrimaryKey key: String, value: String)`.
  - `@Fts4 WorkFtsEntity("work_fts")(@PrimaryKey @ColumnInfo(name = "rowid") rowid: Long, text: String)` : table FTS autonome (sans `contentEntity`), tokeniseur par défaut, car le texte est déjà replié.
  - Pas de clé étrangère : l'ordre d'arrivée des lignes n'est pas garanti, et les cascades sont faites par `SyncDao`.
- Produces (`Converters.kt`) : `List<String>` ↔ JSON via `RcJson`.
- Produces (`AppDatabase.kt`) : `@Database(version = 1, exportSchema = true) abstract class AppDatabase : RoomDatabase` avec `catalogDao()`, `syncDao()`, `metaDao()`. `fun buildDatabase(context: Context): AppDatabase` (fichier `rc-cache.db`, `fallbackToDestructiveMigration(dropAllTables = true)` : ce n'est qu'un cache, et une base vidée perd sa révision, ce qui force une synchronisation complète).
- Produces (`ReadModels.kt`) :
  - `WorkSummary(id: Long, type: String, titleFr: String, year: Int?, poster: String?, playable: Boolean, episodic: Boolean)`
  - `CollectionSummary(id: Long, name: String, count: Int, playableCount: Int)` : `count` = toutes les œuvres de la collection (quel que soit le filtre), `playableCount` = celles qui sont lisibles (`work.playable`)
  - `ResumeRow(targetKind: String, targetId: Long, workId: Long, seasonId: Long?, titleFr: String, poster: String?, seasonNumber: Int?, episodeNumber: Int?, position: Double, duration: Double?, updatedAt: Double)`
  - `MediaFileRow(id: Long, workId: Long?, episodeId: Long?, quality: String, size: String?, present: Boolean, volumeLabel: String?)` (jointure `media_file.volumeUuid = volume.uuid`)
  - `EpisodeRow(id: Long, number: Int, title: String?, position: Double?, duration: Double?, watched: Boolean?, updatedAt: Double?, playable: Boolean)` (progression en `LEFT JOIN` sur `progress(targetKind = 'episode')`, `playable` = un fichier présent).
- Produces (`CatalogDao.kt`, lecture seule, flux Room) :
  - `fun worksByType(type: String, playableOnly: Boolean): Flow<List<WorkSummary>>` (tri `sortKey, id`)
  - `fun worksInCollection(collectionId: Long, playableOnly: Boolean): Flow<List<WorkSummary>>` (tri `rank IS NULL, CAST(rank AS INTEGER), sortKey`)
  - `fun recentWorks(playableOnly: Boolean, limit: Int): Flow<List<WorkSummary>>` (tri `id DESC`)
  - `fun collections(playableOnly: Boolean): Flow<List<CollectionSummary>>` (seulement les collections non vides sous le filtre : `playableCount > 0` si `playableOnly`, sinon `count > 0` ; tri par nom)
  - `fun resumeRows(limit: Int): Flow<List<ResumeRow>>` (`watched = 0 AND position > 0`, œuvres et épisodes réunis par jointures **internes** : une progression dont l'œuvre ou l'épisode a été supprimé (le serveur ne supprime jamais les progressions) n'apparaît pas ; tri `updatedAt DESC`)
  - `fun search(match: String, playableOnly: Boolean, limit: Int = 200): Flow<List<WorkSummary>>` (`JOIN work_fts ON work_fts.rowid = work.id WHERE work_fts MATCH :match`, tri `sortKey, id`)
  - `fun work(id: Long): Flow<WorkEntity?>`, `fun collectionsOfWork(workId: Long): Flow<List<CollectionEntity>>`, `fun seasonsOfWork(workId: Long): Flow<List<SeasonEntity>>`, `fun season(id: Long): Flow<SeasonEntity?>`, `fun mediaFilesOfWork(workId: Long): Flow<List<MediaFileRow>>`, `fun mediaFilesOfEpisode(episodeId: Long): Flow<List<MediaFileRow>>`, `fun episodesOfSeason(seasonId: Long): Flow<List<EpisodeRow>>` (tri `number`), `fun progress(kind: String, id: Long): Flow<ProgressEntity?>`
  - `suspend fun workCount(): Int`, `fun observeWorkCount(): Flow<Int>`, `suspend fun hasWork(id: Long): Boolean`, `suspend fun hasSeason(id: Long): Boolean`
- Produces (`MetaDao.kt`) : `suspend fun get(key: String): String?`, `@Upsert suspend fun put(entity: MetaEntity)`, `suspend fun delete(key: String)`, `fun observe(key: String): Flow<String?>`.
- Produces (`KeyValueStore.kt`) : `interface KeyValueStore { suspend fun get(key: String): String?; suspend fun put(key: String, value: String?) }` (`null` supprime), `class MetaKeyValueStore(dao: MetaDao) : KeyValueStore`, et les clés `const val META_REVISION = "sync.revision"`, `META_SERVER_HOST = "server.host"`, `META_SERVER_PORT = "server.port"`, `META_SERVER_MANUAL = "server.manual"`.
- Produces (`Search.kt`) :
  - `fun fold(s: String): String` : NFD, suppression des diacritiques (`\p{Mn}`), `œ` → `oe`, `æ` → `ae`, minuscules (`Locale.ROOT`).
  - `fun ftsText(w: WorkDto): String` : `fold` de `titleFr`, `titleVo`, `titleAlt`, `directors` et `cast`, joints par des espaces.
  - `fun buildFtsQuery(input: String): String?` : `fold`, découpage sur tout caractère qui n'est ni lettre ni chiffre, jetons vides retirés, chaque jeton suivi de `*`, joints par une espace (ET implicite de FTS4). `null` si aucun jeton. Les mots-clés FTS saisis (`OR`, `NOT`, `NEAR`) deviennent des jetons en minuscules, donc inoffensifs.
  - `fun WorkDto.toEntity(): WorkEntity` (avec `sortKey = fold(titleFr)` et `playable = false`, recalculé par la synchronisation).
- Produces (test, `TestDb.kt`) : `fun testDb(): AppDatabase` (`Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AppDatabase::class.java).allowMainThreadQueries().build()`), et `suspend fun AppDatabase.seed(works: List<WorkEntity>, files: List<MediaFileEntity> = emptyList(), …)` pour insérer directement via `syncDao()`.
- `SyncDao` est déclaré ici avec ses `@Upsert` par entité (`upsertWorks`, `upsertFts`, `upsertSeasons`, `upsertEpisodes`, `upsertMediaFiles`, `upsertVolumes`, `upsertCollections`, `upsertWorkCollections`, `upsertProgress`). Les suppressions et le recalcul arrivent en Task 6.

- [ ] **Step 1: tests qui échouent** :
  - `SearchTest` (JUnit simple) : `fold("Dix Petits Nègres") == "dix petits negres"`, `fold("Œdipe") == "oedipe"`, `buildFtsQuery("l'etr") == "l* etr*"`, `buildFtsQuery("  ") == null`, `buildFtsQuery("\"*-OR") == "or*"`, `buildFtsQuery("Patellière") == "patelliere*"`.
  - `CatalogDaoTest` (Robolectric, `testDb()`, œuvres insérées à partir de `sync_full.json` via `toEntity()` et `ftsText`, avec `playable` fixé à la main : 1, 3, 5 lisibles) :
    - `search_accent_insensitive` (Review Focus 3) : `search(buildFtsQuery("etranger")!!, false)` → `[2]`. `"prénom"` → `[3, 4]`. `"patelliere"` → `[3]`. `"noee abi"` → `[1]`. `"negres"` sur une œuvre ajoutée « Agatha Christie : Dix Petits Nègres » → trouvée.
    - `search_special_chars_never_throw` : pour chaque saisie de `listOf("\"", "*", "-", "OR", "a\"b", "((", "NEAR/3")`, `buildFtsQuery(it)?.let { q -> dao.search(q, false).first() }` ne lève pas d'exception.
    - `playable_filter` : `worksByType("film", true)` → ids `[1, 3]` ; `worksByType("film", false)` → `[2, 1, 3]` (tri `sortKey` : « alien… », « ava », « le prenom »).
    - `sort_ignores_accents` : les œuvres « Élise » et « Eva » sont triées « Élise » avant « Eva ».
    - `recent_by_id_desc` : `recentWorks(false, 2)` → `[5, 4]`.
    - `resume_rows_work_and_episode` : avec la progression de la fixture, `resumeRows(10)` renvoie l'épisode 2 (`seasonNumber == 1`, `episodeNumber == 2`, `workId == 5`, `seasonId == 1`) en premier, puis l'œuvre 1.
    - `resume_rows_skip_orphan_progress` : une `ProgressEntity("work", 999, …)` sans œuvre 999 n'apparaît pas dans `resumeRows(10)`.
    - `observe_work_count` : `observeWorkCount().first() == 5`.
    - `collections_hide_empty_under_filter` : `collections(true)` → seulement la collection 1 (l'œuvre 2 n'est pas lisible). `collections(false)` → les deux.
    - `media_files_with_volume_label` : `mediaFilesOfWork(4)` → `volumeLabel == "Théâtre"`, `present == false`.
- [ ] **Step 2:** `./gradlew testDebugUnitTest --tests '*SearchTest' --tests '*CatalogDaoTest'` → FAIL.
- [ ] **Step 3:** implémenter les entités, DAO, `Search.kt` et `KeyValueStore.kt`.
- [ ] **Step 4:** relancer → PASS. Vérifier que `app/schemas/fr.remotecontrol.data.db.AppDatabase/1.json` a été généré.
- [ ] **Step 5:** commit `feat(android): cache Room et recherche plein texte`.

---

### Task 6: Synchronisation incrémentale

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/data/sync/{SyncWriter.kt,SyncRepository.kt,SyncCoordinator.kt}`, `android/app/src/test/java/fr/remotecontrol/testing/FakeRcApi.kt`
- Modify: `android/app/src/main/java/fr/remotecontrol/data/db/SyncDao.kt`
- Test: `android/app/src/test/java/fr/remotecontrol/data/sync/SyncRepositoryTest.kt`, `android/app/src/test/java/fr/remotecontrol/data/sync/SyncCoordinatorTest.kt`

**Interfaces:**
- Consumes: `RcApi`, `ApiResult`, `SyncDto` (Tasks 2-3), `AppDatabase`, `SyncDao`, `MetaDao`, `toEntity`, `ftsText`, `META_REVISION` (Task 5), `ServerEvent` (Task 2).
- Produces (`SyncDao.kt`, ajouts) :
  - `suspend fun deleteWorksCascade(ids: List<Long>)` (`@Transaction`) : fichiers des œuvres et de leurs épisodes, épisodes, saisons, `work_collection`, `work_fts`, puis `work`.
  - `suspend fun deleteSeasonsCascade(ids: List<Long>)`, `suspend fun deleteEpisodesCascade(ids: List<Long>)`, `suspend fun deleteMediaFiles(ids: List<Long>)`, `suspend fun deleteWorkCollectionsOf(workIds: List<Long>)`
  - `suspend fun deleteCollections(ids: List<Long>)` (`@Transaction`) : les lignes `work_collection` de ces collections, puis `collection` (arbitrage 3).
  - `suspend fun clearCatalog()` : vide toutes les tables, sauf `meta`.
  - `suspend fun recomputePlayable()` : `UPDATE work SET playable = EXISTS(SELECT 1 FROM media_file m WHERE m.present = 1 AND (m.workId = work.id OR m.episodeId IN (SELECT e.id FROM episode e JOIN season s ON e.seasonId = s.id WHERE s.workId = work.id)))`
  - Toute liste d'identifiants passée à `IN (:ids)` est découpée par paquets de 500 (limite de 999 variables du SQLite d'Android 10).
- Produces (`SyncWriter.kt`) : `class SyncWriter(db: AppDatabase, afterUpserts: suspend () -> Unit = {}) { suspend fun apply(dto: SyncDto) }` (`afterUpserts` est un crochet de test appelé après l'étape 3). Tout se passe dans **une seule** `db.withTransaction`, dans cet ordre :
  1. si `dto.full` : `clearCatalog()` ;
  2. suppressions de `deleted` (œuvres, saisons, épisodes, fichiers et collections, en cascade ; les enfants listés par le serveur et ceux supprimés en cascade se recouvrent sans conflit). Volumes et progressions ne sont jamais supprimés ;
  3. upserts (œuvres et leurs lignes `work_fts`, saisons, épisodes, fichiers, volumes, collections, progression) ;
  4. `work_collections` : pour **chaque œuvre présente dans `dto.works`**, `deleteWorkCollectionsOf` puis insertion des liens de la réponse qui la concernent (le contrat n'a ni `rev` ni suppression pour cette table) ;
  5. `recomputePlayable()` ;
  6. en dernier, `meta[META_REVISION] = dto.revision`.
- Produces (`SyncRepository.kt`) :
  - `sealed interface SyncOutcome { data class Done(val revision: Long, val full: Boolean) : SyncOutcome; data class Failed(val error: ApiError) : SyncOutcome }`
  - `class SyncRepository(db: AppDatabase, api: RcApi, writer: SyncWriter = SyncWriter(db))` : `suspend fun sync(forceFull: Boolean = false): SyncOutcome` (`since = null` si `forceFull` ou s'il n'y a pas de révision locale, sinon la révision locale ; un `Mutex` empêche deux synchronisations simultanées), `suspend fun localRevision(): Long?`, `val syncing: StateFlow<Boolean>`.
- Produces (`SyncCoordinator.kt`) : `class SyncCoordinator(sync: SyncRepository, events: Flow<ServerEvent>)` avec `suspend fun run()`, qui collecte jusqu'à annulation. Pour chaque `Hello` ou `Catalog` de révision `r`, avec `local = localRevision()` : `local == null || r < local` → `sync(forceFull = true)` ; `r > local` → `sync()` ; `r == local` → rien.
- Produces (test, `FakeRcApi.kt`) : `class FakeRcApi : RcApi`, avec une réponse programmable par méthode (`var syncResponses: ArrayDeque<ApiResult<SyncDto>>`, `var tracksResult`, `var playResult`, etc.) et l'enregistrement des appels (`val calls: MutableList<String>` au format `"sync(null)"`, `"tvShow(work,1)"`, `"play(PlayRequestDto(…))"`, `"seek(-10.0,true)"`…). Réutilisé par les Tasks 7, 10, 11, 12 et 13.

- [ ] **Step 1: tests qui échouent** (`SyncRepositoryTest`, Robolectric, `testDb()`, `FakeRcApi` alimenté par les fixtures) :
  - `full_sync_populates_and_computes_playable` : `sync()` → `Done(3, true)`. `workCount() == 5`. Œuvres lisibles : `{1, 3, 5}` (4 a son disque absent, 2 n'a aucun fichier). `localRevision() == 3`. Le premier appel est `"sync(null)"`.
  - `incremental_applies_upserts_and_deletes` : full puis `sync_incremental` → l'appel est `"sync(3)"`. L'œuvre 3 a disparu, ainsi que son fichier 3. L'épisode 3 et le fichier 7 ont disparu. `work(1).titleAlt == "Ava la sauvage"`. L'œuvre 4 est devenue lisible. `localRevision() == 5`. `search("sauvage")` trouve l'œuvre 1.
  - `work_collections_replaced_per_work` : après l'incrémental, `collectionsOfWork(1)` est vide (l'œuvre 1 était dans la réponse sans lien) et `collectionsOfWork(2)` contient toujours « Alien [1979-] » (absente de la réponse, donc intacte).
  - `deleted_work_cascades` : un incrémental qui supprime l'œuvre 5 efface aussi ses 2 saisons, ses 3 épisodes, leurs fichiers et sa ligne FTS.
  - `deleted_collection_removed` : full, puis `sync_incremental` modifié dans le test (`deleted = DeletedDto(collection = listOf(2))`) → `collections(false)` ne contient plus « Alien [1979-] », et `collectionsOfWork(2)` est vide.
  - `deletes_more_than_999_ids` : 1 500 œuvres insérées, puis un incrémental qui en supprime 1 200 → il en reste 300, sans exception.
  - `full_sync_removes_stale_rows` (Review Focus 2) : une œuvre 99 présente localement mais absente d'un nouveau full → elle a disparu après `sync(forceFull = true)`.
  - `failed_write_leaves_cache_untouched` (Review Focus 2) : après un full, `SyncWriter(db, afterUpserts = { error("panne") })` appliqué à `sync_incremental` lève l'exception, puis `workCount() == 5`, `work(1).titleAlt == null` et `localRevision() == 3`.
  - `network_failure_reports_failed` : `Err(Unreachable)` → `Failed(Unreachable)`, et la base est intacte.
  - `SyncCoordinatorTest` (événements fournis par un `MutableSharedFlow`) : `hello_newer_triggers_incremental` (local 3, `Hello(5)` → `"sync(3)"`), `same_revision_no_sync`, `lower_revision_forces_full` (local 5, `Catalog(2)` → `"sync(null)"`, Review Focus 2), `empty_cache_forces_full`.
- [ ] **Step 2:** `./gradlew testDebugUnitTest --tests '*Sync*Test'` → FAIL.
- [ ] **Step 3:** implémenter les ajouts de `SyncDao`, `SyncWriter`, `SyncRepository`, `SyncCoordinator`.
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** commit `feat(android): synchronisation incrémentale du catalogue`.

---

### Task 7: Découverte du serveur et réglages

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/discovery/{ServerAddress.kt,NsdServerDiscovery.kt,ServerLocator.kt}`, `android/app/src/main/java/fr/remotecontrol/ui/settings/{SettingsViewModel.kt,SettingsScreen.kt}`, `android/app/src/test/java/fr/remotecontrol/testing/{FakeKeyValueStore.kt,MainDispatcherRule.kt}`
- Test: `android/app/src/test/java/fr/remotecontrol/discovery/{ServerAddressTest.kt,ServerLocatorTest.kt}`, `android/app/src/test/java/fr/remotecontrol/ui/settings/SettingsViewModelTest.kt`

**Interfaces:**
- Consumes: `ServerProbe` (Task 3), `InfoDto` (Task 2), `KeyValueStore` et les clés `META_SERVER_*` (Task 5), `SyncRepository`, `SyncOutcome` (Task 6), `CatalogDao.workCount` (Task 5).
- Produces (`ServerAddress.kt`) :
  - `data class ServerAddress(val host: String, val port: Int = 8765) { val baseUrl: HttpUrl; val display: String }` : `baseUrl` est construit avec `HttpUrl.Builder().scheme("http").host(host).port(port)`, qui met les crochets IPv6. `display` vaut `"192.168.1.20:8765"`.
  - `fun parseManualAddress(input: String): ServerAddress?` : accepte `hôte`, `hôte:port`, `[ipv6]:port`, une IPv6 nue, et `http://hôte:port/…`. Port par défaut 8765, port valide de 1 à 65535. `null` pour une saisie vide, un hôte contenant une espace, ou un port invalide.
- Produces (`NsdServerDiscovery.kt`) :
  - `fun interface ServerDiscovery { fun discover(): Flow<ServerAddress> }`
  - `class NsdServerDiscovery(context: Context) : ServerDiscovery` : `NsdManager.discoverServices("_rc._tcp", PROTOCOL_DNS_SD, …)`, puis une résolution à la fois (file d'attente : `resolveService` refuse deux résolutions simultanées). Adresse : `hostAddresses` (API 34+) ou `host` (avant 34), en préférant une `Inet4Address` (les IPv6 de lien local portent un identifiant de zone que `HttpUrl` n'accepte pas). `stopServiceDiscovery` à l'annulation. Sans test JVM : vérifié en Task 14.
- Produces (`ServerLocator.kt`) :
  - `sealed interface ServerState { data object Searching; data class Connected(val address: ServerAddress, val manual: Boolean); data object NotFound }`
  - `class ServerLocator(store: KeyValueStore, probe: ServerProbe, discovery: ServerDiscovery, discoveryTimeoutMs: Long = 5_000)` :
    - `val state: StateFlow<ServerState>`, `fun currentBaseUrl(): HttpUrl?` (non nul seulement en `Connected`).
    - `suspend fun locate()` : passe à `Searching`, puis : (1) l'adresse mémorisée, si `probe` répond → `Connected(manual = valeur mémorisée)` ; (2) sinon la première adresse de `discover()` pour laquelle `probe` répond, dans la limite de `discoveryTimeoutMs`, est mémorisée avec `manual = false` → `Connected` ; (3) sinon `NotFound`. Une adresse manuelle qui ne répond plus n'est pas effacée.
    - `suspend fun setManual(address: ServerAddress): Boolean` : sonde l'adresse. Si elle répond, la mémorise avec `manual = true`, passe à `Connected` et renvoie vrai. Sinon, renvoie faux et ne change rien.
- Produces (`ui/settings`) :
  - `data class SettingsUi(val serverLabel: String, val manualInput: String = "", val revision: Long? = null, val workCount: Int = 0, val busy: Boolean = false, val message: String? = null)`. `serverLabel` vaut `Texts.serverDetected(display)`, `Texts.serverManual(display)`, `Texts.searching` ou `Texts.noServer`.
  - `class SettingsViewModel(locator: ServerLocator, sync: SyncRepository, dao: CatalogDao) : ViewModel` : `state: StateFlow<SettingsUi>`, `fun onManualInput(text: String)`, `fun saveManual()` (adresse invalide → `Texts.invalidAddress` ; `setManual` faux → `Texts.addressUnreachable` ; vrai → `sync(forceFull = true)`, puis `Texts.synced(n)`), `fun rediscover()` (`locator.locate()`), `fun resync()` (`sync(forceFull = true)` → `Texts.synced(n)` ou `error.messageFr()`).
  - `@Composable fun SettingsScreen(state: SettingsUi, onManualInput: (String) -> Unit, onSaveManual: () -> Unit, onRediscover: () -> Unit, onResync: () -> Unit, onBack: () -> Unit)` : libellés `Texts.settingsServer`, `Texts.manualAddress`, `Texts.save`, `Texts.rediscover`, `Texts.resync`, `Texts.catalogInfo(revision, workCount)`.

- [ ] **Step 1: tests qui échouent** :
  - `ServerAddressTest` : `parseManualAddress("192.168.1.20") == ServerAddress("192.168.1.20", 8765)`, `"192.168.1.20:9000"` → port 9000, `"http://mini-pc.local:8765/"` → hôte `mini-pc.local`, `"[fe80::1]:9000"` → hôte `fe80::1` et port 9000, `"fe80::1"` → port 8765, `""`, `"a b"` et `"x:70000"` → `null`. `ServerAddress("fe80::1").baseUrl.toString() == "http://[fe80::1]:8765/"`.
  - `ServerLocatorTest` (`FakeKeyValueStore` en mémoire, `ServerProbe` et `ServerDiscovery` factices, `runTest`) :
    - `stored_address_used_when_alive` : la découverte n'est pas collectée.
    - `stored_address_fails_then_nsd_finds_new_one` (Review Focus 1) : l'ancienne IP ne répond pas, NSD émet la nouvelle → `Connected(nouvelle, manual = false)`, et la nouvelle adresse est mémorisée.
    - `nsd_skips_non_responding_candidates` : NSD émet A (muette) puis B → `Connected(B)`.
    - `not_found_after_timeout` : NSD n'émet rien → `NotFound` après `advanceTimeBy(5_001)`, et `currentBaseUrl() == null`.
    - `manual_rejected_when_unreachable` : `setManual` faux, l'état est inchangé.
    - `manual_kept_flag` : après `setManual(ok)`, un `locate()` réussi garde `manual = true`.
  - `SettingsViewModelTest` (Robolectric, `MainDispatcherRule`) : `server_label_detected` (`Connected(ServerAddress("192.168.1.20"), manual = false)` → `serverLabel == "192.168.1.20:8765 (détecté)"`), `invalid_address_message`, `unreachable_manual_message`, `manual_ok_triggers_full_sync` (`FakeRcApi.calls` contient `"sync(null)"`, et le message vaut `"Catalogue synchronisé (5 œuvres)"`), `resync_failure_message` (`Err(Unreachable)` → `"Serveur injoignable"`).
  - Ajouter dans `testing/` le `MainDispatcherRule` (JUnit `TestWatcher` qui appelle `Dispatchers.setMain(UnconfinedTestDispatcher())` puis `resetMain()`) et `FakeKeyValueStore : KeyValueStore` (une `MutableMap`). Tous deux sont réutilisés par les Tasks 8 à 13.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implémenter les trois fichiers de `discovery/`, puis `SettingsViewModel` et `SettingsScreen`.
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** commit `feat(android): découverte du serveur et réglages`.

---

### Task 8: Assemblage, navigation, images et accueil

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/AppContainer.kt`, `android/app/src/main/java/fr/remotecontrol/data/CatalogFilter.kt`, `android/app/src/main/java/fr/remotecontrol/ui/common/{Routes.kt,NavEvent.kt,Images.kt}`, `android/app/src/main/java/fr/remotecontrol/ui/home/{HomeViewModel.kt,HomeScreen.kt}`
- Modify: `android/app/src/main/java/fr/remotecontrol/{RcApp.kt,MainActivity.kt}`
- Test: `android/app/src/test/java/fr/remotecontrol/ui/common/ImagesTest.kt`, `android/app/src/test/java/fr/remotecontrol/ui/home/HomeViewModelTest.kt`

**Interfaces:**
- Consumes: tout ce qui précède.
- Produces (`AppContainer.kt`) : `class AppContainer(context: Context)` avec `val appScope: CoroutineScope` (`SupervisorJob() + Dispatchers.Default`), `val okHttp: OkHttpClient = rcOkHttpClient()`, `val db: AppDatabase`, `val catalogDao: CatalogDao`, `val store: KeyValueStore`, `val locator: ServerLocator`, `val api: OkHttpRcApi` (fournisseur `{ locator.currentBaseUrl() }`, et le locator reçoit `api` comme `ServerProbe` : les deux sont `by lazy`), `val eventsClient: EventsClient`, `val events: SharedFlow<ServerEvent>` (`eventsClient.events().shareIn(appScope, SharingStarted.WhileSubscribed(5_000))`), `val sync: SyncRepository`, `val coordinator: SyncCoordinator`, `val filter: CatalogFilter`. (Task 10 ajoute `playLauncher`, Task 12 ajoute `remote`.) `RcApp` crée le conteneur et implémente `SingletonImageLoader.Factory`.
- Produces (`CatalogFilter.kt`) : `class CatalogFilter { val playableOnly: MutableStateFlow<Boolean> = MutableStateFlow(true) }` : partagé par l'accueil, le parcours et la recherche, non persistant.
- Produces (`Images.kt`) :
  - `enum class AssetSize(val wire: String) { Thumb("thumb"), Tv("tv"), Orig("orig") }`
  - `fun assetUrl(base: HttpUrl?, hash: String, size: AssetSize): String` → `"<base>api/assets/<hash>/<size>.jpg"`, avec `http://offline.invalid/` quand `base == null`.
  - `fun assetCacheKey(hash: String, size: AssetSize): String` → `"asset/<hash>/<size>"` : clé de cache disque **et** mémoire, indépendante de l'hôte.
  - `val LocalServerBaseUrl = staticCompositionLocalOf<HttpUrl?> { null }` : fourni à la racine par `MainActivity` (`CompositionLocalProvider`), à partir de `locator.state` (`(state as? Connected)?.address?.baseUrl`).
  - `@Composable fun Poster(hash: String?, size: AssetSize, modifier: Modifier)` : lit `LocalServerBaseUrl.current`, puis `AsyncImage` sur `assetUrl(base, hash, size)` avec `diskCacheKey` et `memoryCacheKey = assetCacheKey(...)`, et un cadre gris uni quand `hash == null`.
  - `fun buildImageLoader(context: Context, okHttp: OkHttpClient): ImageLoader` : `OkHttpNetworkFetcherFactory(callFactory = { okHttp })`, disque `cacheDir/images`, 300 Mo. La stratégie de cache par défaut de Coil 3 sert le disque sans revalidation, ce qui convient aux URL immuables.
- Produces (`Routes.kt`, `@Serializable`) : `data object HomeRoute`, `data class BrowseRoute(val type: String? = null, val collectionId: Long? = null)`, `data object SearchRoute`, `data class WorkRoute(val id: Long)`, `data class SeasonRoute(val id: Long)`, `data object RemoteRoute`, `data object ScanRoute`, `data object SettingsRoute`.
- Produces (`NavEvent.kt`) : `sealed interface NavEvent { data object ToRemote; data class ToWork(val id: Long); data class ToSeason(val id: Long) }`.
- Produces (`ui/home`) :
  - `data class ResumeItem(val workId: Long, val seasonId: Long?, val title: String, val poster: String?, val progress: Float, val label: String)`, et `fun toResumeItem(row: ResumeRow): ResumeItem` : titre `Texts.episodeTitle(titleFr, seasonNumber, episodeNumber)` pour un épisode, ou le titre seul pour une œuvre. `progress = position / duration` (0 si la durée est inconnue). `label = Texts.resumeAt(position)`.
  - `data class HomeUi(val resume: List<ResumeItem> = emptyList(), val recent: List<WorkSummary> = emptyList(), val collections: List<CollectionSummary> = emptyList(), val playableOnly: Boolean = true, val catalogEmpty: Boolean = false, val server: ServerState = ServerState.Searching)`
  - `class HomeViewModel(dao: CatalogDao, filter: CatalogFilter, locator: ServerLocator) : ViewModel` : `state: StateFlow<HomeUi>` (`resumeRows(10)`, `recentWorks(playableOnly, 20)`, `collections(playableOnly)`, `catalogEmpty` depuis `observeWorkCount() == 0`, `server` depuis `locator.state`), `fun setPlayableOnly(value: Boolean)`.
  - `@Composable fun HomeScreen(state: HomeUi, onPlayableOnly: (Boolean) -> Unit, onOpenWork: (Long) -> Unit, onOpenSeason: (Long) -> Unit, onOpenType: (String) -> Unit, onOpenCollection: (Long) -> Unit, onSearch: () -> Unit, onScan: () -> Unit, onRemote: () -> Unit, onSettings: () -> Unit)` : sections `Texts.resume`, `Texts.sectionRecent`, `Texts.sectionBrowse` (une entrée par `WORK_TYPES`, libellé `Texts.typeLabel`) et `Texts.sectionCollections` ; actions `Texts.search`, `Texts.scan`, `Texts.remote`, `Texts.settings`. Bascule `Texts.playableOnly`/`Texts.wholeCatalogue`. Bandeau `Texts.catalogEmpty` si le catalogue est vide, et `Texts.noServer` avec un lien vers les réglages si l'état est `NotFound`. Libellés texte : pas de bibliothèque d'icônes.
- `MainActivity` : `NavHost` sur `HomeRoute`, avec `SettingsRoute` branché sur `SettingsScreen` (Task 7). Les routes non encore écrites (Tasks 9 à 13) affichent un `Text` provisoire. Dans `lifecycleScope`, `repeatOnLifecycle(STARTED)` lance `locator.locate()` puis `coordinator.run()`. **La synchronisation initiale passe uniquement par `SyncCoordinator`** (sur le `Hello` du WebSocket) : `MainActivity` n'appelle jamais `sync.sync()` lui-même. Les ViewModels sont créés avec `viewModel { … }` à partir du conteneur (`(application as RcApp).container`).

- [ ] **Step 1: tests qui échouent** :
  - `ImagesTest` (Review Focus 1) : `assetCacheKey("abc", AssetSize.Thumb) == "asset/abc/thumb"`. `assetUrl("http://192.168.1.20:8765/".toHttpUrl(), "abc", AssetSize.Thumb) == "http://192.168.1.20:8765/api/assets/abc/thumb.jpg"`. Les URL de deux hôtes différents diffèrent, mais leur `assetCacheKey` est identique. `assetUrl(null, …)` commence par `http://offline.invalid/`.
  - `HomeViewModelTest` (Robolectric, base peuplée par `SyncWriter.apply(sync_full)`) : `resume_lists_episode_then_work` (titres `"Back to Life — S01E02"` puis `"Ava"`, `label == "Reprendre à 00:05"` pour l'épisode), `recent_respects_filter` (lisibles : `[5, 3, 1]` ; puis `setPlayableOnly(false)` → `[5, 4, 3, 2, 1]`), `catalog_empty_flag` (base vide → `catalogEmpty == true` ; puis `SyncWriter.apply(sync_full)` → `catalogEmpty == false` sans recréer le ViewModel).
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implémenter `AppContainer`, `CatalogFilter`, `Images`, `Routes`, `NavEvent`, l'accueil et le `NavHost`.
- [ ] **Step 4:** `./gradlew testDebugUnitTest assembleDebug` → PASS.
- [ ] **Step 5:** commit `feat(android): assemblage, navigation et accueil`.

---

### Task 9: Parcours par type ou collection, et recherche

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/ui/browse/{BrowseViewModel.kt,BrowseScreen.kt}`, `android/app/src/main/java/fr/remotecontrol/ui/search/{SearchViewModel.kt,SearchScreen.kt}`, `android/app/src/main/java/fr/remotecontrol/ui/common/WorkList.kt`
- Modify: `android/app/src/main/java/fr/remotecontrol/MainActivity.kt` (routes `BrowseRoute`, `SearchRoute`)
- Test: `android/app/src/test/java/fr/remotecontrol/ui/browse/BrowseViewModelTest.kt`, `android/app/src/test/java/fr/remotecontrol/ui/search/SearchViewModelTest.kt`

**Interfaces:**
- Consumes: `CatalogDao.worksByType`, `worksInCollection`, `search`, `buildFtsQuery`, `CatalogFilter`, `Texts.typeLabel`, `Poster` (Task 8), `MainDispatcherRule` (Task 7).
- Produces :
  - `data class BrowseUi(val title: String, val works: List<WorkSummary> = emptyList(), val playableOnly: Boolean = true)`
  - `class BrowseViewModel(route: BrowseRoute, dao: CatalogDao, filter: CatalogFilter) : ViewModel` : `state`, `fun setPlayableOnly(value: Boolean)`. Le titre vaut `Texts.typeLabel(type)`, ou le nom de la collection (lu via `collections(false)`).
  - `data class SearchUi(val query: String = "", val results: List<WorkSummary> = emptyList(), val playableOnly: Boolean = true)`
  - `class SearchViewModel(dao: CatalogDao, filter: CatalogFilter) : ViewModel` : `state`, `fun onQuery(text: String)`, `fun setPlayableOnly(value: Boolean)` (même nom que sur l'accueil et le parcours ; branché sur `onPlayableOnly`). Requête différée de 150 ms (`debounce`) ; `buildFtsQuery == null` → liste vide.
  - `@Composable fun WorkList(works: List<WorkSummary>, onOpen: (WorkSummary) -> Unit)` : `LazyColumn` avec `key = id`, affiche `Thumb`, titre, année, et la mention `Texts.notAvailable` (« Non lisible ») quand `!playable` (le détail disque absent / non numérisé est sur la fiche).
  - `BrowseScreen(state, onPlayableOnly, onOpen, onBack)` et `SearchScreen(state, onQuery, onPlayableOnly, onOpen, onBack)`. Ouvrir une œuvre mène à `WorkRoute(id)`, pour toute œuvre, épisodique ou non.

- [ ] **Step 1: tests qui échouent** (Robolectric, base `sync_full`) :
  - `BrowseViewModelTest` : `type_title_and_filter` (`BrowseRoute(type = "film")` → titre `"Films"`, ids `[1, 3]`, puis `[2, 1, 3]` sans filtre), `collection_title_and_order` (`BrowseRoute(collectionId = 2)` sans filtre → titre `"Alien [1979-]"`, ids `[2]`).
  - `SearchViewModelTest` (`advanceTimeBy(200)`) : `query_folds_accents` (`"prenom"` → `[3, 4]` sans filtre, `[3]` avec le filtre), `blank_query_empty`, `filter_toggle_from_search` (`"prenom"` → `[3]`, puis `setPlayableOnly(false)` → `[3, 4]`, et `filter.playableOnly.value == false`), `debounce_only_last_query` (`"a"`, `"av"`, `"ava"` envoyés en 50 ms → un seul résultat final `[1]`).
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implémenter.
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** commit `feat(android): parcours et recherche`.

---

### Task 10: Fiche œuvre et fiche saison

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/player/{PlayCommands.kt,PlayLauncher.kt,Versions.kt}`, `android/app/src/main/java/fr/remotecontrol/ui/work/{WorkViewModel.kt,WorkScreen.kt}`, `android/app/src/main/java/fr/remotecontrol/ui/season/{SeasonViewModel.kt,SeasonScreen.kt}`
- Modify: `android/app/src/main/java/fr/remotecontrol/AppContainer.kt` (`val playLauncher: PlayLauncher`), `android/app/src/main/java/fr/remotecontrol/MainActivity.kt` (routes `WorkRoute`, `SeasonRoute`)
- Test: `android/app/src/test/java/fr/remotecontrol/player/{VersionsTest.kt,PlayLauncherTest.kt}`, `android/app/src/test/java/fr/remotecontrol/ui/work/WorkViewModelTest.kt`, `android/app/src/test/java/fr/remotecontrol/ui/season/SeasonViewModelTest.kt`

**Interfaces:**
- Consumes: `CatalogDao` (Task 5), `RcApi`, `ApiResult`, `ApiError.messageFr` (Task 3), `FakeRcApi` (Task 6), `MainDispatcherRule` (Task 7), `NavEvent` (Task 8), `Texts` (dont `typeLabel`, `versionLabel`, `episodeCode`), `formatDuration`.
- Produces (`PlayCommands.kt`) :
  - `enum class TargetKind(val wire: String) { Work("work"), Episode("episode") }`, `data class PlayTarget(val kind: TargetKind, val id: Long)`, `enum class StartMode(val wire: String) { Start("start"), Resume("resume") }`
  - `data class TrackChoice(val mediaFileId: Long, val audio: Int?, val subtitle: SubSel)`
  - `fun playRequest(target: PlayTarget, start: StartMode, choice: TrackChoice? = null): PlayRequestDto` : sans `choice`, `media_file_id`, `audio` et `subtitle` sont nuls et le serveur applique ses choix par défaut (version mémorisée, dernières pistes).
- Produces (`PlayLauncher.kt`) — **le seul** chemin de lancement, partagé par `WorkViewModel`, `SeasonViewModel` (Task 10) et `PrePlayViewModel` (Task 11) :
  - `sealed interface PlayOutcome { data object Started : PlayOutcome; data class Failed(val message: String) : PlayOutcome }`
  - `class PlayLauncher(api: RcApi) { suspend fun launch(target: PlayTarget, start: StartMode, choice: TrackChoice? = null): PlayOutcome }` : `api.play(playRequest(target, start, choice))` ; `Ok` → `Started`, `Err(e)` → `Failed(e.messageFr())`. Le ViewModel appelant traduit `Started` en `NavEvent.ToRemote` et `Failed` en `message`.
- Produces (`Versions.kt`) :
  - `val QUALITY_ORDER = listOf("UHD", "2HD", "FHD", "HD", "SD", "BD")`
  - `sealed interface Availability { data object Present; data class VolumeAbsent(val label: String?); data object NotDigitized }`
  - `fun availabilityOf(files: List<MediaFileRow>): Availability` : un fichier présent → `Present` ; aucun fichier → `NotDigitized` ; sinon `VolumeAbsent` avec le premier `volumeLabel` non nul.
  - `fun Availability.label(): String?` : `Present` → `null`, `VolumeAbsent(l)` → `Texts.volumeAbsent(l)`, `NotDigitized` → `Texts.notDigitized`.
  - `fun sortVersions(files: List<MediaFileRow>): List<MediaFileRow>` (selon `QUALITY_ORDER`, qualités inconnues en dernier).
  - `fun preferredVersion(files: List<MediaFileRow>, progressMediaFileId: Long?, start: StartMode): Long?` : en `Resume`, la version de la progression si elle est présente ; sinon la meilleure version présente ; `null` si aucune.
  - `fun highlightEpisode(rows: List<EpisodeRow>): Long?` : règle du serveur (`highlight_episode_id`) : l'épisode qui a une progression (`updatedAt != null`) la plus récente ; sinon le premier épisode non vu ; `null` si la liste est vide.
- Produces (`ui/work`) :
  - `data class VersionUi(val mediaFileId: Long, val label: String, val present: Boolean, val absentLabel: String?)` (`label` = `Texts.versionLabel(quality, size)`, soit `"FHD · 3.8G"`).
  - `data class WorkUi(val work: WorkEntity, val typeLabel: String, val durationLabel: String?, val availabilityLabel: String?, val versions: List<VersionUi>, val seasons: List<SeasonEntity>, val collections: List<CollectionEntity>, val resumeLabel: String?, val canPlay: Boolean, val message: String? = null)`. `canPlay` = œuvre non épisodique **et** `Present`. `resumeLabel` = `Texts.resumeAt(position)` si une progression existe avec `position > 0` et `!watched`.
  - `class WorkViewModel(workId: Long, dao: CatalogDao, api: RcApi, launcher: PlayLauncher) : ViewModel` : `state: StateFlow<WorkUi?>`, `events: Flow<NavEvent>` (`Channel`), `fun playFromStart()`, `fun resume()` (`launcher.launch(PlayTarget(Work, id), mode)` sans `choice`), `fun clearMessage()`. `typeLabel = Texts.typeLabel(work.type)`. À la création (`init`) : **un seul** `api.tvShow("work", workId)`, dont l'échec est ignoré en silence (la navigation reste hors ligne).
  - `WorkScreen(state, onPlayFromStart, onResume, onPrePlay: (StartMode) -> Unit, onOpenSeason: (Long) -> Unit, onOpenCollection: (Long) -> Unit, onBack)` : affiche `Tv`, titres VF et VO (VO si différent), année, `durationLabel`, réalisateurs (`Texts.directors`), genres, casting (`Texts.cast`), synopsis, collections (`Texts.sectionCollections`), versions (`Texts.versions`), mention de disponibilité ; boutons `Texts.playFromStart` (principal), `resumeLabel` (secondaire), `Texts.tracksAndVersion` ; liste des saisons (`Texts.seasons`) pour une œuvre épisodique. `onPrePlay` est branché en Task 11 (bouton masqué d'ici là).
- Produces (`ui/season`) :
  - `data class EpisodeUi(val id: Long, val label: String, val status: String?, val highlighted: Boolean, val canResume: Boolean, val playable: Boolean)`. `label` = `Texts.episodeCode(season.number, number)`, suivi de `" — titre"` si le titre existe. `status` = `Texts.watched` si vu, `Texts.started(position)` si entamé, sinon `null`.
  - `data class SeasonUi(val season: SeasonEntity, val workTitle: String, val episodes: List<EpisodeUi>, val message: String? = null)`
  - `class SeasonViewModel(seasonId: Long, dao: CatalogDao, api: RcApi, launcher: PlayLauncher) : ViewModel` : `state: StateFlow<SeasonUi?>`, `events: Flow<NavEvent>`, `fun play(episodeId: Long, mode: StartMode)` (`launcher.launch(PlayTarget(Episode, episodeId), mode)`), `fun clearMessage()`. À la création : **un seul** `api.tvShow("season", seasonId)`.
  - `SeasonScreen(state, onPlay: (Long, StartMode) -> Unit, onPrePlay: (Long, StartMode) -> Unit, onBack)` : pour chaque épisode, `Texts.fromStart` et, si `canResume`, `Texts.resume`. L'épisode mis en évidence a un fond distinct, et la liste défile jusqu'à lui à l'ouverture.

- [ ] **Step 1: tests qui échouent** :
  - `VersionsTest` : `availabilityOf` (fichiers de l'œuvre 4 → `VolumeAbsent("Théâtre")`, liste vide → `NotDigitized`). `preferredVersion` (Ava, `Start` → 1 (FHD) ; `Resume` avec la progression sur 2 → 2 ; `Resume` avec la progression sur un fichier absent → le meilleur présent). `highlightEpisode` (progression sur l'épisode 2 → 2 ; aucune progression → 1 ; l'épisode 1 vu le plus récemment et l'épisode 2 entamé plus tôt → 1). `playRequest(PlayTarget(Episode, 2), Resume)` → `PlayRequestDto("episode", 2, "resume")`.
  - `PlayLauncherTest` (`FakeRcApi`) : `ok_is_started` (`launch(PlayTarget(Work, 1), Start)` → `Started`, et `calls` contient `play(PlayRequestDto(work,1,start,null,null,null))`), `volume_absent_is_failed` (`Err(Server("volume_absent", "DISK1"))` → `Failed("Disque « DISK1 » non branché")`), `unreachable_is_failed` (→ `Failed("Serveur injoignable")`), `choice_is_forwarded` (`TrackChoice(2, 1, SubSel.None)` → `PlayRequestDto("work", 1, "start", 2, 1, SubSel.None)`).
  - `WorkViewModelTest` (Robolectric, base `sync_full`, `FakeRcApi`, vrai `PlayLauncher(fakeApi)`) :
    - `init_sends_tv_show_once` : `calls.count { it == "tvShow(work,1)" } == 1`, même après deux collectes de `state`.
    - `tv_show_failure_is_silent` : `tvShow` → `Err(Unreachable)` → `message == null`, et l'état est quand même chargé.
    - `ava_state` : `durationLabel == "1 h 39"`, `resumeLabel == "Reprendre à 00:10"`, `canPlay`, versions `["FHD · 3.8G", "HD · 1.6G"]`.
    - `theatre_absent_disk` : œuvre 4 → `availabilityLabel == "Disque « Théâtre » non branché"`, `canPlay == false`.
    - `alien_not_digitized` : œuvre 2 → `"Non numérisé"`.
    - `play_success_navigates_to_remote` : `playFromStart()` → `calls` contient `play(PlayRequestDto(work,1,start,…))`, et l'événement `NavEvent.ToRemote` est émis.
    - `play_error_shows_message` : `play` → `Err(Server("volume_absent", "DISK1"))` → `message == "Disque « DISK1 » non branché"`, pas de navigation.
    - `series_lists_seasons_no_play` : œuvre 5 → 2 saisons, `canPlay == false`.
  - `SeasonViewModelTest` (même montage, vrai `PlayLauncher(fakeApi)`) : `init_sends_tv_show_season`, `highlight_and_status` (saison 1 : l'épisode 2 a `highlighted`, `status == "Entamé — 5:00"` et `canResume` ; l'épisode 1 n'a pas de `status`), `play_episode_resume` (→ `PlayRequestDto("episode", 2, "resume")`, puis `ToRemote`).
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implémenter.
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** commit `feat(android): fiches œuvre et saison`.

---

### Task 11: Choix de la version et des pistes avant lecture

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/player/Tracks.kt`, `android/app/src/main/java/fr/remotecontrol/ui/preplay/{PrePlayViewModel.kt,PrePlaySheet.kt}`
- Modify: `android/app/src/main/java/fr/remotecontrol/ui/work/WorkScreen.kt`, `android/app/src/main/java/fr/remotecontrol/ui/season/SeasonScreen.kt`, `android/app/src/main/java/fr/remotecontrol/MainActivity.kt` (afficher la feuille)
- Test: `android/app/src/test/java/fr/remotecontrol/player/TracksTest.kt`, `android/app/src/test/java/fr/remotecontrol/ui/preplay/PrePlayViewModelTest.kt`

**Interfaces:**
- Consumes: `RcApi.tracks`, `MediaTracksDto`, `SubSel` (Tasks 2-3), `CatalogDao.mediaFilesOfWork`, `mediaFilesOfEpisode`, `progress` (Task 5), `PlayTarget`, `StartMode`, `TrackChoice`, `PlayLauncher`, `PlayOutcome`, `sortVersions`, `preferredVersion`, `VersionUi` (Task 10), `Texts.languageName`, `Texts.externalSub`, `Texts.embeddedSub`, `Texts.subtitleNone` (Task 1).
- Produces (`Tracks.kt`) :
  - `data class TrackOption(val index: Int, val label: String)`, `fun trackOptions(tracks: List<TrackDto>): List<TrackOption>` : libellé `Texts.languageName(lang)`, suivi de `" — $title"` si `title` existe et diffère du nom de langue.
  - `data class SubtitleOption(val sel: SubSel, val label: String)`, `fun subtitleOptions(t: MediaTracksDto): List<SubtitleOption>` : d'abord `SubSel.None` (`Texts.subtitleNone`), puis les externes (`SubSel.External(path)`, `Texts.externalSub(lang)`), puis les intégrés (`SubSel.Embedded(index)`, `Texts.embeddedSub(lang)`).
- Produces (`ui/preplay`) :
  - `data class PrePlayArgs(val target: PlayTarget, val start: StartMode)`
  - `data class PrePlayUi(val versions: List<VersionUi> = emptyList(), val selectedMediaFileId: Long? = null, val audio: List<TrackOption> = emptyList(), val subtitles: List<SubtitleOption> = emptyList(), val selectedAudio: Int? = null, val selectedSubtitle: SubSel = SubSel.None, val loading: Boolean = true, val message: String? = null, val canConfirm: Boolean = false)`
  - `class PrePlayViewModel(args: PrePlayArgs, dao: CatalogDao, api: RcApi, launcher: PlayLauncher) : ViewModel` : charge les fichiers de la cible, sélectionne `preferredVersion(...)`, puis appelle `api.tracks(id)` et pré-sélectionne `default.audio` et `default.subtitle` (arbitrage 8 : ce `default` reflète déjà le dernier choix mémorisé pour la cible). Fonctions : `fun selectVersion(mediaFileId: Long)` (recharge les pistes), `fun selectAudio(index: Int)`, `fun selectSubtitle(sel: SubSel)`, `fun confirm()` (`launcher.launch(target, start, TrackChoice(selected, audio, subtitle))`, **toujours** avec les pistes affichées, même si l'utilisateur n'y a pas touché ; `Started` → `NavEvent.ToRemote`, `Failed` → `message`). Une erreur de `tracks` → `message = error.messageFr()`, `canConfirm = false`. Les versions absentes sont affichées mais ne sont pas sélectionnables.
  - `@Composable fun PrePlaySheet(state: PrePlayUi, onVersion: (Long) -> Unit, onAudio: (Int) -> Unit, onSubtitle: (SubSel) -> Unit, onConfirm: () -> Unit, onDismiss: () -> Unit)` (`ModalBottomSheet`, sections `Texts.version`, `Texts.audio`, `Texts.subtitles`, bouton `Texts.startPlayback`).

- [ ] **Step 1: tests qui échouent** :
  - `TracksTest` : `trackOptions(tracks.audio)` → `[TrackOption(1, "Anglais"), TrackOption(2, "Français")]` (titre « Français » identique au nom de langue, donc masqué). `subtitleOptions(tracks)` → libellés `["Aucun", "Français (fichier)", "Anglais (intégré)"]`, avec `sel[1] == SubSel.External("/media/beber/DISK1/Films/A/Ava [2017] [1h45,1920x1038,16x9,FR,sEN].1.fr.srt")`. (Les noms de langue sont testés en Task 1.)
  - `PrePlayViewModelTest` (Robolectric, base `sync_full`, `FakeRcApi.tracksResult = tracks.json`) :
    - `defaults_from_server` : `PrePlayArgs(Work 1, Start)` → `selectedMediaFileId == 1`, `selectedAudio == 2`, `selectedSubtitle == SubSel.None`, `canConfirm`, et `calls` contient `"tracks(1)"`.
    - `resume_preselects_progress_version` : `Resume` → `selectedMediaFileId == 2`.
    - `confirm_untouched_sends_displayed_defaults` (arbitrage 8) : `confirm()` sans rien changer → `PlayRequestDto("work", 1, "start", 1, 2, SubSel.None)` (jamais de champs nuls depuis la feuille).
    - `confirm_sends_choice` : `selectAudio(1)`, `selectSubtitle(External(...))`, puis `confirm()` → `PlayRequestDto("work", 1, "start", 1, 1, External(...))`, et `ToRemote`.
    - `tracks_volume_absent` : `Err(Server("volume_absent", "Théâtre"))` → `message == "Disque « Théâtre » non branché"`, `canConfirm == false`.
    - `episode_target_uses_episode_files` : `PrePlayArgs(Episode 2, Start)` → versions `[6]`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implémenter, puis brancher `onPrePlay` dans `WorkScreen` et `SeasonScreen` (la feuille s'affiche dans la même destination de navigation, et son ViewModel a pour clé `"preplay-${kind}-${id}-${start}"`).
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** commit `feat(android): choix de version et de pistes avant lecture`.

---

### Task 12: Télécommande

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/player/RemoteController.kt`, `android/app/src/main/java/fr/remotecontrol/ui/remote/{RemoteViewModel.kt,RemoteScreen.kt}`
- Modify: `android/app/src/main/java/fr/remotecontrol/AppContainer.kt` (`val remote: RemoteController`), `android/app/src/main/java/fr/remotecontrol/MainActivity.kt` (route `RemoteRoute`)
- Test: `android/app/src/test/java/fr/remotecontrol/player/RemoteControllerTest.kt`, `android/app/src/test/java/fr/remotecontrol/ui/remote/RemoteViewModelTest.kt`

**Interfaces:**
- Consumes: `RcApi` (commandes, `playerState`, `tracks`), `ServerEvent.Hello`, `ServerEvent.Player`, `PlayerSnapshotDto` (Tasks 2-4), `trackOptions`, `subtitleOptions`, `TrackOption`, `SubtitleOption` (Task 11), `FakeRcApi` (Task 6), `MainDispatcherRule` (Task 7), `formatClock`, `Texts`.
- Produces (`RemoteController.kt`) : `class RemoteController(api: RcApi, events: Flow<ServerEvent>, scope: CoroutineScope)`, avec `val snapshot: StateFlow<PlayerSnapshotDto?>` : un `channelFlow` qui émet d'abord le résultat de `api.playerState()` (s'il réussit), puis chaque `Hello.player` et chaque `Player.snapshot`, partagé par `stateIn(scope, SharingStarted.WhileSubscribed(5_000), null)`.
- Produces (`ui/remote`) :
  - `data class RemoteUi(val running: Boolean = false, val title: String = Texts.idle, val position: Double = 0.0, val duration: Double? = null, val positionLabel: String = "0:00", val durationLabel: String = "", val paused: Boolean = false, val volume: Int = 100, val audio: List<TrackOption> = emptyList(), val subtitles: List<SubtitleOption> = emptyList(), val selectedAudio: Int? = null, val selectedSubtitle: SubSel = SubSel.None, val message: String? = null)`
  - `class RemoteViewModel(api: RcApi, controller: RemoteController) : ViewModel` : `state: StateFlow<RemoteUi>`. Titre = `current.title`, ou `Texts.nothingPlaying`, ou `Texts.idle` quand `!running`. Les pistes sont chargées par `api.tracks(current.mediaFileId)` à chaque changement de `mediaFileId`, une fois par identifiant. Fonctions :
    - `fun togglePause()` → `pause(!paused)`. `fun restart()` → `seek(0.0, relative = false)`. `fun skip(seconds: Int)` → `seek(seconds.toDouble(), relative = true)` (boutons −60, −10, +10, +60).
    - `fun onSeekDrag(seconds: Double)` : pendant le glissement, `position` et `positionLabel` suivent le doigt, et les positions poussées par le serveur sont ignorées. `fun onSeekRelease()` : **une seule** commande `seek(position, relative = false)`, puis le serveur reprend la main.
    - `fun onVolumeDrag(level: Int)` et `fun onVolumeRelease()` : même principe, avec une seule commande `volume(level)` au relâchement.
    - `fun selectAudio(index: Int?)`, `fun selectSubtitle(sel: SubSel)`, `fun stop()`, `fun clearMessage()`. Toute erreur → `message = error.messageFr()`.
  - `@Composable fun RemoteScreen(state: RemoteUi, onTogglePause, onRestart, onSkip: (Int) -> Unit, onSeekDrag: (Double) -> Unit, onSeekRelease, onVolumeDrag: (Int) -> Unit, onVolumeRelease, onAudio: (Int?) -> Unit, onSubtitle: (SubSel) -> Unit, onStop, onBack)` : libellés `Texts.play`/`Texts.pause`, `Texts.restart`, `Texts.skipLabel(-60)`, `skipLabel(-10)`, `skipLabel(10)`, `skipLabel(60)`, `Texts.volume`, `Texts.audio`, `Texts.subtitles`, `Texts.stop`. Commandes désactivées quand `!running`.

- [ ] **Step 1: tests qui échouent** :
  - `RemoteControllerTest` : `initial_state_from_http_then_events` (`playerState` → `player_state.json`, puis un événement `Player` dont la position vaut 700 → les valeurs successives de `snapshot` contiennent 612.5 puis 700) ; `hello_updates_snapshot`.
  - `RemoteViewModelTest` (`FakeRcApi`, événements fournis par un `MutableSharedFlow`, `MainDispatcherRule`) :
    - `maps_snapshot` : `title == "Ava"` (`current.workId == 1` disponible pour un retour à la fiche), `positionLabel == "10:12"`, `durationLabel == "1:39:00"`, `volume == 80`, `selectedAudio == 2`, `audio.size == 2` après `"tracks(2)"`.
    - `skip_and_restart_commands` : `skip(-10)` → `"seek(-10.0,true)"`, `skip(60)` → `"seek(60.0,true)"`, `restart()` → `"seek(0.0,false)"`.
    - `toggle_pause_uses_current_state` : `paused = false` → `"pause(true)"`.
    - `drag_ignores_server_positions_and_seeks_once` (Review Focus 4) : `onSeekDrag(3000.0)`, puis un événement serveur à 620, puis `onSeekDrag(3100.0)` → `state.position == 3100.0`. `onSeekRelease()` → exactement une commande `seek`, `"seek(3100.0,false)"`. Un événement serveur à 3101 est ensuite repris.
    - `volume_sent_on_release_only` : trois `onVolumeDrag`, puis `onVolumeRelease()` → une seule commande `"volume(55)"`.
    - `idle_error_message` : `stop()` → `Err(Server("idle", null))` → `message == "Aucune lecture en cours"`.
    - `not_running_shows_idle` : `running = false` → `title == "Aucune lecture en cours"`.
    - `tracks_loaded_once_per_media` : deux événements avec le même `mediaFileId` → un seul `"tracks(2)"`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implémenter. Brancher l'accès `Texts.remote` de l'accueil (déjà prévu par `onRemote`) et la navigation `NavEvent.ToRemote` depuis les Tasks 10 et 11.
- [ ] **Step 4:** PASS.
- [ ] **Step 5:** commit `feat(android): télécommande`.

---

### Task 13: Scan des QR-codes

**Files:**
- Create: `android/app/src/main/java/fr/remotecontrol/qr/{QrPayload.kt,QrDecoder.kt,QrAnalyzer.kt}`, `android/app/src/main/java/fr/remotecontrol/ui/scan/{ScanViewModel.kt,ScanScreen.kt}`
- Modify: `android/app/src/main/java/fr/remotecontrol/MainActivity.kt` (route `ScanRoute`)
- Test: `android/app/src/test/java/fr/remotecontrol/qr/{QrPayloadTest.kt,QrDecoderTest.kt}`, `android/app/src/test/java/fr/remotecontrol/ui/scan/ScanViewModelTest.kt`

**Interfaces:**
- Consumes: `CatalogDao.hasWork`, `hasSeason` (Task 5), `SyncRepository.sync`, `FakeRcApi` (Task 6), `MainDispatcherRule` (Task 7), `NavEvent` (Task 8), `Texts`.
- Produces :
  - `sealed interface QrTarget { data class Work(val id: Long); data class Season(val id: Long) }`, `fun parseQr(text: String): QrTarget?` : motif `^rc://([ws])/(\d+)$` après `trim()`. Tout le reste → `null`.
  - `object QrDecoder { fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): String? }` : `PlanarYUVLuminanceSource(luma, rowStride, height, 0, 0, width, height, false)`, `HybridBinarizer`, `QRCodeReader` avec les indications `TRY_HARDER` et `POSSIBLE_FORMATS = [QR_CODE]`. `NotFoundException`, `ChecksumException` et `FormatException` → `null`.
  - `class QrAnalyzer(onText: (String) -> Unit) : ImageAnalysis.Analyzer` : copie `planes[0]` (Y) dans un `ByteArray`, passe `rowStride`, et ferme toujours l'`ImageProxy`. Classe mince, vérifiée en Task 14.
  - `data class ScanUi(val message: String? = null, val busy: Boolean = false)`
  - `class ScanViewModel(dao: CatalogDao, sync: SyncRepository) : ViewModel` : `state`, `events: Flow<NavEvent>`, `fun onDecoded(text: String)`, `fun clearMessage()`. Pendant `busy`, ou après une navigation, tout nouveau décodage est ignoré. Un texte non reconnu → `Texts.qrUnknown`, signalé une seule fois tant que le même texte revient. Une cible connue du cache → `ToWork(id)` ou `ToSeason(id)`. Une cible inconnue → `sync.sync()`, puis nouvelle vérification ; toujours absente → `Texts.qrNotInCatalog`.
  - `@Composable fun ScanScreen(state: ScanUi, onDecoded: (String) -> Unit, onBack: () -> Unit)` : demande la permission `CAMERA` (`rememberLauncherForActivityResult`), avec le message `Texts.cameraPermission` en cas de refus. `PreviewView` dans un `AndroidView`, CameraX `Preview` + `ImageAnalysis` (`STRATEGY_KEEP_ONLY_LATEST`, résolution cible 1280×720), caméra arrière, `bindToLifecycle` sur le `LocalLifecycleOwner`.

- [ ] **Step 1: tests qui échouent** :
  - `QrPayloadTest` : `parseQr("rc://w/42") == QrTarget.Work(42)`, `parseQr(" rc://s/7\n") == QrTarget.Season(7)`. `"rc://x/1"`, `"rc://w/abc"`, `"https://exemple.fr"` et `""` → `null`.
  - `QrDecoderTest` (Review Focus 5) : générer `QRCodeWriter().encode("rc://w/42", BarcodeFormat.QR_CODE, 400, 400)` et le convertir en luminance (noir 0, blanc 255). `decode(luma, 400, 400) == "rc://w/42"`. Même image dans un tampon de `rowStride = 448` (lignes complétées par des 0x80, dernier rang non complété) → `decode(buf, 400, 400, 448) == "rc://w/42"`. Une image unie → `null`.
  - `ScanViewModelTest` (Robolectric, base `sync_full`) : `known_work_navigates_once` (`onDecoded("rc://w/1")` trois fois → un seul `ToWork(1)`), `season_navigates` (`"rc://s/2"` → `ToSeason(2)`), `foreign_qr_message` (`"https://x"` → `"QR-code non reconnu"`), `foreign_qr_signalled_once` (`"https://x"`, `clearMessage()`, puis `"https://x"` encore → `message == null` ; puis `"https://y"` → `message == "QR-code non reconnu"`), `unknown_id_syncs_then_message` (`"rc://w/999"` → `calls` contient un appel `sync`, puis `message == "Œuvre introuvable dans le catalogue"`), `unknown_id_found_after_sync` (la réponse de synchronisation contient l'œuvre 999 → `ToWork(999)`).
- [ ] **Step 2:** FAIL.
- [ ] **Step 3:** implémenter.
- [ ] **Step 4:** `./gradlew testDebugUnitTest assembleDebug checkNoGms` → PASS (ZXing core n'apporte aucune dépendance Google).
- [ ] **Step 5:** commit `feat(android): scan des QR-codes`.

---

### Task 14: Vérification de bout en bout sur un appareil

**Files:**
- Modify: au besoin, uniquement pour corriger ce que la vérification révèle (chaque correction avec son test, dans la tâche qui possède le code).
- Create: `android/README.md` (5 à 10 lignes : prérequis, `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, installation `adb install -r app/build/outputs/apk/debug/app-debug.apk`).

**Interfaces:**
- Consumes: l'appli complète, et le serveur de dev du plan serveur (Task 11, Step 3 : `rc fake-tree`, `rc serve` sur le port 8765).

- [ ] **Step 1:** toute la suite : `cd android && ./gradlew clean testDebugUnitTest assembleDebug checkNoGms` → BUILD SUCCESSFUL.
- [ ] **Step 2:** lancer le serveur de dev sur le PC (même réseau Wi-Fi que le téléphone), puis installer l'APK sur le Murena (/e/OS) et, si possible, sur le Galaxy S21 FE.
- [ ] **Step 3: découverte.** Premier lancement : le serveur est trouvé par NSD sans rien saisir, et les réglages affichent `"<ip>:8765 (détecté)"`. Couper le serveur et relancer l'appli : l'accueil reste navigable, et une commande affiche « Serveur injoignable ». Tester l'adresse manuelle dans les réglages.
- [ ] **Step 4: synchronisation.** Premier chargement complet (environ 3 990 œuvres) en moins de 30 s. Puis modifier `medias.txt` côté serveur : l'événement `catalog` met à jour la fiche sans action manuelle.
- [ ] **Step 5: navigation et télé.** Accueil, parcours de chaque type, recherche (« negres », « patelliere »), bascule du filtre. Ouvrir une fiche : elle s'affiche sur `http://<pc>:8765/tv/`. Affiches : couper le serveur, relancer l'appli, les affiches déjà vues s'affichent toujours.
- [ ] **Step 6: lecture.** Lire Ava depuis le début, puis tous les contrôles de la télécommande (pause, ±10 s, ±60 s, glisser la barre, volume, piste audio, sous-titre externe, aucun, arrêt). Ensuite « Reprendre à … » depuis la fiche, et un épisode d'une série depuis la fiche saison, avec l'épisode entamé mis en évidence. Ouvrir une autre fiche pendant la lecture : la télé reste sur la vidéo.
- [ ] **Step 7: QR.** Afficher sur l'écran du PC un QR `rc://w/1` (par exemple `qrencode -t ansiutf8 'rc://w/1'`) et un QR de saison : la fiche s'ouvre sur le téléphone et sur la télé. Un QR étranger affiche « QR-code non reconnu ».
- [ ] **Step 8:** consigner les écarts éventuels avec le contrat d'API dans `android/README.md`, section « Écarts constatés » (vide si aucun).
- [ ] **Step 9:** commit `docs(android): vérification sur appareil`.

---

## Auto-revue

- **Couverture de la spec :** §3 (API 29, sans GMS, APK) → Task 1 (`checkNoGms`) et Task 13. §7 découverte → Task 7 ; synchronisation → Task 6 ; images immuables → Task 8 ; commandes → Tasks 3, 10, 11, 12 ; WebSocket → Tasks 4, 6, 12. §8 pistes avant et pendant la lecture → Tasks 11 et 12 ; « Lire depuis le début » et « Reprendre à hh:mm » → Task 10 ; épisode mis en évidence → Task 10 ; la télé reste sur la vidéo → aucun `force` envoyé (Global Constraints, vérifié en Task 14). §10 écrans → Tasks 7 à 13 ; filtre lisible → Tasks 5, 8, 9 ; hors ligne → Tasks 5, 6, 8. §11 QR → Task 13. `GET /api/works/{id}`, `GET /api/seasons/{id}` et `GET /api/tv/state` ne sont volontairement pas utilisés : les fiches viennent du cache pour rester hors ligne, et `highlightEpisode` reproduit la règle du serveur.
- **Types :** `PlayTarget`, `StartMode`, `playRequest`, `PlayLauncher` et `PlayOutcome` sont définis en Task 10 et réutilisés en Task 11 (un seul chemin de lancement). `TrackOption` et `SubtitleOption` sont définis en Task 11 et réutilisés en Task 12. `FakeRcApi` est défini en Task 6, `MainDispatcherRule` et `FakeKeyValueStore` en Task 7. `LocalServerBaseUrl` et `observeWorkCount` (Tasks 8 et 5) alimentent `Poster` et `catalogEmpty`. `NavEvent` est défini en Task 8.
- **Contrat :** les arbitrages 1 à 8 (`docs/superpowers/plans/2026-11-22-api-contract-rulings.md`) sont appliqués : vrais booléens (aucun décodage toléré), `work_collections` remplacé par œuvre, `deleted.collection`, « Récemment ajoutés » par identifiant décroissant, `current` avec `work_id` et `season_id`, champ `start`, toutes les 409 avec `volume_label`, `progress.audio`/`subtitle` ignorés, `default` de `/tracks` repris et renvoyé tel quel par la feuille de choix.
- **Textes :** toutes les chaînes visibles sont des membres de `Texts` (Task 1) ; les tâches d'écran ne citent que des membres de `Texts`.
