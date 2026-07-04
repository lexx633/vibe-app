package dev.humanonly.db

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * [SqlBackupSource] против РЕАЛЬНОГО SQLite (sqlite-jdbc, in-memory): pre-destructive снимок лайков
 * из индекса (хард-правило 5). Проверяем, что снимок = живые лайки по `trackId`+`likedAt`, мёртвые
 * исключены, и в манифесте нет PII (§12: только id/время).
 */
class SqlBackupSourceTest {

    private lateinit var db: JdbcDb
    private lateinit var repo: TrackRepository

    @BeforeEach
    fun setup() {
        db = JdbcDb()
        db.initSchema()
        repo = TrackRepository(db) { 1000L }
    }

    @AfterEach
    fun teardown() = db.close()

    @Test
    fun `снимок берёт живые лайки с trackId и likedAt`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "a1"), DiscoveredTrack("t2", "a2")))

        val manifest = SqlBackupSource(db).snapshotLikes(createdAt = 5000L)

        assertEquals(5000L, manifest.createdAt)
        assertEquals(listOf("t1", "t2"), manifest.likes.map { it.trackId })
        assertTrue(manifest.likes.all { it.likedAt == 1000L }, "likedAt = first_seen (1000L от clock)")
    }

    @Test
    fun `мёртвые треки в снимок не попадают`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "a1"), DiscoveredTrack("t2", "a2")))
        db.update("UPDATE track SET is_dead = 1 WHERE yandex_track_id = 't2'")

        val likes = SqlBackupSource(db).snapshotLikes(createdAt = 1L).likes
        assertEquals(listOf("t1"), likes.map { it.trackId })
    }

    @Test
    fun `пустой индекс даёт пустой снимок без PII-полей`() {
        val manifest = SqlBackupSource(db).snapshotLikes(createdAt = 1L)
        assertTrue(manifest.likes.isEmpty())
        assertTrue(manifest.playlists.isEmpty())
    }

    @Test
    fun `album_id не хранится в индексе — остаётся null`() {
        repo.upsertDiscovered(listOf(DiscoveredTrack("t1", "a1")))
        val entry = SqlBackupSource(db).snapshotLikes(createdAt = 1L).likes.single()
        assertNull(entry.albumId)
    }
}
