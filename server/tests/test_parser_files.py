from pathlib import Path

import pytest

from rc.catalog.model import ParsedSeason, PosterRef, SubtitleRef
from rc.catalog.parser import parse_catalog
from rc.catalog.paths import (
    base_name,
    episode_info,
    relpath,
    resolve_dir,
    season_number,
    subtitle_filename,
    subtitle_lang,
)

FIXTURE = Path(__file__).parent / "fixtures" / "medias_sample.txt"


@pytest.fixture(scope="module")
def result():
    return parse_catalog(FIXTURE.read_text(encoding="utf-8"))


def work(result, title_fr):
    return next(w for w in result.works if w.title_fr == title_fr)


def test_ava_two_versions_not_episodic(result):
    w = work(result, "Ava")
    assert w.episodic is False
    assert len(w.videos) == 2
    assert w.videos[0].rel_dir == ("Films", "A")
    assert w.videos[0].subtitles == [
        SubtitleRef("Ava [2017] [1h45,1920x1038,16x9,FR,sEN].1.fr.srt", "fr")
    ]
    assert w.poster == PosterRef("Ava [2017] [0h01].jpg", ("Films", "A"))


def test_alien_dvd_not_playable(result):
    w = work(result, "Alien, le huitième passager")
    assert len(w.videos) == 1
    assert w.videos[0].playable is False
    assert w.videos[0].filename is None


def test_theatre_dir(result):
    w = next(w for w in result.works if w.type == "theatre")
    assert w.videos[0].rel_dir == ("Theatre",)


def test_series_seasons(result):
    w = work(result, "Back to Life")
    assert w.episodic is True
    assert w.seasons == [ParsedSeason(1, "Saison 1"), ParsedSeason(2, "Saison 2")]
    assert len(w.episodes) == 3
    ep = next(e for e in w.episodes if (e.season, e.number) == (2, 1))
    assert ep.videos[0].rel_dir == ("Series", "Back to Life [2019]", "Saison 02")


def test_parts_without_pattern(result):
    w = work(result, "Agatha Christie : Dix Petits Nègres")
    assert w.episodic is True
    assert [(e.season, e.number) for e in w.episodes] == [(0, 1), (0, 2)]
    assert w.seasons == [ParsedSeason(0, "Épisodes")]
    assert w.episodes[0].title == "Dix petits negres - 1"
    assert w.episodes[0].videos[0].rel_dir == (
        "Series",
        "Agatha Cristie - Dix petits negres [2015]",
    )


def test_doc_level3_season(result):
    w = work(result, "Le Fabuleux Destin des Inventions")
    ep = next(e for e in w.episodes if (e.season, e.number) == (1, 2))
    assert ep.title == "Et la longitude fut"
    assert ep.videos[0].rel_dir == (
        "Docs",
        "Inventions",
        "Le Fabuleux Destin des Inventions [2002]",
        "Saison 01",
    )


def test_season_closed_by_lower_level_md():
    r = parse_catalog(
        "MD ; 0 ; Series\n"
        "MN ; serie ; X [2000] ; X ; *\n"
        "MD ; 1 ; *\n"
        "MD ; 2 ; Saison 01 ; Saison 1\n"
        "MF ; VI ; HD ; 1G ; S01E01 [0h20,1x1,16x9,FR].mp4\n"
        "MD ; 2 ; Extras\n"
        "MF ; VI ; HD ; 1G ; Bonus [0h05,1x1,16x9,FR].mp4\n"
    )
    w = r.works[0]
    assert w.seasons == [ParsedSeason(1, "Saison 1"), ParsedSeason(0, "Épisodes")]
    assert [(e.season, e.number) for e in w.episodes] == [(1, 1), (0, 1)]


def test_season_from_pattern_without_md_season():
    r = parse_catalog(
        "MD ; 0 ; Series\n"
        "MN ; serie ; X [2000] ; X ; *\n"
        "MD ; 1 ; *\n"
        "MF ; VI ; HD ; 1G ; S03E04 - Titre [0h20,1x1,16x9,FR].mp4\n"
    )
    w = r.works[0]
    assert w.seasons == [ParsedSeason(3, "Saison 3")]
    assert (w.episodes[0].season, w.episodes[0].number) == (3, 4)


def test_episode_versions_share_number():
    r = parse_catalog(
        "MN ; serie ; X [2000] ; X ; *\n"
        "MF ; VI ; HD ; 1G ; S01E01 [0h20,1x1,16x9,FR].mp4\n"
        "MF ; VI ; FHD ; 2G ; S01E01 [0h20,2x2,16x9,FR].mp4\n"
    )
    assert len(r.works[0].episodes) == 1
    assert len(r.works[0].episodes[0].videos) == 2


def test_episodic_non_playable_stays_in_videos():
    r = parse_catalog(
        "MN ; serie ; X [2000] ; X ; *\n"
        "MF ; VI ; DVD\n"
        "MF ; VI ; HD ; 1G ; S01E01 [0h20,1x1,16x9,FR].mp4\n"
    )
    w = r.works[0]
    assert len(w.videos) == 1 and w.videos[0].playable is False
    assert len(w.episodes) == 1


def test_two_different_titles_make_episodic():
    r = parse_catalog(
        "MN ; film ; X [2000] ; X ; *\n"
        "MF ; VI ; HD ; 1G ; Part A [0h20,1x1,16x9,FR].mp4\n"
        "MF ; VI ; HD ; 1G ; Part B [0h20,1x1,16x9,FR].mp4\n"
    )
    assert r.works[0].episodic is True


def test_file_without_work_warns():
    r = parse_catalog("MF ; AF ; a.jpg\nMF ; VI ; HD ; 1G ; a [0h20].mp4\n")
    assert len(r.warnings) == 2


def test_synopsis_keeps_separators():
    r = parse_catalog("MN ; film ; X [2000] ; X ; *\nMP ; un ; deux ; trois\n")
    assert r.works[0].synopsis == "un ; deux ; trois"


def test_paths_units():
    ep = "S01E02 - Et la longitude fut [0h51,640x360,16x9,FR].mp4"
    assert episode_info(ep) == (1, 2, "Et la longitude fut")
    assert episode_info("S01E01 [0h27].mp4") == (1, 1, None)
    assert episode_info("Ava [0h27].mp4") == (None, None, None)
    assert subtitle_lang("x.9.de.vtt") == "de"
    assert subtitle_lang("x.1.fre.srt") == "fr"
    assert subtitle_lang("x.srt") is None
    assert base_name("Ava [2017] [1h45,1920x1038].mp4") == "Ava"
    assert subtitle_filename("Ava [1h].mp4", "*.1.fr.srt") == "Ava [1h].1.fr.srt"
    assert relpath(("Films", "A"), "a.mp4") == "Films/A/a.mp4"
    assert resolve_dir("*", "K") == "K"
    assert resolve_dir("Films", "K") == "Films"
    assert season_number("Saison 02", None) == 2
    assert season_number("x", "Saison 3") == 3
    assert season_number("Extras", None) is None


def test_index_codes_accepted_silently():
    r = parse_catalog("RN ; Scott, Ridley\nRF ; x\nRC ; y\nQS ; z\n")
    assert r.warnings == []


def test_missing_space_after_first_separator():
    r = parse_catalog(
        "MN ; film ; X [2000] ; X ; *\nMI ;1h44 ; 2000 ; Dir ; drame\nMP ;un ; deux\n"
    )
    assert r.warnings == []
    assert r.works[0].duration_min == 104
    assert r.works[0].directors == ["Dir"]
    assert r.works[0].synopsis == "un ; deux"


def test_unpatterned_episode_never_collides_with_explicit_number():
    r = parse_catalog(
        "MN ; serie ; X [2000] ; X ; *\n"
        "MF ; VI ; HD ; 1G ; Bonus [0h05,1x1,16x9,FR].mp4\n"
        "MF ; VI ; HD ; 1G ; S00E01 [0h20,1x1,16x9,FR].mp4\n"
    )
    nums = sorted((e.season, e.number) for e in r.works[0].episodes)
    assert nums == [(0, 1), (0, 2)]


def test_reopened_season_not_duplicated():
    r = parse_catalog(
        "MN ; serie ; X [2000] ; X ; *\n"
        "MD ; 1 ; Saison 01 ; Saison 1\n"
        "MF ; VI ; HD ; 1G ; S01E01 [0h20,1x1,16x9,FR].mp4\n"
        "MD ; 1 ; Saison 01 bis ; Saison 1\n"
        "MF ; VI ; HD ; 1G ; S01E02 [0h20,1x1,16x9,FR].mp4\n"
    )
    assert r.works[0].seasons == [ParsedSeason(1, "Saison 1")]
