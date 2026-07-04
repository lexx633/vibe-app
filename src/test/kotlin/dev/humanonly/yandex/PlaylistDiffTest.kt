package dev.humanonly.yandex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * Офлайн: форма тела `diff` для change-relative плейлиста. Выверено по референс-репо
 * (MarshalX/yandex-music-api `utils/difference.py`): массив операций, ключ трека сериализуется как `albumId`.
 */
class PlaylistDiffTest {

    @Test
    fun `insertTrack — массив с одной insert-операцией и album-aware треком`() {
        assertEquals(
            """[{"op":"insert","at":3,"tracks":[{"id":"42","albumId":"777"}]}]""",
            PlaylistDiff.insertTrack(at = 3, trackId = "42", albumId = "777"),
        )
    }

    @Test
    fun `insertTrack в начало (at=0) допустим`() {
        assertEquals(
            """[{"op":"insert","at":0,"tracks":[{"id":"1","albumId":"2"}]}]""",
            PlaylistDiff.insertTrack(at = 0, trackId = "1", albumId = "2"),
        )
    }

    @Test
    fun `deleteRange — массив с одной delete-операцией и полуинтервалом`() {
        assertEquals(
            """[{"op":"delete","from":2,"to":3}]""",
            PlaylistDiff.deleteRange(from = 2, to = 3),
        )
    }

    @Test
    fun `insertTrack с отрицательным at запрещён`() {
        assertThrows(IllegalArgumentException::class.java) { PlaylistDiff.insertTrack(-1, "1", "2") }
    }

    @Test
    fun `deleteRange требует 0 le from lt to`() {
        assertThrows(IllegalArgumentException::class.java) { PlaylistDiff.deleteRange(3, 3) }
        assertThrows(IllegalArgumentException::class.java) { PlaylistDiff.deleteRange(5, 2) }
    }
}
