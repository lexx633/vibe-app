package dev.humanonly.android

import android.content.Context
import dev.humanonly.backup.BackupSerialization
import dev.humanonly.db.SqlBackupSource
import dev.humanonly.pipeline.BackupGuard
import java.io.File

/**
 * [BackupGuard] на устройстве (хард-правило 5, §F7): гарантирует, что ПЕРЕД авто-дизлайком есть свежий
 * восстановимый снимок лайков. `latestBackupId()` возвращает id актуального бэкапа, а если свежего нет —
 * атомарно пишет новый снимок ([SqlBackupSource]) в `filesDir/backups` и возвращает его id. `null` вернётся
 * только при сбое записи → [dev.humanonly.pipeline.ActionDispatcher.execute] тогда откажется действовать
 * (деструктив без бэкапа НЕ произойдёт).
 *
 * Свежесть — по [ttlMs] (дефолт 24 ч): в пределах TTL переиспользуем последний бэкап (не плодим файлы
 * на каждый прогон), иначе снимаем заново. Снимок содержит только id/время (§12, без PII); восстановление —
 * штатным F7-restore по этому манифесту. Атомарность — запись во `*.tmp` + `renameTo` (без «полуфайлов»).
 */
class DeviceBackupGuard(
    context: Context,
    private val source: SqlBackupSource,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val now: () -> Long = System::currentTimeMillis,
) : BackupGuard {

    private val dir = File(context.applicationContext.filesDir, BACKUP_DIR)

    override fun latestBackupId(): String? =
        freshBackupId() ?: runCatching { writeSnapshot() }.getOrNull()

    /** id самого свежего бэкапа в пределах TTL, либо null. */
    private fun freshBackupId(): String? {
        val cutoff = now() - ttlMs
        return dir.listFiles { f -> f.isFile && f.name.startsWith(PREFIX) && f.name.endsWith(SUFFIX) }
            ?.filter { (createdAtOf(it.name) ?: -1L) >= cutoff }
            ?.maxByOrNull { createdAtOf(it.name) ?: 0L }
            ?.name
    }

    /** Снять снимок лайков и записать атомарно (tmp → rename). Возвращает id (имя файла). */
    private fun writeSnapshot(): String {
        dir.mkdirs()
        val createdAt = now()
        val name = "$PREFIX$createdAt$SUFFIX"
        val target = File(dir, name)
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(BackupSerialization.encode(source.snapshotLikes(createdAt)))
        if (target.exists()) target.delete() // тот же ms — перезапишем свежим
        check(tmp.renameTo(target)) { "не удалось атомарно переименовать бэкап лайков" }
        return name
    }

    private fun createdAtOf(name: String): Long? =
        name.removePrefix(PREFIX).removeSuffix(SUFFIX).toLongOrNull()

    companion object {
        const val BACKUP_DIR = "backups"
        private const val PREFIX = "likes-"
        private const val SUFFIX = ".json"

        /** TTL свежести бэкапа — сутки: авто-прогон в пределах суток переиспользует снимок. */
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
    }
}
