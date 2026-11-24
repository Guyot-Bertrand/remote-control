"""API HTTP et WebSocket."""

from __future__ import annotations

import logging

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.gzip import GZipMiddleware
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from rc.api.origin import OriginCheckMiddleware
from rc.api.routes import (Services, build_router, http_error_handler,
                           validation_error_handler)
from rc.api.ws import build_ws_router
from rc.messages import error_body

__all__ = ["Services", "build_router", "build_ws_router", "install_api"]

log = logging.getLogger(__name__)

GZIP_MIN_SIZE = 1000
GZIP_EXCLUDED = ("/api/assets/",)  # JPEG déjà compressés : gzip ne ferait que coûter


class SelectiveGZipMiddleware:
    """GZip, sauf pour les chemins exclus."""

    def __init__(self, app: ASGIApp, minimum_size: int,
                 excluded: tuple[str, ...] = GZIP_EXCLUDED) -> None:
        self.app = app
        self.gzip = GZipMiddleware(app, minimum_size=minimum_size)
        self.excluded = excluded

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] == "http" and scope["path"].startswith(self.excluded):
            await self.app(scope, receive, send)
        else:
            await self.gzip(scope, receive, send)


class InternalErrorMiddleware:
    """Exception imprévue dans une route HTTP : trace dans le journal et réponse
    500 {"error": "internal", "volume_label": null, "message": ...} plutôt qu'un texte nu.
    Si la réponse a déjà commencé, l'exception est relancée (rien d'autre à faire)."""

    def __init__(self, app: ASGIApp) -> None:
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return
        started = False

        async def tracking_send(message) -> None:
            nonlocal started
            if message["type"] == "http.response.start":
                started = True
            await send(message)

        try:
            await self.app(scope, receive, tracking_send)
        except Exception:
            log.exception("erreur interne sur %s %s", scope.get("method"), scope.get("path"))
            if started:
                raise
            await JSONResponse(error_body("internal"), status_code=500)(scope, receive, send)


def install_api(app: FastAPI, services: Services) -> None:
    """Monte les routeurs, la compression gzip (réponses de 1 000 octets ou plus, hors
    affiches) et les
    erreurs au format de l'API (404/405 de routage, 422 {"error": "invalid", ...},
    500 {"error": "internal", ...}), et le refus
    des hôtes non autorisés et des requêtes inter-origines (403 / WebSocket 1008)."""
    app.add_middleware(InternalErrorMiddleware)  # au plus près des routes
    app.add_middleware(SelectiveGZipMiddleware, minimum_size=GZIP_MIN_SIZE)
    app.add_middleware(OriginCheckMiddleware, allowed_hosts=services.config.allowed_hosts)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.add_exception_handler(StarletteHTTPException, http_error_handler)
    app.include_router(build_router(services))
    app.include_router(build_ws_router(services))
