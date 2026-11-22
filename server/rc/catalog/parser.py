from __future__ import annotations

import re
from collections.abc import Callable

from rc.catalog.model import (
    CollectionRef,
    ParsedEpisode,
    ParsedGroup,
    ParsedSeason,
    ParsedWork,
    ParseResult,
    PosterRef,
    SubtitleRef,
    VideoFile,
)
from rc.catalog.paths import (
    QUALITY_ORDER,
    base_name,
    episode_info,
    resolve_dir,
    season_number,
    subtitle_filename,
    subtitle_lang,
)

KNOWN_CODES = frozenset("GB TG GN GD GL GM GC GS MN MI MC MP MD MF MQ MG".split())

_SEP = re.compile(r"\s;(?:\s+|$)")
_IMDB = re.compile(r"imdb\.com/title/(tt\d+)")
_YEAR_IN_KEY = re.compile(r"\[(\d{4})")
_DURATION = re.compile(r"^(\d+)h(\d{1,2})$")


class _State:
    def __init__(self) -> None:
        self.result = ParseResult()
        self.work: ParsedWork | None = None
        self.group: ParsedGroup | None = None
        self.line_no = 0
        self.stack: list[str] = []
        # saison ouverte : (niveau, numéro)
        self.season: tuple[int, int] | None = None
        # vidéos de l'œuvre courante avec le numéro de saison ouverte à ce moment
        self.entries: list[tuple[VideoFile, int | None]] = []


Handler = Callable[[_State, list[str]], None]


def _split(line: str) -> list[str]:
    return [f.strip() for f in _SEP.split(line.strip())]


def _csv(value: str) -> list[str]:
    return [v.strip() for v in value.split(",") if v.strip()]


def _field(fields: list[str], i: int) -> str:
    return fields[i] if i < len(fields) else ""


def _on_gn(st: _State, f: list[str]) -> None:
    st.group = ParsedGroup(_field(f, 1), None, None)
    st.result.groups.append(st.group)


def _on_gd(st: _State, f: list[str]) -> None:
    value = _field(f, 1)
    if st.group and value and value != "...":
        st.group.description = value


def _on_gl(st: _State, f: list[str]) -> None:
    if st.group and _field(f, 1):
        st.group.link = f[1]


def _on_mn(st: _State, f: list[str]) -> None:
    _finalize(st)
    st.season = None
    vo = _field(f, 4)
    alt = _field(f, 5)
    key = _field(f, 2)
    m = _YEAR_IN_KEY.search(key)
    st.work = ParsedWork(
        type=_field(f, 1).lower(),
        key=key,
        title_fr=_field(f, 3),
        title_vo=None if vo in ("", "*") else vo,
        title_alt=alt or None,
        year=int(m.group(1)) if m else None,
        duration_min=None,
        episode_count=None,
        directors=[],
        genres=[],
        cast=[],
        synopsis=None,
        imdb_id=None,
        tags=[],
        collections=[],
        poster=None,
        videos=[],
        seasons=[],
        episodes=[],
        episodic=False,
        line_no=st.line_no,
    )
    st.result.works.append(st.work)


def _on_mi(st: _State, f: list[str]) -> None:
    w = st.work
    if not w:
        return
    dur = _field(f, 1)
    if "/" in dur:
        w.episode_count = dur
    elif m := _DURATION.match(dur):
        w.duration_min = int(m.group(1)) * 60 + int(m.group(2))
    year = _field(f, 2)
    if year.isdigit():
        w.year = int(year)
    w.directors = _csv(_field(f, 3))
    w.genres = _csv(_field(f, 4))


def _on_mc(st: _State, f: list[str]) -> None:
    if st.work:
        st.work.cast = _csv(_field(f, 1))


def _on_mp(st: _State, f: list[str]) -> None:
    if st.work and _field(f, 1):
        st.work.synopsis = " ; ".join(f[1:])


def _on_mq(st: _State, f: list[str]) -> None:
    if st.work:
        m = _IMDB.search(" ; ".join(f))
        if m:
            st.work.imdb_id = m.group(1)


def _on_mg(st: _State, f: list[str]) -> None:
    if not st.work:
        return
    if len(f) >= 3:
        ref = CollectionRef(name=f[2], rank=f[1] or None)
    else:
        ref = CollectionRef(name=_field(f, 1), rank=None)
    st.work.collections.append(ref)


def _warn(st: _State, msg: str) -> None:
    st.result.warnings.append(f"ligne {st.line_no} : {msg}")


def _on_md(st: _State, f: list[str]) -> None:
    level_txt = _field(f, 1)
    if not level_txt.isdigit():
        _warn(st, "MD sans niveau valide")
        return
    n = int(level_txt)
    name = _field(f, 2)
    if st.season and n <= st.season[0]:
        st.season = None
    if not name:
        st.stack = st.stack[:n]
        return
    work_key = st.work.key if st.work else None
    if name == "*" and work_key is None:
        _warn(st, "MD * sans œuvre courante")
    st.stack = st.stack[:n] + [resolve_dir(name, work_key)]
    label = _field(f, 3) or None
    num = season_number(name, label)
    if num is not None and st.work:
        st.work.seasons.append(ParsedSeason(num, label or name))
        st.season = (n, num)


def _on_video(st: _State, f: list[str]) -> None:
    if not st.work:
        _warn(st, "MF ; VI sans œuvre courante")
        return
    quality = _field(f, 2)
    size = _field(f, 3)
    filename = _field(f, 4) or None
    subs = []
    if filename:
        for ref in f[5:]:
            if ref:
                sub = subtitle_filename(filename, ref)
                subs.append(SubtitleRef(sub, subtitle_lang(sub)))
    video = VideoFile(
        quality=quality,
        size=None if size in ("", "?") else size,
        filename=filename,
        rel_dir=tuple(st.stack),
        subtitles=subs,
        playable=filename is not None and quality in QUALITY_ORDER,
    )
    st.entries.append((video, st.season[1] if st.season else None))


def _on_mf(st: _State, f: list[str]) -> None:
    kind = _field(f, 1)
    if kind == "TG" and st.work:
        st.work.tags.extend(re.findall(r"\[[^\]]+\]", " ".join(f[2:])))
    elif kind == "VI":
        _on_video(st, f)
    elif kind == "AF":
        if not st.work:
            _warn(st, "MF ; AF sans œuvre courante")
        elif _field(f, 2):
            st.work.poster = PosterRef(f[2], tuple(st.stack))


def _finalize(st: _State) -> None:
    """Range les vidéos de l'œuvre courante : fichiers ou épisodes."""
    w = st.work
    entries, st.entries = st.entries, []
    if not w:
        return
    playable = [(v, s) for v, s in entries if v.playable]
    w.episodic = (
        w.type == "serie"
        or bool(w.seasons)
        or any(episode_info(v.filename or "")[0] is not None for v, _ in playable)
        or len({base_name(v.filename or "") for v, _ in playable}) >= 2
    )
    if not w.episodic:
        w.videos = [v for v, _ in entries]
        return
    w.videos = [v for v, _ in entries if not v.playable]
    known = {s.number for s in w.seasons}
    counters: dict[int, int] = {}
    by_base: dict[tuple[int, str], int] = {}
    episodes: dict[tuple[int, int], ParsedEpisode] = {}
    for v, open_season in playable:
        filename = v.filename or ""
        p_season, p_number, p_title = episode_info(filename)
        season = open_season if open_season is not None else p_season
        if season is None:
            season = 0
        if season not in known:
            known.add(season)
            w.seasons.append(
                ParsedSeason(season, "Épisodes" if season == 0 else f"Saison {season}")
            )
        if p_number is not None:
            number, title = p_number, p_title
        else:
            base = base_name(filename)
            title = base
            if (season, base) in by_base:
                number = by_base[(season, base)]
            else:
                number = counters.get(season, 0) + 1
                counters[season] = number
                by_base[(season, base)] = number
        key = (season, number)
        if key in episodes:
            episodes[key].videos.append(v)
        else:
            episodes[key] = ParsedEpisode(season, number, title, [v])
    w.episodes = list(episodes.values())


def _accept(st: _State, f: list[str]) -> None:
    """Code accepté mais non interprété."""


HANDLERS: dict[str, Handler] = {
    "GN": _on_gn,
    "GD": _on_gd,
    "GL": _on_gl,
    "MN": _on_mn,
    "MI": _on_mi,
    "MC": _on_mc,
    "MP": _on_mp,
    "MQ": _on_mq,
    "MG": _on_mg,
    "MF": _on_mf,
    "MD": _on_md,
    "GB": _accept,
    "TG": _accept,
    "GM": _accept,
    "GC": _accept,
    "GS": _accept,
}


def parse_catalog(text: str) -> ParseResult:
    st = _State()
    for n, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        st.line_no = n
        fields = _split(line)
        code = fields[0]
        if code not in KNOWN_CODES:
            st.result.warnings.append(f"ligne {n} : code inconnu {code}")
            continue
        HANDLERS[code](st, fields)
    _finalize(st)
    return st.result
