package fr.remotecontrol.ui.common

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun formatDuration() {
        assertEquals("1 h 39", formatDuration(99))
        assertEquals("45 min", formatDuration(45))
        assertEquals("2 h 00", formatDuration(120))
    }

    @Test
    fun formatClock() {
        assertEquals("1:15", formatClock(75.0))
        assertEquals("1:02:05", formatClock(3725.0))
        assertEquals("0:00", formatClock(-3.0))
    }

    @Test
    fun formatHhMm() {
        assertEquals("01:12", formatHhMm(4320.0))
        assertEquals("00:10", formatHhMm(600.0))
    }

    @Test
    fun remaining_minutes() {
        assertEquals(27, remainingMinutes(5940.0 - 1620.0, 5940.0))
        assertEquals(19, remainingMinutes(300.0, 1440.0))
        assertEquals(1, remainingMinutes(1410.0, 1440.0)) // 30 s → arrondi à 1 min
        assertEquals(0, remainingMinutes(1500.0, 1440.0))
    }

    @Test
    fun remaining_label() {
        assertEquals("reste 27 min", Texts.remaining(27))
        assertEquals("reste 1 h 05", Texts.remaining(65))
    }

    @Test
    fun texts() {
        assertEquals("Reprendre à 01:12", Texts.resumeAt(4320.0))
        assertEquals("Disque « DISK1 » non branché", Texts.volumeAbsent("DISK1"))
        assertEquals("Disque non branché", Texts.volumeAbsent(null))
        assertEquals("Théâtre", Texts.typeLabel("theatre"))
        assertEquals("Langue inconnue", Texts.languageName(null))
        assertEquals("SV", Texts.languageName("sv"))
        assertEquals("Épisode 2", Texts.episodeCode(0, 2))
        assertEquals("S01E02", Texts.episodeCode(1, 2))
        assertEquals("Back to Life — S01E02", Texts.episodeTitle("Back to Life", 1, 2))
        assertEquals("HD", Texts.versionLabel("HD", null))
        assertEquals("FHD · 3.8G", Texts.versionLabel("FHD", "3.8G"))
        assertEquals("−60 s", Texts.skipLabel(-60))
        assertEquals("+10 s", Texts.skipLabel(10))
    }

    @Test
    fun digitsStayLatinOnAnyLocale() {
        val saved = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-EG"))
            assertEquals("1 h 02", formatDuration(62))
            assertEquals("1:02:05", formatClock(3725.0))
            assertEquals("01:12", formatHhMm(4320.0))
            assertEquals("S01E02", Texts.episodeCode(1, 2))
        } finally {
            Locale.setDefault(saved)
        }
    }
}
