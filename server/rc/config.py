"""Configuration du serveur rc."""
from __future__ import annotations

import os
import tomllib
from dataclasses import dataclass, field, fields
from pathlib import Path

DEFAULT_CONFIG_PATH = Path("~/.config/rc/config.toml")


def _default_data_dir() -> Path:
    return Path("~/.local/share/rc").expanduser()


def _default_scan_roots() -> list[Path]:
    return [Path("/media"), Path("/mnt"), Path("/run/media")]


@dataclass
class Config:
    data_dir: Path = field(default_factory=_default_data_dir)
    port: int = 8765
    scan_roots: list[Path] = field(default_factory=_default_scan_roots)
    catalog_path: Path | None = None
    mpv_path: str = "mpv"
    mpv_extra_args: list[str] = field(default_factory=list)
    tmdb_api_key: str = ""
    poll_interval: float = 30.0


def load_config(path: Path | None = None) -> Config:
    """Charge la configuration : `path`, puis RC_CONFIG, puis ~/.config/rc/config.toml."""
    if path is None:
        env = os.environ.get("RC_CONFIG")
        path = Path(env) if env else DEFAULT_CONFIG_PATH
    path = Path(path).expanduser()
    if not path.is_file():
        return Config()
    with path.open("rb") as fh:
        raw = tomllib.load(fh)
    known = {f.name for f in fields(Config)}
    values = {k: v for k, v in raw.items() if k in known}
    for key in ("data_dir", "catalog_path"):
        if values.get(key) is not None:
            values[key] = Path(values[key]).expanduser()
    if "scan_roots" in values:
        values["scan_roots"] = [Path(p).expanduser() for p in values["scan_roots"]]
    return Config(**values)
