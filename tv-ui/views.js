/* Écran télé : vues qui reproduisent le téléphone (arbitrages 14 à 16), d'après la
   maquette « Remote-Control - 4a - Télé, vues miroir ».
   - renderView(view, pause) aiguille sur view.screen : accueil, parcourir, collection,
     recherche ; un écran inconnu se replie sur l'accueil miroir.
   - Les fiches (œuvre, saison, version et pistes) chargent leurs données (sheetJson
     d'app.js, gardées d'une vue à l'autre) : renderView renvoie alors une promesse.
   - Chaque vue porte le coin d'état (renderStateCorner) : horloge, ou vidéo en pause.
   Chargé après pause.js et avant app.js ; utilise esc, poster, ICON, KIND, NBSP, joinDot,
   fmtMinutes et differs d'app.js au moment du rendu. Les affiches gardent le même `src`
   d'une vue à l'autre : le fondu de app.js les reprend sans les recharger. */
'use strict';

const BROWSE_COLS = 8;
const BROWSE_ROWS = 2;
const COLLECTION_ROWS = 12; // deux colonnes de six
const SEARCH_COLUMNS = 3;
const SEARCH_MIN_CHARS = 2;
const EVENING_HOUR = 18; // « Bonsoir » à partir de 18 h
// Accueil : hauteurs des rangées (px), d'après style.css, et place sous l'en-tête
// (jusqu'à mi-marge du bas, comme sur la maquette).
const HOME_ROOM = 1080 - 348 - 32;
const HOME_GAP = 28;
const HOME_LABEL_H = 32;
const HOME_ROW_L_H = 225 + 10 + 32 + 10 + 32; // affiche, titre, « reste N min »
const HOME_ROW_S_H = 195 + 10 + 32; // affiche, titre
const HOME_COLL_H = 167; // carte de collection sur deux lignes

const VIEW_ICON = {
  check: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="m5 12.5 4.5 4.5L19 7.5"/></svg>`,
  list: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M9 6h11M9 12h11M9 18h11M4.5 6h.01M4.5 12h.01M4.5 18h.01"/></svg>`,
  search: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><circle cx="11" cy="11" r="7"/><path d="m20 20-3.6-3.6"/></svg>`,
  pencil: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 20h4L19 9l-4-4L4 16z"/></svg>`,
};

// -- outils -------------------------------------------------------------------------

/** « 1 240 » (espace fine insécable, comme sur le téléphone). */
function fmtCount(n) {
  return Number(n).toLocaleString('fr-FR');
}

function plural(n, one, many) {
  return `${n}${NBSP}${n > 1 ? many : one}`;
}

/** Caractère replié : minuscule, sans diacritique (chaîne vide pour un diacritique seul). */
function foldChar(c) {
  return c.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase();
}

/** Texte replié (minuscules, sans diacritiques) et, pour chacun de ses caractères,
    l'indice de début et de fin du caractère d'origine correspondant. */
function foldWithMap(text) {
  let folded = '';
  const from = [];
  const to = [];
  let i = 0;
  for (const c of text) {
    const f = foldChar(c);
    // Diacritique seul (texte déjà décomposé) : il reste dans le passage du caractère
    // qui le précède, pour que l'accent soit surligné avec sa lettre.
    if (!f && to.length) to[to.length - 1] = i + c.length;
    for (let k = 0; k < f.length; k += 1) {
      from.push(i);
      to.push(i + c.length);
    }
    folded += f;
    i += c.length;
  }
  return { folded, from, to };
}

/** Surligne en laiton les passages de `text` qui correspondent à `query`, sans tenir
    compte des accents ni de la casse : la recherche se fait sur les textes repliés
    (NFD, diacritiques retirés), le surlignage sur le texte d'origine aux mêmes positions.
    Chaque morceau est échappé avant d'être entouré : ni la requête ni le titre ne sont
    jamais insérés tels quels. */
function highlight(text, query) {
  const src = String(text ?? '');
  const words = String(query ?? '').split(/\s+/).map((w) => foldWithMap(w).folded)
    .filter((w) => w.length >= SEARCH_MIN_CHARS);
  if (!src || !words.length) return esc(src);
  const { folded, from, to } = foldWithMap(src);
  const ranges = [];
  for (const w of words) {
    for (let at = folded.indexOf(w); at !== -1; at = folded.indexOf(w, at + 1)) {
      ranges.push([from[at], to[at + w.length - 1]]);
    }
  }
  if (!ranges.length) return esc(src);
  ranges.sort((a, b) => a[0] - b[0]);
  let out = '';
  let pos = 0;
  for (const [start, end] of ranges) {
    if (end <= pos) continue; // déjà couvert par un passage qui le chevauche
    const s = Math.max(start, pos);
    out += esc(src.slice(pos, s)) + `<mark>${esc(src.slice(s, end))}</mark>`;
    pos = end;
  }
  return out + esc(src.slice(pos));
}

/** En-tête d'une vue : surtitre laiton, titre, ligne de sous-titre. */
function viewHead(overline, titleHtml, { titleCls = '', sub = '', marker = '' } = {}) {
  return `<div class="mv-head">
<div class="mv-head-text">
<span class="mv-overline"><span class="mv-dot"></span>${overline}</span>
<h1 class="mv-title ${titleCls}">${titleHtml}</h1>
${sub ? `<div class="mv-sub">${sub}</div>` : ''}
</div>
${marker}
</div>`;
}

function filterPill(filter) {
  const label = filter === 'playable' ? 'Œuvres lisibles' : filter === 'all' ? 'Tout le catalogue' : '';
  return label ? `<span class="mv-filter">${VIEW_ICON.check(26)}${label}</span>` : '';
}

/** Repère de position : grand repère laiton, texte, curseur sur la longueur de la liste. */
function positionMarker(big, text, ratio) {
  const pct = Math.round(Math.min(1, Math.max(0, ratio || 0)) * 100);
  return `<div class="mv-marker">
<span class="mv-marker-big">${big}</span>
<div class="mv-marker-side">
<span class="mv-marker-text">${text}</span>
<span class="mv-track"><span style="left:${pct}%"></span></span>
</div></div>`;
}

/** Indice de l'ancre dans `items` (0 si elle n'y est pas). */
function anchorIndex(items, anchor) {
  const i = items.findIndex((it) => it.kind === 'work' && it.id === anchor);
  return i < 0 ? 0 : i;
}

/** Marques d'une vignette : vu, entamé, disque absent, non numérisé. */
function thumbMarks(it, { badgeSize = 22, iconSize = 36 } = {}) {
  let out = '';
  if (it.kind === 'work' && !it.playable) {
    const absent = it.absent_volume != null;
    const icon = absent ? ICON.drive(iconSize, 1.6) : ICON.disc(iconSize);
    const label = absent ? it.absent_volume : (it.medium || 'Non numérisé');
    out += `<div class="mv-off">${icon}<span class="mv-off-label">${esc(label)}</span></div>`;
  }
  if (it.watched) out += `<div class="seen-badge">${ICON.check(badgeSize)}</div>`;
  else if (it.progress_ratio) {
    const pct = Math.round(it.progress_ratio * 100);
    out += `<div class="thumb-bar" role="img" aria-label="Entamé, ${pct} %"><span style="width:${pct}%"></span></div>`;
  }
  return out;
}

/** « S01E05 ». */
function episodeCode(it) {
  const two = (n) => String(n ?? '').padStart(2, '0');
  return `S${two(it.season_number)}E${two(it.number)}`;
}

/** Titre affiché d'un élément enrichi (œuvre, épisode, collection). */
function itemTitle(it) {
  if (it.kind === 'episode') return `${it.work_title} · ${episodeCode(it)}`;
  if (it.kind === 'collection') return it.name;
  return it.title_fr;
}

function itemPoster(it) {
  const hash = it.kind === 'collection' ? (it.posters || [])[0] : it.poster;
  return poster(hash, itemTitle(it), it.kind === 'work' ? it.year : null);
}

function collectionCounts(c) {
  return `${plural(c.count, 'œuvre', 'œuvres')} · ${plural(c.playable_count, 'lisible', 'lisibles')}`;
}

function directorsText(it) {
  return (it.directors || []).join(', ');
}

/** « 1958 · Jacques Tati » (liste par rang). */
function workMeta(it) {
  return joinDot([it.year, directorsText(it)]);
}

// -- accueil --------------------------------------------------------------------------

function homeTile(it, size) {
  const left = it.remaining_min != null && !it.watched
    ? `<span class="mv-tile-left">reste ${fmtMinutes(it.remaining_min)}</span>` : '';
  return `<div class="mv-tile mv-tile-${size}">
<div class="mv-thumb">${itemPoster(it)}${thumbMarks(it)}</div>
<span class="mv-tile-title">${esc(itemTitle(it))}</span>${left}
</div>`;
}

function collectionCard(c) {
  return `<div class="mv-coll-card">
<span class="mv-coll-icon">${VIEW_ICON.list(38)}</span>
<span class="mv-coll-text"><span class="mv-coll-name">${esc(c.name)}</span>`
    + `<span class="mv-coll-count">${esc(collectionCounts(c))}</span></span>
</div>`;
}

/** Sorte de rangée : collections (cartes), « Reprendre » (grandes vignettes avec ce
    qui reste), ou autres (petites vignettes). */
function homeSectionKind(s) {
  const items = s.items || [];
  if (items.every((it) => it.kind === 'collection')) return 'coll';
  const resume = /^reprendre/i.test(s.label || '')
    || items.every((it) => it.progress_ratio != null || it.remaining_min != null);
  return resume ? 'l' : 's';
}

/** Hauteur d'une rangée (étiquette comprise), pour ne montrer que les rangées entières. */
function homeSectionHeight(s, kind) {
  const label = HOME_LABEL_H + HOME_GAP;
  if (kind === 'coll') {
    const lines = Math.ceil((s.items || []).length / 3);
    return label + lines * HOME_COLL_H + (lines - 1) * 32;
  }
  return label + (kind === 'l' ? HOME_ROW_L_H : HOME_ROW_S_H);
}

function homeSection(s, kind) {
  const items = s.items || [];
  const label = `<span class="mv-section-label">${esc(String(s.label || '').toUpperCase())}</span>`;
  if (kind === 'coll') {
    return `${label}<div class="mv-coll-grid">${items.map(collectionCard).join('')}</div>`;
  }
  return `${label}<div class="mv-row mv-row-${kind}">${items.map((it) => homeTile(it, kind)).join('')}</div>`;
}

/** Accueil : salutation selon l'heure, puis les rangées du téléphone. */
function renderHomeView(view, pause) {
  const greeting = new Date().getHours() < EVENING_HOUR ? 'Bonjour' : 'Bonsoir';
  // Rangées entières seulement : la suivante n'apparaît que si elle tient à l'écran.
  let room = HOME_ROOM;
  const shown = [];
  for (const s of (view.sections || []).filter((x) => (x.items || []).length)) {
    const kind = homeSectionKind(s);
    const h = homeSectionHeight(s, kind) + (shown.length ? HOME_GAP : 0);
    if (h > room && shown.length) break;
    room -= h;
    shown.push(homeSection(s, kind));
  }
  const body = shown.join('');
  return `<div class="screen view mv-home">
${viewHead('ACCUEIL', greeting, { sub: view.subtitle ? `<span>${esc(view.subtitle)}</span>` : '' })}
${renderStateCorner(pause)}
<div class="mv-body mv-home-body">${body}</div>
</div>`;
}

// -- parcourir ----------------------------------------------------------------------

/** Portion de la grille à montrer : la rangée de l'ancre d'abord. Les rangées suivent
    la position absolue dans la liste (`position.index`), pour que les colonnes ne
    bougent pas quand le téléphone défile : la grille avance d'une rangée entière. */
function browseWindow(items, view) {
  const a = anchorIndex(items, view.anchor);
  const index = view.position && view.position.index;
  let start = Number.isInteger(index) && index >= 1 ? a - ((index - 1) % BROWSE_COLS) : a;
  start = Math.max(0, start);
  while (start >= BROWSE_COLS && items.length - start <= BROWSE_COLS * (BROWSE_ROWS - 1)) {
    start -= BROWSE_COLS; // fin de liste : on garde l'écran rempli
  }
  return items.slice(start, start + BROWSE_COLS * BROWSE_ROWS);
}

function browseTile(it) {
  return `<div class="mv-tile">
<div class="mv-thumb">${itemPoster(it)}${thumbMarks(it)}</div>
<span class="mv-tile-title">${esc(itemTitle(it))}</span>
</div>`;
}

/** Parcourir : en-tête, filtre, repère de position, grille d'affiches autour de l'ancre. */
function renderBrowseView(view, pause) {
  const items = view.items || [];
  const p = view.position;
  const marker = p && p.index != null
    ? positionMarker(esc(p.letter || ''),
      esc(joinDot([p.letter, p.total ? `${fmtCount(p.index)} / ${fmtCount(p.total)}` : fmtCount(p.index)])),
      p.total ? p.index / p.total : 0)
    : '';
  const sub = (view.subtitle ? `<span>${esc(view.subtitle)}</span>` : '') + filterPill(view.filter);
  return `<div class="screen view mv-browse">
${viewHead('PARCOURIR', esc(view.title || ''), { sub, marker })}
${renderStateCorner(pause)}
<div class="mv-body"><div class="mv-grid">${browseWindow(items, view).map(browseTile).join('')}</div></div>
</div>`;
}

// -- collection -----------------------------------------------------------------------

function rankNumber(it) {
  const n = parseInt(it && it.rank, 10);
  return Number.isFinite(n) ? n : null;
}

function collectionRow(it) {
  let side = '';
  if (!it.playable && it.absent_volume != null) {
    side = `<span class="mv-rank-off warn">${ICON.drive(30)}${esc(it.absent_volume)}</span>`;
  } else if (!it.playable) {
    side = `<span class="mv-rank-off">${ICON.disc(30)}${esc(it.medium || 'Non numérisé')}</span>`;
  } else if (it.watched) {
    side = `<span class="mv-rank-seen">${ICON.check(34)}</span>`;
  }
  const bar = !it.watched && it.progress_ratio
    ? `<div class="thumb-bar" role="img" aria-label="Entamé, ${Math.round(it.progress_ratio * 100)} %">`
      + `<span style="width:${Math.round(it.progress_ratio * 100)}%"></span></div>` : '';
  return `<div class="mv-rank-row${it.playable ? '' : ' off'}">
<span class="mv-rank">${esc(it.rank ?? '—')}</span>
<div class="mv-rank-thumb">${itemPoster(it)}${bar}</div>
<span class="mv-rank-text"><span class="mv-rank-title">${esc(itemTitle(it))}</span>`
    + `<span class="mv-rank-meta">${esc(workMeta(it))}</span></span>
${side}
</div>`;
}

/** Collection : liste par rang autour de l'ancre, repère « n° 412 · 408–419 sur 1000 ». */
function renderCollectionView(view, pause) {
  const items = (view.items || []).filter((it) => it.kind === 'work');
  const found = items.findIndex((it) => it.id === view.anchor);
  const start = Math.max(0, Math.min((found < 0 ? 0 : found) - 4, items.length - COLLECTION_ROWS));
  const shown = items.slice(start, start + COLLECTION_ROWS);
  const p = view.position || {};
  // Rangs extrêmes des lignes affichées ; sans ancre reçue, la première ligne affichée.
  const ranks = shown.map(rankNumber).filter((n) => n != null);
  const anchorItem = found < 0 ? shown[0] : items[found];
  const anchorRank = anchorItem ? (anchorItem.rank ?? p.index) : p.index;
  let marker = '';
  if (anchorRank != null) {
    const range = ranks.length ? `${Math.min(...ranks)}–${Math.max(...ranks)}` : '';
    const text = [range, p.total ? `sur ${fmtCount(p.total)}` : ''].filter(Boolean).join(' ');
    const ratio = p.total ? (rankNumber({ rank: anchorRank }) ?? p.index ?? 0) / p.total : 0;
    marker = positionMarker(`n°${NBSP}${esc(anchorRank)}`, esc(text), ratio);
  }
  const title = view.title || '';
  const sub = (view.subtitle ? `<span>${esc(view.subtitle)}</span>` : '') + filterPill(view.filter);
  return `<div class="screen view mv-collection">
${viewHead('COLLECTION', esc(title), { titleCls: 'mv-title-coll', sub, marker })}
${renderStateCorner(pause)}
<div class="mv-body"><div class="mv-ranks">${shown.map(collectionRow).join('')}</div></div>
</div>`;
}

// -- recherche ------------------------------------------------------------------------

function initials(name) {
  return String(name || '').split(/[\s-]+/).filter(Boolean).slice(0, 2)
    .map((w) => [...w][0].toUpperCase()).join('');
}

function personRow(person, query) {
  const count = person.count != null ? plural(person.count, 'œuvre', 'œuvres') : '';
  return `<div class="mv-person">
<span class="mv-avatar">${esc(initials(person.name))}</span>
<div class="mv-found-text"><span class="mv-found-title">${highlight(person.name, query)}</span>`
    + `<span class="mv-found-meta">${esc(joinDot([person.role, count]))}</span></div>
</div>`;
}

function foundMeta(it) {
  if (it.kind === 'episode') return it.title || '';
  if (it.kind === 'collection') return collectionCounts(it);
  return joinDot([it.year, KIND[it.type] || '', directorsText(it)]); // « 1979 · Film · Ridley Scott »
}

function foundRow(it, query) {
  return `<div class="mv-found">
<div class="mv-thumb">${itemPoster(it)}${thumbMarks(it, { iconSize: 38 })}</div>
<div class="mv-found-text"><span class="mv-found-title">${highlight(itemTitle(it), query)}</span>`
    + `<span class="mv-found-meta">${esc(foundMeta(it))}</span></div>
</div>`;
}

/** « Aussi connu sous » : le titre alternatif (à défaut, le titre original) et l'œuvre
    qui le porte. */
function akaCard(it, query) {
  const alt = it.kind !== 'work' ? null
    : differs(it.title_alt, it.title_fr) ? it.title_alt
      : differs(it.title_vo, it.title_fr) ? it.title_vo : null;
  const name = alt || itemTitle(it);
  const sub = alt ? `Titre original de «${NBSP}${it.title_fr}${NBSP}»` : foundMeta(it);
  return `<div class="mv-aka">
<span class="mv-aka-title">${highlight(name, query)}</span>
<span class="mv-found-meta">${esc(sub)}</span>
</div>`;
}

function searchSection(s, query) {
  const people = s.people || [];
  const items = s.items || [];
  const many = people.length + items.length > 2;
  let rows;
  if (people.length) rows = people.map((p) => personRow(p, query)).join('');
  else if (/^aussi connu/i.test(s.label || '')) rows = items.map((it) => akaCard(it, query)).join('');
  else rows = items.map((it) => foundRow(it, query)).join('');
  return `<div class="mv-search-col${many ? ' compact' : ''}">
<span class="mv-section-label">${esc(String(s.label || '').toUpperCase())}</span>
${rows}
</div>`;
}

function searchState(icon, title, text) {
  return `<div class="mv-empty">
<span class="mv-empty-icon">${icon}</span>
<span class="mv-empty-title">${title}</span>
<span class="mv-empty-text">${text}</span>
</div>`;
}

/** Recherche : la requête en grand, puis Personnes, Œuvres, Aussi connu sous. */
function renderSearchView(view, pause) {
  const query = String(view.query || '').trim();
  const sections = (view.sections || [])
    .filter((s) => (s.people || []).length || (s.items || []).length);
  const short = [...query].length < SEARCH_MIN_CHARS;
  const total = sections.reduce((n, s) => n + (s.people || []).length + (s.items || []).length, 0);
  const title = `<span class="mv-quote">«${NBSP}</span>${esc(query)}<span class="mv-quote">${NBSP}»</span>`;
  const sub = short ? '' : `<span>${plural(total, 'résultat', 'résultats')}</span>`;
  let body;
  if (short) {
    body = searchState(VIEW_ICON.pencil(56), 'Tapez au moins deux lettres',
      "Les résultats s'affichent dès la deuxième lettre.");
  } else if (!sections.length) {
    body = searchState(VIEW_ICON.search(56), 'Aucun résultat',
      'La recherche porte sur les titres français, originaux et alternatifs, la réalisation et le casting.');
  } else {
    const cols = sections.slice(0, SEARCH_COLUMNS);
    // Les colonnes d'affiches sont un peu plus larges que celles de texte.
    const widths = cols.map((s) => ((s.people || []).length || /^aussi connu/i.test(s.label || '')
      ? 'minmax(0,1fr)' : 'minmax(0,1.2fr)')).join(' ');
    body = `<div class="mv-search" style="grid-template-columns:${widths}">`
      + `${cols.map((s) => searchSection(s, query)).join('')}</div>`;
  }
  return `<div class="screen view mv-searchview">
${viewHead('RECHERCHE', title, { titleCls: 'mv-title-query', sub })}
${renderStateCorner(pause)}
<div class="mv-body">${body}</div>
</div>`;
}

// -- fiches : œuvre, saison, version et pistes (maquette 4b) ------------------------------

const PREPLAY_OPTIONS = 4; // choix visibles par colonne
const NO_SUBTITLE = { kind: 'none' };
let shownSheetKey = null; // fiche affichée (tenue à jour par app.js)

const SHEET_ICON = {
  film: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3.5" y="3.5" width="17" height="17" rx="2"/><path d="M7.5 3.5v17M16.5 3.5v17M3.5 8h4M3.5 12h4M3.5 16h4M16.5 8h4M16.5 12h4M16.5 16h4"/></svg>`,
  audio: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M4 15v-3a8 8 0 0 1 16 0v3"/><rect x="3" y="14" width="4" height="6" rx="1.5"/><rect x="17" y="14" width="4" height="6" rx="1.5"/></svg>`,
  subtitles: (s) => `<svg width="${s}" height="${s}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><rect x="3" y="5" width="18" height="14" rx="2"/><path d="M7 12h3M13 12h4M7 15.5h6M15.5 15.5h1.5"/></svg>`,
};

/** Fiche montrée par une vue : même clé d'une vue à l'autre, la fiche est mise à jour
    sur place (sans entrée animée ni rechargement). */
function sheetKey(view) {
  if (!view) return null;
  if (view.screen === 'work') return `work:${view.work_id}`;
  if (view.screen === 'season') return `season:${view.season_id}`;
  if (view.screen === 'preplay') {
    return view.episode_id != null ? `preplay:episode:${view.episode_id}` : `preplay:work:${view.work_id}`;
  }
  return null;
}

async function renderWorkView(view, pause, enter) {
  return renderWork(await sheetJson(`/api/works/${view.work_id}`), { pause, enter });
}

async function renderSeasonView(view, pause, enter) {
  let season;
  let work;
  if (view.work_id != null) {
    [season, work] = await Promise.all([
      sheetJson(`/api/seasons/${view.season_id}`), sheetJson(`/api/works/${view.work_id}`)]);
  } else {
    season = await sheetJson(`/api/seasons/${view.season_id}`);
    work = await sheetJson(`/api/works/${season.work_id}`);
  }
  return renderSeason({ season, work },
    { selectedId: view.episode_id ?? null, active: activeEpisode(), pause, enter });
}

/** Pistes choisies par défaut (copie de rc/probe.py default_selection) : audio français,
    sinon la première piste avec des sous-titres français (fichier, puis intégrés). */
function defaultTracks(t) {
  if (!t.audio.length) return { audio: null, subtitle: NO_SUBTITLE };
  const chosen = t.audio.find((a) => a.lang === 'fr') || t.audio[0];
  if (chosen.lang === 'fr') return { audio: chosen.index, subtitle: NO_SUBTITLE };
  const ext = t.external.find((e) => e.lang === 'fr');
  if (ext) return { audio: chosen.index, subtitle: { kind: 'external', path: ext.path } };
  const emb = t.subtitles.find((x) => x.lang === 'fr');
  if (emb) return { audio: chosen.index, subtitle: { kind: 'embedded', index: emb.index } };
  return { audio: chosen.index, subtitle: NO_SUBTITLE };
}

function sameSubtitle(a, b) {
  const x = a || NO_SUBTITLE;
  const y = b || NO_SUBTITLE;
  if (x.kind !== y.kind) return false;
  if (x.kind === 'embedded') return x.index === y.index;
  if (x.kind === 'external') return x.path === y.path;
  return true;
}

/** Badge d'un choix : « Par défaut », ou « Dernier choix » (choix enregistré, s'il
    diffère du défaut). */
function choiceBadge(isDefault, isLast) {
  const text = isDefault ? 'Par défaut' : isLast ? 'Dernier choix' : '';
  return text ? `<span class="ep-badge">${text}</span>` : '';
}

function preplayOption({ on, label, sub, badge }) {
  return `<div class="pp-opt${on ? ' on' : ''}">
<span class="pp-radio"></span>
<span class="pp-opt-text"><span class="pp-opt-label">${esc(label)}</span>`
    + `${sub ? `<span class="pp-opt-sub">${esc(sub)}</span>` : ''}</span>
${badge}
</div>`;
}

/** Colonne de choix : au plus PREPLAY_OPTIONS lignes, autour du choix retenu. */
function preplayColumn(icon, label, options, note) {
  const on = Math.max(0, options.findIndex((o) => o.on));
  const start = Math.max(0, Math.min(on - 1, options.length - PREPLAY_OPTIONS));
  const body = options.length
    ? options.slice(start, start + PREPLAY_OPTIONS).map(preplayOption).join('')
    : `<span class="pp-note">${esc(note)}</span>`;
  return `<div class="pp-col">
<span class="pp-label">${icon}${label}</span>
${body}
</div>`;
}

/** Cible de la vue « version et pistes » : titre, sous-titre, versions, progression. */
async function preplayTarget(view) {
  if (view.episode_id == null) {
    const w = await sheetJson(`/api/works/${view.work_id}`);
    return { title: w.title_fr, sub: [w.year, fmtMinutes(w.duration_min)], files: w.media_files, progress: w.progress };
  }
  // La vue d'un épisode ne porte que son identifiant : sa saison est celle de la
  // dernière fiche de saison affichée qui le contient.
  const seasonId = view.season_id ?? EPISODE_SEASON.get(view.episode_id);
  if (seasonId == null) throw new Error(`épisode ${view.episode_id} : saison inconnue`);
  const season = await sheetJson(`/api/seasons/${seasonId}`);
  season.episodes.forEach((e) => EPISODE_SEASON.set(e.id, season.id));
  const ep = season.episodes.find((e) => e.id === view.episode_id);
  if (!ep) throw new Error(`épisode ${view.episode_id} absent de la saison ${seasonId}`);
  const code = episodeCode({ season_number: season.number, number: ep.number });
  return {
    title: `${season.work.title_fr} · ${code}`,
    sub: [ep.title, fmtMinutes(episodeMinutes(ep))],
    files: ep.media_files,
    progress: ep.progress,
  };
}

/** Version et pistes : la version, l'audio et les sous-titres choisis sur le téléphone. */
async function renderPreplayView(view, pause, enter) {
  const target = await preplayTarget(view);
  const versions = readableVersions(target.files);
  const chosen = versions.find((m) => m.id === view.media_file_id) || versions[0] || null;
  const tracks = chosen && chosen.present
    ? await sheetJson(`/api/media/${chosen.id}/tracks`).catch(() => null) : null;
  const lastVersion = target.progress ? target.progress.media_file_id : null;

  const versionOpts = versions.map((m) => ({
    on: m === chosen,
    label: joinDot([m.quality, resolution(m)]),
    sub: !m.present ? diskAbsentText(m.volume_label)
      : versions.length === 1 ? 'Seule version disponible' : versionLangs(m).join(' · '),
    badge: choiceBadge(m === versions[0], m.id === lastVersion),
  }));

  let audioOpts = [];
  let subOpts = [];
  if (tracks) {
    const def = defaultTracks(tracks);
    const last = tracks.default || {};
    // Pistes du téléphone ; sans sous-titres envoyés, le téléphone a choisi « Aucun ».
    const phoneSet = view.media_file_id != null || view.audio_track != null;
    const audio = view.audio_track ?? last.audio;
    const subtitle = view.subtitle_track || (phoneSet ? NO_SUBTITLE : last.subtitle);
    const french = tracks.audio.some((a) => a.lang === 'fr');
    audioOpts = tracks.audio.map((a) => ({
      on: a.index === audio,
      label: a.lang ? langLabel(a.lang) : `Piste ${a.index}`,
      sub: a.lang && a.lang !== 'fr' ? 'Version originale' : a.lang === 'fr' && tracks.audio.length > 1 ? 'Version française' : '',
      badge: choiceBadge(a.index === def.audio, a.index === last.audio && last.audio !== def.audio),
    }));
    const subChoice = (sel, label, sub) => ({
      on: sameSubtitle(sel, subtitle),
      label,
      sub,
      badge: choiceBadge(sameSubtitle(sel, def.subtitle),
        sameSubtitle(sel, last.subtitle) && !sameSubtitle(last.subtitle, def.subtitle)),
    });
    subOpts = [
      ...tracks.external.map((e) => subChoice({ kind: 'external', path: e.path },
        e.lang ? langLabel(e.lang) : 'Sous-titres', 'Fichier à côté de la vidéo')),
      ...tracks.subtitles.map((x) => subChoice({ kind: 'embedded', index: x.index },
        x.lang ? langLabel(x.lang) : `Piste ${x.index}`, 'Piste intégrée')),
      subChoice(NO_SUBTITLE, 'Aucun', ''),
    ];
  }
  const note = !chosen ? 'Aucune version lisible'
    : !chosen.present ? diskAbsentText(chosen.volume_label) : 'Pistes indisponibles';
  const sub = target.sub.filter((x) => x !== null && x !== undefined && x !== '')
    .map((x) => `<span>${esc(x)}</span>`).join('<span class="muted">·</span>');
  return `<div class="screen view mv-preplay">
${viewHead('VERSION ET PISTES', esc(target.title), { sub })}
${renderStateCorner(pause)}
<div class="mv-body${enter ? ' rc-enter' : ''}">
<div class="pp-cols">
${preplayColumn(SHEET_ICON.film(30), 'VERSION', versionOpts, note)}
${preplayColumn(SHEET_ICON.audio(30), 'AUDIO', audioOpts, note)}
${preplayColumn(SHEET_ICON.subtitles(30), 'SOUS-TITRES', subOpts, note)}
</div>
<div class="pp-hint">${ICON.phone(34)}Lancez la lecture depuis le téléphone</div>
</div>
</div>`;
}

// -- aiguillage -----------------------------------------------------------------------

const VIEW_RENDERERS = {
  home: renderHomeView,
  browse: renderBrowseView,
  collection: renderCollectionView,
  search: renderSearchView,
};

const SHEET_RENDERERS = {
  work: renderWorkView,
  season: renderSeasonView,
  preplay: renderPreplayView,
};

/** Vue du téléphone ; `pause` non nul : vidéo en pause dans le coin d'état. Une fiche
    renvoie une promesse ; elle n'est animée à l'entrée que si elle change. */
function renderView(view, pause) {
  const v = view || {};
  const sheet = SHEET_RENDERERS[v.screen];
  if (sheet) return sheet(v, pause, sheetKey(v) !== shownSheetKey);
  return (VIEW_RENDERERS[v.screen] || renderHomeView)(v, pause);
}
