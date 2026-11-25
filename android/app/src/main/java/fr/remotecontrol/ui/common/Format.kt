package fr.remotecontrol.ui.common

import fr.remotecontrol.data.fold
import java.util.Locale
import kotlin.math.roundToInt

val WORK_TYPES = listOf("film", "serie", "doc", "theatre", "tele", "spectacle")

fun formatDuration(minutes: Int): String =
    if (minutes < 60) "$minutes min" else "${minutes / 60} h ${"%02d".format(Locale.ROOT, minutes % 60)}"

fun formatClock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
}

fun formatHhMm(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    return "%02d:%02d".format(Locale.ROOT, total / 3600, (total % 3600) / 60)
}

/** Position de reprise, comme les maquettes : « 1 h 12 » à partir d'une heure, sinon en minutes (ou secondes). */
fun formatResumePosition(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).toLong()
    return when {
        total >= 3600 -> formatDuration((total / 60).toInt())
        total >= 60 -> "${total / 60} min"
        else -> "$total s"
    }
}

/** Minutes restantes, arrondies (0 si la position dépasse la durée). */
fun remainingMinutes(position: Double, duration: Double): Int =
    ((duration - position).coerceAtLeast(0.0) / 60.0).roundToInt()

/** Entier groupé par milliers à la française, avec une espace fine insécable : « 1 912 ». */
fun formatCount(n: Int): String {
    val digits = kotlin.math.abs(n.toLong()).toString()
    val grouped = digits.reversed().chunked(3).joinToString(" ").reversed()
    return if (n < 0) "-$grouped" else grouped
}

/** Lettre d'index d'un titre (sans accents), pour le défilement rapide ; « # » s'il commence par un chiffre. */
fun indexLetter(title: String): String {
    val first = fold(title).firstOrNull { it.isLetterOrDigit() } ?: return "#"
    return if (first.isLetter()) first.uppercase() else "#"
}

/** Nom français d'une langue (« fr » → « Français ») ; toute autre langue : son code en majuscules. */
fun languageName(code: String): String = when (code) {
    "fr" -> "Français"
    "en" -> "Anglais"
    "de" -> "Allemand"
    "es" -> "Espagnol"
    "it" -> "Italien"
    "ja" -> "Japonais"
    "nl" -> "Néerlandais"
    "pt" -> "Portugais"
    "ru" -> "Russe"
    else -> code.uppercase(Locale.ROOT)
}

/** Pastilles de langues : audio puis sous-titres (« ST français ») ; vide si les deux sont inconnus. */
fun languagePills(audio: List<String>?, subtitles: List<String>?): List<String> =
    audio.orEmpty().map(::languageName) +
        subtitles.orEmpty().map { "ST ${languageName(it).lowercase(Locale.ROOT)}" }

/** « 1920 × 1080 » (espaces fines insécables), ou null si une dimension est inconnue ou nulle. */
fun resolutionLabel(width: Int?, height: Int?): String? =
    if (width == null || height == null || width <= 0 || height <= 0) null else "$width × $height"

/** « 41 min · reste 18 min », « 1 h 15 », « 41 min » ou « reste 18 min » ; null si rien n'est connu. */
fun episodeDurationLine(durationMin: Int?, remainingMin: Int?): String? =
    listOfNotNull(
        durationMin?.let(::formatDuration),
        remainingMin?.let { Texts.remaining(it) },
    ).takeIf { it.isNotEmpty() }?.joinToString(" · ")
