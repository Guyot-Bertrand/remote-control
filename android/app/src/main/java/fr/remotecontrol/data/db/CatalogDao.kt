package fr.remotecontrol.data.db

import androidx.room.Dao
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// Progression propre à l'œuvre (pas aux épisodes) : ratio si entamée et non vue, et drapeau « vu ».
private const val WORK_PROGRESS = "FROM progress p WHERE p.targetKind = 'work' AND p.targetId = work.id"
private const val SUMMARY = "work.id, work.type, work.titleFr, work.year, work.poster, work.playable, work.episodic, " +
    "work.directors, " +
    "(SELECT MIN(p.position / p.duration, 1.0) $WORK_PROGRESS " +
    "AND p.watched = 0 AND p.position > 0 AND p.duration > 0) AS progress, " +
    "COALESCE((SELECT p.watched $WORK_PROGRESS), 0) AS watched, " +
    // Œuvre non lisible mais numérisée : libellé du disque débranché qui porte un de ses fichiers ('' si inconnu).
    "CASE WHEN work.playable = 1 THEN NULL ELSE (SELECT COALESCE(v.label, '') FROM media_file m " +
    "LEFT JOIN volume v ON m.volumeUuid = v.uuid WHERE m.present = 0 AND (m.workId = work.id OR m.episodeId IN " +
    "(SELECT e.id FROM episode e JOIN season s ON s.id = e.seasonId WHERE s.workId = work.id)) " +
    "ORDER BY v.label IS NULL, v.label LIMIT 1) END AS absentVolume"

@Dao
interface CatalogDao {
    @Query("SELECT $SUMMARY FROM work WHERE type = :type AND (:playableOnly = 0 OR playable = 1) ORDER BY sortKey, id")
    fun worksByType(type: String, playableOnly: Boolean): Flow<List<WorkSummary>>

    @Query("SELECT $SUMMARY FROM work WHERE (:playableOnly = 0 OR playable = 1) ORDER BY sortKey, id")
    fun allWorks(playableOnly: Boolean): Flow<List<WorkSummary>>

    @Query("SELECT workId, rank FROM work_collection WHERE collectionId = :collectionId AND rank IS NOT NULL")
    fun collectionRanks(collectionId: Long): Flow<List<WorkRank>>

    @Query(
        "SELECT $SUMMARY FROM work JOIN work_collection wc ON wc.workId = work.id " +
            "WHERE wc.collectionId = :collectionId AND (:playableOnly = 0 OR work.playable = 1) " +
            "ORDER BY wc.rank IS NULL, CAST(wc.rank AS INTEGER), work.sortKey",
    )
    fun worksInCollection(collectionId: Long, playableOnly: Boolean): Flow<List<WorkSummary>>

    @Query("SELECT $SUMMARY FROM work WHERE (:playableOnly = 0 OR playable = 1) ORDER BY id DESC LIMIT :limit")
    fun recentWorks(playableOnly: Boolean, limit: Int): Flow<List<WorkSummary>>

    @Query(
        "SELECT c.id AS id, c.name AS name, COUNT(w.id) AS count, " +
            "COALESCE(SUM(w.playable), 0) AS playableCount FROM collection c " +
            "JOIN work_collection wc ON wc.collectionId = c.id " +
            "JOIN work w ON w.id = wc.workId " +
            "GROUP BY c.id HAVING (CASE WHEN :playableOnly THEN SUM(w.playable) ELSE COUNT(w.id) END) > 0 " +
            "ORDER BY c.name",
    )
    fun collections(playableOnly: Boolean): Flow<List<CollectionSummary>>

    @Query(
        "SELECT p.targetKind AS targetKind, p.targetId AS targetId, w.id AS workId, s.id AS seasonId, " +
            "w.titleFr AS titleFr, w.poster AS poster, s.number AS seasonNumber, e.number AS episodeNumber, " +
            "p.position AS position, p.duration AS duration, p.updatedAt AS updatedAt " +
            "FROM progress p " +
            "LEFT JOIN episode e ON p.targetKind = 'episode' AND e.id = p.targetId " +
            "LEFT JOIN season s ON s.id = e.seasonId " +
            "JOIN work w ON w.id = CASE WHEN p.targetKind = 'work' THEN p.targetId ELSE s.workId END " +
            "WHERE p.watched = 0 AND p.position > 0 " +
            "AND (p.targetKind = 'work' OR (p.targetKind = 'episode' AND e.id IS NOT NULL AND s.id IS NOT NULL)) " +
            "ORDER BY p.updatedAt DESC LIMIT :limit",
    )
    fun resumeRows(limit: Int): Flow<List<ResumeRow>>

    @Query(
        "SELECT $SUMMARY FROM work JOIN work_fts ON work_fts.rowid = work.id " +
            "WHERE work_fts MATCH :match AND (:playableOnly = 0 OR work.playable = 1) " +
            "ORDER BY work.sortKey, work.id LIMIT :limit",
    )
    fun search(match: String, playableOnly: Boolean, limit: Int = 200): Flow<List<WorkSummary>>

    /** Comme [search], avec les titres original et alternatif et le casting (sections de la recherche). */
    @Query(
        "SELECT $SUMMARY, work.titleVo, work.titleAlt, work.`cast` FROM work JOIN work_fts ON work_fts.rowid = work.id " +
            "WHERE work_fts MATCH :match AND (:playableOnly = 0 OR work.playable = 1) " +
            "ORDER BY work.sortKey, work.id LIMIT :limit",
    )
    fun searchHits(match: String, playableOnly: Boolean, limit: Int = 200): Flow<List<SearchHit>>

    @Query("SELECT * FROM work WHERE id = :id")
    fun work(id: Long): Flow<WorkEntity?>

    @Query(
        "SELECT c.id, c.name, wc.rank FROM collection c JOIN work_collection wc ON wc.collectionId = c.id " +
            "WHERE wc.workId = :workId ORDER BY c.name",
    )
    fun collectionsOfWork(workId: Long): Flow<List<WorkCollectionRow>>

    @Query("SELECT * FROM season WHERE workId = :workId ORDER BY number")
    fun seasonsOfWork(workId: Long): Flow<List<SeasonEntity>>

    @Query("SELECT * FROM season WHERE id = :id")
    fun season(id: Long): Flow<SeasonEntity?>

    @Query(
        "SELECT m.id, m.workId, m.episodeId, m.quality, m.size, m.present, v.label AS volumeLabel " +
            "FROM media_file m LEFT JOIN volume v ON m.volumeUuid = v.uuid WHERE m.workId = :workId ORDER BY m.id",
    )
    fun mediaFilesOfWork(workId: Long): Flow<List<MediaFileRow>>

    @Query(
        "SELECT m.id, m.workId, m.episodeId, m.quality, m.size, m.present, v.label AS volumeLabel " +
            "FROM media_file m LEFT JOIN volume v ON m.volumeUuid = v.uuid WHERE m.episodeId = :episodeId ORDER BY m.id",
    )
    fun mediaFilesOfEpisode(episodeId: Long): Flow<List<MediaFileRow>>

    @Query(
        "SELECT e.id AS id, e.number AS number, e.title AS title, p.position AS position, p.duration AS duration, " +
            "p.watched AS watched, p.updatedAt AS updatedAt, " +
            "EXISTS(SELECT 1 FROM media_file m WHERE m.episodeId = e.id AND m.present = 1) AS playable " +
            "FROM episode e LEFT JOIN progress p ON p.targetKind = 'episode' AND p.targetId = e.id " +
            "WHERE e.seasonId = :seasonId ORDER BY e.number",
    )
    fun episodesOfSeason(seasonId: Long): Flow<List<EpisodeRow>>

    /** Qualités des fichiers des épisodes d'une saison (pastilles de l'en-tête). */
    @Query("SELECT DISTINCT m.quality FROM media_file m JOIN episode e ON m.episodeId = e.id WHERE e.seasonId = :seasonId")
    fun seasonQualities(seasonId: Long): Flow<List<String>>

    @Query(
        "SELECT e.number AS number, e.title AS title, s.number AS seasonNumber, w.titleFr AS workTitle, " +
            "w.poster AS poster, w.year AS year FROM episode e JOIN season s ON s.id = e.seasonId " +
            "JOIN work w ON w.id = s.workId WHERE e.id = :id",
    )
    fun episodeHeader(id: Long): Flow<EpisodeHeader?>

    @Query("SELECT * FROM progress WHERE targetKind = :kind AND targetId = :id")
    fun progress(kind: String, id: Long): Flow<ProgressEntity?>

    @Query(
        "SELECT w.poster AS poster, w.year AS year, " +
            "(SELECT m.quality FROM media_file m WHERE m.id = :mediaFileId) AS quality FROM work w WHERE w.id = :workId",
    )
    fun playingInfo(workId: Long, mediaFileId: Long): Flow<PlayingInfo?>

    @Query("SELECT COUNT(*) FROM work")
    suspend fun workCount(): Int

    @Query("SELECT COUNT(*) FROM work")
    fun observeWorkCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM work WHERE playable = 1")
    suspend fun playableCount(): Int

    @Query("SELECT EXISTS(SELECT 1 FROM work WHERE id = :id)")
    suspend fun hasWork(id: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM season WHERE id = :id)")
    suspend fun hasSeason(id: Long): Boolean
}
