"""Bus d'événements : diffusion non bloquante vers des files bornées."""

from __future__ import annotations

import asyncio

QUEUE_SIZE = 100


class EventBus:
    def __init__(self) -> None:
        self._queues: set[asyncio.Queue[dict]] = set()

    def subscribe(self) -> asyncio.Queue[dict]:
        q: asyncio.Queue[dict] = asyncio.Queue(maxsize=QUEUE_SIZE)
        self._queues.add(q)
        return q

    def unsubscribe(self, q: asyncio.Queue[dict]) -> None:
        self._queues.discard(q)

    @property
    def subscriber_count(self) -> int:
        return len(self._queues)

    def publish(self, msg: dict) -> None:
        for q in list(self._queues):
            if q.full():
                try:
                    q.get_nowait()  # file pleine : on perd le plus ancien
                except asyncio.QueueEmpty:
                    pass
            q.put_nowait(msg)
