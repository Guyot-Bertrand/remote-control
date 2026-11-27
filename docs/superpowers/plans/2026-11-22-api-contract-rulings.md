## Contrat API — arbitrages (2026-11-22)

Complète la Task 10 du plan serveur ; fait foi pour le serveur et l'appli Android.

1. `/api/sync` : champs décodés **sans suffixe** (`directors`, `genres`, `cast`, `tags`, `subtitles`) ; `episodic`, `present`, `watched` et `default` sont de vrais booléens JSON.
2. `work_collections` : pour chaque œuvre renvoyée, la liste complète de ses appartenances (`{work_id, collection_id, rank}`) ; le client remplace les liens de cette œuvre.
3. `deleted` contient les clés `work`, `season`, `episode`, `media_file`, `collection`, y compris les enfants supprimés avec une œuvre. Les volumes et les progressions ne sont jamais supprimés.
4. « Récemment ajoutés » = identifiant d'œuvre décroissant (identifiants AUTOINCREMENT, jamais réutilisés).
5. `current` = `{target_kind, target_id, media_file_id, work_id, season_id (ou null), title}`. Dans `hello`, `player` a la même forme que `GET /api/player/state` (`{state, current}`). Les pistes se lisent via `GET /api/media/{media_file_id}/tracks`.
6. La requête de lecture utilise le champ `start` (`"start"`/`"resume"`). Toutes les erreurs 409 ont la forme `{"error": code, "volume_label": str|null}`. Un sous-titre externe est renvoyé tel que fourni par `/tracks`, avec son `path` absolu côté serveur.
7. `progress.audio` et `progress.subtitle` : le serveur les renvoie décodés (`audio`: int|null, `subtitle`: SubSel|null). Le client peut les ignorer : sans choix explicite, le serveur réapplique de lui-même le dernier choix.
8. `GET /api/media/{id}/tracks` → `default` reflète d'abord le dernier choix enregistré dans `progress` pour la cible (s'il existe et que la piste existe encore), sinon `default_selection`. Le client envoie donc toujours explicitement les pistes affichées.

## Ajouts (2026-11-24, retours d'essai)

9. Toutes les réponses d'erreur 409 (et 403, 404, 405, 422, 500 sous `/api/`, au même format `{error, volume_label, message}`) portent en plus `"message"` : une phrase en français prête à afficher (ex. `{"error":"player_missing","volume_label":null,"message":"Le lecteur vidéo (mpv) n'est pas installé sur le PC."}`). Le client affiche son propre texte pour les codes connus, et `message` pour un code inconnu.
10. Nouveau code 409 `player_missing` : l'exécutable du lecteur (mpv) est introuvable sur le PC. `player` reste pour « le lecteur ne répond pas / n'a pas démarré ».
11. `GET /api/info` ajoute `"player_available": bool` (exécutable du lecteur présent sur le PC).
12. `POST /api/play` sur le média déjà en cours de lecture ne relance pas le lecteur : `start` revient au début (0), `resume` garde la position en cours (aucun saut) ; le serveur applique les pistes demandées et lève la pause, sans fermer la vidéo. Si la lecture se termine pendant ce traitement, le lecteur est relancé normalement.

## Ajouts de l'itération 2 (2026-11-25)

*Conception : `docs/superpowers/specs/2026-11-24-iteration-2-design.md`. Ces arbitrages font foi pour le serveur, l'écran télé et l'appli.*

13. **`/api/sync`, nouveaux champs** (toujours présents, `null` si inconnus) :
    - `works[].medium` : `"Blu-ray"`, `"DVD"`, `"DVD (copie)"`, `"DVD (TNT)"`, `"DVD (satellite)"` ou `null`. Vaut `null` dès que l'œuvre (ou l'un de ses épisodes) a un fichier lisible au catalogue. Correspondance des codes : `BR`→Blu-ray, `DVD`→DVD, `DVR`→DVD (copie), `DVT`→DVD (TNT), `DVS`→DVD (satellite). Si plusieurs codes : le premier dans cet ordre.
    - `media_files[]` : `audio_langs` (liste de codes, ordre des pistes, sans doublon, langues inconnues omises), `subtitle_langs` (idem ; pistes intégrées puis fichiers externes), `width`, `height` (entiers), `duration_s` (réel). Tous `null` tant que le fichier n'a pas été analysé.
    - `episodes[].duration_min` : entier arrondi, d'après le fichier analysé de l'épisode (le mieux classé s'il y en a plusieurs), sinon `null`.
    - Réponse complète (`full: true`) seulement : `total_works`, nombre d'œuvres de la réponse.
14. **`POST /api/tv/view`** — la vue affichée par le téléphone. Corps :
    ```json
    {"phone_id": "3f2a…", "seq": 1764090000123,
     "screen": "home|browse|collection|search|work|season|preplay|remote",
     "title": "Films", "subtitle": "2 552 films · Titre", "filter": "playable|all|null",
     "query": "tchernia",
     "work_id": 12, "season_id": 3, "episode_id": 45, "media_file_id": 7, "collection_id": 4,
     "audio_track": 1, "subtitle_track": {"kind": "external", "path": "…"},
     "items": [{"kind": "work", "id": 101}],
     "anchor": 101,
     "position": {"letter": "M", "index": 1240, "total": 2552},
     "sections": [{"label": "Personnes", "people": [{"name": "Pierre Tchernia", "role": "Réalisation", "count": 1}], "items": []},
                  {"label": "Œuvres", "people": [], "items": [{"kind": "work", "id": 101}]}]}
    ```
    - Seuls `phone_id` (1 à 64 caractères), `seq` (entier ≥ 0) et `screen` sont obligatoires ; tout le reste est facultatif (`null`, liste vide).
    - `items` : au plus 40 ; `sections` : au plus 6, au plus 40 éléments chacune. `kind` ∈ `work`, `episode`, `collection`. Au-delà, ou forme invalide : 422 `invalid`.
    - `seq` : strictement croissant par `phone_id` (l'appli utilise l'heure en millisecondes, forcée croissante). Une vue de `seq` inférieur ou égal au dernier reçu de ce téléphone est ignorée.
    - Réponse : `{"accepted": true|false}` (`false` : vue périmée).
    - La vue `remote` n'a pas d'en-tête ni d'éléments : elle signale seulement que la télécommande est ouverte.
15. **État télé élargi** (`GET /api/tv/state`, événement `{"type":"tv","state":…}`, et `hello.tv`) :
    ```json
    {"screen": "home|work|season|view|playing|paused|message",
     "work_id": null, "season_id": null, "message": null,
     "view": {…la vue reçue, avec items et sections.items enrichis…} | null,
     "pause": {"image": "/api/player/pause.jpg?v=3", "title": "Heroes — S01E05",
               "position": 1394.0, "duration": 2490.0, "work_id": 12} | null}
    ```
    - `view` : la télé reproduit la vue du téléphone. Avec `pause` non nul, c'est l'interface avec la vidéo en pause dans le coin d'état.
    - `paused` : télécommande ouverte pendant une pause, image en plein écran ; `pause` est non nul.
    - `pause.image` est `null` si la capture a échoué.
    - `home`, `work`, `season` (veille et fiches de `tv/show`) restent pour la compatibilité.
16. **Éléments enrichis** (dans `view.items` et `view.sections[].items`, même ordre que reçu ; un identifiant inconnu est omis) :
    - `{"kind":"work","id","type","title_fr","title_vo","year","poster","playable","watched","progress_ratio","remaining_min","absent_volume","medium","rank"}` — `rank` : rang dans `view.collection_id` (texte, `null` hors collection) ; `absent_volume` : libellé du disque non branché qui porte les fichiers de l'œuvre, sinon `null`. Ajout (revue de la Task 12) : l'œuvre porte aussi `directors` (liste de noms, éventuellement vide) et `title_alt` (titre alternatif, `null` s'il n'y en a pas).
    - `{"kind":"episode","id","work_id","season_id","work_title","season_number","number","title","poster","watched","progress_ratio","remaining_min"}` (`poster` : celui de la série).
    - `{"kind":"collection","id","name","count","playable_count","posters"}` (`posters` : au plus 3 affiches de ses œuvres, par rang).
17. **Qui pilote la télé** : le téléphone qui a envoyé la dernière vue acceptée. Les commandes de lecture ne changent rien.
    - Pendant une lecture **non en pause**, une vue est mémorisée sans être affichée.
    - En pause, une vue autre que `remote` fait passer la télé en `view` avec `pause`. La vidéo est alors mise de côté (capture, puis mpv en arrière-plan), et l'écran réapparaît en plein écran à la reprise.
    - Une vue `remote` pendant que la vidéo est mise de côté donne `paused`.
    - En fin de lecture, la télé montre la dernière vue reçue (ou la dernière fiche de `tv/show`).
    - Sans vue reçue depuis 10 minutes et hors pause, la télé revient à la veille (`home`).
18. **`GET /api/player/pause.jpg`** : la dernière capture (JPEG, `Cache-Control: no-store`), 404 `not_found` s'il n'y en a pas. Le paramètre `v` sert seulement à forcer le rechargement.
19. **Vidéo mise de côté** : l'événement `player` garde `running: true, paused: true` et la position de la pause. Pour le mécanisme `restart` :
    - les commandes `seek`, `volume`, `audio`, `subtitle` sont acceptées (200) et appliquées à la relance ;
    - `pause` `false` (ou une demande de lecture du même média) relance la vidéo ;
    - `stop` termine la lecture.

## Ajouts (2026-11-26, étape 3)

20. **`pause.detail`** (état télé, objet `pause`) : texte prêt à afficher, ou `null`. Le serveur le compose à partir du fichier en lecture :
    - épisode : « titre de l'épisode · qualité résolution », par exemple « Hiros · SD 624 × 352 » ;
    - film : « qualité résolution », par exemple « FHD 1920 × 1080 » ;
    - la qualité est le code du catalogue (`media_file.quality`), la résolution vient de l'analyse (`width` × `height`, avec une espace fine insécable U+202F de chaque côté du « × ») ;
    - une partie inconnue (titre d'épisode vide, résolution non analysée ou nulle) est omise ; `detail` vaut `null` si tout l'est.
    La télé l'affiche sous le titre, en plein écran (`paused`) seulement.
21. **`sections[].kind`** (vue du téléphone, `POST /api/tv/view`) : `resume`, `recent`, `collections`, `people`, `works` ou `aka`, facultatif (`null` accepté). Toute autre valeur donne 422 `invalid`. Le serveur le transmet tel quel dans `view.sections`. La télé choisit la mise en page de la section selon `kind`, et sans `kind` selon la règle précédente (titre et contenu).
    - accueil : « Reprendre » → `resume`, « Récemment ajoutés » → `recent`, « Collections » → `collections` ;
    - recherche : « Personnes » → `people`, « Œuvres » → `works`, « Aussi connu sous » → `aka`.
22. **`POST /api/tv/show` est retirée** (404). Les écrans `home`, `work` et `season` de l'état télé restent : la veille utilise `home`.
23. **`pause_background`** (config serveur) vaut `hide` par défaut : la piste vidéo de mpv est retirée pendant la pause (fenêtre fermée, mpv lancé et en pause), puis rendue à la reprise. Les commandes `seek`, `volume`, `audio`, `subtitle` et `pause` vont directement à mpv, comme en `minimize`. Valeurs : `hide`, `restart`, `minimize` ; une valeur inconnue donne `hide`.

## Ajouts (2026-11-27, étape 4)

24. **`ratings`** (œuvre, dans `/api/sync` `works[]` et `/api/works/{id}`) : liste ordonnée de `{"code", "name", "value", "url"}`, vide si rien.
    - `code` : `IM`, `SC`, `SC@xx` (un par critique), `WK`, `NP`, `NT`, dans cet ordre (les `SC@xx` dans l'ordre du fichier) ;
    - `name` : nom affiché — `IMDb`, `Sens Critique`, `Wikipédia`, `Note personnelle`, `Note technique`, et pour `SC@xx` le nom du critique tiré de la ligne `QS` (`Sens Critique / Prénom Nom (pseudo)` → `Prénom Nom`) ; à défaut le code ;
    - `value` : nombre (`7.0`) ou `null` (`?`, vide, illisible) ; `url` : texte commençant par `http` ou `null`.
    - Un même code deux fois : la première ligne gagne. Les autres codes sont ignorés.
    - La télé n'affiche jamais `NP` ni `NT` ; elle n'affiche que `IM`, `SC`, `SC@xx` ayant une valeur.
25. **`tmdb_url`** (œuvre) : `https://www.themoviedb.org/movie/<id>` ou `…/tv/<id>`, ou `null`.
26. **Affiches extraites** : `poster` peut désigner une image extraite de la vidéo ; aucun changement de forme. Priorité : affiche locale > TMDB > image extraite.
