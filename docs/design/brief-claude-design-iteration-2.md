# Brief pour Claude Design — Remote-Control, itération 2

*À donner à Claude Design avec les maquettes existantes :*
- *« Remote-Control - 1 - Identité & composants » : on garde l'identité ;*
- *« 2 - Ecran télé » ;*
- *« 3 - Appli android ».*

*Cette itération **étend** ces maquettes. Elle ne change ni l'identité, ni les couleurs, ni les polices.*

## Ce qui change, en deux phrases

Jusqu'ici, la télé n'affichait qu'une veille, la fiche d'une œuvre, la fiche d'une saison et la lecture. Désormais, **la télé reproduit à peu près ce qu'on regarde sur le téléphone** (catalogue, recherche, fiches…) et **montre clairement qu'une vidéo est en pause**, en gardant une image de la vidéo dans un coin pendant qu'on navigue.

## Rappel des contraintes (inchangées)

- **Télé :** canevas 1920×1080 en HTML/CSS, lisible à 3 m, avec des marges de sécurité d'environ 5 %.
- **Machine modeste :**
  - pas de flou ni de `backdrop-filter` ;
  - animations sobres, limitées à `transform` et `opacity` ;
  - affiches de 600 px de large.
- **La télé est passive.** Personne ne la touche : pas de curseur ni de focus. Elle montre ce que le téléphone affiche.
- **Écran horizontal contre écran vertical.** La télé n'est pas une copie du téléphone : elle présente **les mêmes informations**, mises en page pour un écran large vu de loin. Elle peut montrer moins d'éléments à la fois que le téléphone.
- Tout le texte est en français.

---

## 1. Télé — vues qui reproduisent le téléphone

Pour chaque vue, le téléphone envoie :
- l'écran affiché ;
- son titre et son sous-titre ;
- la liste ordonnée des œuvres **visibles autour de l'endroit où il se trouve**, une quarantaine au plus ;
- l'élément en haut de l'écran du téléphone, qu'on appelle « ancre ».

La télé affiche cette portion, centrée sur l'ancre. Quand l'utilisateur fait défiler le téléphone, la télé suit à son rythme, sans transition brutale : un fondu enchaîné ou un léger glissement suffit.

### 1.1 Accueil
Rangées « Reprendre » (œuvres et épisodes entamés, avec barre de progression et « reste N min »), « Récemment ajoutés », « Collections » (« N œuvres · M lisibles »).
- **Veille :** sans activité du téléphone depuis 10 minutes, la télé revient à la veille actuelle, avec l'horloge et « Vu récemment ». Elle reste telle quelle.

### 1.2 Parcourir
- **En-tête :** le type affiché (« Films », « Séries », « Documentaires », « Théâtre », « Téléfilms », « Spectacles »), le sous-titre « 1 912 films · Titre » et l'état du filtre (« Œuvres lisibles » ou « Tout le catalogue »).
- **Grille d'affiches** centrée sur la position du téléphone, avec les états de vignette déjà définis :
  - vu, entamé (barre de progression) ;
  - disque non branché, non numérisé ;
  - sans affiche.
- **Repère de position :** proposer un indicateur discret, par exemple la lettre courante en grand ou « M · 1 240 / 2 929 ».

### 1.3 Collection
- **En-tête :** nom de la collection, « N œuvres · M lisibles ».
- **Liste ordonnée par rang** (« n° 412 »), centrée sur la position.

### 1.4 Recherche
- **En-tête :** la recherche saisie, par exemple « tchernia », en grand.
- **Sections :** « Personnes » (nom, rôle « Réalisation » ou « Interprétation », nombre d'œuvres), « Œuvres », « Aussi connu sous ».
- Les lettres trouvées sont **surlignées en laiton**, comme sur le téléphone.
- **États :** aucun résultat, et « Tapez au moins deux lettres ».

### 1.5 Fiche œuvre
La maquette existe déjà. Elle gagne :
- les **pastilles de langue** par version, avec audio et sous-titres (« FR · VO », « ST français ») ;
- la **résolution**, par exemple « 1920 × 1080 » à côté de « FHD » ;
- le **support** des œuvres non numérisées (« DVD », « Blu-ray »).

### 1.6 Fiche saison
La maquette existe déjà. Elle gagne :
- **l'épisode sélectionné sur le téléphone**, mis en évidence. Il peut différer de l'épisode « à suivre » : prévoir les deux marques ensemble, par exemple « Sélectionné » et « À suivre » ;
- la **durée de chaque épisode**.

### 1.7 Choix de version et de pistes
Nouvelle vue télé : reprendre le contenu de l'écran téléphone « Version et pistes », c'est-à-dire la version choisie, la piste audio et les sous-titres, avec les badges « Par défaut » et « Dernier choix ». La télé montre **ce qui est sélectionné** sur le téléphone. Le bouton « Lire sur la télé » reste sur le téléphone.

---

## 2. Télé — la pause

### 2.1 Vidéo en pause pendant qu'on navigue
Quand la vidéo est en pause et qu'on quitte la télécommande sur le téléphone, la télé affiche la vue qui suit le téléphone (§ 1). Elle **garde une image de la vidéo en pause** :
- **Zone réservée à l'image en pause,** au même endroit dans toutes les vues de § 1, et qui ne masque rien d'utile. Par exemple : un coin, une colonne étroite, ou une bande en bas. **À proposer, c'est le point le plus important du brief.** L'image fait environ 16/9, et une taille de 480 × 270 sur 1920 × 1080 est un ordre de grandeur.
- **L'indicateur de pause** est sur l'image ou juste à côté :
  - icône pause ;
  - titre (« Heroes · S01E05 » ou « Bonjour l'angoisse ») ;
  - temps écoulé et total, par exemple « 23:14 / 41:30 », avec une fine barre de progression.
- **Mise en page :** les vues de § 1 doivent rester lisibles **avec** cette zone. Si besoin, montrer chaque vue en deux variantes, sans et avec la pause.

### 2.2 Télécommande ouverte pendant la pause
L'image en pause s'affiche **en plein écran**, avec un indicateur de pause bien visible : icône, titre, temps écoulé et total, barre de progression.
- **Style :** calme, il peut rester affiché longtemps.
- **Marquage de l'écran :** prévoir un léger mouvement très lent pour ne pas marquer l'écran.

### 2.3 Reprise
Rien à dessiner : la vidéo revient en plein écran.

---

## 3. Téléphone — compléments

À dessiner seulement si les maquettes existantes ne suffisent pas.
- **Pastilles de langue et résolution** sur les versions :
  - fiche œuvre ;
  - écran « Version et pistes » ;
  - ligne « année · qualité · langue » de la télécommande.
- **Durée de chaque épisode** dans la fiche saison, sur les lignes et sur la carte sélectionnée, par exemple « 41 min · reste 18 min ».
- **Support des œuvres non numérisées** (« DVD », « Blu-ray ») sur la fiche et sur les vignettes « Non numérisé ».
- **Première synchronisation avec progression chiffrée :**
  - « Téléchargement du catalogue… » d'abord, avec une barre indéterminée ;
  - puis « 1 250 / 3 992 œuvres ».

---

## 4. Ce qu'on attend en sortie

- **Un fichier « Remote-Control - 4 - Itération 2 »** contenant :
  - les vues télé de § 1, en 1920 × 1080 et avec des données réalistes ;
  - les états de pause de § 2 : la zone réservée dans chaque vue, puis le plein écran en pause ;
  - les compléments téléphone de § 3.
- **Les nouveaux composants**, en réutilisant les variables CSS existantes :
  - l'indicateur de pause, en version vignette et en version plein écran ;
  - le repère de position ;
  - les pastilles de langue et de résolution ;
  - la marque « Sélectionné ».
- **Les choix laissés au designer :** l'emplacement et la forme de la zone d'image en pause, et le repère de position dans les listes.

## 5. Données d'exemple

Tirées du catalogue de test et du vrai catalogue.
- **Série :** *Heroes* (2006), Tim Kring, science-fiction, saison 1, 23 épisodes de 41 à 53 min. VO anglaise avec sous-titres français. Le fichier est en 624 × 352.
  - Pause sur « S01E05 », à 23:14 sur 41:30.
  - « À suivre » : S01E06. « Sélectionné » sur le téléphone : S01E12.
- **Film :** *Bonjour l'angoisse* (1988), Pierre Tchernia, comédie, 1 h 30, avec Michel Serrault et Guy Marchand. Version SD en 704 × 576, audio français.
- **Spectacle :** *La symphonie des jeux vidéo* (2022), France 5, 1 h 52, en 1280 × 720.
- **Non numérisé :** *Alien, le huitième passager* (1979), Ridley Scott. Support : DVD.
- **Recherche « tchernia » :**
  - Personnes : Pierre Tchernia (Réalisation · 1 œuvre) ;
  - Œuvres : *Bonjour l'angoisse*.
- **Recherche « alien » :**
  - Œuvres : *Alien, le huitième passager* ;
  - Aussi connu sous : « Alien ».
- **Parcourir les films :**
  - « 2 552 films · Titre », avec le filtre « Œuvres lisibles » ;
  - position du téléphone autour de la lettre M ;
  - repère « M · 1 240 / 2 552 ».
