package echo.music.iad1tya.playback

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import timber.log.Timber

data class DetiTaleItem(
    val id: String,
    val title: String,
    val isCategory: Boolean, // true = složka/pohádka k rozkliknutí, false = přehratelná kapitola
    val mediaUri: String? = null,
    val artworkUrl: String? = null
)

class DetiOnlineParser {

    companion object {
        private const val TAG = "DetiOnlineParser"
        private const val BASE_URL = "https://deti-online.com"
        private const val DEFAULT_CATALOG_URL = "$BASE_URL/audioskazki/sbornik-skazok-mp3/"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        private const val TIMEOUT_MS = 10_000
    }

    private fun fetch(url: String): Document =
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get()

    /**
     * Stáhne seznam pohádek / knih ze sbírky
     */
    suspend fun getCatalog(catalogUrl: String = DEFAULT_CATALOG_URL): List<DetiTaleItem> =
        withContext(Dispatchers.IO) {
            try {
                val doc = fetch(catalogUrl)
                val primary = parseCatalogPrimary(doc)
                val result = if (primary.isNotEmpty()) primary else parseCatalogFallback(doc, catalogUrl)
                if (result.isEmpty()) Timber.tag(TAG).w("Katalog je prázdný (změnil se HTML?)")
                result.distinctBy { it.id }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "getCatalog selhalo")
                emptyList()
            }
        }

    /** Původní selektory */
    private fun parseCatalogPrimary(doc: Document): List<DetiTaleItem> {
        val list = mutableListOf<DetiTaleItem>()
        for (el in doc.select(".scroll-grid-img-wrap .item, .grid-img .item")) {
            val linkEl = el.selectFirst("a.a") ?: el.selectFirst("a[href]") ?: continue
            val title = el.select(".t2").text().ifEmpty { linkEl.text() }.trim()
            val href = absolute(linkEl.attr("href"))
            if (title.isNotEmpty() && href.isNotEmpty()) {
                list.add(
                    DetiTaleItem(
                        id = href,
                        title = title,
                        isCategory = true,
                        artworkUrl = imageUrl(el.selectFirst("img"))
                    )
                )
            }
        }
        return list
    }

    /** Záloha: odkazy na detail pohádky, které obsahují obrázek */
    private fun parseCatalogFallback(doc: Document, catalogUrl: String): List<DetiTaleItem> {
        val list = mutableListOf<DetiTaleItem>()
        val catalogPath = catalogUrl.trimEnd('/')
        for (a in doc.select("a[href*=/audioskazki/sbornik-skazok-mp3/]:has(img)")) {
            val href = absolute(a.attr("href"))
            if (href.isEmpty() || href.trimEnd('/') == catalogPath) continue
            val img = a.selectFirst("img")
            val title = a.text().trim().ifEmpty { img?.attr("alt")?.trim().orEmpty() }
            if (title.isEmpty()) continue
            list.add(
                DetiTaleItem(
                    id = href,
                    title = title,
                    isCategory = true,
                    artworkUrl = imageUrl(img)
                )
            )
        }
        return list
    }

    /**
     * Z detailu pohádky extrahuje stopy s ROT13 dekódováním MP3 odkazů
     */
    suspend fun getTracks(pageUrl: String): List<DetiTaleItem> = withContext(Dispatchers.IO) {
        try {
            val doc = fetch(pageUrl)
            val items = doc.select("ul.playlist li.item[data-f]").ifEmpty { doc.select("li[data-f]") }

            val tracks = mutableListOf<DetiTaleItem>()
            items.forEachIndexed { index, item ->
                val rawDataF = item.attr("data-f").trim()
                if (rawDataF.isEmpty()) return@forEachIndexed

                val title = item.select(".name .t").text()
                    .ifEmpty { item.select(".name").text() }
                    .trim()
                    .ifEmpty { "Kapitola ${index + 1}" }

                val decodedPath = rot13(rawDataF)
                val fullMp3Url = "https://stat4.deti-online.com/s/$decodedPath.mp3"
                tracks.add(
                    DetiTaleItem(
                        id = fullMp3Url,
                        title = title,
                        isCategory = false,
                        mediaUri = fullMp3Url
                    )
                )
            }
            if (tracks.isEmpty()) Timber.tag(TAG).w("Žádné stopy na %s (změnil se HTML?)", pageUrl)
            tracks.distinctBy { it.id }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "getTracks selhalo pro %s", pageUrl)
            emptyList()
        }
    }

    // ---- Pomocné --------------------------------------------------------------------------

    private fun absolute(url: String): String {
        val u = url.trim()
        return when {
            u.isEmpty() -> ""
            u.startsWith("//") -> "https:$u"
            u.startsWith("/") -> "$BASE_URL$u"
            else -> u
        }
    }

    /** Vrátí absolutní URL obrázku, nebo null (lazy-load placeholdery a data: URI se ignorují). */
    private fun imageUrl(img: Element?): String? {
        if (img == null) return null
        val raw = listOf("data-src", "data-original", "src")
            .map { img.attr(it).trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("data:") }
            ?: return null
        return absolute(raw).ifEmpty { null }
    }

    private fun rot13(input: String): String {
        val out = StringBuilder(input.length)
        for (c in input) {
            when (c) {
                in 'a'..'z' -> {
                    val shifted = c.code + 13
                    out.append(if (shifted > 'z'.code) (shifted - 26).toChar() else shifted.toChar())
                }
                in 'A'..'Z' -> {
                    val shifted = c.code + 13
                    out.append(if (shifted > 'Z'.code) (shifted - 26).toChar() else shifted.toChar())
                }
                else -> out.append(c)
            }
        }
        return out.toString()
    }
}
