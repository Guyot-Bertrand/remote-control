from pathlib import Path

import pytest

from rc.catalog.model import CollectionRef, ParsedGroup, ParsedRating
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


def test_parser_keeps_physical_codes(result):
    assert work(result, "Alien, le huitième passager").media == ["DVD"]
    assert work(result, "Ava").media == []
    assert work(result, "Alien, le huitième passager").videos[0].playable is False


RATINGS_SAMPLE = """QS ; SC@ES ; Sens Critique / Éric Schwald (Sergent_Pepper) ; https://www.senscritique.com/Sergent_Pepper/collection
QS ; SC@ZZ ; Sens Critique / Divers
QS ; NP ; Note personnelle
MD ; 0 ; Films
MN ; film ; Ava [2017] ; Ava ; *
MQ ; WK ; ? ; https://fr.wikipedia.org/wiki/Ava
MQ ; SC@ES ; 8.0
MQ ; IM ; 7.0 ; https://www.imdb.com/title/tt5974624/
MQ ; IM ; 9.9 ; https://www.imdb.com/title/tt0000001/
MQ ; SC ; 6.2 ; https://www.senscritique.com/film/ava/123
MQ ; SC@ZZ ; ?
MQ ; NP ; 7.0
MQ ; YT@IC ; ? ; https://www.youtube.com/watch?v=x
"""


def _ratings(text):
    w = parse_catalog(text).works[0]
    return [(r.code, r.name, r.value, r.url) for r in w.ratings]


def test_ratings_order_names_values_urls():
    assert _ratings(RATINGS_SAMPLE) == [
        ("IM", "IMDb", 7.0, "https://www.imdb.com/title/tt5974624/"),
        ("SC", "Sens Critique", 6.2, "https://www.senscritique.com/film/ava/123"),
        ("SC@ES", "Éric Schwald", 8.0, None),
        ("SC@ZZ", "Divers", None, None),
        ("WK", "Wikipédia", None, "https://fr.wikipedia.org/wiki/Ava"),
        ("NP", "Note personnelle", 7.0, None),
    ]
    assert parse_catalog(RATINGS_SAMPLE).works[0].imdb_id == "tt5974624"


def test_rating_code_without_qs_line_shows_code():
    assert _ratings("MN ; film ; A [2000] ; A ; *\nMQ ; SC@AB ; 5.0\n") == [
        ("SC@AB", "SC@AB", 5.0, None)
    ]


def test_label_without_slash_or_parenthesis_kept_whole():
    text = "QS ; SC@XY ; Critique libre\nMN ; film ; A [2000] ; A ; *\nMQ ; SC@XY ; 5.0\n"
    assert _ratings(text)[0][1] == "Critique libre"


def test_malformed_note_is_none():
    for bad in ("7,5", "abc"):
        text = f"MN ; film ; A [2000] ; A ; *\nMQ ; IM ; {bad} ; https://x.org/a\n"
        assert _ratings(text) == [("IM", "IMDb", None, "https://x.org/a")]
    assert _ratings("MN ; film ; A [2000] ; A ; *\nMQ ; IM ;\n") == [("IM", "IMDb", None, None)]
    text = "MN ; film ; A [2000] ; A ; *\nMQ ; WK ; ? ; ftp://x.org\n"
    assert _ratings(text) == [("WK", "Wikipédia", None, None)]


def test_wikipedia_rating_never_has_value():
    text = "MN ; film ; A [2000] ; A ; *\nMQ ; WK ; 5.0 ; https://fr.wikipedia.org/wiki/A\n"
    assert _ratings(text) == [("WK", "Wikipédia", None, "https://fr.wikipedia.org/wiki/A")]


def test_rating_url_with_semicolon_kept_whole():
    url = "https://fr.wikipedia.org/wiki/A?x=1;y=2"
    text = f"MN ; film ; A [2000] ; A ; *\nMQ ; IM ; 7.0 ; {url}\nMQ ; NP;6.0;{url}\n"
    assert _ratings(text) == [("IM", "IMDb", 7.0, url), ("NP", "Note personnelle", 6.0, url)]


def test_mq_before_any_work_is_silently_ignored():
    res = parse_catalog("MQ ; IM ; 7.0 ; https://www.imdb.com/title/tt1/\n")
    assert res.works == []  # comportement actuel : ignorée, sans avertissement


def test_mq_without_space_after_separator():
    text = "MN ; film ; A [2000] ; A ; *\nMQ ; SC@ES ;7.0\nMQ ; SC@ES ; 3.0\n"
    w = parse_catalog(text).works[0]
    assert w.ratings == [ParsedRating("SC@ES", "SC@ES", 7.0, None)]


def test_mq_empty_middle_field():
    text = "MN ; film ; A [2000] ; A ; *\nMQ ; SC ; ; http://x\n"
    w = parse_catalog(text).works[0]
    assert w.ratings == [ParsedRating("SC", "Sens Critique", None, "http://x")]
