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
    val artworkUrl: String? = null,
    val durationMin: Int? = null // délka pohádky v minutách (jen u katalogu), null = neznámá
)

/** Odkaz na věkovou kategorii ("filtr podle věku" na webu). maxAge == null -> "minAge a víc". */
data class DetiAgeLink(
    val slug: String,
    val url: String,
    val minAge: Int,
    val maxAge: Int?
)

/** Jedna stránka katalogu: pohádky (v pořadí z webu = podle oblíbenosti) + nalezené věkové filtry. */
data class DetiCatalogPage(
    val items: List<DetiTaleItem>,
    val ageLinks: List<DetiAgeLink>
)

class DetiOnlineParser {

    companion object {
        private const val TAG = "DetiOnlineParser"
        private const val BASE_URL = "https://deti-online.com"
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        private const val TIMEOUT_MS = 10_000

        const val TOP_URL = "https://deti-online.com/audioskazki/"
        const val DEFAULT_CATALOG_URL = "https://deti-online.com/audioskazki/sbornik-skazok-mp3/"
        const val AGE_2_3_URL = "https://deti-online.com/audioskazki/dlya-malyshey-2-3-let/"
        const val AGE_4_5_URL = "https://deti-online.com/audioskazki/dlya-detey-4-5-let/"
        const val AGE_6_7_URL = "https://deti-online.com/audioskazki/dlya-detey-6-7-let/"

        /** Odkud se bere "Top pohádek" (první dostupná stránka s výsledky). */
        val TOP_URLS = listOf(TOP_URL, DEFAULT_CATALOG_URL)

        private val HOURS_MIN_REGEX = Regex("""(\d+)\s*ч(?!\p{L})\s*(?:(\d+)\s*мин(?!\p{L}))?""")
        private val MINUTES_REGEX = Regex("""(\d+)\s*мин(?!\p{L})""")
        private val SPACES_REGEX = Regex("""\s+""")
        private val NUMBER_REGEX = Regex("""\d+""")
    }

    private fun fetch(url: String): Document =
        Jsoup.connect(url)
            .userAgent(USER_AGENT)
            .timeout(TIMEOUT_MS)
            .get()

    /** Jen pohádky (kvůli zpětné kompatibilitě). */
    suspend fun getCatalog(catalogUrl: String = DEFAULT_CATALOG_URL): List<DetiTaleItem> =
        getCatalogPage(catalogUrl).items

    /**
     * Stáhne stránku katalogu (úvodní stránka, sbírka, věková kategorie, ...):
     * seznam pohádek + odkazy na věkové filtry.
     */
    suspend fun getCatalogPage(catalogUrl: String = DEFAULT_CATALOG_URL): DetiCatalogPage =
        withContext(Dispatchers.IO) {
            try {
                val doc = fetch(catalogUrl)
                val primary = parseCatalogPrimary(doc)
                val fallback = parseCatalogFallback(doc)
                val items = (if (fallback.size > primary.size) fallback else primary)
                    .distinctBy { it.id }
                if (items.isEmpty()) Timber.tag(TAG).w("Katalog %s je prázdný (změnil se HTML?)", catalogUrl)
                DetiCatalogPage(items, parseAgeLinks(doc))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "getCatalogPage selhalo pro %s", catalogUrl)
                DetiCatalogPage(emptyList(), emptyList())
            }
        }

    /** Původní selektory */
    private fun parseCatalogPrimary(doc: Document): List<DetiTaleItem> {
        val list = mutableListOf<DetiTaleItem>()
        for (el in doc.select(".scroll-grid-img-wrap .item, .grid-img .item")) {
            val linkEl = el.selectFirst("a.a") ?: el.selectFirst("a[href]") ?: continue
            val title = stripDuration(el.select(".t2").text().ifEmpty { linkEl.text() })
            val href = absolute(linkEl.attr("href"))
            if (title.isNotEmpty() && href.isNotEmpty()) {
                list.add(
                    DetiTaleItem(
                        id = href,
                        title = title,
                        isCategory = true,
                        artworkUrl = imageUrl(el.selectFirst("img")),
                        durationMin = parseDurationMinutes(el.text())
                    )
                )
            }
        }
        return list
    }

    /**
     * Záloha: libovolný odkaz na detail pohádky (/audioskazki/<sekce>/<pohádka>/), který obsahuje obrázek.
     * Funguje na úvodní stránce, katalogu i na věkových stránkách.
     */
    private fun parseCatalogFallback(doc: Document): List<DetiTaleItem> {
        val list = mutableListOf<DetiTaleItem>()
        for (a in doc.select("a[href*=/audioskazki/]:has(img)")) {
            val href = absolute(a.attr("href")).substringBefore('#')
            if (!isTaleLink(href)) continue
            val img = a.selectFirst("img")
            val title = stripDuration(a.text().ifEmpty { img?.attr("alt").orEmpty() })
            if (title.isEmpty()) continue
            list.add(
                DetiTaleItem(
                    id = href,
                    title = title,
                    isCategory = true,
                    artworkUrl = imageUrl(img),
                    durationMin = parseDurationMinutes(a.text())
                        ?: parseDurationMinutes(containerText(a))
                )
            )
        }
        return list
    }

    /** Věkové filtry ("pro 2-3 roky" apod.): odkazy /audioskazki/dlya-.../ - věk se čte z čísel v adrese. */
    private fun parseAgeLinks(doc: Document): List<DetiAgeLink> {
        val prefix = "$BASE_URL/audioskazki/"
        val found = LinkedHashMap<String, DetiAgeLink>()
        for (a in doc.select("a[href*=/audioskazki/dlya-]")) {
            val href = absolute(a.attr("href")).substringBefore('#').substringBefore('?')
            if (!href.startsWith(prefix)) continue
            val slug = href.removePrefix(prefix).trim('/')
            if (slug.isEmpty() || slug.contains('/') || !slug.startsWith("dlya-")) continue
            val numbers = NUMBER_REGEX.findAll(slug).mapNotNull { it.value.toIntOrNull() }.toList()
            if (numbers.isEmpty()) continue
            found.getOrPut(slug) { DetiAgeLink(slug, "$prefix$slug/", numbers[0], numbers.getOrNull(1)) }
        }
        return found.values
            .distinctBy { it.minAge to it.maxAge }
            .sortedWith(compareBy<DetiAgeLink>({ it.minAge }, { it.maxAge ?: Int.MAX_VALUE }))
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

    /** Detail pohádky má v cestě aspoň dva segmenty za /audioskazki/ (sekce + pohádka). */
    private fun isTaleLink(href: String): Boolean {
        val prefix = "$BASE_URL/audioskazki/"
        if (!href.startsWith(prefix)) return false
        val segments = href.removePrefix(prefix).trim('/').split('/')
        return segments.size >= 2 && segments.all { it.isNotEmpty() }
    }

    /** Text nejbližšího <li> (obsahuje i délku), jinak text rodiče odkazu. */
    private fun containerText(a: Element): String {
        var node: Element? = a.parent()
        var depth = 0
        while (node != null && depth < 4) {
            if (node.tagName() == "li") return node.text()
            node = node.parent()
            depth++
        }
        return a.parent()?.text().orEmpty()
    }

    /** "1 ч 10 мин" -> 70, "5 мин" -> 5, "2 ч" -> 120, jinak null. Hledá se kdekoli v textu. */
    private fun parseDurationMinutes(text: String): Int? {
        val hm = HOURS_MIN_REGEX.find(text)
        if (hm != null) {
            val hours = hm.groupValues[1].toIntOrNull() ?: 0
            val minutes = hm.groupValues[2].toIntOrNull() ?: 0
            return hours * 60 + minutes
        }
        return MINUTES_REGEX.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** Odstraní z názvu případný text délky ("127 ч 20 мин"), který je v odkazu spolu s obrázkem. */
    private fun stripDuration(text: String): String =
        text.replace(HOURS_MIN_REGEX, " ")
            .replace(MINUTES_REGEX, " ")
            .replace(SPACES_REGEX, " ")
            .trim()

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
