import pytest

from rc.messages import error_body, message


@pytest.mark.parametrize("code, label, text", [
    ("volume_absent", "DISK1", "Disque « DISK1 » non branché."),
    ("volume_absent", None, "Disque non branché."),
    ("not_found", None, "Fichier vidéo introuvable sur le disque."),
    ("not_playable", None, "Aucune version lisible de ce titre."),
    ("idle", None, "Aucune lecture en cours."),
    ("player", None, "Le lecteur vidéo ne répond pas."),
    ("player_missing", None, "Le lecteur vidéo (mpv) n'est pas installé sur le PC."),
    ("invalid", None, "Requête invalide."),
    ("forbidden", None, "Accès refusé."),
    ("internal", None, "Erreur interne du serveur."),
    ("inconnu", None, "Erreur inattendue."),
])
def test_message(code, label, text):
    assert message(code, label) == text


def test_error_body_keeps_keys_and_adds_message():
    assert error_body("volume_absent", "DISK1") == {
        "error": "volume_absent", "volume_label": "DISK1",
        "message": "Disque « DISK1 » non branché."}
