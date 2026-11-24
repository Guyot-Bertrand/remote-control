"""Messages d'erreur en français, prêts à afficher (appli Android, télé).

Seul endroit où un code d'erreur de l'API devient une phrase."""

from __future__ import annotations

MESSAGES = {
    "not_found": "Fichier vidéo introuvable sur le disque.",
    "not_playable": "Aucune version lisible de ce titre.",
    "idle": "Aucune lecture en cours.",
    "player": "Le lecteur vidéo ne répond pas.",
    "player_missing": "Le lecteur vidéo (mpv) n'est pas installé sur le PC.",
    "invalid": "Requête invalide.",
    "forbidden": "Accès refusé.",
    "internal": "Erreur interne du serveur.",
}
UNKNOWN = "Erreur inattendue."
RESOURCE_NOT_FOUND = "Élément introuvable."  # 404 : œuvre, saison, média inexistants


def message(code: str, volume_label: str | None = None) -> str:
    if code == "volume_absent":
        return (f"Disque « {volume_label} » non branché." if volume_label
                else "Disque non branché.")
    return MESSAGES.get(code, UNKNOWN)


def error_body(code: str, volume_label: str | None = None) -> dict:
    """Corps des erreurs 409/422/403/500 : {"error", "volume_label", "message"}."""
    return {"error": code, "volume_label": volume_label,
            "message": message(code, volume_label)}
