/* Écran télé : outils communs (mise en forme, composants, horloge), partagés par pause.js,
   sheets.js, views.js et app.js. Chargé en premier : ne dépend d'aucun autre fichier. */
'use strict';

const NBSP = ' ';
const READABLE = ['UHD', '2HD', 'FHD', 'HD', 'SD', 'BD'];
const KIND = {
  film: 'Film', doc: 'Documentaire', serie: 'Série', theatre: 'Théâtre',
  tele: 'Téléfilm', spectacle: 'Spectacle',
};

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

/** Secondes -> « 1:02:03 » ou « 12:03 ». */
function fmtClock(sec) {
  const s = Math.max(0, Math.floor(sec || 0));
  const h = Math.floor(s / 3600);
  const mm = String(Math.floor((s % 3600) / 60));
  const ss = String(s % 60).padStart(2, '0');
  return h ? `${h}:${mm.padStart(2, '0')}:${ss}` : `${mm}:${ss}`;
}
