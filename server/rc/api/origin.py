"""Refus des requêtes inter-origines : POST /api/* et WebSocket /api/*.

Sans en-tête Origin (appli native) ou avec la même origine (page /tv), la requête passe.
Sinon : 403 {"error": "forbidden", "volume_label": null}, ou fermeture WebSocket 1008.
"""

from __future__ import annotations

from urllib.parse import urlsplit

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

WS_POLICY_VIOLATION = 1008


def origin_allowed(headers: dict[bytes, bytes]) -> bool:
    origin = headers.get(b"origin")
    if origin is None:
        return True
    host = headers.get(b"host", b"").decode("latin-1").lower()
    netloc = urlsplit(origin.decode("latin-1")).netloc.lower()
    return bool(netloc) and netloc == host


class OriginCheckMiddleware:
    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        kind = scope["type"]
        guarded = scope.get("path", "").startswith("/api/") and (
            kind == "websocket" or (kind == "http" and scope["method"] == "POST"))
        if guarded and not origin_allowed(dict(scope["headers"])):
            if kind == "websocket":
                await send({"type": "websocket.close", "code": WS_POLICY_VIOLATION})
            else:
                response = JSONResponse({"error": "forbidden", "volume_label": None},
                                        status_code=403)
                await response(scope, receive, send)
            return
        await self.app(scope, receive, send)
