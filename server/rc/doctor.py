"""Diagnostic du mini-PC : décodage vidéo matériel (vainfo, ffprobe, mpv)."""

from __future__ import annotations

import asyncio
import contextlib
import json
import re
import shutil
import subprocess
import tempfile
import time
from collections import Counter
from pathlib import Path

from rc.config import Config
from rc.player.mpv import MpvClient
from rc.volumes import VolumeUnreadable, build_index, list_volumes

PROBE_TIMEOUT = 15  # s par fichier
SURVEY_BUDGET = 600  # s pour toute l'enquête
MAX_SURVEY = 300  # fichiers sondés au plus, répartis sur toute la collection
UNREADABLE = "illisible"
_VA_LINE = re.compile(r"^\s*VAProfile(\w+)\s*:\s*VAEntrypointVLD\b", re.M)
_VA_MAP = {
    "H264Main": "h264", "H264High": "h264", "H264ConstrainedBaseline": "h264",
    "HEVCMain": "hevc", "HEVCMain10": "hevc10",
    "VP9Profile0": "vp9", "VP9Profile2": "vp9_10", "AV1Profile0": "av1",
    "MPEG2Main": "mpeg2", "MPEG2Simple": "mpeg2",
    "VC1Simple": "vc1", "VC1Main": "vc1", "VC1Advanced": "vc1",
    "VP8Version0_3": "vp8",
    "MPEG4Simple": "mpeg4", "MPEG4AdvancedSimple": "mpeg4", "MPEG4Main": "mpeg4",
}
KNOWN = set(_VA_MAP.values())  # clés dont le matériel peut être vérifié
_CODECS = {"mpeg2video": "mpeg2"}


def parse_vainfo(text: str) -> set[str]:
    return {_VA_MAP[n] for n in _VA_LINE.findall(text) if n in _VA_MAP}


def codec_key(video_codec: str, profile: str | None) -> str:
    codec = _CODECS.get(video_codec, video_codec)
    if codec == "hevc" and profile not in (None, "Main", "Main 10"):
        return f"hevc ({profile})"
    if codec == "hevc" and profile == "Main 10":
        return "hevc10"
    if codec == "vp9" and profile not in (None, "Profile 0", "Profile 2"):
        return f"vp9 ({profile})"
    if codec == "vp9" and profile == "Profile 2":
        return "vp9_10"
    return codec


def _probe_key(path: Path, runner) -> str:
    try:
        proc = runner(
            ["ffprobe", "-v", "error", "-select_streams", "v:0",
             "-show_entries", "stream=codec_name,profile", "-of", "json", str(path)],
            capture_output=True, text=True, check=True, timeout=PROBE_TIMEOUT,
        )
        stream = (json.loads(proc.stdout).get("streams") or [None])[0]
        if not stream or not stream.get("codec_name"):
            return UNREADABLE
        return codec_key(stream["codec_name"], stream.get("profile"))
    except (OSError, subprocess.SubprocessError, ValueError):
        return UNREADABLE


def _spread(paths: list[Path], limit: int) -> list[Path]:
    if len(paths) <= limit:
        return paths
    return [paths[i * len(paths) // limit] for i in range(limit)]


def _survey(media_paths: list[Path], runner, budget: float = SURVEY_BUDGET,
            clock=time.monotonic) -> tuple[Counter[str], dict[str, Path], bool]:
    counts: Counter[str] = Counter()
    examples: dict[str, Path] = {}
    start = clock()
    for path in _spread(sorted(media_paths), MAX_SURVEY):
        if clock() - start > budget:
            return counts, examples, True
        key = _probe_key(path, runner)
        counts[key] += 1
        examples.setdefault(key, path)
    return counts, examples, False


def survey(media_paths: list[Path], runner=subprocess.run) -> Counter[str]:
    return _survey(media_paths, runner)[0]


async def _play_sample(path: Path, mpv_path: str, extra_args: list[str],
                       play_seconds: int) -> dict:
    """Lit `path` pendant `play_seconds` et relève les compteurs mpv ; dur délai max."""
    with tempfile.TemporaryDirectory(prefix="rc-doctor-") as tmp:
        sock = Path(tmp) / "mpv.sock"
        proc = await asyncio.create_subprocess_exec(
            mpv_path, "--no-terminal", "--idle=no", "--hwdec=auto-safe", "--fs", "--keep-open=yes",
            "--volume=0", f"--input-ipc-server={sock}", *extra_args, str(path),
        )
        client = MpvClient(sock)
        try:
            async def run() -> dict:
                await client.connect(retries=100)
                await asyncio.sleep(play_seconds)
                out = {}
                for prop in ("hwdec-current", "frame-drop-count", "decoder-frame-drop-count"):
                    out[prop] = await client.command("get_property", prop)
                return out
            return await asyncio.wait_for(run(), play_seconds + 15)
        finally:
            with contextlib.suppress(Exception):
                await asyncio.wait_for(client.command("quit"), 1)
            await client.close()
            if proc.returncode is None:
                proc.terminate()
                try:
                    await asyncio.wait_for(proc.wait(), 3)
                except asyncio.TimeoutError:
                    proc.kill()
                    await proc.wait()


def _collect_paths(config: Config) -> tuple[list[Path], list[str]]:
    notes: list[str] = []
    try:
        volumes = list_volumes(config.scan_roots, dev_dir_volumes=config.dev_dir_volumes)
    except (OSError, subprocess.SubprocessError, ValueError) as exc:
        return [], [f"Impossible de lister les disques : {exc}"]
    paths: list[Path] = []
    for vol in volumes:
        try:
            paths.extend(p for p in build_index(vol.mount).values()
                         if p.suffix.lower() in VIDEO_EXT)
        except VolumeUnreadable as exc:
            notes.append(f"Disque illisible : {exc}")
    if not volumes:
        notes.append("Aucun disque monté dans les dossiers analysés.")
    return paths, notes


def run_doctor(config: Config, samples: int = 3, play_seconds: int = 20, *,
               runner=subprocess.run, which=shutil.which, play=_play_sample,
               media_paths: list[Path] | None = None) -> str:
    lines = ["Diagnostic du décodage vidéo", ""]

    # 1. profils matériels
    hw: set[str] | None = None
    try:
        proc = runner(["vainfo"], capture_output=True, text=True, timeout=30)
        found = parse_vainfo((proc.stdout or "") + "\n" + (proc.stderr or ""))
        if found:
            hw = found
            lines.append("Profils décodables par le matériel : " + ", ".join(sorted(hw)))
        else:
            lines.append("vainfo ne signale aucun profil de décodage matériel exploitable.")
    except (OSError, subprocess.SubprocessError):
        lines.append("vainfo introuvable ou en échec.")

    # 2. collection
    notes: list[str] = []
    if media_paths is None:
        media_paths, notes = _collect_paths(config)
    lines += notes
    counts, examples, cut = _survey(media_paths, runner)
    lines += ["", f"Collection : {len(media_paths)} fichier(s) présent(s)."]
    sampled = sum(counts.values())
    if sampled < len(media_paths):
        lines.append(f"Échantillon sondé : {sampled} fichiers répartis sur toute la collection "
                     f"(limite de {MAX_SURVEY}, pour rester rapide).")
    if cut:
        lines.append(f"Enquête interrompue après {SURVEY_BUDGET // 60} min : résultats partiels.")
    for key, n in counts.most_common():
        label = "non sondable (ffprobe a échoué)" if key == UNREADABLE else key
        lines.append(f"  {label} : {n}")
    if hw is None:
        lines.append("Décodage matériel non vérifiable (vainfo indisponible).")
    else:
        bad = sum(n for k, n in counts.items() if k in KNOWN and k not in hw)
        unk = sum(n for k, n in counts.items() if k != UNREADABLE and k not in KNOWN)
        if bad:
            lines.append(f"{bad} fichier{'s' if bad > 1 else ''} sans décodage matériel possible "
                         "(décodage logiciel, lecture potentiellement saccadée).")
        else:
            lines.append("Aucun fichier sondé sans décodage matériel possible.")
        if unk:
            lines.append(f"{unk} fichier{'s' if unk > 1 else ''} non vérifié{'s' if unk > 1 else ''} "
                         "(codec ou profil que vainfo ne permet pas d'évaluer).")

    # 3. lecture d'échantillons
    lines.append("")
    mpv = which(config.mpv_path)
    if mpv is None:
        lines.append("mpv introuvable : test de lecture ignoré")
    else:
        lines.append("Attention : le test de lecture ouvre mpv en plein écran sur la télé.")
        picks = [(k, examples[k]) for k, _ in counts.most_common() if k != UNREADABLE][:samples]
        if not picks:
            lines.append("Aucun fichier lisible à tester.")
        for key, path in picks:
            lines.append(f"Lecture de {play_seconds} s ({key}) : {path.name}")
            try:
                res = asyncio.run(play(path, mpv, config.mpv_extra_args, play_seconds))
            except Exception as exc:
                lines.append(f"  échec de la lecture : {exc or type(exc).__name__}")
                continue
            hwdec = res.get("hwdec-current")
            lines.append(f"  décodage : {hwdec if hwdec and hwdec != 'no' else 'logiciel'}"
                         f" ; images perdues : {res.get('frame-drop-count')}"
                         f" ; perdues au décodage : {res.get('decoder-frame-drop-count')}")
    return "\n".join(lines)
