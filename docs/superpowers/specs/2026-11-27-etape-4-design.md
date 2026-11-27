# Remote-Control — Étape 4 — Document de conception

*Date : 2026-11-27. Statut : validé, réalisé et fusionné le 2026-11-27.*
*S'appuie sur l'étape 3 (`2026-11-26-iteration-3-design.md`), le contrat d'API (`docs/superpowers/plans/2026-11-22-api-contract-rulings.md`, arbitrages 1 à 23) et les décisions notées dans `2026-11-25-notes-a-revoir.md` (sections « Affiches pour la vraie installation » et « Notes et liens des œuvres »).*

## 1. Intention

Les fiches des œuvres doivent être plus riches et toutes avoir une image, en vue de la vraie installation (3 992 œuvres) :
- **Notes et liens.** Le `medias.txt` de l'utilisateur porte, dans ses lignes `MQ`, des notes (IMDb, Sens Critique, critiques Sens Critique nommés, note personnelle, note technique) et des liens (Sens Critique, IMDb, Wikipédia). Aujourd'hui, le serveur n'en garde que l'identifiant IMDb.
- **Une image pour chaque œuvre.** Le vrai catalogue n'a aucune affiche locale (`MF ; AF`). TMDB en fournira environ 90 % avec une clé ; pour le reste, et tant qu'il n'y a pas de clé, une image extraite de la vidéo vaut mieux que l'affiche de remplacement.

**Reporté :** installation sur le mini-PC (elle comprendra la clé TMDB), format des QR-codes, appli de bureau et fiches PDF ; sources `WB`, `CP`, `SL`, `IO`, `GE`, `CC`, `RU`, liens YouTube et autres vidéos en ligne.

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs**
1. **Notes sur la fiche de l'œuvre.**
   - Télé : IMDb, Sens Critique et les critiques Sens Critique (`SC@xx`), avec leur nom.
   - Téléphone : les mêmes, plus la note personnelle (`NP`) et la note technique (`NT`).
2. **Liens sur le téléphone**, ouverts dans le navigateur : Sens Critique, IMDb, Wikipédia, TMDB.
3. **Image extraite de la vidéo** pour toute œuvre lisible sans affiche locale ni affiche TMDB.
4. **Priorité des affiches :** locale (`AF`) > TMDB > image extraite. Une clé TMDB ajoutée après coup remplace les images extraites.

**Non-objectifs**
- Trier ou filtrer par note.
- Modifier les notes depuis l'appli (le `medias.txt` reste la seule source).
- Afficher les liens sur la télé (on ne peut pas les suivre).

**Anti-objectifs (échec même si « ça marche »)**
- **La note personnelle ou la note technique apparaît sur la télé.** Elles restent sur le téléphone.
- **Une image extraite remplace une affiche locale ou TMDB**, ou une image extraite reste alors qu'une affiche TMDB est disponible.
- **L'extraction ralentit le mini-PC pendant une lecture**, ou montre une image noire ou de générique pour la plupart des œuvres.
- **La fiche télé perd de l'information** de façon visible : titre, infos, état, versions. Le résumé peut perdre une ligne quand les notes sont là.

## 3. Contraintes

- Français partout ; pas de services Google ; réseau local sans authentification.
- Page télé légère : pas de flou ; animations limitées à `transform` et `opacity`.
- ffmpeg est disponible avec ffprobe (déjà utilisé par l'analyse).
- `medias.txt` fait foi ; les lignes non reconnues sont journalisées, jamais bloquantes.

## 4. Approches retenues

### 4.1 Notes et liens

- **A — le serveur lit les notes à l'import et les transmet, retenue.** Il lit les lignes `MQ` de chaque œuvre et les lignes `QS` (une par source : code, libellé, profil), range le tout avec l'œuvre, et l'expose dans `/api/sync` (téléphone) et `/api/works/{id}` (télé). L'identifiant TMDB, obtenu en cherchant l'affiche, sert à construire le lien TMDB.
- **B — le téléphone lit lui-même `medias.txt`.** Écartée : la télé n'en profiterait pas, et seul le serveur lit ce fichier.

### 4.2 Image extraite

- **A — étape de fond du serveur, après l'analyse, retenue.** Elle a besoin du fichier sur un disque branché et de sa durée, que l'analyse fournit déjà.
- **B — extraction à l'import du catalogue.** Écartée : le disque peut être débranché et la durée inconnue à ce moment-là.

### 4.3 Mise en page (choisie par l'utilisateur sur maquettes, le 2026-11-27)

- **Télé : maquette A.** Une rangée de pastilles sous la ligne « Réalisation », noms des critiques en entier : « IMDb **7,0** », « Sens Critique **6,2** », « Éric Schwald **8** », « Antoine Goya **7** ». Les valeurs IMDb et Sens Critique en couleur d'accent, celles des critiques en blanc. Écartée : une ligne de texte dans les infos (B).
- **Téléphone : maquette D.**
  - une ligne « Ma note **très bien** · Technique **bien** » quand l'une ou l'autre existe ;
  - une rangée de pastilles de notes, comme à la télé, avec les prénoms abrégés (« É. Schwald ») ;
  - une rangée de boutons « Sens Critique ↗ », « IMDb ↗ », « Wikipédia ↗ », « TMDB ↗ ».
  Écartée : une liste « Notes » où chaque ligne est cliquable (C).

Les maquettes rendues sont dans `.superpowers/brainstorm/` (non suivi par git) ; le rendu A a été produit sur la vraie page télé.

## 5. Conception

### 5.1 Lecture des notes et des sources

**Lignes `QS` : `QS ; code ; libellé ; URL du profil`.** Elles se trouvent dans l'en-tête du fichier, une par source. Le libellé d'un critique a la forme `Sens Critique / Prénom Nom (pseudo)` ; le nom affiché est `Prénom Nom`, c'est-à-dire la partie après « / » et avant « ( ». Un code sans ligne `QS` affiche son code.

**Lignes `MQ` : `MQ ; code ; note ; URL`.** Une ligne appartient à l'œuvre courante (dernière ligne `MN`).
- codes retenus : `IM`, `SC`, `SC@xx` (tout suffixe), `WK`, `NP`, `NT` ; les autres sont ignorés sans avertissement ;
- note : nombre décimal (`7.0`, `6.2`) ; `?`, vide ou illisible donne « pas de note » ;
- URL : champ suivant s'il commence par `http` ; sinon pas de lien ;
- même code deux fois pour une œuvre : la première ligne gagne ;
- `WK` : seule l'URL sert (bouton « Wikipédia ») ; sa note, toujours `?`, est ignorée ;
- l'identifiant IMDb continue d'être tiré du lien IMDb, comme aujourd'hui.

**Rangement.** Une liste ordonnée par œuvre : `IM`, `SC`, puis les `SC@xx` dans l'ordre du fichier, puis `WK`, `NP`, `NT`. Chaque élément : code, nom affiché, note (nombre ou nul), lien (texte ou nul). Elle entre dans l'empreinte de l'œuvre : un changement de note crée une nouvelle révision. Le premier import après la mise à jour révise donc toutes les œuvres, ce qui est voulu.

### 5.2 Notes personnelle et technique

Échelle de la légende du fichier : 1 bof, 3 pas terrible, 5 bien, 7 très bien, 9 excellent. Le téléphone affiche le mot ; une autre valeur s'affiche en chiffre (« 6 »). La télé ne les affiche jamais. Le serveur les transmet aussi à la télé dans `/api/works/{id}` (réseau local sans authentification, pas de filtrage côté serveur) : c'est la page télé, seule à consommer cette réponse sur la télé, qui les écarte.

### 5.3 Lien TMDB

Quand le serveur cherche une affiche sur TMDB (`/find/{imdb}`), il garde l'identifiant et le type (film ou série) du résultat retenu. Le lien est `https://www.themoviedb.org/movie/<id>` ou `…/tv/<id>`. Sans recherche TMDB réussie, pas de lien TMDB.

*Choix du concepteur :* une œuvre qui a déjà une affiche locale, ou dont la recherche d'affiche a eu lieu avant cette étape, n'a pas d'identifiant TMDB. Le serveur fait pour elle une recherche d'identifiant seule (`/find`, sans télécharger d'image), une seule fois par œuvre, avec un repère distinct du repère « affiche essayée ». Même cadence que la recherche d'affiche (20 œuvres par tour), après elle.

### 5.4 Affiches : provenance et priorité

La provenance de l'affiche de chaque œuvre est connue : `local`, `tmdb` ou `frame` (image extraite). *Choix du concepteur :* une colonne `poster_source` sur l'œuvre, à côté de `poster_asset` (une même image peut servir à plusieurs œuvres, et `asset.source` ne suffit pas). Les affiches existantes reçoivent `local` ou `tmdb` d'après `asset.source` (`'tmdb'` ou un chemin).
- **Affiche locale** (`AF` présente et lisible) : elle gagne toujours, comme aujourd'hui.
- **TMDB** : la recherche concerne les œuvres sans affiche ou avec une image extraite. Une affiche TMDB trouvée remplace l'image extraite. La règle actuelle « une seule tentative par œuvre » reste : une œuvre essayée sans succès ne l'est plus. Sans clé, aucune tentative n'a lieu et aucun repère n'est écrit (comportement actuel) : une œuvre jamais essayée faute de clé le sera dès que la clé est là, même si elle a reçu une image extraite entre-temps.
- **Image extraite** : seulement pour une œuvre sans affiche du tout, et dont la recherche TMDB a déjà échoué ou n'est pas possible (pas de clé, pas d'identifiant IMDb). Avec une clé fraîchement ajoutée, TMDB passe donc d'abord ; les œuvres qu'il ne trouve pas reçoivent ensuite une image extraite.

### 5.5 Extraction d'une image

- **Quand :** étape de fond, au rythme de l'analyse et suspendue pendant une lecture avec le même mécanisme qu'elle ; un fichier à la fois.
- **Quel fichier :** pour un film, sa meilleure version présente (ordre de qualité existant) ; pour une œuvre épisodique, le premier épisode dont un fichier est présent (saison puis numéro), même si des épisodes antérieurs sont sur un disque débranché. Une image extraite n'est pas refaite quand les versions changent.
- **Quel moment :** 10 % de la durée analysée. L'extraction attend que le fichier ait été analysé ; si l'analyse a échoué ou n'a pas donné de durée : 5 minutes, ou le début si le fichier est plus court.
- **Comment :** ffmpeg extrait une image, puis le traitement d'affiche existant (redimensionnement 300 et 600 px, empreinte) la range comme les autres. Échec (fichier illisible, ffmpeg absent) : journalisé, l'œuvre est écartée jusqu'au prochain index complet, comme les échecs de l'analyse.
- **Révision :** une nouvelle image crée une révision ; le téléphone la reçoit à la synchro suivante.

### 5.6 Contrat

À ajouter au document des arbitrages :
- **24.** Œuvre (`/api/sync` `works[]`, `/api/works/{id}`) : `ratings`, liste ordonnée de `{"code", "name", "value", "url"}` (`value` : nombre ou `null` ; `url` : texte ou `null`). Liste vide si rien.
- **25.** Œuvre : `tmdb_url`, texte ou `null`.
- **26.** Les affiches peuvent venir d'une image extraite de la vidéo ; l'appli et la télé les traitent comme les autres (champ `poster` inchangé).

### 5.7 Téléphone

- Base locale : nouveaux champs de l'œuvre, nouvelle version de schéma avec la migration destructive existante. Le premier lancement après la mise à jour refait une synchro complète.
- Fiche de l'œuvre : section de la maquette D, sous les infos existantes ; masquée si l'œuvre n'a ni note ni lien.
- Les boutons ouvrent le lien dans le navigateur du téléphone (intention Android standard, sans service Google).

### 5.8 Télé

- Fiche de l'œuvre : rangée de pastilles de la maquette A, entre la ligne « Réalisation » et la ligne d'état ou de reprise ; seulement `IM`, `SC` et `SC@xx` ayant une note ; absente sinon.
- La rangée tient sur une ligne dans les cas courants (quatre pastilles) ; au-delà, elle passe à la ligne et le résumé, déjà limité en hauteur, perd des lignes.
- Un aperçu `?preview=` montre une œuvre avec notes, une sans notes, et une avec beaucoup de critiques.

## 6. Risques

1. **Images extraites peu parlantes** (scène sombre). 10 % de la durée évite génériques et noir de début ; si c'est insuffisant, la règle se change sans toucher au reste.
2. **Charge sur le mini-PC.** Une extraction prend une à quelques secondes ; un fichier à la fois, jamais pendant une lecture.
3. **Format des libellés `QS`.** Un libellé sans « / » ni « ( » s'affiche entier.

## 7. Laissé au builder

- Forme du stockage des notes en base (colonne JSON sur l'œuvre, à la manière de `cast_json`, ou table à part) et des champs TMDB.
- Emplacement exact de l'étape d'extraction dans les boucles de fond existantes.
- Détails visuels fins (espacements, tailles) dans l'esprit des maquettes A et D.
