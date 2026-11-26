"""Pilotage de mpv par son socket IPC JSON."""

import asyncio
import contextlib
import copy
import json
import logging
import sys
import uuid
from collections.abc import AsyncIterator
from pathlib import Path
from typing import Any

from rc.player.base import PlayerState, SubSel

log = logging.getLogger("rc.player.mpv")

OBSERVED = ("time-pos", "duration", "pause", "volume", "aid", "sid", "eof-reached")
COMMAND_TIMEOUT = 5  # secondes : au-delà, mpv est considéré comme figé


class MpvError(Exception):
    pass


class MpvTimeout(MpvError):
    """mpv n'a pas répondu à une commande dans le délai imparti."""


class MpvClient:
    def __init__(self, socket_path: Path):
        self.socket_path = Path(socket_path)
        self._reader: asyncio.StreamReader | None = None
        self._writer: asyncio.StreamWriter | None = None
        self._task: asyncio.Task | None = None
        self._pending: dict[int, asyncio.Future] = {}
        self._events: asyncio.Queue[dict | None] = asyncio.Queue()
        self._next_id = 0
        self._next_observe = 0

    async def connect(self, retries: int = 50, delay: float = 0.1) -> None:
        last: Exception | None = None
        for _ in range(retries):
            try:
                self._reader, self._writer = await asyncio.open_unix_connection(
                    str(self.socket_path)
                )
                break
            except (FileNotFoundError, ConnectionRefusedError, OSError) as e:
                last = e
                await asyncio.sleep(delay)
        else:
            raise ConnectionError(f"connexion à mpv impossible : {last}")
        self._task = asyncio.create_task(self._read_loop())

    async def _read_loop(self) -> None:
        assert self._reader is not None
        try:
            while True:
                line = await self._reader.readline()
                if not line:
                    break
                try:
                    msg = json.loads(line)
                except ValueError:
                    continue
                if "request_id" in msg:
                    fut = self._pending.pop(msg["request_id"], None)
                    if fut is not None and not fut.done():
                        fut.set_result(msg)
                elif "event" in msg:
                    self._events.put_nowait(msg)
        except (ConnectionError, OSError):
            pass
        finally:
            for fut in self._pending.values():
                if not fut.done():
                    fut.set_exception(ConnectionError("socket mpv fermé"))
            self._pending.clear()
            self._events.put_nowait(None)

    async def command(self, *args: Any) -> Any:
        if self._writer is None:
            raise ConnectionError("non connecté")
        self._next_id += 1
        rid = self._next_id
        fut: asyncio.Future = asyncio.get_running_loop().create_future()
        self._pending[rid] = fut
        try:
            self._writer.write(
                json.dumps({"command": list(args), "request_id": rid}).encode() + b"\n"
            )
            await self._writer.drain()
        except (ConnectionError, OSError) as e:
            self._pending.pop(rid, None)
            raise ConnectionError(str(e)) from e
        try:
            reply = await asyncio.wait_for(fut, COMMAND_TIMEOUT)
        except asyncio.TimeoutError:
            raise MpvTimeout(f"mpv ne répond pas à {args[0]!r}") from None
        finally:
            self._pending.pop(rid, None)
        if reply.get("error") != "success":
            raise MpvError(reply.get("error"))
        return reply.get("data")

    async def set_property(self, name: str, value: Any) -> None:
        await self.command("set_property", name, value)

    async def observe(self, name: str) -> int:
        self._next_observe += 1
        await self.command("observe_property", self._next_observe, name)
        return self._next_observe

    async def events(self) -> AsyncIterator[dict]:
        while True:
            ev = await self._events.get()
            if ev is None:
                self._events.put_nowait(None)  # les appels suivants se terminent aussi
                return
            yield ev

    async def close(self) -> None:
        if self._task is not None:
            self._task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await self._task
            self._task = None
        if self._writer is not None:
            self._writer.close()
            with contextlib.suppress(Exception):
                await self._writer.wait_closed()
            self._writer = None


class MpvPlayer:
    def __init__(self, mpv_path: str, extra_args: list[str], runtime_dir: Path):
        self.mpv_path = mpv_path
        self.extra_args = list(extra_args)
        self.runtime_dir = Path(runtime_dir)
        self.on_state = lambda state: None
        self.state = PlayerState()
        self._proc: asyncio.subprocess.Process | None = None
        self._client: MpvClient | None = None
        self._pump: asyncio.Task | None = None
        self._sock: Path | None = None
        self._external: list[Path] = []
        self._embedded = 0
        self._audio: int | None = None
        self._subtitle: SubSel = {"kind": "none"}
        self._stopping = False
        self._vid_kept: Any = None  # piste vidéo retirée par set_video(False)

    # -- cycle de vie ---------------------------------------------------

    async def start(self, path, start_at, audio, subtitle, external_subs) -> None:
        await self.stop()
        self.runtime_dir.mkdir(parents=True, exist_ok=True)
        self._sock = self.runtime_dir / f"mpv-{uuid.uuid4().hex[:12]}.sock"
        self._external = [Path(p) for p in external_subs]
        self._embedded = 0
        self._audio, self._subtitle = audio, subtitle
        self._stopping = False
        self._vid_kept = None
        self.state = PlayerState(running=True)
        args = [
            self.mpv_path, "--fs", "--no-terminal", "--idle=no",
            f"--input-ipc-server={self._sock}", "--hwdec=auto-safe",
            "--alang=fr,fre,fra", "--slang=fr,fre,fra", f"--start={start_at}",
            # Barre de volume affichée 5 s sur la vidéo, pleine à 100 (bornes de l'API).
            "--osd-duration=5000", "--volume-max=100",
            *("--sub-file=" + str(p) for p in self._external),
            *self.extra_args, str(path),
        ]
        client = MpvClient(self._sock)
        self._client = client
        try:
            self._proc = await asyncio.create_subprocess_exec(*args)
            await client.connect()
            for name in OBSERVED:
                await client.observe(name)
        except BaseException:
            await self._cleanup()
            self.state.running = False
            self.state.error = "start_failed"
            self._emit()
            raise
        self._pump = asyncio.create_task(self._run(client, self._proc))

    async def _run(self, client: MpvClient, proc: asyncio.subprocess.Process) -> None:
        try:
            async for ev in client.events():
                try:
                    await self._handle(client, ev)
                except MpvError:
                    log.exception("erreur mpv pendant le traitement de %s", ev.get("event"))
        except ConnectionError:
            pass
        finally:
            with contextlib.suppress(Exception):
                await asyncio.wait_for(proc.wait(), 3)
            await client.close()
            if self._sock is not None:
                self._sock.unlink(missing_ok=True)
            self.state.running = False
            if not self.state.ended and not self._stopping:
                self.state.error = "process_exit"
            self._emit()

    def _emit(self) -> None:
        try:
            self.on_state(copy.copy(self.state))
        except Exception:
            log.exception("on_state a levé une exception")

    async def _handle(self, client: MpvClient, ev: dict) -> None:
        name = ev.get("event")
        if name == "property-change":
            self._apply(ev.get("name"), ev.get("data"))
            self._emit()
        elif name == "file-loaded":
            tracks = await client.command("get_property", "track-list") or []
            self._embedded = sum(
                1 for t in tracks if t.get("type") == "sub" and not t.get("external")
            )
            if self._audio is not None:
                await client.set_property("aid", self._audio)
            await client.set_property("sid", self._sid(self._subtitle))
        elif name == "end-file" and ev.get("reason") == "eof":
            self.state.ended = True
            self._emit()

    def _apply(self, prop: str | None, data: Any) -> None:
        s = self.state
        # time-pos et duration deviennent indisponibles (null) en fin de fichier :
        # on garde alors la dernière valeur connue, utile pour décider « vu ».
        if prop == "time-pos":
            if isinstance(data, (int, float)) and not isinstance(data, bool):
                s.position = data
        elif prop == "duration":
            if isinstance(data, (int, float)) and not isinstance(data, bool):
                s.duration = data
        elif prop == "pause":
            s.paused = bool(data)
        elif prop == "volume":
            if isinstance(data, (int, float)):
                s.volume = int(data)
        elif prop == "aid":
            s.audio = data if isinstance(data, int) and not isinstance(data, bool) else None
        elif prop == "sid":
            s.subtitle = self._sel_from_sid(data)
        elif prop == "eof-reached" and data is True:
            s.ended = True

    def _sel_from_sid(self, sid: Any) -> SubSel:
        if not isinstance(sid, int) or isinstance(sid, bool):
            return {"kind": "none"}
        if sid <= self._embedded:
            return {"kind": "embedded", "index": sid}
        k = sid - self._embedded
        if 1 <= k <= len(self._external):
            return {"kind": "external", "path": str(self._external[k - 1])}
        return {"kind": "none"}

    def _sid(self, sel: SubSel) -> int | str:
        kind = sel.get("kind")
        if kind == "embedded":
            return sel["index"]
        if kind == "external":
            paths = [str(p) for p in self._external]
            if str(sel["path"]) in paths:
                return self._embedded + paths.index(str(sel["path"])) + 1
        return "no"

    # -- commandes ------------------------------------------------------

    def _c(self) -> MpvClient:
        if self._client is None:
            raise RuntimeError("aucune lecture en cours")
        return self._client

    async def set_pause(self, paused: bool) -> None:
        await self._c().set_property("pause", paused)

    async def seek(self, seconds: float, relative: bool) -> None:
        await self._c().command("seek", seconds, "relative" if relative else "absolute")

    async def set_volume(self, level: int) -> None:
        # Préfixe osd-bar : mpv montre sa barre de volume (sans texte) en changeant la valeur.
        await self._c().command("osd-bar", "set", "volume", str(level))

    async def set_audio(self, index: int | None) -> None:
        await self._c().set_property("aid", "auto" if index is None else index)

    async def set_subtitle(self, sel: SubSel) -> None:
        await self._c().set_property("sid", self._sid(sel))

    async def screenshot(self, dest: Path) -> None:
        # « video » : image seule, sans sous-titres ni OSD
        await self._c().command("screenshot-to-file", str(dest), "video")

    async def set_minimized(self, minimized: bool) -> None:
        await self._c().set_property("window-minimized", minimized)
        if not minimized:
            await self._c().set_property("fullscreen", True)

    async def set_video(self, enabled: bool) -> None:
        """Retire la piste vidéo (mpv ferme sa fenêtre) ou la rend, en plein écran."""
        client = self._c()
        if not enabled:
            try:
                vid = await client.command("get_property", "vid")
            except MpvError:
                vid = None
            self._vid_kept = None if vid is False or vid == "no" else vid
            await client.set_property("vid", "no")
            return
        await client.set_property("vid", self._vid_kept if self._vid_kept is not None else "auto")
        await client.set_property("fullscreen", True)

    async def stop(self) -> None:
        self._stopping = True
        if self._client is not None and self._proc and self._proc.returncode is None:
            with contextlib.suppress(Exception):
                await asyncio.wait_for(self._client.command("quit"), 1)
        if self._pump is not None:
            try:
                await asyncio.wait_for(asyncio.shield(self._pump), 3)
            except (asyncio.TimeoutError, Exception):
                pass
        await self._cleanup()

    async def _cleanup(self) -> None:
        proc, pump, client = self._proc, self._pump, self._client
        self._proc = self._pump = self._client = None
        if proc is not None and proc.returncode is None:
            proc.terminate()
            try:
                await asyncio.wait_for(proc.wait(), 3)
            except asyncio.TimeoutError:
                proc.kill()
                await proc.wait()
        elif proc is not None:
            await proc.wait()
        if pump is not None and not pump.done():
            pump.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await pump
        if client is not None:
            await client.close()
        if self._sock is not None:
            self._sock.unlink(missing_ok=True)


async def _demo(path: str) -> None:
    import tempfile

    with tempfile.TemporaryDirectory() as d:
        p = MpvPlayer("mpv", [], Path(d))
        p.on_state = lambda s: print(s)
        await p.start(Path(path), 0, None, {"kind": "none"}, [])
        await asyncio.sleep(5)
        await p.set_pause(True)
        await asyncio.sleep(1)
        await p.seek(10, True)
        await p.set_pause(False)
        await asyncio.sleep(3)
        await p.stop()


if __name__ == "__main__":
    asyncio.run(_demo(sys.argv[1]))
