# Remote-Control — Étape 3 — Document de conception

*Date : 2026-11-26. Statut : en attente de validation.*
*S'appuie sur l'itération 2 (`2026-11-24-iteration-2-design.md`) et sur le contrat d'API (`docs/superpowers/plans/2026-11-22-api-contract-rulings.md`, arbitrages 1 à 19).*

## 1. Intention

L'itération 2 est fusionnée et a été essayée par l'utilisateur. Cette étape **améliore l'existant**, sans fonction nouvelle d'envergure :
- elle rend la reprise après une pause quasi immédiate ;
- elle ajoute deux confort d'usage ;
- elle prépare le mini-PC, une machine modeste ;
- elle fait le ménage relevé par les revues de l'itération 2.

**Reportés :**
- **Format des QR-codes.** Le passage à `rc://w/<type>/<clé>` est en attente de la décision de l'utilisateur.
- **Installation sur le mini-PC et fiches PDF.** L'utilisateur envisage un troisième composant, une appli de bureau pour gérer la base et produire les PDF.

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs**
1. **Reprise rapide après une pause.** Nouveau mode de mise de côté « masquer la vidéo », qui devient le mode par défaut.
2. **Touches de volume du téléphone.** Quand la télécommande est ouverte, elles règlent le volume de la télé.
3. **Ligne de détail sur la pause en plein écran.** Elle donne le titre de l'épisode, la qualité et la résolution, comme la maquette 4b.
4. **Charge réduite sur le mini-PC.** Revérification de l'analyse et requête de disponibilité.
5. **Contrat plus solide.** Les sections de vue portent un code, et la télé ne lit plus leurs titres français.
6. **Ménage.** Retrait de la route `tv/show` et découpage du code de la page télé.
7. **Barre de la première synchronisation fidèle** à l'écriture réelle.

**Non-objectifs**
- QR-codes, installation, fiches PDF.
- Toute modification visible de la mise en page des écrans, hormis la ligne de détail du point 3.

**Anti-objectifs (échec même si « ça marche »)**
- **La télé clignote** ou montre un écran intermédiaire à la reprise. C'est corrigé à l'itération 2 et doit le rester.
- **Les touches de volume agissent sur la télé hors de l'écran télécommande**, ou le téléphone perd le réglage de son propre volume.
- **Le découpage de la page télé change son rendu ou son comportement.**

## 3. Contraintes

Elles sont inchangées :
- français partout ;
- pas de services Google ;
- réseau local sans authentification ;
- page télé légère : pas de flou, animations limitées à `transform` et `opacity` ;
- bureau de l'utilisateur sous GNOME Wayland, où un programme ne peut ni placer sa fenêtre ni la remettre au premier plan.

## 4. Approches retenues

### 4.1 Reprise rapide (choisie par l'utilisateur après essai)

- **A — « masquer la vidéo » (`hide`), retenue.**
  - En pause, on retire sa piste vidéo à mpv (propriété `vid` = `no`). Sa fenêtre se ferme, et mpv reste lancé, en pause, fichier ouvert.
  - À la reprise, on rend la piste (`vid` = valeur gardée, `auto` à défaut). Une nouvelle fenêtre s'ouvre en plein écran (`--fs`) et passe devant le navigateur, comme une fenêtre neuve. On enlève ensuite la pause.
  - **Essai concluant le 2026-11-26 sur le bureau de l'utilisateur, sur deux cycles :** fenêtre disparue, puis revenue devant, en plein écran, rapidement et au bon endroit.
- **B — accélérer la relance du mode `restart`.** Gain modeste. Écartée, car A est bien plus efficace.
- **C — ne rien changer.** Écartée.

**Conséquences :**
- `pause_background` accepte `hide` (le défaut), `restart` et `minimize`.
- `minimize` reste disponible pour le mini-PC si son gestionnaire de fenêtres le permet. Il échoue sous GNOME Wayland.
- Une valeur inconnue se replie sur `hide`, avec le même avertissement au démarrage qu'aujourd'hui.
- Une config qui fixe explicitement `restart` reste en `restart`. Dans `app.py`, `pause_mode` devient un choix à trois valeurs.
- La télé se comporte exactement comme en `restart` ou en `minimize`. On passe en `view` avec le coin d'état, ou en `paused` en plein écran si la télécommande est ouverte.
- Si, sur une autre machine, mpv accepte `vid=no` sans fermer sa fenêtre, on bascule à la main la config sur `restart`. Pas de détection automatique.

### 4.2 Les autres points

Chacun n'a qu'une manière raisonnable de se faire ; ce sont les choix du concepteur, décrits en § 5.

## 5. Conception

### 5.1 Mode `hide`

**Mise de côté :**
1. Capture de l'image, comme aujourd'hui.
2. Lecture de `vid` dans mpv, gardée dans la session.
3. `set_property vid no`.
4. Si mpv refuse, on se replie sur `restart` pour cette pause, comme `minimize` le fait déjà.

**Pendant la mise de côté :**
- les commandes (position, volume, pistes, pause) vont directement à mpv, qui tourne toujours, comme en `minimize` ;
- la position affichée dans le coin d'état suit les déplacements, comme en `minimize`.

**Reprise :**
1. `set_property vid <valeur gardée>`, puis `fullscreen yes` par sécurité, puis `pause no`.
2. La télé garde la capture en plein écran jusqu'à ce que la vidéo avance (mécanisme `_await_video` de l'itération 2, inchangé).

**Arrêt pendant la mise de côté :** il arrête mpv normalement.

**Interface du lecteur :** `Player.set_video(enabled: bool)`.
- Dans `MpvPlayer`, il garde la valeur de `vid` à la désactivation et la rétablit à la réactivation.
- Dans `FakePlayer`, il enregistre l'appel et lève l'exception `video_error` si elle est posée.

**Fin de fichier pendant la mise de côté :** elle ne peut pas arriver, la vidéo est en pause. Si mpv s'arrête quand même, l'arrêt suit le chemin normal, et la session se termine.

### 5.2 Touches de volume

- **Où :** uniquement quand l'écran Télécommande est l'écran actif (`RESUMED`) et qu'une lecture est en cours. Partout ailleurs, les touches gardent leur effet normal sur le volume du téléphone.
- **Effet :** volume de la télé ± 5, borné entre 0 et 100, à partir du dernier volume connu par les événements `player` (`state.volume`, entier de 0 à 100).
  - Le gestionnaire n'est inscrit que si une lecture est connue. Sans état connu, l'appui garde son effet normal sur le téléphone. Envoi par `POST /api/player/volume`.
  - Un appui maintenu répète les pas.
  - Les envois sont regroupés : au plus 4 par seconde, en n'envoyant que la dernière valeur.
  - Le curseur de volume de l'écran suit tout de suite.
- **Réalisation :**
  - l'activité intercepte `KEYCODE_VOLUME_UP` et `KEYCODE_VOLUME_DOWN` quand un gestionnaire est inscrit ;
  - l'écran Télécommande inscrit son gestionnaire tant qu'il est `RESUMED` ;
  - l'appui est consommé, donc le téléphone n'affiche pas son propre curseur de volume.
- **Échec d'envoi :** même message d'erreur que le curseur de volume actuel.

### 5.3 Ligne de détail sur la pause en plein écran

Contrat, arbitrage 20 : l'objet `pause` gagne `detail`, un texte prêt à afficher ou `null`. Il est composé par le serveur, à partir de `episode.title`, de `media_file.quality` (le code du catalogue, par exemple SD ou FHD) et de `media_file.width` et `height` (l'analyse) pour le fichier en lecture :
- **épisode :** « titre de l'épisode · qualité résolution », par exemple « Hiros · SD 624 × 352 » ;
- **film :** « qualité résolution » ;
- chaque partie est omise si elle est inconnue, et `detail` vaut `null` si tout l'est.

La résolution s'écrit avec des espaces fines insécables autour du « × », comme dans l'appli. La télé affiche cette ligne sous le titre, en plein écran, comme dans 4b. Elle ne l'affiche pas dans le coin d'état.

### 5.4 Charge sur le mini-PC

**Revérification de l'analyse** (`analysis.pending_files`, cas `recheck`). Elle ne retient que les fichiers présents dont la taille ou la date diffère du cache d'analyse (`probe_cache`, colonnes `size` et `mtime`, comparaison exacte comme dans `cached_tracks`), ou qui n'ont pas de cache. Le `stat` se fait dans le fil auxiliaire, sans transaction. Aujourd'hui, tous les fichiers sont remis en file toutes les 10 minutes, chacun avec son fil, sa connexion et sa transaction.

**Disponibilité dans `enrich`** (`serialize._availability`). La condition `COALESCE(mf.work_id, s.work_id) IN (…)` empêche l'usage d'un index. Elle est remplacée par deux requêtes indexées réunies :
- fichiers des œuvres (`media_file.work_id`) ;
- fichiers de leurs épisodes, par saison puis épisode.

Des index sont ajoutés si nécessaire, par `CREATE INDEX IF NOT EXISTS` dans `schema.sql`. Ce fichier est rejoué à chaque ouverture, donc une base existante les reçoit aussi. `SCHEMA_VERSION` passe à 3, et le refus d'une base plus récente, déjà en place, est accepté. Le résultat de `enrich` reste identique : les tests existants font foi.

### 5.5 Sections avec un code

Contrat, arbitrage 21 : chaque section d'une vue porte `kind`, parmi `resume`, `recent`, `collections`, `people`, `works` et `aka`.
- L'appli le remplit :
  - accueil : « Reprendre » → `resume`, « Récemment ajoutés » → `recent`, « Collections » → `collections` ;
  - recherche : `people`, `works` et `aka`.
- Le serveur l'accepte comme champ déclaré du modèle `Section` (`Literal[…] | None = None`) et le transmet tel quel dans la vue enrichie.
- La télé choisit la mise en page de chaque section, à l'accueil comme dans la recherche, selon `kind`. Sans `kind`, elle se rabat sur la règle actuelle, qui lit le titre.

### 5.6 Ménage

**Route `POST /api/tv/show`.** Elle est retirée, avec son modèle et ses tests ; l'appli ne l'utilise plus depuis l'itération 2. `TvController.show()` reste, car il sert à la veille.

**Page télé.** Elle est découpée par responsabilité, en scripts classiques chargés dans l'ordre :
- `common.js` : outils, échappement, formats, affiches, langues ;
- `pause.js` : inchangé ;
- `sheets.js` : fiches œuvre et saison, version et pistes, cache des fiches ;
- `views.js` : vues miroir ;
- `app.js` : état, WebSocket, routage, fondus, veille, aperçu.

Le découpage part de `app.js` ; `common.js` et `sheets.js` sont nouveaux.

Aucun changement de rendu. On le vérifie ainsi :
1. Avant le découpage, on prend une capture de référence de chaque aperçu `?preview=` avec Playwright, en 1920×1080.
2. On reprend les mêmes captures après le découpage.
3. Un petit script Pillow compare les deux séries pixel par pixel. L'horloge est figée dans le mode aperçu, ou masquée pour la comparaison.

### 5.7 Barre de la première synchronisation

`SyncProgress.Writing` devient `Writing(done, total, fraction)`.
- `done` et `total` comptent les œuvres. Le texte « 1 250 / 3 992 œuvres » et son test ne changent pas.
- `fraction`, entre 0 et 1, mesure l'écriture de **toutes** les lignes : œuvres, saisons, épisodes, fichiers, collections, liens et progressions. Le total est connu avant l'écriture, puisque la réponse complète est déjà téléchargée.
- La barre suit `fraction`.

## 6. Contrat — ajouts

À ajouter au document des arbitrages :
- **20.** `pause.detail` (§ 5.3).
- **21.** `sections[].kind` (§ 5.5).
- **22.** `POST /api/tv/show` est retirée.
- **23.** `pause_background` vaut `hide` par défaut.

## 7. Risques

1. **`hide` sur la machine cible.** Le comportement d'une fenêtre neuve dépend du gestionnaire de fenêtres. `restart` reste disponible partout.
2. **Plusieurs pistes vidéo.** Si un fichier en a plusieurs, la piste gardée est rétablie. Sans valeur lisible, on rétablit `auto`.
3. **Touches de volume.** Il faut tester sur un vrai téléphone, car certains constructeurs interceptent les touches. Si elles ne remontent pas à l'appli, le curseur à l'écran reste disponible.

## 8. Laissé au builder

- Découpage exact des fonctions entre les fichiers de la page télé.
- Nom des index et opportunité d'une migration.
- Cadence fine du regroupement des envois de volume, dans la limite de 4 par seconde.
