# Remote-Control — Itération 2 — Document de conception

*Date : 2026-11-24 · Statut : validé par l’utilisateur le 2026-11-25 · S'appuie sur le design de l'itération 1 (`2026-11-22-remote-control-design.md`) et sur le contrat d'API (`docs/superpowers/plans/2026-11-22-api-contract-rulings.md`, arbitrages 1 à 12).*

## 1. Intention

L'itération 1 est validée par l'utilisateur : catalogue, fiches, lecture pilotée par le téléphone, écran télé.

L'itération 2 rapproche l'écran télé de ce que l'on fait sur le téléphone. Elle permet de quitter la vidéo en pause sans la perdre de vue, et elle affiche les informations que les maquettes prévoyaient mais que le serveur ne fournissait pas encore.

**Pourquoi, selon l'utilisateur :**
- « retrouver à peu près les mêmes infos » sur la télé que sur le téléphone. C'est « un peu du luxe », mais souhaité.
- Une fois une vidéo lancée, on ne revoit l'interface qu'en arrêtant la vidéo. En pause, on veut remonter aux infos ou à la navigation et voir que la vidéo est en pause, idéalement en la gardant visible dans une zone prévue, sans qu'elle masque rien.

## 2. Objectifs, non-objectifs, anti-objectifs

**Objectifs**
1. **La télé réplique le téléphone**, hors lecture ou quand la vidéo est en pause :
   - accueil, parcourir, collection, recherche ;
   - fiche œuvre, fiche saison avec l'épisode sélectionné ;
   - choix de version et de pistes.

   Elle montre à peu près les mêmes informations, mises en page pour un écran horizontal vu à 3 m. Elle suit approximativement la position dans les longues listes.
2. **Vidéo en pause.**
   - **Déclencheur :** la vidéo est en pause **et** le téléphone quitte l'écran télécommande. La télé revient alors à l'interface qui suit le téléphone.
   - **Pendant la pause :** un indicateur de pause est affiché en permanence. La vidéo en pause reste visible en vignette dans une zone prévue par la maquette, sans rien masquer.
   - **Reprise :** reprendre depuis le téléphone (télécommande, mini-lecteur, « Ouvrir la télécommande » puis lecture) ramène la vidéo en plein écran.
3. **Données manquantes**, fournies par le serveur et affichées par l'appli et la télé là où les maquettes les prévoient :
   - langues audio et sous-titres de chaque version ;
   - durée des épisodes ;
   - résolution de chaque version ;
   - support des œuvres non numérisées (DVD, Blu-ray…) ;
   - progression chiffrée de la première synchronisation.
4. **Corriger le test instable** `BrowseViewModelTest.selection_visible_before_works_load`.

**Non-objectifs**
- Le nom du PC (« a peu d'importance »).
- Installation sur le mini-PC, fiches PDF : plus tard.
- Copier l'écran du téléphone pixel par pixel, ou une réactivité instantanée. « Ça ne sera sans doute pas aussi réactif », c'est accepté.
- Lecture vidéo dans le navigateur, écartée à l'itération 1.

**Anti-objectifs (échec même si « ça marche »)**
- La télé change d'écran pendant une lecture en cours, c'est-à-dire non en pause.
- La télé saute, clignote ou rafraîchit à chaque geste du téléphone, de façon pénible à 3 m.
- La vignette en pause masque une partie utile de l'interface.
- La télé et le téléphone affichent des résultats différents pour la même recherche.
- Des envois du téléphone vers le serveur qui vident la batterie ou ralentissent l'appli.

## 3. Contraintes

- Mini-PC modeste. L'écran télé reste une page web légère : pas de flou, animations limitées à `transform` et `opacity`, rendu en 1920×1080.
- Sans services Google côté Android. Réseau local, sans authentification. Protections Host et Origin inchangées.
- La gestion des fenêtres du mini-PC (mode kiosque) n'est pas encore choisie. La vignette doit donc avoir une solution de repli.
- **Les nouvelles vues télé et l'indicateur de pause demandent de nouvelles maquettes**, à produire avec Claude Design (§ 9) **avant** de coder les écrans. La logique peut avancer en parallèle avec des écrans provisoires.

## 4. Approches retenues (choisies par l'utilisateur)

**A. Comment la télé sait ce qu'affiche le téléphone : A2 retenue.**
- **A1, écartée.** Le téléphone envoie l'écran et ses réglages, et le serveur recalcule les listes. Il faudrait réécrire en Python la recherche sans accents et les tris de l'appli, avec un risque de résultats divergents.
- **A2, retenue.** Le téléphone envoie l'écran, son en-tête et la liste ordonnée des identifiants visibles autour de sa position (fenêtre d'environ 40 éléments). Le serveur enrichit ces identifiants depuis sa base (titres, affiches, états) et la télé affiche.
  - Avantage : mêmes résultats garantis sur les deux écrans.
  - Limite : la télé n'affiche que ce que le téléphone a envoyé.

**B. Vidéo en pause en vignette : B2 retenue (décision de l'utilisateur, 2026-11-24).** *B1 (fenêtre mpv réduite en vignette) est écartée : sous GNOME Wayland, et sans doute sous d'autres gestionnaires, un programme ne peut ni placer sa fenêtre ni rester au premier plan ; le système de la machine cible n'est pas encore connu. Par sécurité :*
- *B1, pour mémoire (écartée) : réduire la fenêtre mpv en vignette positionnée au premier plan.*
- **Retenu : B2.** Capture de l'image courante de mpv, mpv passe en arrière-plan, la télé affiche la capture dans la zone prévue ; à la reprise, mpv repasse au premier plan en plein écran.
- **Mise en arrière-plan : deux mécanismes, choisis par configuration** (`pause_background`) :
  - `minimize` : réduire la fenêtre mpv (propriété `window-minimized`), puis la restaurer en plein écran à la reprise ;
  - `restart` : arrêter mpv en mémorisant position et pistes, puis le relancer à la reprise (une à deux secondes). Ce mécanisme est déjà éprouvé sur le bureau de l'utilisateur, puisqu'un mpv nouvellement lancé passe bien devant le navigateur.
  - Sous Wayland, rien ne garantit qu'une fenêtre restaurée repasse devant le navigateur. **Un essai rapide sur la machine de l'utilisateur, pendant l'implémentation, fixe la valeur par défaut** ; à défaut, ce sera `restart`.

**Données manquantes : une seule voie.** Analyse ffprobe en tâche de fond après l'import, résultat mis en cache par fichier (le cache de pistes existe déjà). Le support provient du code de qualité du catalogue : `DVD`, `BR`, `DVR`, `DVT`, `DVS`.

## 5. Architecture et contrat

### 5.1 Ce que le téléphone envoie : la « vue courante »

Nouvel appel `POST /api/tv/view`, en remplacement de l'usage de `POST /api/tv/show` pour la navigation. `tv/show` reste pour la compatibilité.

```json
{
  "screen": "home | browse | collection | search | work | season | preplay",
  "title": "Films",                       // en-tête affiché (texte prêt, en français)
  "subtitle": "2 929 lisibles · tri par titre",
  "query": "tchernia",                    // recherche seulement
  "work_id": 12, "season_id": 3,           // fiches
  "episode_id": 45,                        // fiche saison : épisode sélectionné
  "media_file_id": 7,                      // preplay : version choisie
  "items": [101, 102, 103],                // œuvres visibles, dans l'ordre (≤ 40)
  "anchor": 101,                           // premier élément visible
  "sections": [{"label": "Personnes", "people": ["Pierre Tchernia"]},
               {"label": "Œuvres", "items": [101, 102]}],   // recherche seulement
  "seq": 128                               // compteur croissant par téléphone
}
```

**Règles d'envoi.**
- Le téléphone envoie la vue quand l'écran change, quand les réglages changent, ou quand la position de défilement change d'au moins une ligne.
- Il envoie au plus 2 fois par seconde, avec une fusion qui ne garde que la dernière vue. Pour la recherche, il attend la fin de la frappe (comme aujourd'hui, environ 300 ms).
- Rien n'est envoyé quand l'appli est en arrière-plan.

**Règles du serveur.**
- Il ignore une vue plus ancienne (`seq`) qu'une vue déjà reçue du même téléphone.
- C'est le dernier téléphone qui a agi qui pilote l'affichage de la télé. Un identifiant de téléphone aléatoire, stocké localement, accompagne chaque envoi.
- Il enrichit `items` depuis sa base : titre, année, type, affiche, lisible, vu/entamé, disque absent. Il publie ensuite un événement télé `{"type":"tv","view":{…enrichi…}}`.
- Il ignore une vue reçue pendant une lecture en cours (non en pause), mais la mémorise : c'est elle qui s'affiche à la pause suivante ou à l'arrêt.

### 5.2 Ce qu'affiche la télé

L'état télé devient :
- `playing` : vidéo en cours ;
- `paused_overlay` : interface affichée, vidéo en pause en vignette ;
- `view` : la vue du téléphone ;
- `message`.

Le passage en `paused_overlay` se fait quand la vidéo est en pause **et** que le téléphone envoie une vue autre que la télécommande. Le téléphone envoie `{"screen":"remote"}` quand il ouvre la télécommande.

L'interface télé redessine chaque vue à partir des données enrichies, dans le style des nouvelles maquettes. Les longues listes sont centrées autour de `anchor`.

### 5.3 Vignette vidéo en pause (B2)

Sur passage en `paused_overlay` : capture de l'image (`screenshot-to-file`, sans sous-titres, dans le dossier de données, servie à la télé par une URL dédiée), puis mise en arrière-plan de mpv selon `pause_background` (`minimize` ou `restart`). La télé affiche la capture dans la zone prévue par la maquette.

Sur reprise : `minimize` → restaurer, plein écran, `pause=no` ; `restart` → relance à la position enregistrée avec les mêmes pistes, puis lecture.

**Indicateur de pause :** le titre, l'icône pause et le temps écoulé sur total, sur la vignette ou à côté, selon la maquette.

### 5.4 Données manquantes

**Analyse en tâche de fond après chaque import et chaque localisation de fichiers.**
- Chaque fichier présent sans analyse à jour est sondé par ffprobe, un fichier à la fois, à faible priorité.
- On garde les langues audio, les langues des sous-titres intégrés et externes, la largeur et la hauteur, et la durée.
- Le cache est invalidé quand la date de modification ou la taille du fichier change.
- Chaque résultat fait avancer la révision, et les clients le reçoivent par la synchronisation.

**Contrat, ajouts à `/api/sync` :**
- `media_files[]` : `audio_langs`, `subtitle_langs` (codes `fr`, `en`… ou `null` si inconnu), `width`, `height`, `duration_s`.
- `episodes[]` : `duration_min`, déduit du fichier principal.
- `works[]` : `medium` (`"DVD"`, `"Blu-ray"`, `"DVD (copie)"`, `"DVD (TNT)"`, `"DVD (satellite)"` ou `null`).
- `/api/sync` complète (`full`) gagne `total_works`, pour afficher la progression de la première synchronisation. L'appli affiche « n / total » pendant l'écriture par paquets.

### 5.5 Test instable

`BrowseViewModelTest.selection_visible_before_works_load` : collecte non synchronisée en `Dispatchers.Unconfined`. À rendre déterministe, avec une collecte sur le dispatcher de test et une liste sûre.

## 6. Appli Android

- **Publication de la vue courante.** Chaque écran concerné (accueil, parcourir, collection, recherche, fiches, choix des pistes, télécommande) expose sa « vue » : titre, sous-titre, éléments visibles, ancre, sélection. Un `TvMirror` unique, au niveau de l'appli, fusionne et limite les envois (§ 5.1), au premier plan seulement.
- **Données affichées.** Pastilles de langues et résolution sur les versions, durée des épisodes, support des œuvres non numérisées, progression « n / total » de la première synchronisation. Tout cela selon les maquettes existantes : voir `docs/design/ecarts-maquettes-donnees-serveur.md`.
- Aucun changement de comportement de lecture.

## 6 bis. Précisions issues de la relecture (choix du concepteur, à vérifier)

1. **Accueil.** La télé réplique aussi l'accueil du téléphone. Les rangées « Reprendre », « Récemment ajoutés » et « Collections » sont des `sections`, dont les éléments peuvent être des œuvres ou des épisodes (`{"kind":"work"|"episode","id":…}`). Si aucun téléphone n'a envoyé de vue depuis 10 minutes, ou si aucun n'est connecté, la télé revient à la **veille** actuelle (horloge, « Vu récemment »).
2. **Choix des pistes.** La vue `preplay` porte aussi les pistes choisies (`audio`, `subtitle` au format SubSel), pour que la télé les montre.
3. **Télécommande ouverte pendant la pause.** Retour symétrique : quand le téléphone ouvre la télécommande, la télé affiche la capture en plein écran avec l'indicateur de pause ; mpv ne repasse au premier plan qu'à la reprise.
4. **Mécanisme `restart`, mpv arrêté = « en pause, lecteur mis de côté ».** `current` est conservé, et le mini-lecteur comme la télécommande restent utilisables. Pendant cet état, les commandes position, pistes et volume sont **mémorisées** et appliquées à la relance. La progression enregistrée et l'événement `player` indiquent `paused=true`, pas un arrêt.
5. **Support (`medium`).** L'import conserve désormais les codes physiques du catalogue (`DVD`, `BR`, `DVR`, `DVT`, `DVS`), ce qui demande une migration de schéma. Ordre de priorité : Blu-ray > DVD > DVD (copie) > DVD (TNT) > DVD (satellite). `medium` vaut `null` dès que l'œuvre a un fichier lisible.
6. **« Le téléphone qui a agi ».** Seuls les envois de vue (`/api/tv/view`) comptent. Les commandes de lecture ne changent pas qui pilote l'affichage.
7. **Progression de la première synchronisation.** `/api/sync` complète reste une seule réponse avec `total_works`. L'appli affiche une barre indéterminée pendant le téléchargement, puis « n / total » pendant l'écriture par paquets. Pas de pagination.

## 7. Risques

1. **Gestion des fenêtres** : la restauration au premier plan (`minimize`) peut échouer selon le système ; `restart` reste disponible partout.
2. **Charge réseau ou batterie** due aux envois de vue : limitation à 2 par seconde et rien en arrière-plan.
3. **Télé qui saute** : la vue n'est envoyée que sur changement significatif, et la télé fait des transitions douces (opacité).
4. **Analyse ffprobe de milliers de fichiers sur une machine lente** : un fichier à la fois, à faible priorité, avec cache ; sans impact sur la lecture en cours (pause de l'analyse pendant une lecture).

## 8. Laissé au builder

- Découpage exact des tâches, schéma de cache des analyses, noms des modules.
- Valeurs fines de limitation, dans le respect de § 5.1.
- Valeur par défaut de `pause_background` après l'essai sur la machine de l'utilisateur.

## 9. Maquettes (Claude Design) — livrées le 2026-11-24

Fichiers dans `docs/design/` : « 4a - Télé, vues miroir », « 4b - Saison, pause, composants », « 4c - Téléphone ». **Ils font foi pour les écrans.** Choix notables du designer :
- **Zone de l'image en pause : le « coin d'état » en haut à droite**, environ 448 × 252, à la place de l'horloge. Aucune vue n'a besoin d'une seconde mise en page : en pause, le coin montre l'image, l'icône, le titre, le temps et une fine barre de progression.
- **Repère de position :** grande lettre en laiton (« M · 1 240 / 2 552 ») pour Parcourir, rang (« n° 412 · 408–419 sur 1000 ») pour Collection.
- Chaque vue est montrée sans et avec la pause, plus le plein écran en pause.

### Brief initial

Un **brief Claude Design** sera rédigé, sur le modèle de `docs/design/brief-claude-design.md` et en réutilisant l'identité existante. Il couvrira :
- **Les vues télé qui répliquent le téléphone, en horizontal 1920×1080 :** accueil, parcourir (grille centrée sur la position), collection, recherche (sections Personnes / Œuvres / Aussi connu sous), fiche saison avec l'épisode sélectionné, choix des pistes.
- **La télé en pause :** la zone réservée à la vignette vidéo dans chacune de ces vues, et l'indicateur de pause (titre, icône, temps).
- **Le téléphone :** les pastilles de langue, la résolution, la durée des épisodes, le support DVD, et la progression de la première synchronisation, si les maquettes existantes ne suffisent pas.

**Ordre de travail :** brief → maquettes par l'utilisateur → écrans. Le serveur, le contrat, l'analyse ffprobe, la publication de la vue côté téléphone et la vignette mpv peuvent avancer avant.
