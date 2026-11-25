"""La télé suit la vue du téléphone (`MirrorService`)."""

import asyncio
import logging

import pytest

from rc.mirror import MirrorService
from rc.player.session import PlayRequest

from test_park import emit, env, paused_at_1394  # noqa: F401 (fixture partagée)

V_BROWSE = {"phone_id": "p", "seq": 1, "screen": "browse", "title": "Films",
            "items": [{"kind": "work", "id": 1}]}
V_SEARCH = {"phone_id": "p", "seq": 2, "screen": "search", "query": "ava", "items": []}
V_REMOTE = {"phone_id": "p", "seq": 3, "screen": "remote"}


@pytest.fixture
def m(env):  # noqa: F811
    return MirrorService(env.conn, env.tv, env.svc, clock=env.clock)


def view(base, seq, phone="p"):
    return {**base, "phone_id": phone, "seq": seq}


async def test_view_shown_when_idle(env, m):  # noqa: F811
    assert await m.accept(V_BROWSE)
    assert env.tv.state.screen == "view"
    assert env.tv.state.view["screen"] == "browse" and env.tv.state.pause is None


async def test_stale_seq_ignored_per_phone(env, m):  # noqa: F811
    await m.accept(view(V_BROWSE, 5, "a"))
    assert not await m.accept(view(V_SEARCH, 4, "a"))
    assert not await m.accept(view(V_SEARCH, 5, "a"))
    assert env.tv.state.view["screen"] == "browse"
    assert await m.accept(view(V_SEARCH, 1, "b"))  # autre téléphone : accepté, il pilote
    assert env.tv.state.view["screen"] == "search"


async def test_at_most_16_phones_remembered(env, m):  # noqa: F811
    await m.accept(view(V_BROWSE, 10, "old"))
    for i in range(16):
        await m.accept(view(V_BROWSE, 10, f"p{i}"))
    assert await m.accept(view(V_SEARCH, 1, "old"))  # oublié : accepté de nouveau


async def test_view_during_playback_is_remembered_not_shown(env, m):  # noqa: F811
    await env.svc.play(PlayRequest("work", 1, "start"))
    emit(env, position=10.0, duration=6000.0)
    assert await m.accept(V_SEARCH)
    assert env.tv.state.screen == "playing"
    await env.svc.stop()
    assert env.tv.state.screen == "view" and env.tv.state.view["screen"] == "search"


async def test_view_while_paused_parks_and_shows_corner(env, m):  # noqa: F811
    await paused_at_1394(env)
    assert await m.accept(V_BROWSE)
    assert ("stop",) in env.player.calls
    assert env.svc.parked
    assert env.tv.state.screen == "view" and env.tv.state.view["screen"] == "browse"
    assert env.tv.state.pause["position"] == 1394


async def test_second_view_while_parked_keeps_corner(env, m):  # noqa: F811
    await paused_at_1394(env)
    await m.accept(V_BROWSE)
    env.player.calls.clear()
    assert await m.accept(V_SEARCH)
    assert env.player.calls == []  # déjà mise de côté
    assert env.tv.state.view["screen"] == "search"
    assert env.tv.state.pause["position"] == 1394


async def test_remote_view_while_parked_shows_fullscreen_pause(env, m):  # noqa: F811
    await paused_at_1394(env)
    await m.accept(V_BROWSE)
    assert await m.accept(V_REMOTE)
    assert env.tv.state.screen == "paused"
    assert env.tv.state.pause["position"] == 1394


async def test_remote_view_while_paused_not_parked_does_nothing(env, m):  # noqa: F811
    await paused_at_1394(env)
    assert await m.accept(V_REMOTE)
    assert not env.svc.parked and env.player.calls == []
    assert env.tv.state.screen == "playing"


async def test_remote_view_without_playback_does_nothing(env, m):  # noqa: F811
    await m.accept(V_BROWSE)
    assert await m.accept(V_REMOTE)
    assert env.tv.state.screen == "view" and env.tv.state.view["screen"] == "browse"


async def test_pause_while_phone_elsewhere_parks(env, m):  # noqa: F811
    env.svc.on_pause_change = m.on_pause_change
    await m.accept(view(V_REMOTE, 1))
    await env.svc.play(PlayRequest("work", 1, "start"))
    emit(env, position=1394.0, duration=6000.0)
    await m.accept(view(V_BROWSE, 2))  # mémorisée
    assert env.tv.state.screen == "playing"
    emit(env, position=1394.0, duration=6000.0, paused=True)
    for _ in range(5):
        await asyncio.sleep(0)
    assert env.svc.parked
    assert env.tv.state.screen == "view" and env.tv.state.view["screen"] == "browse"
    assert env.tv.state.pause["position"] == 1394


async def test_pause_while_remote_open_stays_fullscreen(env, m):  # noqa: F811
    env.svc.on_pause_change = m.on_pause_change
    await env.svc.play(PlayRequest("work", 1, "start"))
    emit(env, position=10.0, duration=6000.0)
    await m.accept(V_REMOTE)
    emit(env, position=10.0, duration=6000.0, paused=True)
    for _ in range(5):
        await asyncio.sleep(0)
    assert not env.svc.parked and env.tv.state.screen == "playing"


async def test_scheduled_park_failure_is_logged(env, m, caplog):  # noqa: F811
    env.svc.on_pause_change = m.on_pause_change
    await m.accept(V_BROWSE)
    await env.svc.play(PlayRequest("work", 1, "start"))

    async def broken():
        raise RuntimeError("panne")
    env.svc.park = broken
    with caplog.at_level(logging.ERROR, logger="rc.mirror"):
        emit(env, position=10.0, duration=6000.0, paused=True)
        for _ in range(5):
            await asyncio.sleep(0)
    assert "panne" in caplog.text


async def test_idle_returns_to_standby_after_10_min(env, m):  # noqa: F811
    await m.accept(V_BROWSE)
    env.clock.t += 599
    m.check_idle()
    assert env.tv.state.screen == "view"
    env.clock.t += 2
    m.check_idle()
    assert env.tv.state.screen == "home"


async def test_idle_does_not_leave_pause(env, m):  # noqa: F811
    await paused_at_1394(env)
    await m.accept(V_BROWSE)
    env.clock.t += 601
    m.check_idle()
    assert env.tv.state.screen == "view" and env.tv.state.pause is not None


async def test_idle_does_not_interrupt_playback(env, m):  # noqa: F811
    await m.accept(V_BROWSE)
    await env.svc.play(PlayRequest("work", 1, "start"))
    env.clock.t += 601
    m.check_idle()
    assert env.tv.state.screen == "playing"


async def test_idle_loop_checks_every_30_s(env, m):  # noqa: F811
    await m.accept(V_BROWSE)
    delays = []

    async def sleep(s):
        delays.append(s)
        env.clock.t += s
        if len(delays) > 21:
            raise asyncio.CancelledError

    with pytest.raises(asyncio.CancelledError):
        await m.idle_loop(sleep=sleep)
    assert set(delays) == {30} and env.tv.state.screen == "home"


async def test_items_are_enriched(env, m):  # noqa: F811
    await m.accept({**V_BROWSE, "items": [{"kind": "work", "id": 1},
                                          {"kind": "work", "id": 99999}],
                    "sections": [{"label": "Œuvres", "people": [],
                                  "items": [{"kind": "work", "id": 1}]}]})
    v = env.tv.state.view
    assert [i["title_fr"] for i in v["items"]] == ["Ava"]  # inconnu omis
    assert v["sections"][0]["items"][0]["title_fr"] == "Ava"
    assert v["sections"][0]["label"] == "Œuvres"
    assert v["phone_id"] == "p" and v["title"] == "Films"
