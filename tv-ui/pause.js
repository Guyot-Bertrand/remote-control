/* Écran télé : vidéo en pause (d'après la maquette « Remote-Control - 4b »).
   - renderStateCorner(pause) : coin d'état en haut à droite (448×252) ; l'horloge
     sans pause, la vignette de la vidéo en pause sinon.
   - renderPausedFull(pause) : image en pause en plein écran (télécommande ouverte).
   Chargé après common.js, avant sheets.js, views.js et app.js ; utilise esc, fmtClock,
   clockText et dateText de common.js au moment du rendu. */
'use strict';

function pauseIcon(size) {
  return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" role="img" aria-label="En pause">`
    + '<rect x="6" y="4.5" width="4" height="15" rx="1" fill="currentColor"/>'
    + '<rect x="14" y="4.5" width="4" height="15" rx="1" fill="currentColor"/></svg>';
}

function pauseRatio(position, duration) {
  return duration ? Math.min(1, Math.max(0, position / duration)) : 0;
}

function pauseTime(position, duration) {
  return duration ? `${fmtClock(position)} / ${fmtClock(duration)}` : fmtClock(position);
}

/** « Heroes — S01E05 » (titre du lecteur) -> « Heroes · S01E05 », comme sur la maquette. */
function pauseTitle(title) {
  return String(title || '').replace(/ — (S\d+E\d+)$/, ' · $1');
}

/** Image de la pause : rien si la capture a échoué (fond noir). */
function pauseImage(pause) {
  return pause.image ? `<img src="${esc(pause.image)}" alt="">` : '';
}

/** Coin d'état : horloge, ou vidéo en pause (image, icône, titre, temps, fine barre). */
function renderStateCorner(pause) {
  if (!pause) {
    const now = new Date();
    return `<div class="state-corner">
<span class="corner-clock" data-clock>${clockText(now)}</span>
<span class="corner-date" data-date>${dateText(now)}</span>
<span class="corner-ready"><span class="ready-dot"></span>Prêt</span>
</div>`;
  }
  const ratio = pauseRatio(pause.position, pause.duration);
  return `<div class="state-corner corner-paused">
<div class="corner-art">${pauseImage(pause)}</div>
<div class="corner-info">
<span class="corner-badge">${pauseIcon(24)}</span>
<span class="corner-text"><span class="corner-title">${esc(pauseTitle(pause.title))}</span>`
    + `<span class="corner-time" data-pause-time>${pauseTime(pause.position, pause.duration)}</span></span>
</div>
<span class="corner-bar"><span data-pause-progress style="transform:scaleX(${ratio})"></span></span>
</div>`;
}

/** Plein écran en pause : image (dérive très lente contre le marquage), indicateur en bas. */
function renderPausedFull(pause) {
  const ratio = pauseRatio(pause.position, pause.duration);
  return `<div class="screen paused-full">
<div class="paused-art">${pauseImage(pause)}</div>
<div class="paused-shade"></div>
<div class="paused-info rc-drift">
<div class="paused-row">
<span class="paused-badge">${pauseIcon(60)}</span>
<div class="paused-text">
<span class="paused-head">EN PAUSE</span>
<span class="paused-title">${esc(pauseTitle(pause.title))}</span>
</div>
<span class="paused-time" data-pause-time>${pauseTime(pause.position, pause.duration)}</span>
</div>
<span class="paused-bar"><span data-pause-progress style="transform:scaleX(${ratio})"></span></span>
</div></div>`;
}

/** Avancement affiché (coin ou plein écran) d'après l'événement `player`, sans re-rendu. */
function updatePause(root, st) {
  if (!st || !st.running || !st.duration) return;
  root.querySelectorAll('[data-pause-time]').forEach((el) => {
    el.textContent = pauseTime(st.position, st.duration);
  });
  root.querySelectorAll('[data-pause-progress]').forEach((el) => {
    el.style.transform = `scaleX(${pauseRatio(st.position, st.duration)})`;
  });
}

/** Plein écran déjà affiché avec la même image : met à jour titre, temps et barre sur
    place (sans re-rendu, la dérive continue). Renvoie false s'il faut le redessiner. */
function refreshPausedFull(root, pause) {
  const screen = root.lastElementChild;
  if (!pause || !screen || !screen.classList.contains('paused-full')) return false;
  const img = screen.querySelector('.paused-art img');
  if ((img ? img.getAttribute('src') : null) !== (pause.image || null)) return false;
  screen.querySelector('.paused-title').textContent = pauseTitle(pause.title);
  screen.querySelector('[data-pause-time]').textContent = pauseTime(pause.position, pause.duration);
  screen.querySelector('[data-pause-progress]').style.transform = `scaleX(${pauseRatio(pause.position, pause.duration)})`;
  return true;
}
