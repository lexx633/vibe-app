package dev.humanonly.yandex

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Офлайн-парсинг фикстур с ФЕЙКОВЫМИ url/key/id (без реальных данных/PII/токенов).
 * Проверяем result-обёртку и ignoreUnknownKeys.
 */
class DtoParsingTest {

    private fun resource(path: String): String =
        javaClass.getResourceAsStream(path)!!.readBytes().decodeToString()

    @Test
    fun `get_file_info фикстура парсится в DownloadInfo`() {
        val resp = YandexJson.decodeFromString(
            GetFileInfoResponse.serializer(),
            resource("/yandex/get_file_info.json"),
        )
        val info = resp.result.downloadInfo
        assertEquals("flac-mp4", info.codec)
        assertEquals("lossless", info.quality)
        assertEquals(0, info.bitrate)
        assertEquals(2, info.urls.size)
        assertTrue(info.urls.first().startsWith("https://strm-fake"))
        assertEquals("000102030405060708090a0b0c0d0e0f", info.key)
    }

    @Test
    fun `likes фикстура парсится в список id`() {
        val resp = YandexJson.decodeFromString(
            LikesResponse.serializer(),
            resource("/yandex/likes.json"),
        )
        val ids = resp.result.library.tracks.map { it.id }
        assertEquals(listOf("100000001", "100000002", "100000003"), ids)
    }
}
