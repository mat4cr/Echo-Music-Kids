package echo.music.iad1tya.playback

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Rozposlechnutá pohádka. Záměrně se NEUKLÁDÁ URL mp3 (obsahuje token / časové razítko
 * a časem může přestat platit), ale adresa stránky pohádky + pořadí kapitoly.
 * Při navázání se kapitoly načtou znovu přes DetiOnlineParser.getTracks().
 */
data class DetiHistoryEntry(
    val taleUrl: String,
    val taleTitle: String,
    val artworkUrl: String?,
    val chapterIndex: Int,
    val chapterTitle: String,
    val positionMs: Long,
    val updatedAt: Long,
)

/**
 * Malé vlastní úložiště historie (SharedPreferences + org.json, žádné nové závislosti).
 * Nejnověji poslouchaná pohádka je vždy první. Echo Music si pohádky ve své frontě
 * neukládá (zná jen YouTube metadata), proto vlastní úložiště.
 */
@Singleton
class DetiHistoryStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()
    private var cache: List<DetiHistoryEntry>? = null

    fun getAll(): List<DetiHistoryEntry> = synchronized(lock) { load() }

    fun last(): DetiHistoryEntry? = getAll().firstOrNull()

    fun get(taleUrl: String): DetiHistoryEntry? = getAll().firstOrNull { it.taleUrl == taleUrl }

    /** Uloží/aktualizuje pohádku a přesune ji na první místo. */
    fun save(entry: DetiHistoryEntry) = synchronized(lock) {
        val updated = (listOf(entry) + load().filterNot { it.taleUrl == entry.taleUrl })
            .take(MAX_ENTRIES)
        persist(updated)
    }

    fun remove(taleUrl: String) = synchronized(lock) {
        val current = load()
        if (current.any { it.taleUrl == taleUrl }) {
            persist(current.filterNot { it.taleUrl == taleUrl })
        }
    }

    // ---- interní --------------------------------------------------------------------------

    private fun load(): List<DetiHistoryEntry> {
        cache?.let { return it }
        val parsed = try {
            val arr = JSONArray(prefs.getString(KEY, "[]") ?: "[]")
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val url = o.optString("taleUrl")
                if (url.isEmpty()) return@mapNotNull null
                DetiHistoryEntry(
                    taleUrl = url,
                    taleTitle = o.optString("taleTitle"),
                    artworkUrl = o.optString("artworkUrl").ifEmpty { null },
                    chapterIndex = o.optInt("chapterIndex", 0),
                    chapterTitle = o.optString("chapterTitle"),
                    positionMs = o.optLong("positionMs", 0L),
                    updatedAt = o.optLong("updatedAt", 0L),
                )
            }
        } catch (e: Exception) {
            Timber.tag(TAG).w(e, "Historie je poškozená, začínám znovu")
            emptyList()
        }
        cache = parsed
        return parsed
    }

    private fun persist(list: List<DetiHistoryEntry>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(
                JSONObject()
                    .put("taleUrl", e.taleUrl)
                    .put("taleTitle", e.taleTitle)
                    .put("artworkUrl", e.artworkUrl ?: "")
                    .put("chapterIndex", e.chapterIndex)
                    .put("chapterTitle", e.chapterTitle)
                    .put("positionMs", e.positionMs)
                    .put("updatedAt", e.updatedAt)
            )
        }
        cache = list
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    private companion object {
        const val TAG = "DetiHistory"
        const val PREFS_NAME = "deti_history"
        const val KEY = "entries"
        const val MAX_ENTRIES = 20
    }
}
