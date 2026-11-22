"""Types communs du lecteur."""

from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol

# Sélection de sous-titres, trois formes :
#   {"kind": "none"}
#   {"kind": "embedded", "index": n}      (n : 1-based, = sid mpv)
#   {"kind": "external", "path": "<chemin>"}
SubSel = dict



@dataclass
class PlayerState:
    position: float = 0
    duration: float | None = None
    paused: bool = False
    volume: int = 100
    audio: int | None = None
    subtitle: SubSel = field(default_factory=lambda: {"kind": "none"})
    ended: bool = False
    error: str | None = None
    running: bool = False


class Player(Protocol):
    on_state: Callable[[PlayerState], None]

    async def start(
        self,
        path: Path,
        start_at: float,
        audio: int | None,
        subtitle: SubSel,
        external_subs: list[Path],
    ) -> None: ...

    async def set_pause(self, paused: bool) -> None: ...

    async def seek(self, seconds: float, relative: bool) -> None: ...

    async def set_volume(self, level: int) -> None: ...

    async def set_audio(self, index: int | None) -> None: ...

    async def set_subtitle(self, sel: SubSel) -> None: ...

    async def stop(self) -> None: ...
