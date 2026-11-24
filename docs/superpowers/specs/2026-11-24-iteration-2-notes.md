# Itération 2 — notes à approfondir

*Notées le 2026-11-24 à partir des premiers essais. Rien de tout cela n'est implémenté : on les détaillera, avec de nouvelles maquettes, en ouvrant l'itération 2.*

## 1. Pause : revenir à l'interface sans perdre la vidéo

**Constat.** Une fois la vidéo lancée, mpv couvre tout l'écran télé. Pour revoir l'interface web, il faut arrêter la lecture.

**Souhait.**
- En pause, si l'on remonte sur le téléphone vers la fiche d'infos ou vers les écrans de navigation, la télé suit et affiche l'interface correspondante.
- Un **indicateur visuel** montre en permanence qu'une vidéo est en pause.
- Idéalement, la vidéo reste **en pause, au-dessus du navigateur, dans une zone prédéfinie** prévue par la maquette, par exemple en vignette, pour qu'elle ne masque rien. Elle reprend en plein écran à la reprise.

**Impact.**
- Nouvelles maquettes télé : un écran « en pause » et l'emplacement de la vignette vidéo dans chaque vue.
- Pilotage de la fenêtre mpv : géométrie, plein écran ou non, au premier plan ou non. Le fonctionnement réel dépend de la gestion des fenêtres sur le mini-PC (kiosque) et doit être validé sur la machine cible, en lien avec le plan d'installation.

**Pistes déjà évoquées** (voir la conversation du 2026-11-24) :
- bouton « Infos » qui affiche une incrustation mpv par-dessus la vidéo ;
- la pause fait revenir la page télé.

## 2. La télé suit ce qu'on fait sur le téléphone

**Constat.** Aujourd'hui, la télé n'affiche que la fiche œuvre, la fiche saison, la veille ou la lecture. Quand on parcourt le catalogue sur le téléphone, la télé n'en montre rien.

**Souhait.** Hors lecture, ou quand la vidéo est en pause (voir 1), l'écran télé reflète mieux l'écran du téléphone :
- les vues de catalogue : Parcourir, Collection ;
- la recherche ;
- etc.

**Impact.**
- Maquettes télé pour ces vues.
- Côté API : de nouveaux états télé, par exemple `browse` (type, tri, filtre), `collection`, `search` (requête et résultats).
- L'appli annonce ce qu'elle affiche, avec une cadence raisonnable : par exemple pour une recherche après une pause de frappe.
- Règle de priorité quand plusieurs téléphones sont connectés.

## 3. Autres points en attente (rappel)

- Données serveur manquantes pour les maquettes : voir `docs/design/ecarts-maquettes-donnees-serveur.md`.
- Plan d'installation sur le mini-PC : kiosque, systemd, `rc doctor` sur place.
- Fiches PDF imprimables (phase 2).
