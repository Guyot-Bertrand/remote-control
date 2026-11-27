/* Écran télé Remote-Control : page passive pilotée par TvState (WS /api/events).
   Un écran = une fonction render<Écran>(data) qui renvoie le HTML du canevas 1920×1080. */
'use strict';

// Premier geste : retirer le message « JavaScript bloqué » affiché par défaut.
document.getElementById('nojs')?.remove();

const RECONNECT_MS = 2000;
// Copie de rc/messages.py (SHEET_UNAVAILABLE) ; les autres messages viennent du serveur.
const MSG_SHEET_UNAVAILABLE = 'Fiche indisponible';
const CLOCK_MS = 10000;
const HOME_ROTATE_MS = 3 * 60 * 1000; // alternance « Vu récemment » / « Dans la collection »
const RECENT_SHOWN = 5;

const stage = document.getElementById('stage');
let tv = { screen: 'home', work_id: null, season_id: null, message: null, view: null, pause: null };
let current = null; // lecture en cours (player.current), pour le titre de l'écran « playing »
let playerState = null; // dernier état du lecteur (position, durée, pause)
let seq = 0; // jeton de rendu : une réponse arrivée après un changement d'écran est ignorée
let homeTurn = 0;
let homeTimer = null;
let shown = null; // écran actuellement monté (state.screen)
let shownHtml = null; // son rendu, sans l'heure (stableHtml)
const FADE_SCREENS = new Set(['view', 'paused']); // même écran : fondu, affiches conservées

// -- mise à l'échelle du canevas ------------------------------------------

function fit() {
  const s = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
  const x = (window.innerWidth - 1920 * s) / 2;
  const y = (window.innerHeight - 1080 * s) / 2;
  stage.style.transform = `translate(${x}px, ${y}px) scale(${s})`;
}
window.addEventListener('resize', fit);
fit();

// -- horloge ------------------------------------------------------------------

function tickClock() {
  const now = new Date();
  stage.querySelectorAll('[data-clock]').forEach((el) => { el.textContent = clockText(now); });
  stage.querySelectorAll('[data-date]').forEach((el) => { el.textContent = dateText(now); });
}
setInterval(tickClock, CLOCK_MS);

// -- écrans -------------------------------------------------------------------

/** Veille : heure, date, « Prêt » ; puis « Vu récemment » ou une affiche de la collection. */
function renderHome(data) {
  const now = new Date();
  const show = data.variant === 'showcase' ? data.showcase : null;
  if (show) {
    const meta = joinDot([show.year, (show.directors || []).join(', '), fmtMinutes(show.duration_min)]);
    return `<div class="screen home">
<div class="showcase-art"><div class="rc-zoom">${poster(show.poster, show.title_fr, show.year)}</div>
<div class="showcase-shade"></div></div>
<div class="showcase-pane rc-drift">
${brand()}
<div class="showcase-clockrow"><span class="clock" data-clock>${clockText(now)}</span>${ready()}</div>
<span class="date" data-date>${dateText(now)}</span>
<div class="showcase-info">
<span class="showcase-head">DANS LA COLLECTION</span>
<span class="showcase-title">${esc(show.title_fr)}</span>
${differs(show.title_vo, show.title_fr) ? `<span class="showcase-vo">${esc(show.title_vo)}</span>` : ''}
${meta ? `<span class="showcase-meta">${esc(meta)}</span>` : ''}
${show.collection_name ? `<span class="showcase-coll">${esc(show.collection_name)}</span>` : ''}
</div></div></div>`;
  }
  const recent = (data.recent || []).slice(0, RECENT_SHOWN);
  const items = recent.map((w) => {
    let mark = '';
    if (w.watched) mark = `<div class="seen-badge">${ICON.check(22)}</div>`;
    else if (w.progress_ratio) {
      const pct = Math.round(w.progress_ratio * 100);
      mark = `<div class="thumb-bar" role="img" aria-label="Entamé, ${pct} %"><span style="width:${pct}%"></span></div>`;
    }
    return `<div class="recent-item"><div style="position:relative">`
      + `${poster(w.poster, w.title_fr, w.year)}${mark}</div>`
      + `<span class="recent-title">${esc(w.title_fr)}</span></div>`;
  }).join('');
  return `<div class="screen home">
<div class="home-pane rc-drift">
${brand()}
<div class="home-clockbox">
<span class="clock" data-clock>${clockText(now)}</span>
<span class="date" data-date>${dateText(now)}</span>
<div style="margin-top:16px">${ready()}</div>
</div>
<div class="home-foot">
${recent.length ? `<div class="recent"><span class="recent-head">VU RÉCEMMENT</span><div class="recent-row">${items}</div></div>` : '<div></div>'}
<div class="hint">${ICON.phone(34)}Choisissez une œuvre sur le téléphone</div>
</div></div></div>`;
}
/** Lecture : affiche, titre, épisode, avancement. Reste lisible si la fenêtre du
    navigateur passe devant mpv. data = { work, season, current }. */
function renderPlaying({ work, season, current: cur }) {
  const title = work ? work.title_fr : (cur && cur.title) || '';
  let sub = '';
  if (cur && cur.target_kind === 'episode') {
    const code = cur.title && cur.title.includes(' — ') ? cur.title.split(' — ').pop() : '';
    const ep = season ? (season.episodes || []).find((e) => e.id === cur.target_id) : null;
    sub = joinDot([code, ep && ep.title]);
  } else if (work) {
    sub = joinDot([work.year, (work.directors || []).join(', ')]);
  }
  const mf = work && cur ? (work.media_files || []).find((m) => m.id === cur.media_file_id) : null;
  const art = work ? poster(work.poster, title, work.year)
    : `<div class="poster noposter">${noPoster(title, null)}</div>`;
  return `<div class="screen playing">
<div class="playing-art">${art}</div>
<div class="playing-info rc-enter">
<span class="playing-head">${ICON.play(28)}LECTURE${mf ? `<span class="playing-quality">${esc(mf.quality)}</span>` : ''}</span>
<h1 class="playing-title${title.length > 40 ? ' very-long' : title.length > 22 ? ' long' : ''}">${esc(title)}</h1>
${sub ? `<span class="playing-sub">${esc(sub)}</span>` : ''}
<div class="playing-progress">
<span class="bar playing-bar"><span data-progress></span></span>
<span class="playing-times"><span data-elapsed></span><span data-paused class="playing-paused">En pause</span></span>
</div>
<div class="playing-hint">${ICON.screen(30)}La vidéo s'affiche dans le lecteur.</div>
</div></div>`;
}

const PROGRESS_MS = 1000;
let progressAt = 0;
let progressTimer = null;

/** Avancement de l'écran « playing » : au plus une fois par seconde (le dernier état
    retenu est appliqué en fin d'intervalle), sans re-rendu ; barre animée par transform. */
function updateProgress(force) {
  const wait = PROGRESS_MS - (Date.now() - progressAt);
  if (!force && wait > 0) {
    if (!progressTimer) progressTimer = setTimeout(() => updateProgress(true), wait);
    return;
  }
  clearTimeout(progressTimer);
  progressTimer = null;
  const st = playerState;
  const bar = stage.querySelector('[data-progress]');
  if (!st || !bar) return;
  progressAt = Date.now();
  const ratio = st.duration ? Math.min(1, Math.max(0, st.position / st.duration)) : 0;
  bar.style.transform = `scaleX(${ratio})`;
  stage.querySelector('[data-elapsed]').textContent = st.duration
    ? `${fmtClock(st.position)} / ${fmtClock(st.duration)}` : fmtClock(st.position);
  stage.querySelector('[data-paused]').classList.toggle('on', !!st.paused);
}

/** Message centré (erreur de lecture, disque absent…). */
function renderMessage({ message }) {
  const icon = /^Disque/.test(message || '') ? ICON.drive(64, 1.8) : ICON.warn(64);
  return `<div class="screen message">
<div class="message-brand">${brand()}</div>
<div class="message-box rc-enter">
<span class="message-icon">${icon}</span>
<span class="message-text">${esc(message || '')}</span>
</div></div>`;
}

// -- chargement et affichage ----------------------------------------------------

let fetchOverride = null; // aperçu : données de fiche lues dans dev/preview.json

async function getJson(url) {
  if (fetchOverride) return fetchOverride(url);
  const r = await fetch(url, { cache: 'no-store' });
  if (!r.ok) throw new Error(`${url} : ${r.status}`);
  return r.json();
}
/** Épisode en lecture ou en pause : { id, paused }, sinon null. */
function activeEpisode() {
  const cur = current;
  if (!cur || cur.target_kind !== 'episode' || !playerState || !playerState.running) return null;
  return { id: cur.target_id, paused: !!playerState.paused };
}

function mount(html) {
  stage.innerHTML = html;
  guardPosters(stage);
  fitSynopsis(stage);
}

// Polices chargées après coup : les lignes changent de largeur, le résumé est remesuré.
if (document.fonts) document.fonts.addEventListener('loadingdone', () => fitSynopsis(stage));

/** Copie figée d'une image chargée, à sa taille affichée : elle reste visible dans
    l'écran qui s'efface pendant que l'image elle-même passe dans le nouvel écran. */
function imageStill(img) {
  const still = document.createElement('canvas');
  still.width = img.naturalWidth;
  still.height = img.naturalHeight;
  try {
    still.getContext('2d').drawImage(img, 0, 0);
  } catch {
    return null;
  }
  const cs = getComputedStyle(img);
  still.style.cssText = `display:block;width:${img.offsetWidth}px;height:${img.offsetHeight}px;`
    + `object-fit:${cs.objectFit};object-position:${cs.objectPosition}`;
  return still;
}

/** Reprend dans `next` les images déjà chargées de l'écran affiché (même adresse) :
    les affiches et l'image de pause ne sont ni rechargées ni redessinées. L'écran
    affiché garde à leur place une copie figée, pour ne jamais les voir disparaître. */
function keepImages(next) {
  const old = new Map();
  stage.querySelectorAll('img').forEach((img) => {
    const src = img.getAttribute('src');
    if (src && !old.has(src)) old.set(src, img);
  });
  next.querySelectorAll('img').forEach((img) => {
    const prev = old.get(img.getAttribute('src'));
    if (!prev || !prev.complete || !prev.naturalWidth) return;
    const still = imageStill(prev);
    if (!still) return;
    old.delete(img.getAttribute('src'));
    prev.replaceWith(still);
    for (const { name, value } of [...img.attributes]) {
      if (name !== 'src') prev.setAttribute(name, value);
    }
    img.replaceWith(prev);
  });
}

const FADE_MS = 200;

/** Remplace l'écran par un fondu d'opacité (200 ms) au-dessus de l'ancien. */
function mountFade(html) {
  const tpl = document.createElement('template'); // contenu inerte : aucune image chargée
  tpl.innerHTML = html.trim();
  const next = tpl.content.firstElementChild;
  guardPosters(next);
  keepImages(next);
  next.classList.add('screen-fade');
  let done = false;
  const finish = () => {
    if (done || !next.isConnected) return;
    done = true;
    next.classList.remove('screen-fade');
    while (next.previousElementSibling) next.previousElementSibling.remove(); // pas un écran plus récent
  };
  next.addEventListener('animationend', (ev) => { if (ev.target === next) finish(); });
  setTimeout(finish, FADE_MS + 100); // onglet caché, animations réduites : pas d'animationend
  stage.appendChild(next);
  fitSynopsis(next);
}

async function load(state) {
  switch (state.screen) {
    case 'work':
      return renderWork(await getJson(`/api/works/${state.work_id}`));
    case 'season': {
      const [season, work] = await Promise.all([
        getJson(`/api/seasons/${state.season_id}`), getJson(`/api/works/${state.work_id}`)]);
      return renderSeason({ season, work }, { active: activeEpisode() });
    }
    case 'playing': {
      const cur = current;
      const [work, season] = await Promise.all([
        state.work_id != null ? getJson(`/api/works/${state.work_id}`).catch(() => null) : null,
        cur && cur.season_id != null
          ? getJson(`/api/seasons/${cur.season_id}`).catch(() => null) : null]);
      return renderPlaying({ work, season, current: cur });
    }
    case 'message':
      return renderMessage({ message: state.message });
    case 'view':
      return await renderView(state.view, state.pause);
    case 'paused':
      if (state.pause) return renderPausedFull(state.pause);
    // falls through : plein écran sans pause, on retombe sur la veille
    default: {
      const home = await getJson('/api/tv/home').catch(() => ({ recent: [], showcase: null }));
      const both = home.recent.length && home.showcase;
      const variant = both ? (homeTurn % 2 ? 'showcase' : 'recent')
        : home.showcase && !home.recent.length ? 'showcase' : 'recent';
      return renderHome({ ...home, variant });
    }
  }
}

/** Rendu comparable d'un rendu à l'autre : l'heure et la date (tenues à jour sur place)
    et l'animation d'entrée n'y comptent pas. */
function stableHtml(html) {
  return html.replace(/(data-(?:clock|date)>)[^<]*/g, '$1').replace(/ rc-enter\b/g, '');
}

/** Affiche `state`. `refresh` : même état redessiné après une annonce `catalog` ; rien
    ne bouge si le rendu est identique. */
async function show(state, { refresh = false } = {}) {
  const my = ++seq;
  clearTimeout(homeTimer);
  homeTimer = null;
  if (state.screen !== 'view') sheetCache.clear();
  let html;
  let failed = false;
  try {
    html = await load(state);
  } catch (err) {
    console.warn('écran télé :', err);
    html = renderMessage({ message: MSG_SHEET_UNAVAILABLE });
    failed = true;
  }
  if (my !== seq) return;
  const stable = stableHtml(html);
  if (refresh && state.screen === shown && stable === shownHtml) {
    // contenu inchangé : pas de fondu ni de nouveau montage
  } else if (state.screen === 'paused' && shown === 'paused'
      && refreshPausedFull(stage, state.pause)) {
    // même image : mise à jour sur place, la dérive de l'image continue sans à-coup
  } else if (state.screen === shown && FADE_SCREENS.has(shown)) mountFade(html);
  else mount(html);
  shown = state.screen;
  shownHtml = stable;
  shownSheetKey = state.screen === 'view' && !failed ? sheetKey(state.view) : null;
  if (state.screen === 'playing') updateProgress(true);
  if (state.screen === 'home') {
    homeTimer = setTimeout(() => { homeTimer = null; homeTurn += 1; show(tv); }, HOME_ROTATE_MS);
  }
}

// -- WebSocket ------------------------------------------------------------------

function connect() {
  const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
  const ws = new WebSocket(`${proto}//${location.host}/api/events`);
  ws.onmessage = (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch { return; }
    if (msg.type === 'hello') {
      current = msg.player ? msg.player.current : null;
      playerState = msg.player ? msg.player.state : null;
      tv = msg.tv;
      show(tv);
    } else if (msg.type === 'tv') {
      tv = msg.state;
      show(tv);
    } else if (msg.type === 'player') {
      const before = current;
      current = msg.current;
      playerState = msg.state;
      // La télé passe en « playing » avant l'annonce du média : on redessine à son arrivée.
      if (tv.screen === 'playing' && current && (!before
          || before.media_file_id !== current.media_file_id
          || before.target_id !== current.target_id)) {
        show(tv);
      } else if (tv.screen === 'playing') {
        updateProgress(false);
      } else if (tv.pause) {
        updatePause(stage, playerState);
      }
    } else if (msg.type === 'catalog' && tv.screen !== 'playing') {
      sheetCache.clear();
      // Veille : la vitrine est tirée au hasard à chaque chargement ; elle se met à jour
      // à la prochaine alternance (3 min) plutôt qu'à chaque annonce de l'analyse.
      if (tv.screen === 'home' && shown === 'home' && homeTimer !== null) return;
      show(tv, { refresh: true }); // fiches rechargées après chaque import ou analyse
    }
  };
  ws.onclose = () => setTimeout(connect, RECONNECT_MS);
}

// -- aperçu de développement : ?preview=<nom> (dev/preview.json), sans WebSocket ----

/** Un aperçu est un état télé, avec en plus, facultatifs : `fetch` (réponses de l'API
    par adresse, à la place du serveur) et `player` ({ current, state }). */
async function preview(name) {
  window.rcShow = (state) => {
    const { fetch: answers, player, ...rest } = state;
    if (answers) {
      fetchOverride = async (url) => {
        if (!(url in answers)) throw new Error(`${url} : absent de l'aperçu`);
        return structuredClone(answers[url]);
      };
    }
    if (player) {
      current = player.current;
      playerState = player.state;
    }
    tv = rest;
    show(tv);
  };
  try {
    const states = await (await fetch('dev/preview.json', { cache: 'no-store' })).json();
    if (!states[name]) throw new Error(`aperçu inconnu : ${name}`);
    window.rcShow(states[name]);
  } catch (err) {
    console.warn('aperçu télé :', err);
    mount(renderMessage({ message: `Aperçu «${NBSP}${name}${NBSP}» introuvable` }));
  }
}

mount(renderHome({ recent: [], showcase: null, variant: 'recent' }));
const previewName = new URLSearchParams(location.search).get('preview');
if (previewName) preview(previewName);
else connect();
