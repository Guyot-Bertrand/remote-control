"""Types communs du lecteur (Player/PlayerState arrivent avec la Task 8)."""

# Sélection de sous-titres, trois formes :
#   {"kind": "none"}
#   {"kind": "embedded", "index": n}      (n : 1-based, = sid mpv)
#   {"kind": "external", "path": "<chemin>"}
SubSel = dict
