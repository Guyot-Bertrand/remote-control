/* Écran télé : vues qui reproduisent le téléphone (arbitrages 14 à 16).
   Socle provisoire : en-tête de la vue et coin d'état ; les vues miroir
   (accueil, parcourir, collection, recherche) viennent ensuite. */
'use strict';

/** Vue du téléphone ; `pause` non nul : vidéo en pause dans le coin d'état. */
function renderView(view, pause) {
  const v = view || {};
  return `<div class="screen view">
<div class="view-head">
${v.title ? `<h1 class="view-title">${esc(v.title)}</h1>` : ''}
${v.subtitle ? `<span class="view-sub">${esc(v.subtitle)}</span>` : ''}
</div>
${renderStateCorner(pause)}
</div>`;
}
