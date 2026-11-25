from __future__ import annotations

from dataclasses import dataclass, field


@dataclass
class SubtitleRef:
    filename: str
    lang: str | None


@dataclass
class VideoFile:
    quality: str
    size: str | None
    filename: str | None
    rel_dir: tuple[str, ...]
    subtitles: list[SubtitleRef]
    playable: bool


@dataclass
class PosterRef:
    filename: str
    rel_dir: tuple[str, ...]


@dataclass
class ParsedEpisode:
    season: int
    number: int
    title: str | None
    videos: list[VideoFile]


@dataclass
class ParsedSeason:
    number: int
    label: str


@dataclass
class CollectionRef:
    name: str
    rank: str | None


@dataclass
class ParsedWork:
    type: str
    key: str
    title_fr: str
    title_vo: str | None
    title_alt: str | None
    year: int | None
    duration_min: int | None
    episode_count: str | None
    directors: list[str]
    genres: list[str]
    cast: list[str]
    synopsis: str | None
    imdb_id: str | None
    tags: list[str]
    collections: list[CollectionRef]
    poster: PosterRef | None
    videos: list[VideoFile]
    seasons: list[ParsedSeason]
    episodes: list[ParsedEpisode]
    episodic: bool
    line_no: int
    media: list[str] = field(default_factory=list)


@dataclass
class ParsedGroup:
    name: str
    description: str | None
    link: str | None


@dataclass
class ParseResult:
    works: list[ParsedWork] = field(default_factory=list)
    groups: list[ParsedGroup] = field(default_factory=list)
    warnings: list[str] = field(default_factory=list)
