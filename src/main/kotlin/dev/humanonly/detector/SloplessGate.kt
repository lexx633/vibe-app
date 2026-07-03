package dev.humanonly.detector

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Arrays

/**
 * Hard gate каскада 0 детекции (ADR-0005, ТЗ §10): если `artist_id` в базе slopless —
 * сразу `verdict=suspected`, аудио-детект НЕ запускается (экономия трафика, главный ресурс).
 *
 * Здесь ТОЛЬКО код гейта. Сама база (~140k artist_id из репо slopless, alexeyfv, **GPL-3.0**)
 * в публичный репо НЕ вендорится — грузится в рантайме через [fromJson]. В тестах — синтетическая
 * мини-фикстура, никаких GPL-данных в репозитории (см. решение по лицензии, 2026-07-04).
 *
 * Формат снапшота slopless (сверено с data/yandex_music.json, не по памяти — хард-правило 9):
 *   `{"timestamp":"<ISO-8601>","artists":[<int>, ...]}` — id отсортированы по возрастанию, уникальны.
 *
 * id хранятся как отсортированный [IntArray] → членство бинарным поиском O(log n) без аллокаций
 * на запрос (важно для стриминг-конвейера каскада, §7). Вход не доверенный: дедуп+сортировка на входе.
 */
class SloplessGate private constructor(
    private val sortedArtistIds: IntArray,
    /** Версия базы (timestamp снапшота) → пишется как slopless_db_version (ТЗ §13). */
    val version: String,
) {
    /** Число артистов в базе. */
    val size: Int get() = sortedArtistIds.size

    /** true → артист в базе AI → hard gate hit (verdict=suspected, аудио-детект пропускается). */
    fun isAiArtist(artistId: Int): Boolean =
        Arrays.binarySearch(sortedArtistIds, artistId) >= 0

    /**
     * Удобная перегрузка для строковых id из API ЯМ. Не-числовой/пустой id → false
     * (не роняем конвейер на мусорном id, просто не считаем его AI по этому гейту).
     */
    fun isAiArtist(artistId: String): Boolean {
        val id = artistId.toIntOrNull() ?: return false
        return isAiArtist(id)
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Парсит снапшот slopless. Дедуп + сортировка не доверяя входу (гарантия для бинпоиска). */
        fun fromJson(text: String): SloplessGate {
            val snap = JSON.decodeFromString(SloplessSnapshot.serializer(), text)
            val ids = snap.artists.toSortedSet().toIntArray()
            return SloplessGate(ids, snap.timestamp)
        }
    }
}

/** DTO снапшота slopless. Лишние поля игнорируются (формат может обрасти метаданными). */
@Serializable
private data class SloplessSnapshot(
    val timestamp: String = "",
    val artists: List<Int> = emptyList(),
)
