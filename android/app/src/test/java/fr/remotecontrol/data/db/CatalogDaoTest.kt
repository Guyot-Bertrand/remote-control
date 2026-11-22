package fr.remotecontrol.data.db

import fr.remotecontrol.data.buildFtsQuery
import fr.remotecontrol.data.fold
import fr.remotecontrol.data.ftsText
import fr.remotecontrol.data.toEntity
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.seed
import fr.remotecontrol.testing.testDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CatalogDaoTest {
    private lateinit var db: AppDatabase
    private lateinit var dao: CatalogDao
    private val sync: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_full.json"))
    private val playableIds = setOf(1L, 3L, 5L)

    @Before fun setUp() = runTest {
        db = testDb()
        dao = db.catalogDao()
        db.seed(
            works = sync.works.map { it.toEntity().copy(playable = it.id in playableIds) },
            fts = sync.works.map { WorkFtsEntity(it.id, ftsText(it)) },
            files = sync.mediaFiles.map {
                MediaFileEntity(it.id, it.workId, it.episodeId, it.quality, it.size, it.relpath, it.volumeUuid, it.present)
            },
            seasons = sync.seasons.map { SeasonEntity(it.id, it.workId, it.number, it.label) },
            episodes = sync.episodes.map { EpisodeEntity(it.id, it.seasonId, it.number, it.title) },
            volumes = sync.volumes.map { VolumeEntity(it.uuid, it.label, it.present) },
            collections = sync.collections.map { CollectionEntity(it.id, it.name, it.description, it.link) },
            workCollections = sync.workCollections.map { WorkCollectionEntity(it.workId, it.collectionId, it.rank) },
            progress = sync.progress.map {
                ProgressEntity(it.targetKind, it.targetId, it.mediaFileId, it.position, it.duration, it.watched, it.updatedAt)
            },
        )
    }

    @After fun tearDown() = db.close()

    private suspend fun ids(q: String) = dao.search(buildFtsQuery(q)!!, false).first().map { it.id }

    private suspend fun addWork(id: Long, title: String, type: String = "film") {
        val w = sync.works[0].copy(id = id, type = type, titleFr = title, titleVo = null, titleAlt = null, directors = emptyList(), cast = emptyList())
        db.seed(works = listOf(w.toEntity()), fts = listOf(WorkFtsEntity(id, ftsText(w))))
    }

    @Test fun search_accent_insensitive() = runTest {
        assertEquals(listOf(2L), ids("etranger"))
        assertEquals(listOf(3L, 4L), ids("prénom"))
        assertEquals(listOf(3L), ids("patelliere"))
        assertEquals(listOf(1L), ids("noee abi"))
        addWork(6, "Agatha Christie : Dix Petits Nègres")
        assertEquals(listOf(6L), ids("negres"))
    }

    @Test fun search_special_chars_never_throw() = runTest {
        val expected = mapOf(
            "\"" to null, "*" to null, "-" to null, "OR" to "or*", "a\"b" to "a* b*",
            "((" to null, "NEAR/3" to "near* 3*", "" to null,
        )
        expected.forEach { (input, query) ->
            assertEquals(query, buildFtsQuery(input))
            query?.let { q -> dao.search(q, false).first() }
        }
    }

    @Test fun fts_reupsert_replaces_text() = runTest {
        db.syncDao().upsertFts(listOf(WorkFtsEntity(1, "zorglub")))
        db.syncDao().upsertFts(listOf(WorkFtsEntity(1, "bidule")))
        assertEquals(listOf(1L), ids("bidule"))
        assertEquals(emptyList<Long>(), ids("zorglub"))
    }

    @Test fun fts_delete() = runTest {
        db.syncDao().deleteFts(listOf(1L, 2L))
        assertEquals(emptyList<Long>(), ids("etranger"))
        assertEquals(listOf(3L), ids("patelliere"))
    }

    @Test fun search_typographic_punctuation() = runTest {
        addWork(6, "L\u2019\u00c9tranger")
        addWork(7, "\u00ab Jules\u2014et\u2013Jim \u00bb")
        assertEquals(listOf(2L, 6L), ids("etranger"))
        assertEquals(listOf(2L, 6L), ids("l etr"))
        assertEquals(listOf(7L), ids("jim"))
        assertEquals(listOf(7L), ids("jules et"))
    }

    @Test fun playable_filter() = runTest {
        assertEquals(listOf(1L, 3L), dao.worksByType("film", true).first().map { it.id })
        assertEquals(listOf(2L, 1L, 3L), dao.worksByType("film", false).first().map { it.id })
    }

    @Test fun sort_ignores_accents() = runTest {
        addWork(6, "Eva", "livre")
        addWork(7, "Élise", "livre")
        assertEquals(listOf(7L, 6L), dao.worksByType("livre", false).first().map { it.id })
        assertEquals("elise", fold("Élise"))
    }

    @Test fun recent_by_id_desc() = runTest {
        assertEquals(listOf(5L, 4L), dao.recentWorks(false, 2).first().map { it.id })
    }

    @Test fun resume_rows_work_and_episode() = runTest {
        val rows = dao.resumeRows(10).first()
        assertEquals(listOf("episode", "work"), rows.map { it.targetKind })
        val ep = rows[0]
        assertEquals(1, ep.seasonNumber)
        assertEquals(2, ep.episodeNumber)
        assertEquals(5L, ep.workId)
        assertEquals(1L, ep.seasonId)
        assertEquals(1L, rows[1].workId)
    }

    @Test fun resume_rows_skip_orphan_progress() = runTest {
        db.seed(works = emptyList(), progress = listOf(ProgressEntity("work", 999, null, 10.0, 100.0, false, 1796000000.0)))
        db.seed(works = emptyList(), progress = listOf(ProgressEntity("episode", 999, null, 10.0, 100.0, false, 1796000001.0)))
        assertEquals(listOf(2L, 1L), dao.resumeRows(10).first().map { it.targetId })
    }

    @Test fun observe_work_count() = runTest {
        assertEquals(5, dao.observeWorkCount().first())
    }

    @Test fun collections_hide_empty_under_filter() = runTest {
        assertEquals(listOf(1L), dao.collections(true).first().map { it.id })
        assertEquals(2, dao.collections(false).first().size)
    }

    @Test fun media_files_with_volume_label() = runTest {
        val row = dao.mediaFilesOfWork(4).first().single()
        assertEquals("Théâtre", row.volumeLabel)
        assertEquals(false, row.present)
    }
}
