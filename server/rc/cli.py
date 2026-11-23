"""Commande `rc` : serve, import, fake-tree, doctor."""

from __future__ import annotations

import argparse
import json
import logging
import subprocess
import sys
from pathlib import Path

from rc.config import load_config


def _serve(args) -> int:
    import uvicorn

    from rc.app import create_app

    config = load_config()
    uvicorn.run(create_app(config), host=config.bind_host, port=config.port)
    return 0


def _import(args) -> int:
    from rc.app import import_file
    from rc.catalog.importer import ImportRefused
    from rc.db import open_db
    from rc.volumes import find_catalog_source, list_volumes

    config = load_config()
    src = args.chemin
    if src is None:
        try:
            volumes = list_volumes(config.scan_roots, dev_dir_volumes=config.dev_dir_volumes)
        except (OSError, subprocess.CalledProcessError, json.JSONDecodeError) as exc:
            print(f"Impossible de lister les disques : {exc}", file=sys.stderr)
            return 1
        src = find_catalog_source(volumes, config.catalog_path)
    if src is None or not Path(src).is_file():
        print("Catalogue introuvable : aucun medias.txt sur les disques.", file=sys.stderr)
        return 1
    config.data_dir.mkdir(parents=True, exist_ok=True)
    conn = open_db(config.data_dir / "rc.db")
    try:
        report = import_file(conn, config, Path(src), force=args.force)
    except ImportRefused as exc:
        print(f"Import refusé : {exc}", file=sys.stderr)
        return 1
    except ValueError as exc:  # dont UnicodeDecodeError
        print(f"Catalogue illisible : {exc}", file=sys.stderr)
        return 1
    finally:
        conn.close()
    print(f"Import terminé ({src}) : {report.added} ajoutée(s), {report.updated} "
          f"modifiée(s), {report.deleted} supprimée(s) ; révision {report.revision}.")
    return 0


def _fake_tree(args) -> int:
    from rc.catalog.parser import parse_catalog
    from rc.fake_tree import make_fake_tree

    result = parse_catalog(Path(args.catalogue).read_text(encoding="utf-8"))
    make_fake_tree(result, Path(args.dossier))
    print(f"Arborescence factice créée dans {args.dossier} ({len(result.works)} œuvres).")
    return 0


def _doctor(args) -> int:
    from rc.doctor import run_doctor

    print(run_doctor(load_config(), samples=args.echantillons, play_seconds=args.secondes))
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="rc", description="Serveur Remote-Control")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("serve", help="lancer le serveur").set_defaults(func=_serve)
    p = sub.add_parser("import", help="importer le catalogue immédiatement")
    p.add_argument("chemin", nargs="?", type=Path, help="fichier medias.txt")
    p.add_argument("--force", action="store_true", help="accepter une suppression massive")
    p.set_defaults(func=_import)
    p = sub.add_parser("fake-tree", help="créer une arborescence factice")
    p.add_argument("catalogue", type=Path)
    p.add_argument("dossier", type=Path)
    p.set_defaults(func=_fake_tree)
    p = sub.add_parser("doctor", help="diagnostic du décodage vidéo")
    p.add_argument("--echantillons", type=int, default=3, help="fichiers lus (défaut 3)")
    p.add_argument("--secondes", type=int, default=20, help="durée de lecture (défaut 20)")
    p.set_defaults(func=_doctor)
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s : %(message)s")
    return args.func(args)


if __name__ == "__main__":
    sys.exit(main())
