package dev.humanonly.archive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * Логика [YandexDiskClient] и store'ов на fake-[DiskHttp]: конструкция URL, двухшаговый upload/download
 * (href → PUT/GET без токена), создание родительских папок, дедуп-exists, round-trip манифеста.
 * Реальная сеть Диска — на устройстве/в live-инструменте (хард-правило 9), здесь без сети.
 */
class YandexDiskClientTest {

    /** Записанный вызов транспорта — для проверки, ЧТО именно ушло на Диск. */
    private data class Call(val method: String, val url: String, val auth: Boolean, val body: ByteArray?)

    /**
     * Fake транспорта: маршрутизирует по (метод, «endpoint»-части URL) через заданные хендлеры и пишет
     * лог вызовов. In-memory «Диск»: папки + файлы, чтобы гонять полноценные сценарии.
     */
    private class FakeDiskHttp : DiskHttp {
        val calls = mutableListOf<Call>()
        val dirs = mutableSetOf<String>()
        val files = mutableMapOf<String, ByteArray>()
        private var hrefSeq = 0

        // href → путь файла, на который потом PUT/GET (эмуляция самодостаточной ссылки без токена).
        private val hrefToPath = mutableMapOf<String, String>()

        override fun get(url: String, auth: Boolean): DiskResponse {
            calls += Call("GET", url, auth, null)
            return when {
                url.startsWith("$BASE/resources/upload") -> {
                    val path = pathParam(url)
                    val href = "https://uploader.disk/$path/${hrefSeq++}"
                    hrefToPath[href] = path
                    json(200, """{"href":"$href","method":"PUT","templated":false}""")
                }
                url.startsWith("$BASE/resources/download") -> {
                    val path = pathParam(url)
                    if (path !in files) return DiskResponse(404, ByteArray(0))
                    val href = "https://downloader.disk/$path/${hrefSeq++}"
                    hrefToPath[href] = path
                    json(200, """{"href":"$href"}""")
                }
                url.startsWith("$BASE/resources") -> { // exists
                    val path = pathParam(url)
                    if (path in files || path in dirs) DiskResponse(200, ByteArray(0))
                    else DiskResponse(404, ByteArray(0))
                }
                hrefToPath.containsKey(url) -> DiskResponse(200, files[hrefToPath.getValue(url)] ?: ByteArray(0))
                else -> error("unexpected GET $url")
            }
        }

        override fun put(url: String, body: ByteArray?, auth: Boolean): DiskResponse {
            calls += Call("PUT", url, auth, body)
            return when {
                url.startsWith("$BASE/resources?") -> { // mkdir
                    val path = pathParam(url)
                    if (path in dirs) DiskResponse(409, ByteArray(0))
                    else { dirs += path; DiskResponse(201, ByteArray(0)) }
                }
                hrefToPath.containsKey(url) -> { // upload на href
                    files[hrefToPath.getValue(url)] = body ?: ByteArray(0)
                    DiskResponse(201, ByteArray(0))
                }
                else -> error("unexpected PUT $url")
            }
        }

        private fun json(status: Int, text: String) = DiskResponse(status, text.toByteArray(StandardCharsets.UTF_8))

        /** Декодировать значение параметра `path` из URL (обратно к тому, что закодировал клиент). */
        private fun pathParam(url: String): String {
            val raw = Regex("[?&]path=([^&]+)").find(url)!!.groupValues[1]
            return java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8)
        }

        companion object {
            const val BASE = "https://cloud-api.yandex.net/v1/disk"
        }
    }

    @Test
    fun `exists — 200 true, 404 false, авторизованный запрос`() {
        val http = FakeDiskHttp()
        http.files["/Бекап/vibe/a.flac"] = byteArrayOf(1)
        val client = YandexDiskClient(http)

        assertTrue(client.exists("/Бекап/vibe/a.flac"))
        assertFalse(client.exists("/Бекап/vibe/nope.flac"))
        // все exists-вызовы идут с токеном
        assertTrue(http.calls.filter { it.url.contains("/resources?") }.all { it.auth })
    }

    @Test
    fun `ensureParentDirs создаёт каждый уровень от корня к листу`() {
        val http = FakeDiskHttp()
        YandexDiskClient(http).ensureParentDirs("/Бекап/vibe/flac/ab/x.flac")

        // созданы ровно родительские папки (без самого файла)
        assertEquals(setOf("/Бекап", "/Бекап/vibe", "/Бекап/vibe/flac", "/Бекап/vibe/flac/ab"), http.dirs)
    }

    @Test
    fun `upload — href GET с токеном, PUT байтов на href без токена`() {
        val http = FakeDiskHttp()
        val client = YandexDiskClient(http)
        val payload = byteArrayOf(9, 8, 7, 6)

        client.upload("/Бекап/vibe/x.flac", payload)

        assertArrayEquals(payload, http.files["/Бекап/vibe/x.flac"])
        val hrefPut = http.calls.single { it.method == "PUT" && it.url.startsWith("https://uploader.disk") }
        assertFalse(hrefPut.auth) // на href токен не шлём (хард-правило 4: href самодостаточен)
        val metaGet = http.calls.single { it.url.contains("/resources/upload") }
        assertTrue(metaGet.auth)
    }

    @Test
    fun `download — null на отсутствующем файле, байты на существующем`() {
        val http = FakeDiskHttp()
        val client = YandexDiskClient(http)
        assertNull(client.download("/Бекап/vibe/missing.flac"))

        http.files["/Бекап/vibe/there.flac"] = byteArrayOf(4, 2)
        assertArrayEquals(byteArrayOf(4, 2), client.download("/Бекап/vibe/there.flac"))
    }

    @Test
    fun `upload пробрасывает ошибку на не-2xx href-выдаче`() {
        val http = object : DiskHttp {
            override fun get(url: String, auth: Boolean) = DiskResponse(500, ByteArray(0))
            override fun put(url: String, body: ByteArray?, auth: Boolean) = DiskResponse(201, ByteArray(0))
        }
        assertThrows(IllegalStateException::class.java) { YandexDiskClient(http).upload("/x", byteArrayOf(1)) }
    }

    @Test
    fun `BlobStore резолвит относительный путь под basePath и заливает с родителями`() {
        val http = FakeDiskHttp()
        val store = YandexDiskBlobStore(YandexDiskClient(http))

        assertFalse(store.exists("flac/ab/hash.flac"))
        assertTrue(store.put("flac/ab/hash.flac", byteArrayOf(1, 2, 3)))
        assertTrue(store.exists("flac/ab/hash.flac"))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.get("flac/ab/hash.flac"))

        // залито под /Бекап/vibe с созданными родителями
        assertArrayEquals(byteArrayOf(1, 2, 3), http.files["/Бекап/vibe/flac/ab/hash.flac"])
        assertTrue("/Бекап/vibe/flac/ab" in http.dirs)
    }

    @Test
    fun `ManifestStore round-trip — пусто на отсутствии, затем save→load`() {
        val http = FakeDiskHttp()
        val store = YandexDiskManifestStore(YandexDiskClient(http))

        assertTrue(store.load().entries.isEmpty())

        val entry = ArchiveEntry(
            trackId = "t1", hash = "deadbeef", codec = "flac", quality = "lossless",
            archivePath = "flac/de/deadbeef.flac", verdict = "clean", detectorVersion = "v1",
        )
        store.save(ArchiveManifest().upsert(entry))

        val loaded = store.load()
        assertEquals(1, loaded.entries.size)
        assertEquals(entry, loaded.get("t1"))
        // manifest лежит по фиксированному пути
        assertTrue(http.files.containsKey("/Бекап/vibe/manifest.json"))
    }
}
