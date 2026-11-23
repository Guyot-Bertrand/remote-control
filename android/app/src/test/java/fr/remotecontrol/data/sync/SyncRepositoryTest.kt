package fr.remotecontrol.data.sync

import fr.remotecontrol.data.buildFtsQuery
import fr.remotecontrol.data.db.AppDatabase
import fr.remotecontrol.data.db.WorkEntity
import fr.remotecontrol.net.ApiError
import fr.remotecontrol.net.ApiResult
import fr.remotecontrol.net.DeletedDto
import fr.remotecontrol.net.RcJson
import fr.remotecontrol.net.SyncDto
import fr.remotecontrol.testing.FakeRcApi
import fr.remotecontrol.testing.Fixtures
import fr.remotecontrol.testing.seed
import fr.remotecontrol.testing.testDb
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SyncRepositoryTest {
    private lateinit var db: AppDatabase
    private val api = FakeRcApi()
    private lateinit var repo: SyncRepository
    private val full: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_full.json"))
    private val incr: SyncDto = RcJson.decodeFromString(Fixtures.text("sync_incremental.json"))

    @Before fun setUp() {
        db = testDb()
        repo = SyncRepository(db, api)
    }

    @After fun tearDown() = db.close()

    private val dao get() = db.catalogDao()
    private suspend fun ftsIds(q: String) = dao.search(buildFtsQuery(q)!!, false).first().map { it.id }

    private suspend fun fullThenIncremental(inc: SyncDto = incr) {
        api.syncResponses.addLast(ApiResult.Ok(full))
        repo.sync()
        api.syncResponses.addLast(ApiResult.Ok(inc))
        repo.sync()
    }

    @Test fun full_sync_populates_and_computes_playable() = runTest {
        api.syncResponses.addLast(ApiResult.Ok(full))
        assertEquals(SyncOutcome.Done(3, true), repo.sync())
        assertEquals(5, dao.workCount())
        val all = full.works.map { it.type }.toSet()
        val playable = all.flatMap { dao.worksByType(it, true).first() }.map { it.id }.toSet()
        assertEquals(setOf(1L, 3L, 5L), playable)
        assertEquals(3L, repo.localRevision())
        assertEquals("sync(null)", api.calls.first())
    }

    @Test fun incremental_applies_upserts_and_deletes() = runTest {
        fullThenIncremental()
        assertEquals(listOf("sync(null)", "sync(3)"), api.calls)
        assertFalse(dao.hasWork(3))
        assertTrue(dao.mediaFilesOfWork(3).first().isEmpty())
        assertTrue(dao.mediaFilesOfEpisode(3).first().isEmpty())
        assertEquals(0, db.query("SELECT COUNT(*) FROM episode WHERE id = 3", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals("Ava la sauvage", dao.work(1).first()!!.titleAlt)
        assertTrue(dao.work(4).first()!!.playable)
        assertEquals(5L, repo.localRevision())
        assertEquals(listOf(1L), ftsIds("sauvage"))
    }

    @Test fun work_collections_replaced_per_work() = runTest {
        fullThenIncremental()
        assertTrue(dao.collectionsOfWork(1).first().isEmpty())
        assertEquals(listOf("Alien [1979-]"), dao.collectionsOfWork(2).first().map { it.name })
    }

    @Test fun deleted_work_cascades() = runTest {
        fullThenIncremental(incr.copy(deleted = DeletedDto(work = listOf(5))))
        assertFalse(dao.hasWork(5))
        assertTrue(dao.seasonsOfWork(5).first().isEmpty())
        assertEquals(0, db.query("SELECT COUNT(*) FROM episode", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.query("SELECT COUNT(*) FROM season", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.query("SELECT COUNT(*) FROM media_file WHERE episodeId IS NOT NULL", null).use { it.moveToFirst(); it.getInt(0) })
        assertEquals(0, db.query("SELECT COUNT(*) FROM work_fts WHERE rowid = 5", null).use { it.moveToFirst(); it.getInt(0) })
    }

    @Test fun deleted_collection_removed() = runTest {
        fullThenIncremental(incr.copy(deleted = DeletedDto(collection = listOf(2))))
        assertFalse(dao.collections(false).first().any { it.name == "Alien [1979-]" })
        assertTrue(dao.collectionsOfWork(2).first().isEmpty())
    }

    @Test fun deletes_more_than_999_ids() = runTest {
        val tpl = full.works[0]
        val works = (1000L..2499L).map { tpl.copy(id = it) }
        db.seed(works = works.map { WorkEntity(it.id, it.type, it.titleFr, null, null, null, null, null, emptyList(), emptyList(), emptyList(), null, emptyList(), false, null, "x") })
        api.syncResponses.addLast(ApiResult.Ok(SyncDto(revision = 1, full = false, deleted = DeletedDto(work = (1000L..2199L).toList()))))
        repo.sync()
        assertEquals(300, dao.workCount())
    }

    @Test fun full_sync_removes_stale_rows() = runTest {
        api.syncResponses.addLast(ApiResult.Ok(full))
        repo.sync()
        db.seed(works = listOf(WorkEntity(99, "film", "Fantôme", null, null, null, null, null, emptyList(), emptyList(), emptyList(), null, emptyList(), false, null, "fantome")))
        api.syncResponses.addLast(ApiResult.Ok(full))
        repo.sync(forceFull = true)
        assertFalse(dao.hasWork(99))
        assertEquals(5, dao.workCount())
        assertEquals("sync(null)", api.calls.last())
    }

    @Test fun failed_write_leaves_cache_untouched() = runTest {
        api.syncResponses.addLast(ApiResult.Ok(full))
        repo.sync()
        val failing = SyncWriter(db, afterUpserts = { error("panne") })
        assertThrows(IllegalStateException::class.java) { kotlinx.coroutines.runBlocking { failing.apply(incr) } }
        assertEquals(5, dao.workCount())
        assertNull(dao.work(1).first()!!.titleAlt)
        assertEquals(3L, repo.localRevision())
    }

    @Test fun write_failure_reports_failed_and_resets_syncing() = runTest {
        val failing = SyncRepository(db, api, SyncWriter(db, afterUpserts = { error("panne") }))
        api.syncResponses.addLast(ApiResult.Ok(full))
        assertEquals(SyncOutcome.Failed(ApiError.BadResponse), failing.sync())
        assertFalse(failing.syncing.value)
        assertEquals(0, dao.workCount())
    }

    @Test fun network_failure_reports_failed() = runTest {
        api.syncResponses.addLast(ApiResult.Err(ApiError.Unreachable))
        assertEquals(SyncOutcome.Failed(ApiError.Unreachable), repo.sync())
        assertEquals(0, dao.workCount())
        assertNull(repo.localRevision())
    }

    @Test fun unreadable_local_revision_reports_failed() = runTest {
        db.openHelper.writableDatabase.execSQL("DROP TABLE meta")
        assertEquals(SyncOutcome.Failed(ApiError.BadResponse), repo.sync())
        assertFalse(repo.syncing.value)
        assertEquals(emptyList<String>(), api.calls)
    }
}
