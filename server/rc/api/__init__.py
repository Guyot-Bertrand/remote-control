"""API HTTP et WebSocket."""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.gzip import GZipMiddleware
from starlette.types import ASGIApp, Receive, Scope, Send

from rc.api.origin import OriginCheckMiddleware
from rc.api.routes import Services, build_router, validation_error_handler
from rc.api.ws import build_ws_router

__all__ = ["Services", "build_router", "build_ws_router", "install_api"]

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


def install_api(app: FastAPI, services: Services) -> None:
    """Monte les routeurs, la compression gzip (réponses de 1 000 octets ou plus, hors
    affiches) et les
    erreurs de validation au format de l'API (422 {"error": "invalid", ...}), et le refus
    des hôtes non autorisés et des requêtes inter-origines (403 / WebSocket 1008)."""
    app.add_middleware(SelectiveGZipMiddleware, minimum_size=GZIP_MIN_SIZE)
    app.add_middleware(OriginCheckMiddleware, allowed_hosts=services.config.allowed_hosts)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.include_router(build_router(services))
    app.include_router(build_ws_router(services))
