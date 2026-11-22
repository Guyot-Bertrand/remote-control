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
