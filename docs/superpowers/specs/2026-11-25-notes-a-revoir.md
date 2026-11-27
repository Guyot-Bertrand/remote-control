# Notes à revoir plus tard (après l'itération 2)

*Points relevés pendant les essais du 2026-11-25. Rien d'urgent : jugés acceptables pour l'instant par l'utilisateur.*

## Latence à la reprise d'une vidéo mise de côté — résolue (étape 3)

**Résolu le 2026-11-26.** Le mode `hide`, défaut depuis l'étape 3, ferme la fenêtre de mpv sans l'arrêter (`vid=no`) et la rend à la reprise : reprise quasi immédiate. `restart` reste disponible.

*Constat d'origine :*

**Constat.** En mode `pause_background = "restart"`, la reprise relance mpv. Il y a une petite latence avant que la vidéo reparte : le temps que mpv démarre, ouvre le fichier et se place à la position. Le clignotement est corrigé : la télé garde l'image de la pause en plein écran pendant ce temps.

**Pistes, si on y revient :**
- mode `minimize`, si la machine cible sait ramener la fenêtre au premier plan : pas de relance, reprise immédiate ;
- lancer mpv avec un cache disque ou des options de démarrage plus rapides (`--demuxer-readahead-secs`, `--hr-seek=no` pour une reprise approximative) ;
- garder mpv en vie mais caché d'une autre façon (fenêtre déplacée hors écran, ou bascule par le gestionnaire de fenêtres du kiosque).

## Affiches pour la vraie installation (décidé le 2026-11-26)

**Constat.** Le vrai `medias.txt` n'a aucune ligne `MF ; AF` ; environ 90 % des œuvres ont un lien IMDb (ligne `MQ`). Sans clé TMDB, toutes les œuvres auraient l'affiche de remplacement.

**Décisions de l'utilisateur :**
- **Installation sur le mini-PC :** renseigner `tmdb_api_key` dans `config.toml` fait partie des étapes (clé gratuite themoviedb.org, accès Internet au moins au début, clé jamais dans un dépôt public).
- **Extraction automatique d'une image de la vidéo** (comme `~/rc-test/preparer.py`, ffmpeg) pour les œuvres sans affiche.
- **Priorité :** affiche locale (`AF`) > TMDB > image extraite. Si la clé TMDB est ajoutée après coup, l'affiche TMDB **remplace** l'image extraite (l'extraction ne doit donc pas marquer l'œuvre comme « pourvue » vis-à-vis de TMDB ; garder la source de chaque affiche, par ex. colonne `asset.source`).

## Notes et liens des œuvres (décidé le 2026-11-26)

**Constat.** Le serveur ne garde des lignes `MQ` que l'identifiant IMDb (pour TMDB). Format : `MQ ; code ; note/10 (ou ?) ; URL`. Les sources sont décrites dans les lignes `QS ; code ; libellé ; URL du profil` du `medias.txt` (ex. `SC@xx` = note d'un critique Sens Critique nommé dans la ligne `QS`).

**Décisions de l'utilisateur :**
- **Afficher les notes** sur la fiche de l'œuvre (téléphone et télé), ex. « IMDb 7,0 · Sens Critique 6,2 », plus les notes des critiques (`SC@xx`), avec le nom tiré de la ligne `QS`, et la note personnelle (`NP`) et la note technique (`NT`).
- **Liens** (téléphone seulement, ouverts dans le navigateur) : Sens Critique (`SC`, `SC@xx`), IMDb (`IM`), Wikipédia (`WK`) ; TMDB si possible (lien absent du fichier : à construire depuis l'identifiant TMDB obtenu lors de la recherche d'affiche).
- **Pour plus tard :** `WB` (web), `CP` (Le cinéma est politique), liens YouTube et autres vidéos en ligne.
