package dev.humanonly.backup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BackupSerializationTest {

    private fun sampleManifest() = BackupManifest(
        createdAt = 1_700_000_000_000L,
        likes = listOf(
            LikedTrackEntry(trackId = "100000001", albumId = "200000001", likedAt = 1_699_000_000_000L),
            LikedTrackEntry(trackId = "100000002"),
        ),
        playlists = listOf(
            PlaylistEntry(kind = "3", trackIds = listOf("100000001", "100000002")),
        ),
    )

    @Test
    fun `round-trip preserves data and format version`() {
        val original = sampleManifest()
        val text = BackupSerialization.encode(original)
        val decoded = BackupSerialization.decode(text)

        assertEquals(original, decoded)
        assertEquals(BACKUP_FORMAT_VERSION, decoded.backupFormatVersion)
    }

    @Test
    fun `format version is always written even when default`() {
        val text = BackupSerialization.encode(sampleManifest())
        assertTrue(text.contains("\"backupFormatVersion\":$BACKUP_FORMAT_VERSION"))
    }

    /** PII-правило §12: сериализованный манифест не содержит персональных ключей. */
    @Test
    fun `serialized manifest has no PII keys`() {
        val text = BackupSerialization.encode(sampleManifest()).lowercase()
        for (forbidden in listOf("title", "artist", "\"name\"", "description", "playlist_name")) {
            assertFalse(text.contains(forbidden), "Манифест не должен содержать ключ: $forbidden")
        }
    }
}
