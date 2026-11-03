package echo.music.iad1tya.playback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jedna rozposlouchaná pohádka.
 *
 * Ukládáme URL stránky pohádky + pořadí kapitoly (NE adresu mp3 - ta obsahuje token s platností,
 * takže by po čase přestala fungovat).
 */
data class DetiResumeEntry(
    val taleId: String,        // URL stránky pohádky
    val title: String,         // název pohádky
    val artworkUrl: String?,
    val trackIndex: Int,       // pořadí kapitoly (od 0)
    val trackTitle: String?,   // název kapitoly - kontrola, že se seznam kapitol nezměnil
    val positionMs: Long,      // pozice v kapitole
    val updatedAt: Long
)

/**
 * Jednoduché úložiště historie (SharedPreferences + JSON, žádné nové závislosti).
 * Nejnovější pohádka je první. Drží maximálně MAX_ENTRIES pohádek.
 */
class DetiResumeStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    fun getAll(): List<DetiResumeEntry> = synchronized(lock) { read() }

    fun get(taleId: String): DetiResumeEntry? =
        synchronized(lock) { read().firstOrNull { it.taleId == taleId } }

    fun upsert(entry: DetiResumeEntry) = synchronized(lock) {
        val list = read().filter { it.taleId != entry.taleId }.toMutableList()
        list.add(0, entry)
        write(list.take(MAX_ENTRIES))
    }

    fun remove(taleId: String) = synchronized(lock) {
        write(read().filter { it.taleId != taleId })
    }

    private fun read(): List<DetiResumeEntry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", "")
                if (id.isEmpty()) return@mapNotNull null
                DetiResumeEntry(
                    taleId = id,
                    title = o.optString("title", ""),
                    artworkUrl = o.optString("art", "").ifEmpty { null },
                    trackIndex = o.optInt("idx", 0),
                    trackTitle = o.optString("trackTitle", "").ifEmpty { null },
                    positionMs = o.optLong("pos", 0L),
                    updatedAt = o.optLong("ts", 0L)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun write(list: List<DetiResumeEntry>) {
        val arr = JSONArray()
        for (e in list) {
            arr.put(
                JSONObject().apply {
                    put("id", e.taleId)
                    put("title", e.title)
                    put("art", e.artworkUrl ?: "")
                    put("idx", e.trackIndex)
                    put("trackTitle", e.trackTitle ?: "")
                    put("pos", e.positionMs)
                    put("ts", e.updatedAt)
                }
            )
        }
        prefs.edit().putString(KEY_ENTRIES, arr.toString()).apply()
    }

    companion object {
        private const val PREFS_NAME = "deti_resume_v1"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 15
    }
}
