# Brief pour Claude Design — Remote-Control

## Le produit en deux phrases

Un mini-PC branché sur la télé lit une collection personnelle de films, documentaires, séries, pièces de théâtre, téléfilms et spectacles (environ 4 000 œuvres, sur des disques externes). Un téléphone Android sert de télécommande : on y parcourt la collection, on choisit une œuvre, sa fiche s'affiche **en même temps** sur le téléphone et sur la télé, puis on lance et contrôle la lecture.

**Esprit :** léger, calme, sans écrans de réglages, **entièrement en français**. On veut l'inverse de Kodi : peu d'options, une belle fiche, un bouton « Lire ».

Il y a deux surfaces à concevoir : **l'écran télé** et **l'appli téléphone**.

---

## 1. Écran télé

### Contraintes techniques (impératives)

- **Canevas fixe de 1920×1080**, en HTML/CSS. Ces maquettes seront réutilisées presque telles quelles, donc elles doivent rester du HTML/CSS simple, sans framework.
- **Lisible à 3 m** : grandes tailles de texte, contrastes forts, marges de sécurité d'environ 5 % sur les bords, parce que certaines télés rognent l'image.
- **Machine modeste** (mini-PC basse consommation de plusieurs années) :
  - pas de `backdrop-filter` ni de flou dynamique ;
  - pas d'ombres animées ;
  - animations limitées à `transform` et `opacity`, sobres ;
  - affiches servies en 600 px de large.
- **L'écran est passif** : personne n'interagit avec la télé. Il n'y a donc ni focus, ni curseur, ni boutons cliquables. On montre seulement un état, piloté depuis le téléphone.

### Les 5 états à dessiner

1. **Accueil / veille**
   - Écran calme : nom « Remote-Control », heure, et « Prêt ».
   - Contenu discret possible : dernières œuvres vues, ou une affiche de la collection.
   - Il peut rester affiché des heures : il doit être agréable et ne pas marquer l'écran (prévoir un léger mouvement lent si un fond est présent).
2. **Fiche d'une œuvre** (film, documentaire, théâtre…)
   - Affiche, titre français, et titre original s'il diffère.
   - Année, durée (« 1 h 39 »), réalisateur(s), genres, casting (6 premiers noms), synopsis.
   - Collections d'appartenance (« Les 1000 plus grands films de tous les temps », « Cinéphilie [Sergent Pepper] »…).
   - Versions disponibles (FHD, HD…).
   - États particuliers :
     - progression : « Reprise possible à 1 h 12 » ;
     - « Disque « DISK1 » non branché » ;
     - « Non numérisé ».
3. **Fiche d'une saison** (séries, documentaires en épisodes)
   - En-tête de la série (affiche, titres, année, synopsis court) et « Saison 2 ».
   - Liste des épisodes : numéro, titre s'il existe, durée.
   - Marques **vu** et **entamé** (avec une barre de progression).
   - **Un épisode mis en évidence** : le dernier vu ou entamé, sinon le premier non vu.
   - Une saison peut compter de 2 à plus de 20 épisodes : la liste doit rester lisible sans défilement interactif.
4. **Lecture en cours**
   - Le lecteur vidéo (mpv) s'affiche par-dessus en plein écran.
   - La page montre seulement un fond noir avec le titre en cours, visible un instant au lancement ou à l'arrêt.
5. **Message**
   - Texte centré, par exemple « Lecture interrompue : fichier inaccessible » ou « Disque « DISK1 » non branché ».

### Cas réels à prévoir

- Titres très longs : « Agatha Christie : Dix Petits Nègres », « 71 fragments d'une chronologie du hasard ».
- Synopsis de 1 à 6 lignes.
- Casting vide.
- **Affiche absente** : prévoir un visuel de remplacement élégant avec le titre.
- Films anciens en noir et blanc.
- Affiches de formats variés.

---

## 2. Appli téléphone (Android)

### Contraintes

- Écrans de référence : Samsung Galaxy S21 FE (6,4") et un téléphone Murena (/e/OS).
- Utilisation d'une main, souvent dans la pénombre devant la télé : prévoir un **thème sombre** au minimum.
- Material 3 (Jetpack Compose) : les maquettes servent de **référence visuelle** et seront retranscrites en Compose. Elles doivent donc rester proches des composants Material.
- La navigation marche **hors ligne**, grâce au cache. Seules les commandes ont besoin du PC.
- Par défaut, seules les œuvres lisibles sont affichées, avec un interrupteur « Tout le catalogue ».

### Écrans à dessiner

1. **Accueil**
   - « Reprendre » (œuvres et épisodes entamés).
   - « Récemment ajoutés ».
   - Collections.
   - Indicateur de connexion au PC.
2. **Parcourir par type**
   - Films, Séries, Documentaires, Théâtre, Téléfilms, Spectacles.
   - Grille ou liste d'affiches, pensée pour des milliers d'éléments.
3. **Collection** : liste ordonnée par rang (par exemple n° 1 à 1000).
4. **Recherche**
   - Porte sur les titres français, originaux et alternatifs, les réalisateurs et le casting, sans tenir compte des accents.
   - Résultats immédiats pendant la frappe.
5. **Fiche œuvre**
   - Mêmes informations que sur la télé, en version mobile.
   - Bouton principal **« Lire depuis le début »**, et bouton secondaire **« Reprendre à 1 h 12 »** si une progression existe.
   - Lien vers le choix de version et de pistes.
   - États « disque non branché » et « non numérisé » : bouton Lire désactivé, avec une explication.
6. **Fiche saison**
   - Sélecteur de saison, liste des épisodes.
   - Épisode mis en évidence, marques vu et entamé.
   - Pour chaque épisode : « Depuis le début » ou « Reprendre ».
7. **Avant la lecture : choix des pistes**
   - Version (FHD / HD…).
   - Piste audio (Français, Anglais…).
   - Sous-titres : fichiers à côté de la vidéo, pistes intégrées, ou « Aucun ».
   - Valeurs proposées par défaut : français, ou le dernier choix fait pour cette œuvre.
8. **Télécommande** (pendant la lecture)
   - Titre en cours, barre de position déplaçable, temps écoulé et temps total.
   - Lecture/pause, retour au début, −10 s / +10 s, −60 s / +60 s.
   - Volume, changement de piste audio et de sous-titres, arrêt.
   - C'est l'écran le plus utilisé : gros boutons, utilisable sans regarder.
9. **Scan QR**
   - Vue caméra avec un cadre de visée.
   - Le scan d'une fiche imprimée ouvre l'œuvre ou la saison.
   - Message si le QR-code n'est pas reconnu.
10. **Réglages minimaux**
    - Serveur détecté, ou saisie manuelle de l'adresse une seule fois.
    - Bouton « Resynchroniser ».
    - Version de l'appli.

### États transverses

- PC injoignable : la navigation reste possible et les commandes sont grisées.
- Première synchronisation en cours.
- Catalogue vide.
- Erreurs courtes en français : « Disque « DISK1 » non branché », « Réponse inattendue du serveur. »

---

## 3. Ce qu'on attend en sortie

- **Une identité commune** télé et téléphone : palette, typographie, iconographie, ainsi que les tokens (couleurs, tailles, espacements) sous forme de variables CSS.
- **Télé :** les 5 états en HTML/CSS 1920×1080, avec des données réalistes. Ce sont les exemples ci-dessous.
- **Téléphone :** les 10 écrans ci-dessus, plus les états transverses.
- **Composants récurrents :**
  - vignette d'affiche, avec ses variantes vu, entamé (barre de progression) et indisponible ;
  - pastilles de qualité (FHD, HD…) et de langue ;
  - ligne d'épisode ;
  - boutons Lire et Reprendre.

**À décider ensemble, avant de figer :**
- l'ambiance : salle de cinéma sombre, cinémathèque élégante, ou autre ;
- la couleur d'accent ;
- la place de l'affiche dans la fiche télé (à gauche en pleine hauteur, ou en fond atténué).

**Hors périmètre pour l'instant :** les fiches imprimées A4, 9 par page avec un QR-code, qui viendront dans un second temps.

---

## 4. Données d'exemple (tirées du vrai catalogue)

- **Film :** *Ava* (2017) — Léa Mysius — comédie dramatique — 1 h 39 — avec Juan Cano, Noée Abita, Laure Calamy. Synopsis : « Ava, 13 ans, est en vacances au bord de l'océan quand elle apprend qu'elle va perdre la vue plus vite que prévu… ». Deux versions, FHD et HD. Collection « Les 1000 plus grands films de tous les temps [2020] » (n° 2017).
- **Film avec titre original et alternatif :** *Alien, le huitième passager* / *Alien* / *L'Étranger* (1979) — Ridley Scott — Science-fiction, Horreur — 1 h 57. Non numérisé (DVD).
- **Série :** *Back to Life* (2019) — Laura Solon, Daisy Haggard — comédie — 2 saisons de 6 épisodes d'environ 25 min, en anglais avec sous-titres français.
- **Documentaire en épisodes :** *Le Fabuleux Destin des Inventions* (2002) — 16 épisodes, par exemple « S01E02 — Et la longitude fut ».
- **Théâtre :** *Le Prénom* (2012) — Bernard Murat — 1 h 50.
