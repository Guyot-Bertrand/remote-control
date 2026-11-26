/* Écran télé : fiches d'une œuvre et d'une saison, et leur cache de données.
   Chargé après common.js et pause.js, avant views.js et app.js ; utilise getJson, current
   et playerState d'app.js au moment du rendu. */
'use strict';

const STAGED = new Set(['theatre', 'spectacle']); // « Mise en scène » plutôt que « Réalisation »
const LANG_TAGS = { '[FR]': 'Français', '[VO]': 'VO' };

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
