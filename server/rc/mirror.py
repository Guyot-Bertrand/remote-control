"""La télé suit la vue du téléphone (arbitrages 14, 15 et 17)."""

from __future__ import annotations

import asyncio
import logging
import sqlite3
import time
from collections import OrderedDict
from collections.abc import Callable

from rc.api.serialize import enrich
from rc.player.session import PlaybackService, TvController

log = logging.getLogger(__name__)

MAX_PHONES = 16  # téléphones dont on retient le dernier `seq`
IDLE_CHECK_EVERY = 30  # secondes entre deux vérifications d'inactivité


class MirrorService:
    def __init__(self, conn: sqlite3.Connection, tv: TvController,
                 playback: PlaybackService, clock: Callable[[], float] = time.monotonic,
                 idle_after: float = 600) -> None:
        self.conn, self.tv, self.playback = conn, tv, playback
        self.clock, self.idle_after = clock, idle_after
        self._seqs: OrderedDict[str, int] = OrderedDict()  # dernier seq par téléphone
        self._view: dict | None = None  # dernière vue acceptée (enrichie)
        self._view_at: float | None = None
        self._tasks: set[asyncio.Task] = set()  # mises de côté programmées

    async def accept(self, view: dict) -> bool:
        """Vue du téléphone (corps validé de l'arbitrage 14) ; False si elle est périmée."""
        phone, seq = view["phone_id"], view["seq"]
        last = self._seqs.get(phone)
        if last is not None and seq <= last:
            return False
        self._seqs[phone] = seq
        self._seqs.move_to_end(phone)
        while len(self._seqs) > MAX_PHONES:
            self._seqs.popitem(last=False)  # oublie le plus ancien
        view = self._enriched(view)
        self._view, self._view_at = view, self.clock()
        await self._apply(view)
        return True

    def on_pause_change(self, paused: bool) -> None:
        """Branché sur `playback.on_pause_change` : une pause pendant que le téléphone
        montre autre chose que la télécommande met la vidéo de côté."""
        view = self._view
        if not paused or view is None or view["screen"] == "remote":
            return
        task = asyncio.get_running_loop().create_task(self._apply(view))
        self._tasks.add(task)
        task.add_done_callback(self._task_done)

    def _task_done(self, task: asyncio.Task) -> None:
        self._tasks.discard(task)
        if not task.cancelled() and task.exception() is not None:
            log.error("mise de côté programmée en échec", exc_info=task.exception())

    def check_idle(self) -> None:
        """Retour à la veille sans vue depuis `idle_after` secondes (hors pause)."""
        st = self.tv.state
        if (self._view_at is not None and st.screen == "view" and st.pause is None
                and self.clock() - self._view_at > self.idle_after):
            self.tv.show("home")

    async def idle_loop(self, sleep: Callable = asyncio.sleep) -> None:
        while True:
            await sleep(IDLE_CHECK_EVERY)
            try:
                self.check_idle()
            except Exception:  # CancelledError n'en hérite pas : l'annulation passe
                log.exception("vérification d'inactivité en échec")

    def _enriched(self, view: dict) -> dict:
        view = dict(view)
        cid = view.get("collection_id")
        if view.get("items"):
            view["items"] = enrich(self.conn, view["items"], cid)
        if view.get("sections"):
            view["sections"] = [
                {**s, "items": enrich(self.conn, s["items"], cid)} if s.get("items")
                else dict(s) for s in view["sections"]]
        return view

    async def _apply(self, view: dict) -> None:
        pb = self.playback
        if (view["screen"] != "remote" and pb.current is not None and pb.state.paused
                and not pb.parked):
            await pb.park()
            if self._view is not view:  # vue plus récente reçue pendant la mise de côté
                return
        self._show(view)

    def _show(self, view: dict) -> None:
        pb, remote = self.playback, view["screen"] == "remote"
        if pb.current is None:
            if not remote:
                self.tv.show_view(view)
        elif not pb.state.paused:
            if not remote:  # la télécommande n'est pas un écran à montrer en fin de lecture
                self.tv.remember_view(view)
        elif remote:
            if pb.parked:
                self.tv.show_paused(pb.pause_info())
        else:
            self.tv.show_view(view, pb.pause_info())
