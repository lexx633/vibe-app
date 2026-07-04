package dev.humanonly.android

import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import dev.humanonly.backup.BackupSerialization
import dev.humanonly.db.DiscoveredTrack
import dev.humanonly.db.SqlBackupSource
import dev.humanonly.db.TrackRepository
import dev.humanonly.db.initSchema
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * [DeviceBackupGuard] на реальном framework SQLite (Robolectric) + реальной файловой `filesDir` (хард-правило 5).
 * Проверяем: свежий бэкап создаётся и восстановимо декодируется; в пределах TTL переиспользуется тот же файл;
 * по истечении TTL снимается новый. Без устройства/эмулятора — тот же код, что поедет в APK.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeviceBackupGuardTest {

    private lateinit var sqlite: SQLiteDatabase
    private lateinit var source: SqlBackupSource
    private var nowMs: Long = 1_000_000L

    private val backupsDir: File
        get() = File(ApplicationProvider.getApplicationContext<android.content.Context>().filesDir, "backups")

    @Before
    fun setup() {
        sqlite = SQLiteDatabase.create(null)
        val db = AndroidDb(sqlite)
        db.initSchema()
        TrackRepository(db) { 1000L }
            .upsertDiscovered(listOf(DiscoveredTrack("t1", "a1"), DiscoveredTrack("t2", "a2")))
        source = SqlBackupSource(db)
    }

    @After
    fun teardown() {
        sqlite.close()
        backupsDir.deleteRecursively()
    }

    private fun guard(ttlMs: Long = DeviceBackupGuard.DEFAULT_TTL_MS) =
        DeviceBackupGuard(ApplicationProvider.getApplicationContext(), source, ttlMs) { nowMs }

    @Test
    fun `создаёт восстановимый снимок лайков когда бэкапа нет`() {
        val id = guard().latestBackupId()
        assertNotNull("должен вернуть id свежего бэкапа", id)

        val file = File(backupsDir, id!!)
        assertTrue("файл бэкапа должен существовать", file.isFile)
        val manifest = BackupSerialization.decode(file.readText())
        assertEquals(listOf("t1", "t2"), manifest.likes.map { it.trackId })
    }

    @Test
    fun `в пределах TTL переиспользует тот же бэкап`() {
        val first = guard().latestBackupId()
        nowMs += 60_000 // +1 мин, в пределах суток
        val second = guard().latestBackupId()

        assertEquals("тот же id — новый файл не создаём", first, second)
        assertEquals(1, backupsDir.listFiles()?.count { it.name.endsWith(".json") })
    }

    @Test
    fun `по истечении TTL снимает новый бэкап`() {
        val first = guard(ttlMs = 10_000).latestBackupId()
        nowMs += 20_000 // за пределами TTL
        val second = guard(ttlMs = 10_000).latestBackupId()

        assertNotEquals("должен появиться новый снимок", first, second)
        assertEquals(2, backupsDir.listFiles()?.count { it.name.endsWith(".json") })
    }
}
