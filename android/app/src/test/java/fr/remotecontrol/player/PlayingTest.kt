package fr.remotecontrol.player

import fr.remotecontrol.discovery.ServerAddress
import fr.remotecontrol.discovery.ServerState
import fr.remotecontrol.net.CurrentDto
import fr.remotecontrol.net.PlayerSnapshotDto
import fr.remotecontrol.net.PlayerStateDto
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayingTest {
    private val film = CurrentDto("work", 7, mediaFileId = 70, workId = 7, title = "Film")
    private val episode = CurrentDto("episode", 12, mediaFileId = 120, workId = 3, seasonId = 30, title = "S01E02")
    private fun snap(c: CurrentDto?, running: Boolean = true, ended: Boolean = false, paused: Boolean = false) =
        PlayerSnapshotDto(PlayerStateDto(running = running, ended = ended, paused = paused), c)

    @Test fun live_only_while_running_and_not_ended() {
        assertEquals(film, snap(film).live())
        assertEquals(film, snap(film, paused = true).live())
        assertNull(snap(film, running = false).live())
        assertNull(snap(film, ended = true).live())
        assertNull(snap(null).live())
        assertNull((null as PlayerSnapshotDto?).live())
    }

    @Test fun work_sheet_matches_film_and_series_of_episode() {
        assertTrue(film.playsWork(7))
        assertFalse(film.playsWork(8))
        assertTrue(episode.playsWork(3))
    }

    @Test fun season_and_episode_match_only_their_target() {
        assertTrue(episode.playsSeason(30))
        assertFalse(episode.playsSeason(31))
        assertFalse(film.playsSeason(30))
        assertTrue(episode.playsEpisode(12))
        assertFalse(episode.playsEpisode(13))
        // Un film d'identifiant 12 n'est pas l'épisode 12.
        assertFalse(CurrentDto("work", 12, 1, 12).playsEpisode(12))
    }

    @Test fun button_choice_open_remote_when_playing_here() {
        assertEquals(SheetAction.OpenRemote, sheetAction(playingHere = true, playerAvailable = true))
        assertEquals(SheetAction.OpenRemote, sheetAction(playingHere = true, playerAvailable = false))
        assertEquals(SheetAction.Play, sheetAction(playingHere = false, playerAvailable = true))
        assertEquals(SheetAction.PlayerMissing, sheetAction(playingHere = false, playerAvailable = false))
    }

    @Test fun live_snapshot_unknown_while_not_connected() = runBlocking {
        val connected = ServerState.Connected(ServerAddress("10.0.0.1"), manual = false)
        val s = snap(film)
        assertEquals(s, liveWhileConnected(flowOf(s), flowOf(connected)).first())
        assertNull(liveWhileConnected(flowOf(s), flowOf(ServerState.NotFound)).first())
        assertNull(liveWhileConnected(flowOf(s), flowOf(ServerState.Searching)).first())
    }

    @Test fun player_missing_only_when_connected() {
        val connected = ServerState.Connected(ServerAddress("10.0.0.1"), manual = false)
        assertTrue(playerMissing(connected, playerAvailable = false))
        assertFalse(playerMissing(connected, playerAvailable = true))
        assertFalse(playerMissing(ServerState.NotFound, playerAvailable = false))
        assertFalse(playerMissing(ServerState.Searching, playerAvailable = false))
    }
}
