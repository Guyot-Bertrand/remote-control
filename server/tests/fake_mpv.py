"""Faux mpv : serveur IPC JSON sur socket Unix.

En processus (classe FakeMpv) ou comme exécutable (`python fake_mpv.py --input-ipc-server=SOCK ...`).
En exécutable, il lit le JSON pointé par FAKE_MPV_CONFIG :
  {"props": {nom: valeur}, "script": [{"event": {...}} | {"sleep": s} | {"exit": true}],
   "log": "fichier", "mute": [commandes jamais répondues]}
Le script démarre quand le client observe `eof-reached` (dernière propriété observée).
Chaque commande reçue est ajoutée au fichier log (une liste JSON par ligne).
"""

import asyncio
import json
import os
import sys
from pathlib import Path


class FakeMpv:
    def __init__(self, socket_path, props=None, fail=(), log=None, script=None, mute=()):
        self.socket_path = str(socket_path)
        self.props = props or {}
        self.fail = set(fail)  # noms de commandes qui répondent en erreur
        self.mute = set(mute)  # noms de commandes jamais répondues (mpv figé)
        self.commands: list[list] = []
        self.log = log
        self.script = script or []
        self.exit_event = asyncio.Event()
        self._writers: list[asyncio.StreamWriter] = []
        self._server = None
        self._script_started = False
        self._tasks: list[asyncio.Task] = []

    async def start(self):
        self._server = await asyncio.start_unix_server(self._client, self.socket_path)

    async def close(self):
        for w in self._writers:
            w.close()
        if self._server:
            self._server.close()
            await self._server.wait_closed()

    async def emit(self, event: dict):
        data = json.dumps(event).encode() + b"\n"
        for w in self._writers:
            w.write(data)
            await w.drain()

    async def _client(self, reader, writer):
        self._writers.append(writer)
        try:
            while line := await reader.readline():
                msg = json.loads(line)
                cmd = msg["command"]
                self.commands.append(cmd)
                if self.log:
                    with open(self.log, "a") as f:
                        f.write(json.dumps(cmd) + "\n")
                reply = {"request_id": msg["request_id"], "error": "success", "data": None}
                if cmd[0] in self.fail:
                    reply["error"] = "property unavailable"
                elif cmd[0] == "get_property":
                    reply["data"] = self.props.get(cmd[1])
                data = json.dumps(reply).encode() + b"\n"
                if cmd[0] in self.mute:
                    pass
                elif cmd[0] == "get_property" and cmd[1] == "slow":
                    self._tasks.append(asyncio.create_task(self._late(writer, data)))
                else:
                    writer.write(data)
                    await writer.drain()
                if cmd[0] == "quit":
                    self.exit_event.set()
                if cmd[0] == "observe_property" and cmd[2:3] == ["eof-reached"] and not self._script_started:
                    self._script_started = True
                    self._tasks.append(asyncio.create_task(self._run_script()))
        except ConnectionError:
            pass

    async def _late(self, writer, data):
        await asyncio.sleep(0.05)
        writer.write(data)
        await writer.drain()

    async def _run_script(self):
        for step in self.script:
            if "event" in step:
                await self.emit(step["event"])
            elif "sleep" in step:
                await asyncio.sleep(step["sleep"])
            elif step.get("exit"):
                self.exit_event.set()
                return


async def _main(argv):
    sock = next(a.split("=", 1)[1] for a in argv if a.startswith("--input-ipc-server="))
    cfg = json.loads(Path(os.environ["FAKE_MPV_CONFIG"]).read_text())
    fake = FakeMpv(sock, cfg.get("props"), log=cfg.get("log"), script=cfg.get("script"),
                   mute=cfg.get("mute", ()), fail=cfg.get("fail", ()))
    if cfg.get("log"):
        with open(cfg["log"], "a") as f:
            f.write(json.dumps(["argv", *argv]) + "\n")
    await fake.start()
    await fake.exit_event.wait()
    await asyncio.sleep(0.05)
    await fake.close()


if __name__ == "__main__":
    asyncio.run(_main(sys.argv[1:]))
