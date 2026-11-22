# Remote-Control — Document de conception

*Date : 2026-11-22 · Statut : brouillon, en attente de validation*

## 1. Intention

Un mini-PC basse consommation branché en HDMI sur une télé sert de **lecteur multimédia de salon** pour une collection personnelle d'environ 4 000 œuvres : films, documentaires, séries, théâtre, téléfilms et spectacles, stockées sur des disques externes. Un **téléphone Android sert de télécommande** : il permet de parcourir la collection, d'afficher une fiche sur la télé, puis de lancer et contrôler la lecture.

En complément (phase ultérieure), des **fiches imprimées** (une par œuvre, ou une par saison pour les séries) portent un QR-code. Scanner ce QR-code avec la télécommande ouvre directement l'œuvre.

**Pourquoi construire soi-même :** Kodi et Jellyfin ont été écartés. Ils sont trop complexes à mettre en place, ont trop d'options, et affichent les infos des films en anglais malgré un réglage en français. Le système doit rester **léger**, **simple à installer** et **entièrement en français**.

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs**
- Parcourir rapidement une collection de plusieurs milliers d'œuvres depuis le téléphone.
- Afficher sur la télé une fiche complète : affiche, titre VF et VO, année, réalisateur, durée, genre, casting, synopsis, collections.
- Lancer la lecture et la contrôler : lecture/pause, retour au début, recul et avance rapides, saut à une position, volume, pistes audio et sous-titres.
- Reprendre une œuvre là où on s'était arrêté.
- Séries : naviguer par saison, voir le dernier épisode vu, choisir un épisode et le démarrer au début ou le reprendre.
- Ouvrir une œuvre en scannant un QR-code (le scan est disponible dès la v1, même si les fiches imprimées viennent plus tard).

**Non-objectifs (pour l'instant)**
- Profils utilisateurs ou comptes : un seul suivi de visionnage pour toute la maison.
- Accès depuis l'extérieur du réseau local, streaming vers le téléphone.
- Édition du catalogue depuis l'appli. `medias.txt` reste maintenu par son auteur, avec ses propres outils.
- Génération des PDF de fiches : ce sera une phase 2, une fois le format arrêté. Voir §11.
- Musique et fichiers audio (`AD`).

**Anti-objectifs (échec même si « ça marche »)**
- Des écrans de réglages ou d'options à parcourir sur la télé.
- Une installation en plusieurs étapes manuelles ou qui demande de configurer une adresse IP.
- Des infos en anglais affichées à la place des infos françaises sans prévenir.
- Une interface poussive sur le mini-PC.

## 3. Contraintes

- **Mini-PC :** une machine basse consommation de plusieurs années, au modèle inconnu, qui n'est pas disponible pour des tests à court terme. Linux.
- **Téléphones :** un Murena (/e/OS, Android sans services Google) et un Samsung Galaxy S21 FE. Version minimale visée : **Android 10 (API 29)**. **Aucune dépendance aux Google Play Services** : ni ML Kit non embarqué, ni Firebase, ni Play Store. Distribution par APK.
- **Volume :** environ 4 000 œuvres, environ 5 300 fichiers vidéo, environ 3 600 affiches.
- **Stockage :** un ou plusieurs disques externes, qui peuvent être branchés ou débranchés à tout moment.
- **Écrans :** ils seront conçus avec Claude Design, qui produit du HTML/CSS.

## 4. Approches envisagées

| | A. Page web plein écran + mpv **(retenue)** | B. Appli native Qt/QML + libmpv intégré |
|---|---|---|
| Interface télé | Page HTML dans un navigateur en mode kiosque (Chromium, ou WPE WebKit en repli) | QML |
| Vidéo | mpv en plein écran, piloté par son interface IPC JSON | libmpv intégré à l'appli |
| Pour | Les maquettes Claude Design sont réutilisables presque telles quelles, développement rapide, même technologie que les futures fiches PDF | Transitions fluides, interface superposable à la vidéo, plus économe |
| Contre | Court flash possible entre interface et vidéo, pas d'interface par-dessus la vidéo | Maquettes à retraduire à la main, développement plus long, installation plus délicate |

**Choix : A**, retenue par l'utilisateur « dans un premier temps ». Les critères décisifs sont la légèreté du développement et la réutilisation directe des maquettes. Une option C (box Android TV) a été écartée parce que le mini-PC est imposé.

**Performance (point de vigilance accepté) :** l'interface web est légère si l'on respecte quelques règles : rendu en 1080p même sur une télé 4K, pas de `backdrop-filter`, pas de flou dynamique, animations limitées à `transform` et `opacity`, affiches servies déjà redimensionnées, liste virtualisée. Le **vrai risque matériel est le décodage vidéo** : une puce ancienne peut ne pas décoder en matériel le HEVC/H.265, surtout en 10 bits. Voir §12.

## 5. Architecture

```
┌──────────────── Mini-PC (Linux) ─────────────────┐        ┌──── Téléphone Android ────┐
│                                                  │        │                           │
│  rc-server (Python, FastAPI, un seul service)    │  HTTP  │  Appli Kotlin / Compose   │
│   ├─ import de medias.txt → SQLite               │◄──────►│   ├─ cache local (Room)   │
│   ├─ index des disques (UUID → points de montage)│   +    │   ├─ cache images (Coil)  │
│   ├─ API REST + WebSocket                        │  WS    │   ├─ scan QR (ZXing)      │
│   ├─ annonce mDNS (_rc._tcp)                     │        │   └─ découverte NSD/mDNS  │
│   ├─ pilote mpv (IPC JSON sur socket Unix)       │        └───────────────────────────┘
│   └─ sert l'UI télé (fichiers statiques)         │
│                                                  │
│  Session graphique kiosque (cage + navigateur)   │
│   └─ http://localhost:PORT/tv  ◄── WS état ──────┤
│  mpv (lancé/piloté par rc-server, plein écran)   │
└──────────────────────────────────────────────────┘
```

- **Un seul service** `rc-server` (systemd) concentre toute la logique. L'UI télé et l'appli Android ne sont que des clients.
- **L'UI télé est passive :** elle affiche l'état que le serveur lui pousse par WebSocket (accueil, fiche d'une œuvre, saison, lecture en cours) et n'a aucune interaction propre.
- **Session kiosque :** démarrage automatique, sans bureau, avec un compositeur Wayland kiosque (`cage` ou équivalent) qui lance le navigateur sur `/tv`. mpv s'affiche par-dessus pendant la lecture. Le mécanisme exact de passage au premier plan est laissé au builder, à valider sur la machine.

## 6. Données

### 6.1 Source : `medias.txt` (référence)

Format maison en lignes `CODE ; champ ; champ …`, documenté dans l'en-tête du fichier (version 1.11). Des exemples se trouvent dans `docs/data/` (non versionné, données personnelles). Les éléments utiles :

- `MN` : type (`film`, `tele`, `serie`, `theatre`, `spectacle`, `doc`), **clé unique** (`Titre [année]`), titre français, titre original (`*` = identique), titre alternatif.
- `MI` : durée (pour une série : `épisodes/total`), année, réalisateur(s), genre(s).
- `MC` : casting. `MP` : synopsis.
- `MD` : arborescence. Niveau 0 : catégorie (`Films`, `Series`, `Docs`…). Niveau 1 : sous-dossier (lettre `A`…`Z`, ou `*` = dossier nommé d'après la clé de l'œuvre). Niveau 2 : saison (`Saison 01 ; Saison 1`). Une ligne `MD ; n ;` vide ferme le niveau.
- `MF ; AF` : affiche (fichier image). `MF ; VI` : vidéo (qualité, taille, nom de fichier, sous-titres externes ; `*.1.fr.srt` désigne un fichier de même base). **Une œuvre peut avoir plusieurs versions vidéo** (FHD et HD par exemple). `MF ; TG` : tags (`[FR]`, `[VO]`, `[NB]`, `[ST*]`…).
- `MQ` : notes et liens (IMDb `tt…`, Sens Critique, Wikipédia). `MG ; rang-ou-année ; nom du groupe` : appartenance à des collections. *Attention : la légende documente `GD`/`GG`, mais les données utilisent en pratique `GN`/`GD`/`GL`/`GM`. L'analyseur suit les données réelles.*
- Des documentaires (et d'autres types) ont des épisodes, parfois des saisons au niveau 3. **Toute œuvre qui possède des épisodes est traitée comme épisodique**, quel que soit son type. Les épisodes sans motif `SxxEyy` (par exemple `Dix petits negres - 1`) sont numérotés dans l'ordre d'apparition.
- Les qualités `TS`, `??`, `NO`, `DVD`, `BR`, `DVR`, `DVT`, `DVS` ne correspondent **pas** à un fichier lisible : support physique, ou œuvre à chercher ou à ignorer.

**Import :** analyse en lecture seule vers SQLite, **idempotente**, relancée quand le fichier change (surveillance de la date de modification, ou commande manuelle). Les identifiants internes sont stables, pour que les QR-codes et la progression survivent aux réimports. *Choix du concepteur :* l'identifiant est un entier attribué à la première apparition du couple **(type, clé `MN`)**. Le type est nécessaire parce que la clé n'est pas unique à elle seule : `Le prenom [2012]` existe à la fois comme film et comme pièce de théâtre. L'attribution est conservée en base et n'est jamais réattribuée. Quand l'auteur corrige une clé, par exemple `Cristie` en `Christie`, un petit fichier d'alias sur le PC (`ancienne clé → nouvelle clé`) conserve l'identifiant. Sans alias, l'œuvre corrigée reçoit un nouvel identifiant et l'ancien est signalé comme orphelin dans le journal d'import. Les lignes non reconnues sont journalisées, jamais bloquantes. `medias_liste.txt` et `medias_liste.html` sont des exports dérivés : ils ne sont pas importés.

### 6.2 Modèle (SQLite, sur le PC)

- `work` : id stable, clé, type, titres (VF, VO, alternatif), année, durée, réalisateurs, genres, casting, synopsis, imdb_id, tags, `poster_asset_id`, `updated_at`.
- `season` : id stable (clé de l'œuvre + numéro), work_id, numéro, libellé.
- `episode` : id, season_id, numéro, titre (s'il est présent dans le nom de fichier), durée.
- `media_file` : work_id ou episode_id, qualité, taille, chemin relatif, sous-titres, `volume_id`, présence.
- `collection` et `work_collection` (issus de `MG` et `GD`/`GG`).
- `volume` : UUID du système de fichiers, libellé, dernier point de montage connu, présent ou absent.
- `progress` : media cible (film ou épisode), position, durée, `watched`, `updated_at`.
- `asset` : image et ses déclinaisons redimensionnées, avec un hash du contenu.
- `change_log` : numéro de révision croissant par modification (pour la synchronisation).

### 6.3 Localisation des fichiers

Le chemin relatif se déduit de `MD` et `MF ; VI` (exemple : `Films/A/Ava [2017] [1h45,…].mp4`). Au démarrage, puis à chaque branchement de disque (événement udev ou scan périodique léger), le serveur cherche ces chemins relatifs à la racine de chaque volume monté, et enregistre quel volume contient quoi. **Le nommage réel sur disque n'est pas encore confirmé :** l'import doit isoler cette règle de correspondance dans un module unique et facile à adapter. Une œuvre sans fichier présent reste visible, avec la mention « disque non branché » et le libellé du disque attendu quand il est connu, ou « non numérisé » quand elle n'a aucun fichier vidéo.

**Choix de la version :** la meilleure qualité présente (UHD > 2HD > FHD > HD > SD > BD), avec possibilité d'en choisir une autre sur la fiche.

### 6.4 Images

Les affiches `MF ; AF` sont cherchées à côté des vidéos, puis copiées et redimensionnées sur le PC lors de l'import : vignette d'environ 300 px de large pour le téléphone, environ 600 px pour la télé, originale conservée. Une fois copiées, elles restent disponibles même si le disque est débranché. **En secours seulement**, quand une affiche est absente, une recherche TMDB par identifiant IMDb exact (`/find/{imdb_id}`, en langue `fr-FR`) peut fournir une image. Ce secours est désactivable, et il ne remplace jamais un texte du catalogue.

## 7. API et synchronisation

- **Découverte :** le serveur s'annonce en mDNS (`_rc._tcp`). L'appli le trouve avec NSD, avec en secours une adresse saisie à la main une fois.
- **Synchronisation incrémentale :** `GET /api/sync?since=<révision>` renvoie les œuvres, saisons, épisodes, collections et progressions modifiés ou supprimés depuis cette révision, ainsi que la nouvelle révision. Un premier appel sans `since` renvoie tout (environ 4 000 œuvres, quelques Mo compressés). L'appli stocke le tout dans Room.
- **Images :** `GET /api/assets/{id}?size=thumb|tv|orig`, avec des URL contenant le hash et un `Cache-Control: immutable`. L'appli les met en cache disque (Coil) : chaque image n'est téléchargée qu'une fois.
- **Commandes :** `POST /api/tv/show` (fiche d'une œuvre ou d'une saison), `POST /api/play` (média, `from=start|resume`, version), `POST /api/player/{pause|resume|seek|stop|volume|audio|subtitle}`.
- **État en direct :** `WS /api/events` pousse l'état du lecteur (média, position, durée, pause, pistes disponibles) et les changements de catalogue (« nouvelle révision ») à l'appli comme à l'UI télé.
- **Sécurité :** réseau local seulement, sans authentification en v1 (choix assumé pour la simplicité, à rediscuter si besoin).

## 8. Lecture et suivi

- mpv est lancé par le serveur avec `--input-ipc-server`, en plein écran, avec décodage matériel `--hwdec=auto-safe`. Les pistes audio et sous-titres sont préférées en français (`--alang=fr,fre,fra`, `--slang=fr,fre,fra`), et les sous-titres externes sont chargés.
- **Pistes audio et sous-titres, avant et pendant la lecture.** Sur la fiche, avant de lancer, on peut choisir la piste audio et les sous-titres. Pendant la lecture, la télécommande permet d'en changer à chaud. Les sous-titres proposés sont : les fichiers externes posés à côté de la vidéo (`.srt`, `.vtt`, `.ass`…, déclarés dans le catalogue **et** détectés sur le disque par le nom de base de la vidéo), les pistes intégrées au fichier vidéo, et « aucun ». Avant la lecture, la liste des pistes intégrées est lue sur le PC (`ffprobe`) puis mise en cache par fichier. Par défaut : audio français si disponible, sinon VO, et sous-titres français quand l'audio n'est pas en français. Le dernier choix fait pour une œuvre est retenu pour la reprise.
- **Film, documentaire, théâtre…** : action par défaut **« Lire depuis le début »**. Si une progression existe, un bouton secondaire propose **« Reprendre à hh:mm »**.
- **Séries** : la fiche de saison liste les épisodes, met en évidence le dernier épisode vu ou entamé, et propose pour chacun « Depuis le début » ou « Reprendre ».
- La position est enregistrée **toutes les 10 secondes environ** pendant la lecture, plus à la pause et à l'arrêt. Au-delà de 90 % de la durée, le média est marqué **vu** et sa position de reprise est effacée.
- Un seul suivi pour toute la maison. La progression mémorise **la version lue**, car les versions d'une même œuvre peuvent avoir des durées différentes (Ava : 1h45 et 1h39). « Reprendre » relance cette version.
- Si quelqu'un parcourt la collection sur le téléphone pendant une lecture, **la télé reste sur la vidéo**. La fiche consultée ne s'affiche sur la télé qu'à l'arrêt, ou sur demande explicite.
- Fin d'un épisode : pas d'enchaînement automatique en v1 (choix laissé au builder ; une proposition « épisode suivant » sur la télé est bienvenue).

## 9. Interface télé (passive)

États affichés, à concevoir avec Claude Design :
1. **Veille / accueil** : un écran calme (dernières œuvres vues, ou affiche aléatoire), qui ne dépend d'aucune interaction.
2. **Fiche œuvre** : affiche, titres VF et VO, année, réalisateur, durée, genre, casting, synopsis, collections, versions disponibles, état du disque, progression.
3. **Fiche saison** : en-tête de la série, liste des épisodes, épisode mis en évidence.
4. **Lecture** : mpv en plein écran. Une indication brève sur la vidéo, comme le titre et la barre de progression via l'OSD de mpv, est permise.
5. **Message** : « Disque X non branché », « Fichier introuvable »…

*Choix du concepteur :* les maquettes, télé **et** téléphone, seront faites ensemble avec Claude Design, au fil du projet. Le builder démarre avec des écrans provisoires, sobres mais fonctionnels, et des gabarits faciles à remplacer. Les maquettes télé seront réutilisées presque telles quelles (HTML/CSS). Celles du téléphone serviront de référence visuelle et seront retranscrites en Compose.

Contraintes de conception à transmettre à Claude Design : rendu en 1920×1080, lisible à 3 m, pas de flou dynamique ni d'effets lourds.

## 10. Appli Android

- **Écrans :** accueil (reprendre, récemment ajoutés, collections), parcours par type (films, séries, documentaires, théâtre…), recherche (titres VF, VO et alternatif, réalisateur, casting ; recherche plein texte locale, sans réseau), fiche œuvre, fiche saison, télécommande (lecture en cours), scan QR, réglages minimaux (serveur détecté, resynchroniser).
- **Filtre par défaut :** seulement les œuvres lisibles (fichier présent), avec un bascule « tout le catalogue ».
- Choisir une œuvre l'affiche **en même temps** sur le téléphone et sur la télé.
- **Télécommande :** lecture/pause, retour au début, recul et avance (±10 s, ±60 s), barre de position déplaçable, volume, choix des pistes audio et sous-titres, arrêt.
- **Technologies :** Kotlin, Jetpack Compose, Room, Coil, Ktor ou OkHttp (WebSocket), CameraX + ZXing pour le scan, sans aucun service Google.
- Fonctionne hors ligne pour la navigation (cache). Seules les commandes ont besoin du PC.

## 11. QR-codes et fiches imprimées

- **Contenu du QR-code (fixé dès la v1) :** `rc://w/<id>` pour une œuvre, `rc://s/<id>` pour une saison, où l'identifiant stable est dérivé de la clé du catalogue. Scanné avec l'appli, il ouvre la fiche sur le téléphone et sur la télé. Scanné avec un autre lecteur, il n'affiche que du texte.
- **Phase 2 (non spécifiée) :** génération de PDF A4, 9 fiches par page (3×3), recto-verso (affiche et titre au recto, infos et QR-code au verso, verso en miroir), sur une imprimante grand public. Le format, l'interaction (où et comment on sélectionne les œuvres à imprimer) et le moteur de rendu (WeasyPrint pressenti) seront décidés plus tard.

## 12. Risques

1. **Décodage vidéo sur la machine ancienne** (principal) : environ 4 000 fichiers mp4, dont une part en FHD et peut-être en HEVC. Mesure : un script `rc-doctor` livré tôt vérifie `vainfo` et les codecs pris en charge, puis lit quelques fichiers échantillons et mesure les images perdues. Si nécessaire, on choisit une version de moindre qualité quand le matériel ne suit pas.
2. **Nommage réel des fichiers sur disque** inconnu : la règle de correspondance est isolée dans un module unique (voir §6.3).
3. **Évolution du format `medias.txt`** (déjà en version 1.11) : l'analyseur est tolérant et journalise les lignes inconnues.
4. **Passage de l'interface à la vidéo dans la session kiosque** : à valider sur la machine, avec repli possible sur une autre combinaison compositeur/navigateur.

## 13. Hypothèses provisoires (en attente de confirmation)

Le travail avance sans ces réponses. Chaque hypothèse est isolée pour pouvoir changer sans reprise.

- **Arrivée de `medias.txt` :** il est copié à la racine d'un disque externe et relu à chaque branchement ou modification. Le chemin de la source est un simple paramètre, et l'import se déclenche aussi par une commande manuelle. Changer de mode d'arrivée (envoi depuis l'appli, partage réseau) ne touche que ce point d'entrée.
- **Système du mini-PC :** Debian 13 sur x86_64, installée à neuf et dédiée au projet. L'installation et la session kiosque arrivent **en fin de plan**. Avant cela, tout se développe et se teste sur un PC Linux ordinaire, avec mpv et un navigateur en fenêtre.
- **Nommage sur disque :** les chemins sont déduits du catalogue (§6.3), en attendant une liste réelle des fichiers. Des tests sur une arborescence factice générée à partir de `medias.txt` remplacent les vrais disques.

## 14. Laissé au builder

- Structure du dépôt (proposition : `server/`, `tv-ui/`, `android/`, `docs/`), outils de build, tests.
- Choix précis du compositeur kiosque et du navigateur, ainsi que la méthode de passage au premier plan de mpv.
- Détails de l'API (formats JSON), schéma SQLite exact, stratégie de migration.
- Pas d'avance et de recul, intervalle d'enregistrement de la position (ordres de grandeur donnés ci-dessus).
- Installation : un script unique ou un paquet qui installe le service, la session kiosque et les dépendances (mpv, navigateur).
