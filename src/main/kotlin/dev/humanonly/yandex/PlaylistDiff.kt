package dev.humanonly.yandex

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Билдер тела `diff` для `change-relative` плейлиста (ТЗ §F4, MOVE_TO_PLAYLIST).
 *
 * Форма выверена по референс-репо (MarshalX/yandex-music-api `utils/difference.py`, хард-правило 9):
 * `diff` — это JSON-МАССИВ операций. Поддерживаются две операции:
 *   - вставка: `{"op":"insert","at":<idx>,"tracks":[{"id":<trackId>,"albumId":<albumId>}]}`
 *     (в referenc-репо ключ трека сериализуется как `albumId`, не `album_id`);
 *   - удаление диапазона: `{"op":"delete","from":<from>,"to":<to>}` (полуинтервал [from, to)).
 *
 * Album-aware: вставка ТРЕБУЕТ albumId (API отклоняет вставку без него) — поэтому [insertTrack]
 * принимает и albumId, а вызывающая сторона обязана его разрешить (см. [YandexLibraryActions]).
 */
object PlaylistDiff {

    /** Стабильная сериализация (без пробелов) — тело формы. */
    private val json = Json { encodeDefaults = true }

    /** Одна операция вставки трека в позицию [at]. Возвращает JSON-строку массива из одной операции. */
    fun insertTrack(at: Int, trackId: String, albumId: String): String {
        require(at >= 0) { "at должен быть >= 0, получено $at" }
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "insert")
                put("at", at)
                putJsonArray("tracks") {
                    addJsonObject {
                        put("id", trackId)
                        put("albumId", albumId)
                    }
                }
            }
        }
        return json.encodeToString(JsonArray.serializer(), ops)
    }

    /** Одна операция удаления полуинтервала [[from], [to]) треков. */
    fun deleteRange(from: Int, to: Int): String {
        require(from >= 0 && to > from) { "ожидалось 0 <= from < to, получено from=$from to=$to" }
        val ops = buildJsonArray {
            addJsonObject {
                put("op", "delete")
                put("from", from)
                put("to", to)
            }
        }
        return json.encodeToString(JsonArray.serializer(), ops)
    }
}
