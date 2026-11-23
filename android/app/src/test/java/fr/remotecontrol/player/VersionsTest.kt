package fr.remotecontrol.player

import fr.remotecontrol.data.db.EpisodeRow
import fr.remotecontrol.data.db.MediaFileRow
import fr.remotecontrol.net.PlayRequestDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VersionsTest {
    private fun file(id: Long, quality: String, present: Boolean, label: String? = "DISK1", size: String? = null) =
        MediaFileRow(id, 1, null, quality, size, present, label)

    private val ava = listOf(file(1, "FHD", true, size = "3.8G"), file(2, "HD", true, size = "1.6G"))

    private fun episode(id: Long, watched: Boolean? = null, updatedAt: Double? = null, position: Double? = null) =
        EpisodeRow(id, id.toInt(), null, position, null, watched, updatedAt, true)

    @Test fun availability_volume_absent_uses_label() {
        val theatre = listOf(MediaFileRow(4, 4, null, "SD", "700M", false, "Théâtre"))
        assertEquals(Availability.VolumeAbsent("Théâtre"), availabilityOf(theatre))
    }

    @Test fun availability_present_and_not_digitized() {
        assertEquals(Availability.Present, availabilityOf(ava))
        assertEquals(Availability.NotDigitized, availabilityOf(emptyList()))
    }

    @Test fun availability_volume_absent_first_non_null_label() {
        val files = listOf(file(1, "HD", false, label = null), file(2, "SD", false, label = "B"))
        assertEquals(Availability.VolumeAbsent("B"), availabilityOf(files))
    }

    @Test fun availability_labels() {
        assertNull(Availability.Present.label())
        assertEquals("Disque « Théâtre » non branché", Availability.VolumeAbsent("Théâtre").label())
        assertEquals("Non numérisé", Availability.NotDigitized.label())
    }

    @Test fun sort_versions_by_quality_unknown_last() {
        val files = listOf(file(1, "SD", true), file(2, "XYZ", true), file(3, "UHD", true), file(4, "HD", true))
        assertEquals(listOf(3L, 4L, 1L, 2L), sortVersions(files).map { it.id })
    }

    @Test fun preferred_version_start_is_best_present() {
        assertEquals(1L, preferredVersion(ava, progressMediaFileId = 2, start = StartMode.Start))
    }

    @Test fun preferred_version_resume_uses_progress_file() {
        assertEquals(2L, preferredVersion(ava, progressMediaFileId = 2, start = StartMode.Resume))
    }

    @Test fun preferred_version_resume_on_absent_file_falls_back_to_best_present() {
        val files = listOf(file(1, "FHD", true), file(2, "UHD", false))
        assertEquals(1L, preferredVersion(files, progressMediaFileId = 2, start = StartMode.Resume))
    }

    @Test fun preferred_version_none_present() {
        assertNull(preferredVersion(listOf(file(1, "FHD", false)), null, StartMode.Start))
    }

    @Test fun highlight_started_episode() {
        val rows = listOf(episode(1), episode(2, watched = false, updatedAt = 10.0, position = 300.0), episode(3))
        assertEquals(2L, highlightEpisode(rows))
    }

    @Test fun highlight_first_without_progress() {
        assertEquals(1L, highlightEpisode(listOf(episode(1), episode(2))))
    }

    @Test fun highlight_most_recent_even_if_watched() {
        val rows = listOf(episode(1, watched = true, updatedAt = 20.0), episode(2, watched = false, updatedAt = 10.0, position = 60.0))
        assertEquals(1L, highlightEpisode(rows))
    }

    @Test fun highlight_empty_is_null() {
        assertNull(highlightEpisode(emptyList()))
    }

    @Test fun play_request_without_choice() {
        assertEquals(
            PlayRequestDto("episode", 2, "resume"),
            playRequest(PlayTarget(TargetKind.Episode, 2), StartMode.Resume),
        )
    }
}
