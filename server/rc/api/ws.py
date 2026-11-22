"""WebSocket /api/events : message hello, puis relais du bus d'événements."""

from __future__ import annotations

import asyncio
from dataclasses import asdict

import anyio
from fastapi import APIRouter, WebSocket

from rc.api.routes import Services
from rc.db import current_revision


async def _relay(ws: WebSocket, queue: asyncio.Queue[dict]) -> None:
    while True:
        await ws.send_json(await queue.get())


async def _until_closed(ws: WebSocket) -> None:
    while (await ws.receive())["type"] != "websocket.disconnect":
        pass


def build_ws_router(services: Services) -> APIRouter:
    router = APIRouter()

    @router.websocket("/api/events")
    async def events(ws: WebSocket):
        await ws.accept()
        # Abonnement avant le hello : aucun événement perdu entre les deux. Chaque client
        # a sa file bornée (le bus jette le plus ancien) : un client lent ne bloque personne.
        queue = services.bus.subscribe()
        try:
            await ws.send_json({"type": "hello", "revision": current_revision(services.conn),
                                "tv": asdict(services.tv.state),
                                "player": services.player_snapshot()})
            async with anyio.create_task_group() as tg:
                tg.start_soon(_relay, ws, queue)
                await _until_closed(ws)
                tg.cancel_scope.cancel()
        except Exception:  # client parti (envoi impossible) : rien à faire
            pass
        finally:
            services.bus.unsubscribe(queue)

    return router

