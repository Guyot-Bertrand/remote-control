package fr.remotecontrol.ui.common

import fr.remotecontrol.data.db.PlayingInfo
import fr.remotecontrol.net.CurrentDto
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.PlayerStateDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MiniPlayerTest {
    private val current = CurrentDto("work", 1, 2, 1, null, "Ava")
    private val playing = PlayerSnapshotDto(PlayerStateDto(position = 4324.0, duration = 5940.0, running = true), current)

    @Test fun running_playback_shows_title_times_and_progress() {
        val ui = miniPlayerOf(playing)!!
        assertEquals("Ava", ui.title)
        assertEquals("Sur la télé · 1:12:04 / 1:39:00", ui.subtitle)
        assertEquals(4324f / 5940f, ui.progress, 0.0001f)
        assertEquals(false, ui.paused)
        assertEquals(1L to 2L, ui.workId to ui.mediaFileId)
    }

    @Test fun paused_and_unknown_duration() {
        val ui = miniPlayerOf(playing.copy(state = playing.state.copy(paused = true, duration = null)))!!
        assertEquals("Sur la télé · 1:12:04", ui.subtitle)
        assertEquals(0f, ui.progress)
        assertEquals(true, ui.paused)
    }

    @Test fun title_and_poster_from_local_catalogue() {
        val untitled = playing.copy(current = current.copy(title = null))
        val ui = miniPlayerOf(untitled, PlayingInfo(poster = "abc", year = 2017, quality = "FHD", title = "Ava"))!!
        assertEquals("Ava", ui.title)
        assertEquals("abc", ui.poster)
    }

    @Test fun nothing_when_idle_or_ended() {
        assertNull(miniPlayerOf(null))
        assertNull(miniPlayerOf(PlayerSnapshotDto()))
        assertNull(miniPlayerOf(playing.copy(state = playing.state.copy(ended = true))))
        assertNull(miniPlayerOf(playing.copy(current = null)))
    }
}
