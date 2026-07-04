package dev.humanonly.archive

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/** FS-реализации store'ов [Archiver]: атомарность, дедуп по пути, sanitize, round-trip манифеста. */
class FsArchiveStoresTest {

    @Test
    fun `FsBlobStore put→exists→get round-trip`(@TempDir dir: Path) {
        val store = FsBlobStore(dir)
        val path = "flac/ab/abcdef.flac"
        assertFalse(store.exists(path))
        assertNull(store.get(path))

        assertTrue(store.put(path, byteArrayOf(1, 2, 3)))
        assertTrue(store.exists(path))
        assertArrayEquals(byteArrayOf(1, 2, 3), store.get(path))
    }

    @Test
    fun `FsBlobStore перезапись идемпотентна (dedup по пути)`(@TempDir dir: Path) {
        val store = FsBlobStore(dir)
        val path = "flac/cd/x.flac"
        store.put(path, byteArrayOf(1))
        store.put(path, byteArrayOf(2, 2))
        assertArrayEquals(byteArrayOf(2, 2), store.get(path))
    }

    @Test
    fun `FsBlobStore отвергает path traversal за пределы root`(@TempDir dir: Path) {
        val store = FsBlobStore(dir)
        assertThrows(IllegalArgumentException::class.java) {
            store.put("../escape.flac", byteArrayOf(1))
        }
    }

    @Test
    fun `FsLocalStore write→read→delete`(@TempDir dir: Path) {
        val store = FsLocalStore(dir)
        assertNull(store.read("t1"))

        store.write("t1", byteArrayOf(5, 6, 7))
        assertArrayEquals(byteArrayOf(5, 6, 7), store.read("t1"))

        store.delete("t1")
        assertNull(store.read("t1"))
        // повторный delete безопасен (идемпотентно)
        store.delete("t1")
    }

    @Test
    fun `FsLocalStore sanitize trackId (не создаёт файлы вне root)`(@TempDir dir: Path) {
        val store = FsLocalStore(dir)
        // спецсимволы заменяются на '_' → файл остаётся в root, read/write согласованы
        store.write("../../evil", byteArrayOf(9))
        assertArrayEquals(byteArrayOf(9), store.read("../../evil"))
    }

    @Test
    fun `FsManifestStore load на отсутствующем файле — пустой манифест`(@TempDir dir: Path) {
        val store = FsManifestStore(dir.resolve("manifest.json"))
        val m = store.load()
        assertEquals(MANIFEST_VERSION, m.manifestVersion)
        assertTrue(m.entries.isEmpty())
    }

    @Test
    fun `FsManifestStore save→load round-trip`(@TempDir dir: Path) {
        val store = FsManifestStore(dir.resolve("manifest.json"))
        val entry = ArchiveEntry(
            trackId = "t1", hash = "deadbeef", codec = "flac", quality = "lossless",
            archivePath = "flac/de/deadbeef.flac", verdict = "clean", detectorVersion = "v1",
        )
        store.save(ArchiveManifest().upsert(entry))

        val loaded = store.load()
        assertEquals(1, loaded.entries.size)
        assertEquals(entry, loaded.get("t1"))
    }
}
