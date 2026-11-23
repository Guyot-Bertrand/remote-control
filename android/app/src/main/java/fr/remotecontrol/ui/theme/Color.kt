package fr.remotecontrol.ui.theme

import androidx.compose.ui.graphics.Color

/** Tokens de couleur des maquettes (--rc-*) : noir chaud, ivoire, laiton. */
object RcColors {
    val bg = Color(0xFF110F0D)
    val surface = Color(0xFF1B1815)
    val surface2 = Color(0xFF25211C)
    val surface3 = Color(0xFF302A23)
    val line = Color(0xFF3D362D)
    val text = Color(0xFFF3ECDF)
    val text2 = Color(0xFFBDB19C)
    val text3 = Color(0xFF968B79)
    val accent = Color(0xFFD8A24A)
    val accentStrong = Color(0xFFE8B65E)
    val onAccent = Color(0xFF1A1206)
    val accentSoft = Color(0xFF3A2E1A)
    val seen = Color(0xFF8FB08A)
    val warn = Color(0xFFE39B7B)
    val warnSoft = Color(0xFF3A231B)

    /** Voile sombre des vignettes (rgba(17,15,13,…)). */
    fun scrim(alpha: Float) = bg.copy(alpha = alpha)
}
