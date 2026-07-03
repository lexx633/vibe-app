package dev.humanonly.db

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SchemaTest {

    @Test
    fun `exactly four indexes`() {
        assertEquals(4, Schema.INDEXES.size)
        val cols = setOf("yandex_track_id", "verdict", "is_dead", "last_scan")
        cols.forEach { c ->
            assertTrue(Schema.INDEXES.any { it.contains("($c)") }, "нет индекса по $c")
        }
        assertEquals(1, Schema.INDEXES.count { it.contains("UNIQUE") }, "unique только yandex_track_id")
    }

    @Test
    fun `pragma contains WAL`() {
        assertTrue(Schema.PRAGMA.any { it.contains("journal_mode=WAL") })
        assertTrue(Schema.PRAGMA.any { it.contains("synchronous=NORMAL") })
        assertTrue(Schema.PRAGMA.any { it.contains("temp_store=MEMORY") })
    }

    /** PII-правило §12: audit_log без метаданных title/artist/playlist_name. */
    @Test
    fun `audit_log has no PII columns`() {
        val ddl = Schema.CREATE_AUDIT_LOG.lowercase()
        listOf("title", "artist", "playlist_name").forEach { c ->
            assertFalse(Regex("\\b$c\\b").containsMatchIn(ddl), "audit_log не должен содержать колонку $c")
        }
        listOf("track_id", "action", "from_state", "to_state", "detector_version", "ts").forEach { c ->
            assertTrue(ddl.contains(c), "audit_log должен содержать $c")
        }
    }

    @Test
    fun `track has key columns`() {
        val ddl = Schema.CREATE_TRACK
        listOf("yandex_track_id", "verdict", "processing_stage", "is_dead", "audio_hash", "detector_version")
            .forEach { c -> assertTrue(ddl.contains(c), "track должен содержать $c") }
        assertTrue(ddl.contains("yandex_track_id TEXT NOT NULL"))
    }

    @Test
    fun `detect_cache keyed by audio_hash`() {
        assertTrue(Schema.CREATE_DETECT_CACHE.contains("audio_hash TEXT PRIMARY KEY"))
    }

    @Test
    fun `four tables defined`() {
        assertEquals(4, Schema.CREATE_TABLES.size)
    }
}
