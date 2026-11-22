from pathlib import Path

import pytest

from rc.catalog.model import CollectionRef, ParsedGroup
from rc.catalog.parser import parse_catalog

FIXTURE = Path(__file__).parent / "fixtures" / "medias_sample.txt"


@pytest.fixture(scope="module")
def result():
    return parse_catalog(FIXTURE.read_text(encoding="utf-8"))


def work(result, title_fr):
    return next(w for w in result.works if w.title_fr == title_fr)


def test_counts(result):
    assert len(result.works) == 7
    assert len(result.warnings) == 1
    assert "ZZ" in result.warnings[0]


def test_ava_metadata(result):
    w = work(result, "Ava")
    assert w.type == "film"
    assert w.title_vo is None
    assert w.year == 2017
    assert w.duration_min == 99
    assert w.directors == ["Léa Mysius"]
    assert w.cast[1] == "Noée Abita"
    assert w.imdb_id == "tt6194530"
    assert w.tags == ["[FR]", "[ST*]", "[RU]"]
    assert w.collections == [
        CollectionRef("Les 1000 plus grands films de tous les temps [2020]", "2017")
    ]


def test_alien_alt_title_and_rankless_group(result):
    w = work(result, "Alien, le huitième passager")
    assert w.title_vo == "Alien"
    assert w.title_alt == "L'Étranger"
    assert w.collections == [CollectionRef("Alien [1979-]", None)]


def test_duplicate_key_distinct_types(result):
    same = [w for w in result.works if w.key == "Le prenom [2012]"]
    assert len(same) == 2
    assert {w.type for w in same} == {"film", "theatre"}


def test_series_episode_count(result):
    w = work(result, "Back to Life")
    assert w.episode_count == "12/12"
    assert w.duration_min is None


def test_groups(result):
    assert result.groups == [
        ParsedGroup(
            "Alien [1979-]",
            None,
            "https://fr.wikipedia.org/wiki/Alien_(s%C3%A9rie_de_films)",
        )
    ]
