"""Lecteur factice partagé par les tests."""

import asyncio

from rc.player.base import PlayerState


class FakePlayer:
    def __init__(self):
        self.on_state = lambda s: None
        self.calls = []
        self.fail_start = False
        self.start_error: Exception | None = None  # levée par start (ex. mpv absent)
        self.start_gate: asyncio.Event | None = None  # start lent
        self.stop_gate: asyncio.Event | None = None  # stop lent
        self.stop_state: PlayerState | None = None  # état final émis par stop (comme mpv)
        self.command_error: Exception | None = None  # levée par les commandes (mpv figé)
        self.screenshot_error: Exception | None = None  # levée par screenshot
        self.screenshot_gate: asyncio.Event | None = None  # capture lente
        self.pause_gate: asyncio.Event | None = None  # commande de pause lente (IPC)
        self.minimize_error: Exception | None = None  # levée par set_minimized

    def _command(self, *call):
        self.calls.append(call)
        if self.command_error is not None:
            raise self.command_error

    async def start(self, path, start_at, audio, subtitle, external_subs):
        self.calls.append(("start", dict(path=path, start_at=start_at, audio=audio,
                                         subtitle=subtitle, external_subs=external_subs)))
        if self.start_gate is not None:
            await self.start_gate.wait()
        if self.start_error is not None:
            raise self.start_error
        if self.fail_start:
            raise OSError("boom")

    async def set_pause(self, paused):
        if self.pause_gate is not None:
            await self.pause_gate.wait()  # la commande n'atteint mpv qu'après
        self._command("set_pause", paused)

    async def seek(self, seconds, relative):
        self._command("seek", seconds, relative)

    async def set_volume(self, level):
        self._command("set_volume", level)

    async def set_audio(self, index):
        self._command("set_audio", index)

    async def set_subtitle(self, sel):
        self._command("set_subtitle", sel)

    async def screenshot(self, dest):
        self.calls.append(("screenshot", dest))
        if self.screenshot_gate is not None:
            await self.screenshot_gate.wait()
        if self.screenshot_error is not None:
            raise self.screenshot_error
        dest.write_bytes(b"\xff\xd8fake")

    async def set_minimized(self, minimized):
        self.calls.append(("set_minimized", minimized))
        if self.minimize_error is not None:
            raise self.minimize_error

    async def stop(self):
        self.calls.append(("stop",))
        gate, self.stop_gate = self.stop_gate, None  # seul le premier stop est lent
        if gate is not None:
            await gate.wait()
        if self.stop_state is not None:
            self.on_state(self.stop_state)

    def starts(self):
        return [c[1] for c in self.calls if c[0] == "start"]
