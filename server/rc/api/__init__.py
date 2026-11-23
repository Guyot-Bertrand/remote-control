"""API HTTP et WebSocket."""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.gzip import GZipMiddleware

from rc.api.origin import OriginCheckMiddleware
from rc.api.routes import Services, build_router, validation_error_handler
from rc.api.ws import build_ws_router

__all__ = ["Services", "build_router", "build_ws_router", "install_api"]

GZIP_MIN_SIZE = 1000


def install_api(app: FastAPI, services: Services) -> None:
    """Monte les routeurs, la compression gzip (réponses de 1 000 octets ou plus) et les
    erreurs de validation au format de l'API (422 {"error": "invalid", ...}), et le refus
    des requêtes inter-origines (403 / WebSocket 1008)."""
    app.add_middleware(GZipMiddleware, minimum_size=GZIP_MIN_SIZE)
    app.add_middleware(OriginCheckMiddleware)
    app.add_exception_handler(RequestValidationError, validation_error_handler)
    app.include_router(build_router(services))
    app.include_router(build_ws_router(services))
