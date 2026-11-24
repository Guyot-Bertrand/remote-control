"""Hôtes et origines autorisés (anti DNS rebinding et requêtes inter-origines).

1. Toute requête HTTP ou WebSocket doit porter un en-tête Host dont le nom est une
   adresse IP, « localhost », le nom de la machine (avec ou sans « .local ») ou une
   entrée de `Config.allowed_hosts`.
2. Pour POST /api/* et les WebSocket /api/*, un en-tête Origin présent doit désigner le
   même hôte que Host, et son nom doit aussi être autorisé. Sans Origin (appli native),
   la requête passe.
Refus : 403 {"error": "forbidden", "volume_label": null, "message": "..."}, ou WebSocket refusé (1008).
"""

from __future__ import annotations

import ipaddress
import socket
from collections.abc import Iterable
from urllib.parse import urlsplit

from starlette.responses import JSONResponse
from starlette.types import ASGIApp, Receive, Scope, Send

from rc.messages import error_body

WS_POLICY_VIOLATION = 1008


def host_name(host: str) -> str:
    """Nom d'hôte sans le port, crochets IPv6 retirés, en minuscules."""
    host = host.strip().lower()
    if host.startswith("["):
        return host[1:host.find("]")] if "]" in host else ""
    if host.count(":") == 1:
        host = host.split(":", 1)[0]
    return host


class HostPolicy:
    def __init__(self, allowed_hosts: Iterable[str] = ()) -> None:
        machine = socket.gethostname().lower()
        self.names = {"localhost", machine, f"{machine}.local",
                      *(h.lower() for h in allowed_hosts)}

    def name_allowed(self, name: str) -> bool:
        if not name:
            return False
        try:
            ipaddress.ip_address(name)
            return True
        except ValueError:
            return name in self.names

    def allowed(self, headers: dict[bytes, bytes], check_origin: bool) -> bool:
        raw_host = headers.get(b"host")
        if raw_host is None:
            return False
        host = raw_host.decode("latin-1").strip().lower()
        if not self.name_allowed(host_name(host)):
            return False
        origin = headers.get(b"origin")
        if not check_origin or origin is None:
            return True
        parts = urlsplit(origin.decode("latin-1"))
        return (bool(parts.netloc) and parts.netloc.lower() == host
                and self.name_allowed(parts.hostname or ""))


class OriginCheckMiddleware:
    def __init__(self, app: ASGIApp, allowed_hosts: Iterable[str] = ()) -> None:
        self.app = app
        self.policy = HostPolicy(allowed_hosts)

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        kind = scope["type"]
        if kind not in ("http", "websocket"):
            await self.app(scope, receive, send)
            return
        check_origin = scope.get("path", "").startswith("/api/") and (
            kind == "websocket" or scope["method"] == "POST")
        if not self.policy.allowed(dict(scope["headers"]), check_origin):
            if kind == "websocket":
                await send({"type": "websocket.close", "code": WS_POLICY_VIOLATION})
            else:
                response = JSONResponse(error_body("forbidden"), status_code=403)
                await response(scope, receive, send)
            return
        await self.app(scope, receive, send)
