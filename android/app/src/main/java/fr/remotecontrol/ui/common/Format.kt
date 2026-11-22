package fr.remotecontrol.ui.common

import java.util.Locale

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
