package dev.humanonly.archive

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Локальные файловые реализации store'ов [Archiver] (§F6). Достаточны для устройства: блобы и manifest
 * лежат на файловой системе приложения. Удалённый S3/B2-[BlobStore] — ОТДЕЛЬНЫЙ chunk (нужны ключи,
 * хард-правило 4); здесь — FS-вариант для локального архива + тестов.
 *
 * `java.nio.file` доступен на Android с API 26 (minSdk=26) — тот же код работает и в JVM-тестах, и на
 * устройстве. Все записи атомарны (temp + rename в пределах одной ФС) — частичный файл недопустим
 * (crash-safety §6.1, data-model §7).
 */

/**
 * Блобы на файловой системе под [root]. Путь блоба ([Archiver.archivePath]) — относительный,
 * резолвится от [root]; поддиректории создаются по необходимости. [put] атомарен (temp+rename).
 */
class FsBlobStore(private val root: Path) : BlobStore {

    override fun exists(path: String): Boolean = Files.exists(resolve(path))

    override fun put(path: String, content: ByteArray): Boolean {
        val target = resolve(path)
        Files.createDirectories(target.parent)
        val tmp = Files.createTempFile(target.parent, ".blob-", ".part")
        return try {
            Files.write(tmp, content)
            atomicMove(tmp, target)
            true
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tmp) }
            false
        }
    }

    override fun get(path: String): ByteArray? {
        val p = resolve(path)
        return if (Files.exists(p)) Files.readAllBytes(p) else null
    }

    /** Защита от escape за пределы [root] (path traversal): нормализуем и проверяем префикс. */
    private fun resolve(path: String): Path {
        val resolved = root.resolve(path).normalize()
        require(resolved.startsWith(root)) { "путь блоба выходит за пределы root архива: '$path'" }
        return resolved
    }
}

/**
 * Локальные скачанные FLAC под [root], имя файла — по trackId. Источник для upload и удаления после
 * подтверждённого архивирования (§F6). [write] — для конвейера скачивания (положить блоб перед архивацией).
 */
class FsLocalStore(private val root: Path) : LocalStore {

    override fun read(trackId: String): ByteArray? {
        val p = fileFor(trackId)
        return if (Files.exists(p)) Files.readAllBytes(p) else null
    }

    override fun delete(trackId: String) {
        Files.deleteIfExists(fileFor(trackId))
    }

    /** Положить локальный блоб трека (атомарно). Используется скачиванием до передачи в [Archiver]. */
    fun write(trackId: String, content: ByteArray) {
        Files.createDirectories(root)
        val target = fileFor(trackId)
        val tmp = Files.createTempFile(root, ".local-", ".part")
        try {
            Files.write(tmp, content)
            atomicMove(tmp, target)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }

    /** Имя файла — trackId с sanitize (только [A-Za-z0-9_-]) во избежание traversal/спецсимволов. */
    private fun fileFor(trackId: String): Path {
        require(trackId.isNotBlank()) { "trackId пуст" }
        val safe = trackId.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }.joinToString("")
        return root.resolve("$safe.flac")
    }
}

/**
 * `manifest.json` одним файлом под [file] с АТОМАРНЫМ обновлением (data-model §7): [save] пишет temp +
 * rename. [load] на отсутствующем файле возвращает пустой манифест (первый прогон). Round-trip —
 * через [ArchiveSerialization].
 */
class FsManifestStore(private val file: Path) : ManifestStore {

    override fun load(): ArchiveManifest {
        if (!Files.exists(file)) return ArchiveManifest()
        return ArchiveSerialization.decode(Files.readString(file))
    }

    override fun save(manifest: ArchiveManifest) {
        val parent = file.parent ?: file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val tmp = Files.createTempFile(parent, ".manifest-", ".part")
        try {
            Files.writeString(tmp, ArchiveSerialization.encode(manifest))
            atomicMove(tmp, file)
        } catch (e: IOException) {
            runCatching { Files.deleteIfExists(tmp) }
            throw e
        }
    }
}

/**
 * Atomic rename в пределах одной ФС. Пытаемся ATOMIC_MOVE; если ФС его не поддерживает — падаем на
 * обычный replace (temp уже полностью записан, окно неполного файла минимально).
 */
private fun atomicMove(from: Path, to: Path) {
    try {
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    } catch (e: Exception) {
        Files.move(from, to, StandardCopyOption.REPLACE_EXISTING)
    }
}
