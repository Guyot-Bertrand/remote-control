package fr.remotecontrol.net

import fr.remotecontrol.testing.Fixtures
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiDtosTest {
    @Test
    fun sync_full_decodes() {
        val sync = RcJson.decodeFromString<SyncDto>(Fixtures.text("sync_full.json"))
        assertEquals(5, sync.works.size)
        assertEquals("L'Étranger", sync.works[1].titleAlt)
        assertEquals(false, sync.works[1].episodic)
        assertEquals(true, sync.works[4].episodic)
        assertEquals(false, sync.mediaFiles[3].present)
        assertEquals(false, sync.progress[1].watched)
        assertEquals("Théâtre", sync.volumes[1].label)
        assertEquals(DeletedDto(), sync.deleted)
    }

    @Test
    fun old_server_sync_decodes() {
        // JSON de l'itération 1 : aucun des nouveaux champs.
        val dto = RcJson.decodeFromString<SyncDto>(Fixtures.text("sync_full.json"))
        assertNull(dto.totalWorks)
        assertNull(dto.mediaFiles[0].audioLangs)
        assertNull(dto.mediaFiles[0].subtitleLangs)
        assertNull(dto.mediaFiles[0].width)
        assertNull(dto.mediaFiles[0].durationS)
        assertNull(dto.works[0].medium)
        assertNull(dto.episodes[0].durationMin)
    }

    @Test
    fun new_fields_decode() {
        val dto = RcJson.decodeFromString<SyncDto>(NEW_FIELDS_JSON)
        assertEquals(1200, dto.totalWorks)
        assertEquals("DVD", dto.works[0].medium)
        assertEquals(42, dto.episodes[0].durationMin)
        val f = dto.mediaFiles[0]
        assertEquals(listOf("en"), f.audioLangs)
        assertEquals(listOf("fr", "en"), f.subtitleLangs)
        assertEquals(624, f.width)
        assertEquals(352, f.height)
        assertEquals(2520.5, f.durationS!!, 0.0)
    }

    @Test
    fun sync_incremental_decodes_deleted() {
        val sync = RcJson.decodeFromString<SyncDto>(Fixtures.text("sync_incremental.json"))
        assertEquals(listOf(3L), sync.deleted.work)
        assertEquals(listOf(3L, 7L), sync.deleted.mediaFile)
        assertEquals(emptyList<Long>(), sync.deleted.collection)
        assertEquals(true, sync.mediaFiles[0].present)
    }

    @Test
    fun tracks_decode_defaults() {
        val t = RcJson.decodeFromString<MediaTracksDto>(Fixtures.text("tracks.json"))
        assertEquals("fr", t.audio[1].lang)
        assertEquals("fr", t.external[0].lang)
        assertEquals(2, t.default.audio)
        assertEquals(SubSel.None, t.default.subtitle)
    }

    @Test
    fun subsel_roundtrip() {
        assertEquals("""{"kind":"embedded","index":2}""", RcJson.encodeToString<SubSel>(SubSel.Embedded(2)))
        assertEquals("""{"kind":"none"}""", RcJson.encodeToString<SubSel>(SubSel.None))
        assertEquals(SubSel.Embedded(2), RcJson.decodeFromString<SubSel>("""{"kind":"embedded","index":2}"""))
        assertEquals(SubSel.None, RcJson.decodeFromString<SubSel>("""{"kind":"none"}"""))
        assertEquals(SubSel.External("/a.srt"), RcJson.decodeFromString<SubSel>("""{"kind":"external","path":"/a.srt"}"""))
    }

    @Test
    fun play_request_encodes_snake_case() {
        val json = RcJson.encodeToString(
            PlayRequestDto("work", 1, "resume", mediaFileId = 2, audio = 2, subtitle = SubSel.None),
        )
        assertTrue(json, json.contains(""""target_kind":"work""""))
        assertTrue(json, json.contains(""""target_id":1"""))
        assertTrue(json, json.contains(""""start":"resume""""))
        assertTrue(json, json.contains(""""media_file_id":2"""))
        assertTrue(json, json.contains(""""subtitle":{"kind":"none"}"""))
    }

    @Test
    fun play_request_with_external_subtitle_wire_shape() {
        val path = "/media/beber/DISK1/Films/A/Ava [2017] [1h45,1920x1038,16x9,FR,sEN].1.fr.srt"
        val json = RcJson.encodeToString(PlayRequestDto("work", 1, "start", 1, 1, SubSel.External(path)))
        assertEquals(
            """{"target_kind":"work","target_id":1,"start":"start","media_file_id":1,"audio":1,""" +
                """"subtitle":{"kind":"external","path":"$path"}}""",
            json,
        )
    }

    @Test
    fun events_parse() {
        val hello = parseServerEvent(Fixtures.text("ws_hello.json")) as ServerEvent.Hello
        assertEquals(5L, hello.revision)
        assertNull(hello.player.current)
        assertEquals("home", hello.tv.screen)

        val player = parseServerEvent(Fixtures.text("ws_player.json")) as ServerEvent.Player
        assertEquals(2L, player.snapshot.current!!.mediaFileId)
        assertEquals(1L, player.snapshot.current.workId)
        assertNull(player.snapshot.current.seasonId)
        assertTrue(player.snapshot.state.subtitle is SubSel.External)

        val tv = parseServerEvent(Fixtures.text("ws_tv.json")) as ServerEvent.Tv
        assertEquals(1L, tv.state.workId)

        assertEquals(ServerEvent.Catalog(6), parseServerEvent(Fixtures.text("ws_catalog.json")))
        assertNull(parseServerEvent("""{"type":"bogus"}"""))
        assertNull(parseServerEvent("pas du json"))
    }

    @Test
    fun small_payloads_decode() {
        assertEquals(InfoDto("Remote-Control", 1, 5), RcJson.decodeFromString<InfoDto>(Fixtures.text("info.json")))
        assertEquals(
            ErrorDto("volume_absent", "Théâtre"),
            RcJson.decodeFromString<ErrorDto>(Fixtures.text("error_volume_absent.json")),
        )
        assertEquals(ErrorDto("idle", null), RcJson.decodeFromString<ErrorDto>(Fixtures.text("error_idle.json")))
        val snap = RcJson.decodeFromString<PlayerSnapshotDto>(Fixtures.text("player_state.json"))
        assertEquals(612.5, snap.state.position, 0.0)
    }

    @Test fun section_kind_serialized() {
        assertTrue(RcJson.encodeToString(SectionDto("Reprendre", kind = "resume")).contains("\"kind\":\"resume\""))
    }
}

private const val NEW_FIELDS_JSON = """
{"revision": 9, "full": true, "total_works": 1200,
 "works": [{"id": 1, "type": "film", "key": "k", "title_fr": "A", "medium": "DVD"}],
 "seasons": [{"id": 1, "work_id": 2, "number": 1, "label": "Saison 1"}],
 "episodes": [{"id": 1, "season_id": 1, "number": 1, "title": "E", "duration_min": 42}],
 "media_files": [{"id": 1, "work_id": 1, "quality": "SD", "relpath": "a.avi", "present": true,
   "audio_langs": ["en"], "subtitle_langs": ["fr", "en"], "width": 624, "height": 352, "duration_s": 2520.5}]}
"""
