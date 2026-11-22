from __future__ import annotations

import re
from collections.abc import Callable

from rc.catalog.model import (
    CollectionRef,
    ParsedGroup,
    ParsedWork,
    ParseResult,
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
        st.work.synopsis = f[1]


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


def _on_mf(st: _State, f: list[str]) -> None:
    if _field(f, 1) == "TG" and st.work:
        st.work.tags.extend(re.findall(r"\[[^\]]+\]", " ".join(f[2:])))
    # MF ; VI / AF : Task 3


def _accept(st: _State, f: list[str]) -> None:
    """Code accepted but not interpreted yet (MD: Task 3)."""


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
    "MD": _accept,
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
    return st.result
