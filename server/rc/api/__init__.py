"""API HTTP et WebSocket."""

from __future__ import annotations

from fastapi import FastAPI
from fastapi.middleware.gzip import GZipMiddleware

from rc.api.routes import Services, build_router
from rc.api.ws import build_ws_router

__all__ = ["Services", "build_router", "build_ws_router", "install_api"]

GZIP_MIN_SIZE = 1000


def install_api(app: FastAPI, services: Services) -> None:
    """Monte les routeurs et la compression gzip (réponses de 1 000 octets ou plus)."""
    app.add_middleware(GZipMiddleware, minimum_size=GZIP_MIN_SIZE)
    app.include_router(build_router(services))
    app.include_router(build_ws_router(services))
