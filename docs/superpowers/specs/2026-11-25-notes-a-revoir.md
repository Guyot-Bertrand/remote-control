# Notes à revoir plus tard (après l'itération 2)

*Points relevés pendant les essais du 2026-11-25. Rien d'urgent : jugés acceptables pour l'instant par l'utilisateur.*

## Latence à la reprise d'une vidéo mise de côté

**Constat.** En mode `pause_background = "restart"`, la reprise relance mpv. Il y a une petite latence avant que la vidéo reparte : le temps que mpv démarre, ouvre le fichier et se place à la position. Le clignotement est corrigé : la télé garde l'image de la pause en plein écran pendant ce temps.

**Pistes, si on y revient :**
- mode `minimize`, si la machine cible sait ramener la fenêtre au premier plan : pas de relance, reprise immédiate ;
- lancer mpv avec un cache disque ou des options de démarrage plus rapides (`--demuxer-readahead-secs`, `--hr-seek=no` pour une reprise approximative) ;
- garder mpv en vie mais caché d'une autre façon (fenêtre déplacée hors écran, ou bascule par le gestionnaire de fenêtres du kiosque).
