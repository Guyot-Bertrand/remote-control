# Alignement des écrans Android sur les maquettes (2026-11-24)

Sources : `docs/design/Remote-Control - 3 - Appli android.html` (15 artboards, décodés dans
`/tmp/claude-1000/align/f3/b-*.html`) et `… - 1 - Identité & composants.html` (tokens, déjà repris dans RcTheme).
Base : 5d92139. Commits : 18572ff → 88d8c0f (+ correctif du défilement rapide). Captures : `shots-align/NN-*.png`
(émulateur API 29 sans GMS, serveur de dev, arbre factice de 3 992 œuvres, faux mpv).

Légende : **FAIT** = corrigé côté appli ; **SERVEUR** = nécessite une donnée que l'API ne fournit pas ;
**ÉCART ACCEPTÉ** = différence volontaire.

## Réglages (construit avant les maquettes — priorité)
| Écart | Statut |
|---|---|
| Écran brut (TextButton, OutlinedTextField, boutons empilés) au lieu des sections SERVEUR / CATALOGUE / À PROPOS | FAIT (18572ff) — titres laiton 12/600, lignes 72/56 dp avec pictos |
| En-tête sans pastille, « Réglages » 20/500 | FAIT (`ScreenHeader(showChip = false)`) |
| Ligne serveur : adresse, « détecté automatiquement / saisi à la main », état coloré (Connecté / Recherche… / Hors ligne) | FAIT |
| « Saisir l'adresse manuellement » avec chevron | FAIT (champ arrondi repliable + « Enregistrer ») |
| « Dernière synchronisation — Aujourd'hui à 21:12 · 4 012 œuvres, dont 2 655 lisibles » | FAIT : heure enregistrée par `SyncRepository` (`meta sync.at`), `CatalogDao.playableCount()`, `Texts.lastSync` (testés) |
| « Resynchroniser » en bouton tonal laiton | FAIT |
| À PROPOS : « Version de l'appli — Remote-Control x.y.z » | FAIT (versionName du paquet) |
| Message en bas d'écran (ivoire) avec « Réessayer » après une synchro en échec | FAIT (`RcSnackbar`, commun avec le scanner ; `SettingsUi.syncFailed`) |
| Nom du PC (« PC du salon ») comme titre de la ligne serveur | SERVEUR : `GET /api/info` renvoie `name` fixe (« Remote-Control ») ; il faudrait un champ `host_name` (nom d'affichage du PC, configurable) |
| « Rechercher le serveur » (absent de la maquette) | ÉCART ACCEPTÉ : fonction exigée par le plan, présentée comme une ligne de la section |

## Accueil (+ PC injoignable / catalogue vide / première synchronisation)
| Écart | Statut |
|---|---|
| Reprise d'un épisode : titre « Back to Life », ligne « S2 É3 · reste 11 min » (était « Back to Life — S01E02 ») | FAIT (c890c38, testé) |
| « Catalogue vide » plein écran : pastille, titre Fraunces 26, texte, « Resynchroniser », « Vérifier l'adresse du serveur » (était un bandeau) | FAIT : `HomeBanner.Empty` = connecté, déjà synchronisé, hors synchro, 0 œuvre (testé) |
| « Première synchronisation » plein écran : marque, barre, texte, serveur | FAIT (recherche du serveur puis synchronisation) |
| Barre d'avancement chiffrée « 2 480 / 4 012 » | SERVEUR : `/api/sync` répond d'un bloc ; il faudrait un total annoncé (`total_works` dans un en-tête ou une réponse paginée/streamée) — barre indéterminée en attendant |
| Ligne serveur « PC du salon · 192.168.1.20 » | Adresse affichée ; nom : SERVEUR (`host_name`, voir Réglages) |
| Filtre « Œuvres lisibles / Tout le catalogue », bouton Télécommande, section Parcourir | ÉCART ACCEPTÉ (exigés par le plan, déjà stylés avec le vocabulaire de la maquette) |
| Première synchro avec la barre de navigation | ÉCART ACCEPTÉ (la maquette n'en a pas ; l'appli garde la navigation) |

## Parcourir / Collection
| Écart | Statut |
|---|---|
| Défilement rapide : bulle laiton avec la lettre + poignée au bord droit | FAIT (f8e6f51 + correctif) : poignée à glisser (bande de 20 dp), bulle pendant le défilement, tri par titre ; `indexLetter` testé |
| Lignes non lisibles : nom du disque débranché (« DISK2 », terre cuite, picto disque) | FAIT : `WorkSummary.absentVolume` calculé en SQL (fichiers de l'œuvre ou de ses épisodes), testé ; aussi sur les vignettes (« AUTRES ») |
| Lignes non numérisées : « DVD » (gris, picto disque optique) | Partiel : « Non numérisé » ; le support réel est SERVEUR : champ `medium` (« DVD », « Blu-ray »…) sur `works[]` de `/api/sync` |
| Bandeau « Sur la télé · 1:12:04 / 1:39:00 » au-dessus de la barre | FAIT (88d8c0f) : `miniPlayerOf` (testé), affiche, pause/lecture, avancement ; toucher → télécommande ; masqué sur le scanner |

## Recherche
| Écart | Statut |
|---|---|
| Sections PERSONNES / ŒUVRES / AUSSI CONNU SOUS | FAIT (f287151) : `searchSections` (personne = nom en réalisation ou casting qui répond à toute la saisie, nombre d'œuvres ; titre original/alternatif seul → « Aussi connu sous ») ; nouvelle requête `searchHits` (titres VO/alt et casting) ; testés |
| Ligne personne : initiales Fraunces sur disque, « Réalisation · 2 œuvres », chevron | FAIT (toucher = chercher ce nom) |
| Mise en évidence laiton des passages saisis, sans accents, « Léa Mysi » d'un seul tenant | FAIT : `matchRanges` (testé : accents, « œ », fusion à travers les espaces) |
| « Titre alternatif : … » en troisième ligne | FAIT (« Titre original : … » pour le titre VO) |
| Pas de page « personne » (chevron) | ÉCART ACCEPTÉ : la maquette n'en définit pas ; le chevron relance la recherche sur le nom |

## Fiche œuvre (+ non numérisé, disque non branché, PC injoignable)
| Écart | Statut |
|---|---|
| Rang dans la collection « n° 2017 » en laiton | FAIT (2946735) : `collectionsOfWork` renvoie `wc.rank` (le rang était déjà en cache) |
| Lien « FHD · Français · Sans sous-titres — Modifier » | Partiel FAIT : `choiceSummary` (qualité proposée + choix de sous-titres mémorisé, testé) + « Modifier » ; la langue audio avant sondage est SERVEUR : `audio_langs` / `subtitle_langs` par fichier dans `media_files[]` de `/api/sync` |
| Bandeau « PC injoignable… » avec « Réessayer », lecture désactivée | FAIT (relance `locator.locate(force = true)`) |
| « Reprendre à 1 h 12 » (était « 01:12 ») | FAIT (`formatResumePosition`, testé) |
| Pastilles de langue (« Français », « Allemand », « ST français ») | SERVEUR : `audio_langs`, `subtitle_langs` par fichier |
| Non numérisé : « Cette œuvre n'existe qu'en DVD… » et pastille « DVD » | SERVEUR : `works[].medium` |
| Menu « Plus d'options » (⋮) | Non construit : contenu non spécifié par la maquette ni le plan |

## Fiche saison
| Écart | Statut |
|---|---|
| Pastilles de qualité dans l'en-tête (« FHD ») | FAIT (c29ae04 : `seasonQualities`, testé) |
| Pastilles de langue (« Anglais », « ST français ») | SERVEUR : `audio_langs` / `subtitle_langs` par fichier |
| Durée de chaque épisode (« 25 min ») | SERVEUR : `episodes[].duration_min` (on ne connaît que la durée des épisodes entamés) |
| Titre de la série dans la barre | déjà fait (vague finale) |

## Version et pistes
| Écart | Statut |
|---|---|
| Sous-titres sur deux lignes : langue, puis « Fichier à côté de la vidéo (.srt) » / « Piste intégrée » | FAIT (3b25c40, testé) |
| « Ces choix seront repris la prochaine fois pour ce film. » | FAIT (« ce film / cet épisode / ce documentaire… », testé) |
| Définition sous la version (« 1920 × 1080 ») | SERVEUR : `media_files[].width/height` (aujourd'hui seulement `height` dans `/tracks`, pour la version sondée) |
| Rôle de la piste audio (« Doublage », « Version originale ») | SERVEUR : `works[].original_language` (ou un drapeau `original` par piste dans `/tracks`) |
| Section Audio absente si aucune piste audio | inchangé (dépend du sondage) |

## Télécommande
| Écart | Statut |
|---|---|
| Affiche 56×84 + ligne « 2017 · FHD · Français » | FAIT (26b9564) : `CatalogDao.playingInfo` (affiche, année, qualité du fichier) + langue de la piste audio choisie ; testé |

## Scan QR
| Écart | Statut |
|---|---|
| Message en bas d'écran | déjà conforme ; composant devenu commun (`RcSnackbar`) |
| Reste (cadre, lampe, aide) | déjà conforme (construit d'après la maquette) |

## Transverse
- Erreurs de commande : `MessageBanner` (terre cuite) sur les fiches et la télécommande ; la maquette « Réglages — erreur
  serveur » utilise le message ivoire en bas d'écran : appliqué aux Réglages et au scanner ; ÉCART ACCEPTÉ ailleurs.
- Pastille de connexion périmée après coupure du serveur : comportement de `ServerLocator` (hors périmètre visuel).

## Champs d'API à ajouter (récapitulatif)
1. `/api/info` : `host_name` — nom d'affichage du PC.
2. `/api/sync` `works[]` : `medium` (support physique d'une œuvre non numérisée) ; `original_language`.
3. `/api/sync` `media_files[]` : `audio_langs[]`, `subtitle_langs[]` (dont fichiers externes), `width`, `height`.
4. `/api/sync` `episodes[]` : `duration_min`.
5. `/api/sync` : total annoncé (`total_works`) ou réponse progressive, pour l'avancement de la première synchro.

---

# Correctifs, tour 1 (relecture)
- **I1** `homeBanner` : « Synchronisation » seulement pendant `syncing` ; connecté, vide, hors synchro → `Empty` (déjà
  synchronisé) ou `NotSynced` (« Catalogue non synchronisé », première synchro en échec), avec « Resynchroniser » et
  « Vérifier l'adresse du serveur ». L'en-tête (roue des réglages) reste affiché au-dessus de tous les états.
  Tests : `banner_follows_server_state` (connecté, pas de synchro, jamais synchronisé → NotSynced), **M12**
  `failed_resync_from_home_shows_message` (« Serveur injoignable » affiché).
- **I2** Recherche : `searchSections` calculé dans le flux des réponses, `.flowOn(Dispatchers.Default)` limité à la
  répartition (le délai de frappe reste sur le dispatcher appelant, tests en temps virtuel inchangés) ; cache supprimé.
- **M1** `NowPlayingViewModel` (état du bandeau + lecture/pause ; échec → message ivoire refermable). Tests
  `NowPlayingViewModelTest` (affiche locale ; échec de pause signalé puis effacé).
- **M2** Bandeau et bouton Télécommande de l'accueil : `launchSingleTop`.
- **M3** Titre du bandeau : `current.title`, sinon le titre du catalogue local (`PlayingInfo.title`) ; test.
- **M4** Défilement rapide : zone de saisie seulement quand la poignée est visible, `pointerInput(grid, works)`,
  avancement lu dans le lambda `offset {}`, piste calculée sur la bulle (44 dp).
- **M5–M8** Réglages : `now` fourni par le ViewModel (horloge injectée, rafraîchie chaque minute ; test), pas de
  seconde ligne vide pendant la recherche, « Remote-Control » sans espace final si la version est inconnue (test),
  message d'échec avec « OK » et « Réessayer ».
- **M9** Mise en évidence aussi dans les lignes « Aussi connu sous » (titre alternatif inclus).
- **M10** `matchRanges` sur un titre décomposé (NFD) : les accents combinants font partie du mot et suivent leur
  lettre ; tests `Léa Mysius`, `Étranger`, `Léa`.
- **M11** `seasonQualities` : fichiers présents seulement ; test `season_qualities_only_from_present_files`.
- **M13** « Réessayer » passe par `WorkViewModel.retryServer()` (recherche du serveur injectée) ; test.
Vérification : `./gradlew testDebugUnitTest assembleDebug checkNoGms` → BUILD SUCCESSFUL, 286 tests, 0 échec, aucun
avertissement Kotlin.

# Correctifs, tour 2
- Réglages : l'horloge n'est plus une boucle d'`init` ; `SettingsViewModel.now` =
  `flow { while (true) { emit(clock()); delay(60 s) } }.stateIn(WhileSubscribed(5 s), clock())`, collecté par l'écran
  (`collectAsStateWithLifecycle`) et passé à `SettingsScreen(now = …)`. Gardé à part de `state` (et non combiné dedans)
  pour que `state.value` reste lisible sans abonné (tests et actions existants). Test
  `clock_ticks_only_while_observed` (avance pendant l'abonnement, s'arrête 5 s après sa fin).
- Accueil : le message d'échec de « Resynchroniser » disparaît quand le catalogue n'est plus vide ou dès qu'une
  synchronisation réussit ensuite (heure de dernière synchro mémorisée avec l'échec) ; test prolongé.
- Défilement rapide : `jump` répartit le doigt sur la course de la bulle (hauteur − 44 dp, centrée), comme la poignée.
Vérification : BUILD SUCCESSFUL, 286 tests, 0 échec, aucun avertissement Kotlin.
