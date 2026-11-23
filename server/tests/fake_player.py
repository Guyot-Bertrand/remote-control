"""Lecteur factice partagé par les tests."""

import asyncio

from rc.player.base import PlayerState


class FakePlayer:
    def __init__(self):
        self.on_state = lambda s: None
        self.calls = []
        self.fail_start = False
        self.start_gate: asyncio.Event | None = None  # start lent
        self.stop_gate: asyncio.Event | None = None  # stop lent
        self.stop_state: PlayerState | None = None  # état final émis par stop (comme mpv)

    async def start(self, path, start_at, audio, subtitle, external_subs):
        self.calls.append(("start", dict(path=path, start_at=start_at, audio=audio,
                                         subtitle=subtitle, external_subs=external_subs)))
        if self.start_gate is not None:
            await self.start_gate.wait()
        if self.fail_start:
            raise OSError("boom")

    async def set_pause(self, paused):
        self.calls.append(("set_pause", paused))

    async def seek(self, seconds, relative):
        self.calls.append(("seek", seconds, relative))

    async def set_volume(self, level):
        self.calls.append(("set_volume", level))

    async def set_audio(self, index):
        self.calls.append(("set_audio", index))

    async def set_subtitle(self, sel):
        self.calls.append(("set_subtitle", sel))

    async def stop(self):
        self.calls.append(("stop",))
        gate, self.stop_gate = self.stop_gate, None  # seul le premier stop est lent
        if gate is not None:
            await gate.wait()
        if self.stop_state is not None:
            self.on_state(self.stop_state)

    def starts(self):
        return [c[1] for c in self.calls if c[0] == "start"]
