package fr.remotecontrol.qr

/** Fiche visée par un QR-code imprimé : `rc://w/<id>` (œuvre) ou `rc://s/<id>` (saison). */
sealed interface QrTarget {
    data class Work(val id: Long) : QrTarget
    data class Season(val id: Long) : QrTarget
}

private val QR_PATTERN = Regex("^rc://([ws])/([0-9]+)$", RegexOption.IGNORE_CASE)

/**
 * Lecture stricte : schéma `rc`, hôte `w` ou `s`, identifiant entier strictement positif, rien d'autre.
 * Les blancs autour et la casse du schéma et de l'hôte sont tolérés. Tout le reste → `null`.
 */
fun parseQr(text: String): QrTarget? {
    val m = QR_PATTERN.matchEntire(text.trim()) ?: return null
    val id = m.groupValues[2].toLongOrNull()?.takeIf { it > 0 } ?: return null
    return if (m.groupValues[1].equals("w", ignoreCase = true)) QrTarget.Work(id) else QrTarget.Season(id)
}
