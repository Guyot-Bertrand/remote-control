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
const NBSP = ' ';
const READABLE = ['UHD', '2HD', 'FHD', 'HD', 'SD', 'BD'];
const KIND = {
  film: 'Film', doc: 'Documentaire', serie: 'Série', theatre: 'Théâtre',
  tele: 'Téléfilm', spectacle: 'Spectacle',
};
const STAGED = new Set(['theatre', 'spectacle']); // « Mise en scène » plutôt que « Réalisation »
const LANG_TAGS = { '[FR]': 'Français', '[VO]': 'VO' };

const stage = document.getElementById('stage');
let tv = { screen: 'home', work_id: null, season_id: null, message: null };
let current = null; // lecture en cours (player.current), pour le titre de l'écran « playing »
let playerState = null; // dernier état du lecteur (position, durée, pause)
let seq = 0; // jeton de rendu : une réponse arrivée après un changement d'écran est ignorée
let homeTurn = 0;
let homeTimer = null;

// -- mise à l'échelle du canevas ------------------------------------------

function fit() {
  const s = Math.min(window.innerWidth / 1920, window.innerHeight / 1080);
  const x = (window.innerWidth - 1920 * s) / 2;
  const y = (window.innerHeight - 1080 * s) / 2;
  stage.style.transform = `translate(${x}px, ${y}px) scale(${s})`;
}
window.addEventListener('resize', fit);
fit();

// -- outils de mise en forme ------------------------------------------------

function esc(v) {
  return String(v ?? '').replace(/[&<>"']/g, (c) => (
    { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

/** Minutes -> « 1 h 39 », « 45 min ». */
function fmtMinutes(min) {
  if (min == null || !isFinite(min)) return '';
  const m = Math.max(0, Math.round(min));
  if (m < 60) return `${m}${NBSP}min`;
  return `${Math.floor(m / 60)}${NBSP}h${NBSP}${String(m % 60).padStart(2, '0')}`;
}

/** Secondes -> « 1 h 12 » (position, arrondie à la minute inférieure). */
function fmtPosition(sec) {
  return fmtMinutes(Math.floor((sec || 0) / 60));
}

function capitalize(s) {
  return s ? s.charAt(0).toUpperCase() + s.slice(1) : s;
}

function joinDot(parts) {
  return parts.filter((p) => p !== null && p !== undefined && p !== '').join(' · ');
}

function differs(a, b) {
  return a && b && a.trim().toLowerCase() !== b.trim().toLowerCase();
}

/** Durée d'un fichier, lue dans son nom (« [0h27,1920x1080,…] »), en minutes. */
function minutesFromPath(relpath) {
  const m = /\[(\d+)h(\d{2})[,\]]/.exec(relpath || '');
  return m ? Number(m[1]) * 60 + Number(m[2]) : null;
}

function posterUrl(hash) {
  return `/api/assets/${encodeURIComponent(hash)}/tv.jpg`;
}

function isResumable(p) {
  return !!(p && !p.watched && p.position > 0);
}

// -- composants ---------------------------------------------------------------

const ICON = {
  check: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" role="img" aria-label="Vu"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg>`,
  clock: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="8.5"/><path d="M12 7.5V12l3 2"/></svg>`,
  disc: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="12" cy="12" r="8.5"/><circle cx="12" cy="12" r="2.5"/></svg>`,
  drive: (s, w = 2) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${w}" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="7" width="18" height="10" rx="2"/><circle cx="7.5" cy="12" r="1" fill="currentColor"/><path d="M11 12h6"/></svg>`,
  warn: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M12 3.5 2.8 19.5h18.4z"/><path d="M12 10v4.2M12 17h.01"/></svg>`,
  phone: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="7" y="2.5" width="10" height="19" rx="3"/><circle cx="12" cy="8" r="2"/><path d="M10 13h.01M14 13h.01M10 16.5h.01M14 16.5h.01"/></svg>`,
  screen: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="4.5" width="18" height="12" rx="2"/><path d="M9 20h6M12 16.5V20"/></svg>`,
  play: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 4.5v15l12.5-7.5z" fill="currentColor"/></svg>`,
};

function brand() {
  return '<div class="brand"><span class="brand-dot"></span><span class="brand-name">Remote-Control</span></div>';
}

function ready() {
  return '<div class="ready"><span class="ready-dot"></span>Prêt</div>';
}

/** Carte « sans affiche » : titre en Fraunces et année, sur surface, filet laiton. */
function noPoster(title, year) {
  return `<div class="noposter-frame"><div class="noposter-rule"></div>`
    + `<div class="noposter-title">${esc(title)}</div>`
    + (year ? `<div class="noposter-year">${esc(year)}</div>` : '') + '</div>';
}

/** Affiche (image 600 px) ou carte de remplacement. */
function poster(hash, title, year) {
  if (!hash) {
    return `<div class="poster noposter" role="img" aria-label="Sans affiche : ${esc(title)}">`
      + `${noPoster(title, year)}</div>`;
  }
  return `<div class="poster" role="img" aria-label="Affiche : ${esc(title)}">`
    + `<img src="${posterUrl(hash)}" alt="" data-title="${esc(title)}" data-year="${esc(year ?? '')}"></div>`;
}

/** Image d'affiche illisible : on bascule sur la carte « sans affiche ». */
function guardPosters(root) {
  root.querySelectorAll('.poster img').forEach((img) => {
    img.addEventListener('error', () => {
      const box = img.parentElement;
      box.classList.add('noposter');
      box.innerHTML = noPoster(img.dataset.title, img.dataset.year);
    }, { once: true });
  });
}

function clockText(now) {
  return now.toLocaleTimeString('fr-FR', { hour: '2-digit', minute: '2-digit' });
}

function dateText(now) {
  return now.toLocaleDateString('fr-FR', { weekday: 'long', day: 'numeric', month: 'long' });
}

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

/** Fiche d'une œuvre. */
function renderWork(w) {
  const files = w.media_files || [];
  const readable = files.filter((m) => READABLE.includes(m.quality));
  const title = w.title_fr;
  const titleCls = title.length > 50 ? 'very-long' : title.length > 22 ? 'long' : '';
  const genres = (w.genres || []).map(capitalize).join(', ');
  const p = w.progress;

  let status = '';
  if (!readable.length) {
    status = `<div class="status status-off">${ICON.disc(38)}Non numérisé</div>`;
  } else if (!readable.some((m) => m.present)) {
    const label = (readable.find((m) => m.volume_label) || {}).volume_label;
    const text = label ? `Disque «${NBSP}${label}${NBSP}» non branché` : 'Disque non branché';
    status = `<div class="status status-warn">${ICON.drive(38)}${esc(text)}</div>`;
  }

  let progress = '';
  if (isResumable(p)) {
    const pct = p.duration ? Math.min(100, Math.round((p.position / p.duration) * 100)) : 0;
    progress = `<div class="resume"><span class="resume-label">${ICON.clock(36)}`
      + `Reprise possible à ${fmtPosition(p.position)}</span>`
      + (pct ? `<span class="bar"><span style="width:${pct}%"></span></span>` : '') + '</div>';
  } else if (p && p.watched) {
    progress = `<div class="seen-line">${ICON.check(34)}Déjà vu</div>`;
  }

  const cast = (w.cast || []).slice(0, 6);
  const colls = (w.collections || []).slice(0, 3).map((c) => (
    `<div class="coll"><span class="coll-rank">${c.rank ? `n°${NBSP}${esc(c.rank)}` : '—'}</span>`
    + `<span>${esc(c.name)}</span></div>`)).join('');
  const qualities = READABLE.filter((q) => readable.some((m) => m.quality === q));
  const langs = (w.tags || []).map((t) => LANG_TAGS[t]).filter(Boolean);
  const pills = qualities.map((q) => `<span class="pill pill-quality">${q}</span>`).join('')
    + [...new Set(langs)].map((l) => `<span class="pill pill-lang">${esc(l)}</span>`).join('');
  const directors = (w.directors || []).join(', ');

  return `<div class="screen work">
${poster(w.poster, title, w.year)}
<div class="work-info rc-enter">
<div>
<span class="overline">${esc((KIND[w.type] || w.type || '').toUpperCase())}</span>
<h1 class="work-title ${titleCls}">${esc(title)}</h1>
${differs(w.title_vo, title) ? `<div class="work-vo">${esc(w.title_vo)}</div>` : ''}
</div>
<div class="work-meta">
<span>${esc(joinDot([w.year, fmtMinutes(w.duration_min), genres]))}</span>
${directors ? `<span><span class="muted">${STAGED.has(w.type) ? 'Mise en scène' : 'Réalisation'} : </span>${esc(directors)}</span>` : ''}
</div>
${status}${progress}
${w.synopsis ? `<p class="synopsis">${esc(w.synopsis)}</p>` : ''}
${cast.length ? `<div class="cast"><span class="muted">Avec </span>${esc(cast.join(', '))}</div>` : ''}
<div class="work-foot">
${colls ? `<div class="colls">${colls}</div>` : ''}
${pills ? `<div class="pills">${pills}</div>` : ''}
</div></div></div>`;
}

/** Disposition de la liste d'épisodes : colonnes et hauteur de ligne selon leur nombre. */
function episodeLayout(n) {
  const cols = n <= 7 ? 1 : n <= 16 ? 2 : 3;
  const rows = Math.max(1, Math.ceil(n / cols));
  const gap = cols === 1 ? 10 : 8;
  const avail = 1080 - 2 * 64 - 70 - 28; // sous l'en-tête « Saison n » (70 px) et son écart
  const height = Math.min(cols === 1 ? 116 : 100, Math.floor((avail - (rows - 1) * gap) / rows));
  return { cols, rows, gap, height };
}

/** Fiche d'une saison : data = { season, work } (/api/seasons/{id} et /api/works/{id}). */
function renderSeason({ season, work }) {
  const eps = season.episodes || [];
  const seenCount = eps.filter((e) => e.progress && e.progress.watched).length;
  const L = episodeLayout(eps.length);
  const cls = [L.cols > 1 ? 'compact' : '', L.cols > 2 || L.height < 84 ? 'dense' : ''].join(' ');
  const rows = eps.map((e) => {
    const p = e.progress;
    const seen = !!(p && p.watched);
    const started = isResumable(p);
    const isCurrent = e.id === season.highlight_episode_id;
    const file = (e.media_files || [])[0];
    const minutes = p && p.duration ? p.duration / 60 : minutesFromPath(file && file.relpath);
    let badge = '';
    if (isCurrent && started) badge = '<span class="ep-badge">En cours</span>';
    else if (isCurrent && !p) badge = '<span class="ep-badge">À suivre</span>';
    let bar = '';
    if (started) {
      const pct = p.duration ? Math.min(100, Math.round((p.position / p.duration) * 100)) : 0;
      const left = p.duration ? `<span class="ep-left">reste ${fmtPosition(p.duration - p.position)}</span>` : '';
      bar = `<div class="ep-progress"><span class="bar"><span style="width:${pct}%"></span></span>${left}</div>`;
    }
    return `<div class="ep${seen ? ' seen' : ''}${isCurrent ? ' current' : ''}">`
      + `<span class="ep-num">${e.number}</span>`
      + `<div class="ep-body"><div class="ep-line"><span class="ep-title">${esc(e.title || `Épisode ${e.number}`)}</span>${badge}</div>${bar}</div>`
      + `<span class="ep-dur">${fmtMinutes(minutes)}</span>`
      + `<span class="ep-mark">${seen ? ICON.check(34) : ''}</span></div>`;
  }).join('');
  const w = work;
  const titleCls = w.title_fr.length > 24 ? 'long' : '';
  const sideMeta = [
    joinDot([w.year, (w.genres || []).map(capitalize).join(', ')]),
    (w.directors || []).join(', '),
  ].filter(Boolean).map(esc).join('<br>');
  const plural = (n, word) => `${n}${NBSP}${word}${n > 1 ? 's' : ''}`;
  return `<div class="screen season">
<div class="season-side">
${poster(w.poster, w.title_fr, w.year)}
<div class="season-heading">
<span class="overline">${esc((KIND[w.type] || w.type || '').toUpperCase())}</span>
<h1 class="season-work ${titleCls}">${esc(w.title_fr)}</h1>
</div>
${sideMeta ? `<span class="season-meta">${sideMeta}</span>` : ''}
${w.synopsis ? `<p class="season-synopsis">${esc(w.synopsis)}</p>` : ''}
</div>
<div class="season-main rc-enter">
<div class="season-top">
<h2 class="season-label">${esc(season.label)}</h2>
<span class="season-count">${plural(eps.length, 'épisode')} · ${plural(seenCount, 'vu')}</span>
</div>
<div class="episodes ${cls}" style="grid-template-columns:repeat(${L.cols},minmax(0,1fr));grid-template-rows:repeat(${L.rows},${L.height}px);row-gap:${L.gap}px">${rows}</div>
</div></div>`;
}

/** Secondes -> « 1:02:03 » ou « 12:03 ». */
function fmtClock(sec) {
  const s = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(s / 3600);
  const mm = String(Math.floor((s % 3600) / 60));
  const ss = String(s % 60).padStart(2, '0');
  return h ? `${h}:${mm.padStart(2, '0')}:${ss}` : `${mm}:${ss}`;
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

async function getJson(url) {
  const r = await fetch(url, { cache: 'no-store' });
  if (!r.ok) throw new Error(`${url} : ${r.status}`);
  return r.json();
}

function mount(html) {
  stage.innerHTML = html;
  guardPosters(stage);
}

async function load(state) {
  switch (state.screen) {
    case 'work':
      return renderWork(await getJson(`/api/works/${state.work_id}`));
    case 'season': {
      const [season, work] = await Promise.all([
        getJson(`/api/seasons/${state.season_id}`), getJson(`/api/works/${state.work_id}`)]);
      return renderSeason({ season, work });
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
    default: {
      const home = await getJson('/api/tv/home').catch(() => ({ recent: [], showcase: null }));
      const both = home.recent.length && home.showcase;
      const variant = both ? (homeTurn % 2 ? 'showcase' : 'recent')
        : home.showcase && !home.recent.length ? 'showcase' : 'recent';
      return renderHome({ ...home, variant });
    }
  }
}

async function show(state) {
  const my = ++seq;
  clearTimeout(homeTimer);
  let html;
  try {
    html = await load(state);
  } catch (err) {
    console.warn('écran télé :', err);
    html = renderMessage({ message: MSG_SHEET_UNAVAILABLE });
  }
  if (my !== seq) return;
  mount(html);
  if (state.screen === 'playing') updateProgress(true);
  if (state.screen === 'home') {
    homeTimer = setTimeout(() => { homeTurn += 1; show(tv); }, HOME_ROTATE_MS);
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
      }
    } else if (msg.type === 'catalog' && tv.screen !== 'playing') {
      show(tv); // fiches rechargées après chaque import
    }
  };
  ws.onclose = () => setTimeout(connect, RECONNECT_MS);
}

mount(renderHome({ recent: [], showcase: null, variant: 'recent' }));
connect();
