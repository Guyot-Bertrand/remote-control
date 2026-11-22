"""WebSocket /api/events : message hello, puis relais du bus d'événements."""

from __future__ import annotations

import asyncio
import contextlib
import json
import logging
from dataclasses import asdict

import anyio
from fastapi import APIRouter, WebSocket, WebSocketDisconnect
from starlette.websockets import WebSocketDisconnected

from rc.api.routes import Services
from rc.db import current_revision

log = logging.getLogger(__name__)

# Fin normale d'une connexion : client parti, ou envoi après fermeture.
_CLOSED = (WebSocketDisconnect, WebSocketDisconnected, anyio.ClosedResourceError)


def _is_closed(exc: BaseException) -> bool:
    if isinstance(exc, BaseExceptionGroup):
        return all(_is_closed(e) for e in exc.exceptions)
    return isinstance(exc, _CLOSED)


async def _relay(ws: WebSocket, queue: asyncio.Queue[dict]) -> None:
    while True:
        msg = await queue.get()
        try:
            text = json.dumps(msg)
        except (TypeError, ValueError):  # événement invalide : journalisé, les suivants passent
            log.exception("événement non sérialisable ignoré : %r", msg)
            continue
        await ws.send_text(text)


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
        except Exception as exc:
            if not _is_closed(exc):
                log.exception("erreur sur le WebSocket /api/events")
                with contextlib.suppress(Exception):  # le client se reconnectera
                    await ws.close(1011)
        finally:
            services.bus.unsubscribe(queue)

    return router
