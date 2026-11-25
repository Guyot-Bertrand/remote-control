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
let tv = { screen: 'home', work_id: null, season_id: null, message: null, view: null, pause: null };
let current = null; // lecture en cours (player.current), pour le titre de l'écran « playing »
let playerState = null; // dernier état du lecteur (position, durée, pause)
let seq = 0; // jeton de rendu : une réponse arrivée après un changement d'écran est ignorée
let homeTurn = 0;
let homeTimer = null;
let shown = null; // écran actuellement monté (state.screen)
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

// Libellés de langue communs ; une autre langue s'affiche en code majuscule.
const LANG_LABELS = {
  fr: 'Français', en: 'Anglais', de: 'Allemand', es: 'Espagnol',
  it: 'Italien', ja: 'Japonais', nl: 'Néerlandais', pt: 'Portugais',
};

/** « Anglais », ou « KO » pour une langue sans libellé. */
function langLabel(code) {
  return LANG_LABELS[code] || String(code || '').toUpperCase();
}

/** « ST français », « ST anglais », « ST KO ». */
function subtitleLabel(code) {
  return `ST ${LANG_LABELS[code] ? LANG_LABELS[code].toLowerCase() : String(code || '').toUpperCase()}`;
}

/** Langues audio d'une version : « Français », « FR · VO » ou « Anglais ». */
function audioSummary(langs) {
  if (!langs || !langs.length) return '';
  if (langs.includes('fr')) return langs.length > 1 ? 'FR · VO' : langLabel('fr');
  return langs.map(langLabel).join(' · ');
}

/** Sous-titres d'une version : « ST français » s'il y en a, sinon la première langue. */
function subtitleSummary(langs) {
  if (!langs || !langs.length) return '';
  return subtitleLabel(langs.includes('fr') ? 'fr' : langs[0]);
}

/** Pastilles de langue d'une version analysée (audio, puis sous-titres). */
function versionLangs(m) {
  return [audioSummary(m.audio_langs), subtitleSummary(m.subtitle_langs)].filter(Boolean);
}

/** « 1920 × 1080 » (vide tant que le fichier n'est pas analysé). */
function resolution(m) {
  return m.width && m.height ? `${m.width}${NBSP}×${NBSP}${m.height}` : '';
}

/** Versions lisibles, la version jouée par défaut d'abord (copie de l'ordre de
    rc/player/session.py _choose_version : disque branché, puis meilleure qualité). */
function readableVersions(files) {
  return (files || []).filter((m) => READABLE.includes(m.quality))
    .sort((a, b) => (!a.present - !b.present) || READABLE.indexOf(a.quality) - READABLE.indexOf(b.quality));
}

function diskAbsentText(label) {
  return label ? `Disque «${NBSP}${label}${NBSP}» non branché` : 'Disque non branché';
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

const VERSION_ROWS = 3; // lignes de pastilles (une par version) au plus

/** Pastille de qualité, avec la résolution quand le fichier est analysé. */
function qualityPill(m) {
  const res = resolution(m);
  return `<span class="pill pill-quality">${esc(m.quality)}`
    + (res ? `<span class="pill-sep"></span><span class="pill-res">${res}</span>` : '') + '</span>';
}

/** Une ligne de pastilles par version : qualité et résolution, langues audio, sous-titres.
    Sans langue connue par l'analyse, elles viennent des étiquettes [FR]/[VO] de l'œuvre. */
function versionPills(w, readable) {
  const langPill = (l) => `<span class="pill pill-lang">${esc(l)}</span>`;
  const analysed = readable.some((m) => (m.audio_langs || []).length);
  const tagLangs = analysed ? [] : [...new Set((w.tags || []).map((t) => LANG_TAGS[t]).filter(Boolean))];
  const rows = readable.map((m, i) => qualityPill(m)
    + versionLangs(m).map(langPill).join('') + (i === 0 ? tagLangs.map(langPill).join('') : ''));
  if (!rows.length) rows.push(tagLangs.map(langPill).join(''));
  return [...new Set(rows.filter(Boolean))].slice(0, VERSION_ROWS)
    .map((r) => `<div class="pills">${r}</div>`).join('');
}

/** Fiche d'une œuvre ; `pause` : vidéo en pause dans le coin d'état ; `enter` : entrée
    animée (pas lors d'une mise à jour de la même fiche). */
function renderWork(w, { pause = null, enter = true } = {}) {
  const readable = readableVersions(w.media_files);
  const title = w.title_fr;
  const titleCls = title.length > 50 ? 'very-long' : title.length > 22 ? 'long' : '';
  const genres = (w.genres || []).map(capitalize).join(', ');
  const p = w.progress;

  let status = '';
  if (!readable.length) {
    const text = joinDot(['Non numérisé', w.medium]);
    status = `<div class="status status-off">${ICON.disc(38)}${esc(text)}</div>`;
  } else if (!readable.some((m) => m.present)) {
    const label = (readable.find((m) => m.volume_label) || {}).volume_label;
    status = `<div class="status status-warn">${ICON.drive(38)}${esc(diskAbsentText(label))}</div>`;
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
  const pills = versionPills(w, readable);
  const directors = (w.directors || []).join(', ');

  return `<div class="screen work">
${poster(w.poster, title, w.year)}
${renderStateCorner(pause)}
<div class="work-info${enter ? ' rc-enter' : ''}">
<div class="work-head">
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
${pills ? `<div class="versions">${pills}</div>` : ''}
</div></div></div>`;
}

// Saison (maquette 4b) : trois colonnes de huit lignes sous l'en-tête et le coin d'état.
const EPISODE_COLS = 3;
const EPISODE_ROW_H = 76;
const EPISODE_GAP = 8;
const EPISODE_ROOM = 1080 - 348 - 64;
const EPISODE_SEASON = new Map(); // épisode -> saison, pour la vue « version et pistes »

/** Hauteur des lignes : 76 px jusqu'à 24 épisodes, plus basses au-delà. */
function episodeLayout(n) {
  const rows = Math.max(1, Math.ceil(n / EPISODE_COLS));
  const height = Math.min(EPISODE_ROW_H, Math.floor((EPISODE_ROOM - (rows - 1) * EPISODE_GAP) / rows));
  return { rows, height };
}

/** Durée d'un épisode en minutes : analyse, sinon progression, sinon nom du fichier. */
function episodeMinutes(e) {
  if (e.duration_min != null) return e.duration_min;
  const p = e.progress;
  if (p && p.duration) return p.duration / 60;
  return minutesFromPath(((e.media_files || [])[0] || {}).relpath);
}

/** « À suivre » : l'épisode mis en avant par le serveur ; le suivant s'il est déjà vu
    ou s'il est celui en cours de lecture. */
function nextEpisodeId(eps, highlightId, activeId) {
  const i = eps.findIndex((e) => e.id === highlightId);
  if (i < 0) return null;
  const e = eps[i];
  const done = (e.progress && e.progress.watched) || e.id === activeId;
  if (!done) return e.id;
  return i + 1 < eps.length ? eps[i + 1].id : null;
}

/** Marque d'épisode (pastille). */
function episodeBadge(kind) {
  if (kind === 'selected') return `<span class="ep-badge ep-badge-selected">${ICON.phone(22)}Sélectionné</span>`;
  if (kind === 'next') return `<span class="ep-badge">${ICON.play(20)}À suivre</span>`;
  if (kind === 'paused') return `<span class="ep-badge ep-badge-paused">${pauseIcon(20)}En pause</span>`;
  return '<span class="ep-badge">En cours</span>';
}

/** Langues des fichiers de la saison : « Anglais · ST français ». */
function seasonLangs(eps) {
  const audio = [];
  const subs = [];
  for (const e of eps) {
    for (const m of e.media_files || []) {
      (m.audio_langs || []).forEach((l) => { if (!audio.includes(l)) audio.push(l); });
      (m.subtitle_langs || []).forEach((l) => { if (!subs.includes(l)) subs.push(l); });
    }
  }
  return joinDot([audioSummary(audio), subtitleSummary(subs)]);
}

/** Fiche d'une saison : data = { season, work } (/api/seasons/{id} et /api/works/{id}).
    Marques : `selectedId` (épisode montré par le téléphone), « À suivre », et l'épisode
    en lecture ou en pause (`active` : { id, paused }). */
function renderSeason({ season, work }, { selectedId = null, active = null, pause = null, enter = true } = {}) {
  const eps = season.episodes || [];
  eps.forEach((e) => EPISODE_SEASON.set(e.id, season.id));
  const activeId = active ? active.id : null;
  const nextId = nextEpisodeId(eps, season.highlight_episode_id, activeId);
  const seenCount = eps.filter((e) => e.progress && e.progress.watched).length;
  const L = episodeLayout(eps.length);
  const cls = L.height < 60 ? ' dense' : '';
  const rows = eps.map((e) => {
    const p = e.progress;
    const seen = !!(p && p.watched);
    const started = isResumable(p);
    const flags = { selected: e.id === selectedId, next: e.id === nextId, active: e.id === activeId };
    let dur = fmtMinutes(episodeMinutes(e));
    if (started && p.duration) dur = joinDot([dur, `reste ${Math.max(0, Math.round((p.duration - p.position) / 60))}`]);
    let mark = '';
    if (flags.selected) mark = `<span class="ep-mark ep-mark-selected">${ICON.phone(30)}</span>`;
    else if (flags.active) mark = `<span class="ep-mark ep-mark-active">${active.paused ? pauseIcon(28) : ICON.play(28)}</span>`;
    else if (flags.next) mark = `<span class="ep-mark ep-mark-next">${ICON.play(28)}</span>`;
    else if (seen) mark = `<span class="ep-mark">${ICON.check(28)}</span>`;
    const pct = started && p.duration ? Math.min(100, Math.round((p.position / p.duration) * 100)) : 0;
    const rowCls = ['ep', seen && 'seen', flags.next && 'next', flags.selected && 'selected'].filter(Boolean).join(' ');
    return `<div class="${rowCls}"><div class="ep-line">`
      + `<span class="ep-num">${e.number}</span>`
      + `<span class="ep-title">${esc(e.title || `Épisode ${e.number}`)}</span>`
      + (dur ? `<span class="ep-dur">${esc(dur)}</span>` : '') + mark + '</div>'
      + (pct ? `<span class="ep-bar"><span style="width:${pct}%"></span></span>` : '') + '</div>';
  }).join('');

  const byId = (id) => eps.find((e) => e.id === id);
  const markLine = (kind, e) => (e ? `<span class="season-mark">${episodeBadge(kind)}`
    + `<span class="season-mark-ep">${esc(`${e.number} · ${e.title || `Épisode ${e.number}`}`)}</span></span>` : '');
  const marks = markLine(active && active.paused ? 'paused' : 'playing', byId(activeId))
    + markLine('next', byId(nextId)) + markLine('selected', byId(selectedId));

  const w = work;
  const plural = (n, word) => `${n}${NBSP}${word}${n > 1 ? 's' : ''}`;
  const sideMeta = [
    joinDot([w.year, (w.genres || []).map(capitalize).join(', ')]),
    (w.directors || []).join(', '),
    seasonLangs(eps),
  ].filter(Boolean).map(esc).join('<br>');
  return `<div class="screen season">
<div class="season-side">
${poster(w.poster, w.title_fr, w.year)}
<span class="overline">${esc((KIND[w.type] || w.type || '').toUpperCase())}</span>
${sideMeta ? `<span class="season-meta">${sideMeta}</span>` : ''}
</div>
<div class="season-head">
<h1 class="season-work${w.title_fr.length > 24 ? ' long' : ''}">${esc(w.title_fr)}</h1>
<div class="season-top">
<h2 class="season-label">${esc(season.label)}</h2>
<span class="season-count">${plural(eps.length, 'épisode')} · ${plural(seenCount, 'vu')}</span>
</div>
${marks ? `<div class="season-marks">${marks}</div>` : ''}
</div>
${renderStateCorner(pause)}
<div class="season-main${enter ? ' rc-enter' : ''}">
<div class="episodes${cls}" style="grid-template-rows:repeat(${L.rows},${L.height}px)">${rows}</div>
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

let fetchOverride = null; // aperçu : données de fiche lues dans dev/preview.json

async function getJson(url) {
  if (fetchOverride) return fetchOverride(url);
  const r = await fetch(url, { cache: 'no-store' });
  if (!r.ok) throw new Error(`${url} : ${r.status}`);
  return r.json();
}

// Fiches des vues du téléphone, gardées d'une vue à l'autre (épisode sélectionné, pistes) :
// pas de rechargement ; vidées dès que la télé quitte les vues et à chaque import.
const sheetCache = new Map();

function sheetJson(url) {
  if (!sheetCache.has(url)) {
    const p = getJson(url);
    sheetCache.set(url, p);
    p.catch(() => sheetCache.delete(url));
  }
  return sheetCache.get(url);
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
}

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

async function show(state) {
  const my = ++seq;
  clearTimeout(homeTimer);
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
  if (state.screen === 'paused' && shown === 'paused' && refreshPausedFull(stage, state.pause)) {
    // même image : mise à jour sur place, la dérive de l'image continue sans à-coup
  } else if (state.screen === shown && FADE_SCREENS.has(shown)) mountFade(html);
  else mount(html);
  shown = state.screen;
  shownSheetKey = state.screen === 'view' && !failed ? sheetKey(state.view) : null;
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
      } else if (tv.pause) {
        updatePause(stage, playerState);
      }
    } else if (msg.type === 'catalog' && tv.screen !== 'playing') {
      sheetCache.clear();
      show(tv); // fiches rechargées après chaque import
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
