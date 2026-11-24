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
