package eu.kanade.tachiyomi.extension.all.localstream

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.math.BigInteger

class LocalStream : ParsedHttpSource(), ConfigurableSource {

    override val name = "LocalStream"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, "http://127.0.0.1:8080")
            ?.removeSuffix("/")
            ?: "http://127.0.0.1:8080"

    private val defaultCoverName: String
        get() = preferences.getString(COVER_NAME_PREF, "cover.jpg") ?: "cover.jpg"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Server URL"
            summary = "Masukkan URL lokal dari server file kamu."
            setDefaultValue("http://127.0.0.1:8080")
        }
        screen.addPreference(baseUrlPref)

        val coverNamePref = EditTextPreference(screen.context).apply {
            key = COVER_NAME_PREF
            title = "Nama File Cover Default"
            summary = "Harus sama persis dengan nama file di dalam folder."
            setDefaultValue("cover.jpg")
        }
        screen.addPreference(coverNamePref)
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun naturalSortComparator(): Comparator<String> = Comparator { a, b ->
        val tokensA = tokenize(a)
        val tokensB = tokenize(b)
        val len = minOf(tokensA.size, tokensB.size)

        for (i in 0 until len) {
            val ta = tokensA[i]
            val tb = tokensB[i]
            val cmp = if (ta.firstOrNull()?.isDigit() == true && tb.firstOrNull()?.isDigit() == true) {
                ta.toBigInteger().compareTo(tb.toBigInteger())
            } else {
                ta.lowercase().compareTo(tb.lowercase())
            }
            if (cmp != 0) return@Comparator cmp
        }

        tokensA.size.compareTo(tokensB.size)
    }

    private fun tokenize(s: String): List<String> =
        Regex("(\\d+|\\D+)").findAll(s).map { it.value }.toList()

    private fun looksLikeParentLink(href: String): Boolean {
        val normalized = href.trim().lowercase()
        return normalized == ".." || normalized == "../" || normalized.contains("/../")
    }

    private fun lastPathSegment(raw: String): String {
        val cleaned = raw.substringBefore('?').substringBefore('#').trimEnd('/')
        return cleaned.substringAfterLast('/')
    }

    private fun looksLikeImage(href: String): Boolean {
        val name = lastPathSegment(href).lowercase()
        return name.endsWith(".jpg") ||
            name.endsWith(".jpeg") ||
            name.endsWith(".png") ||
            name.endsWith(".webp") ||
            name.endsWith(".avif") ||
            name.endsWith(".gif") ||
            name.endsWith(".heic") ||
            name.endsWith(".jxl") ||
            name.endsWith(".bmp")
    }

    private fun looksLikeArchive(href: String): Boolean {
        val name = lastPathSegment(href).lowercase()
        return name.endsWith(".cbz") || name.endsWith(".zip")
    }

    private fun encodePathSegments(path: String): String {
        val trimmed = path.trim().trim('/')
        if (trimmed.isEmpty()) return ""

        return trimmed
            .split('/')
            .filter { it.isNotEmpty() }
            .joinToString("/") { segment ->
                val decoded = runCatching { Uri.decode(segment) }.getOrDefault(segment)
                Uri.encode(decoded)
            }
    }

    private fun folderPathFromHref(href: String): String {
        val raw = href.trim()
        if (raw.isEmpty()) return ""

        val pathPart = when {
            raw.startsWith("http://") || raw.startsWith("https://") -> {
                raw.toHttpUrlOrNull()?.encodedPath ?: raw
            }
            else -> raw
        }

        return encodePathSegments(pathPart)
    }

    private fun buildFolderUrl(href: String): String {
        val path = folderPathFromHref(href)
        return if (path.isEmpty()) "/" else "/$path"
    }

    private fun buildThumbnailUrl(folderUrl: String): String {
        val folderPath = folderUrl.trim().trim('/')
        val coverFile = Uri.encode(defaultCoverName.trim())

        return if (folderPath.isEmpty()) {
            "$baseUrl/$coverFile"
        } else {
            "$baseUrl/$folderPath/$coverFile"
        }
    }

    private fun sanitizeAbsoluteUrl(url: String): String {
        val parsed = url.toHttpUrlOrNull()
        return parsed?.toString() ?: url
            .replace(" ", "%20")
            .replace("'", "%27")
            .replace("\"", "%22")
    }

    private fun buildMangaFromElement(element: Element): SManga {
        val folderUrl = buildFolderUrl(element.attr("href"))

        val thumbnailFromListing = element.selectFirst("img[src]")
            ?.absUrl("src")
            ?.takeIf { it.isNotBlank() }

        return SManga.create().apply {
            title = element.text().removeSuffix("/").trim()
            url = folderUrl
            thumbnail_url = thumbnailFromListing ?: buildThumbnailUrl(folderUrl)
        }
    }

    // ─── Browse / Popular ────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("a[href]")
            .filter { element ->
                val href = element.attr("href")
                !looksLikeParentLink(href) && !looksLikeImage(href) && !looksLikeArchive(href)
            }
            .map { buildMangaFromElement(it) }

        return MangasPage(mangas, false)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/#query=${Uri.encode(query.lowercase())}", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val query = response.request.url.fragment?.removePrefix("query=")?.lowercase().orEmpty()

        val mangas = document.select("a[href]")
            .filter { element ->
                val href = element.attr("href")
                !looksLikeParentLink(href) && !looksLikeImage(href) && !looksLikeArchive(href)
            }
            .filter { it.text().lowercase().contains(query) }
            .map { buildMangaFromElement(it) }

        return MangasPage(mangas, false)
    }

    // ─── Manga Details ───────────────────────────────────────────────────────

    override fun mangaDetailsParse(document: Document): SManga {
        val mediaUrls = buildList {
            document.select("a[href]").forEach { el ->
                val href = el.attr("href")
                if (looksLikeImage(href)) add(el.absUrl("href"))
            }
            document.select("img[src]").forEach { el ->
                val src = el.attr("src")
                if (looksLikeImage(src)) add(el.absUrl("src"))
            }
        }

        val coverUrl = mediaUrls.firstOrNull { url ->
            val lower = url.lowercase()
            lower.contains("cover") || lower.contains("poster") || lower.contains("thumb")
        } ?: mediaUrls.firstOrNull()

        return SManga.create().apply {
            description = "Folder manga lokal dari Server LocalStream."
            status = SManga.UNKNOWN

            if (coverUrl != null) {
                thumbnail_url = sanitizeAbsoluteUrl(coverUrl)
            }

            initialized = true
        }
    }

    // ─── Chapter List ────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()

        val chapterElements = document.select("a[href]")
            .filter { element ->
                val href = element.attr("href")
                !looksLikeParentLink(href) &&
                    !looksLikeImage(href) &&
                    (href.endsWith("/") || !looksLikeArchive(href))
            }

        val comparator = naturalSortComparator()
        val sortedElements = chapterElements.sortedWith { a, b ->
            comparator.compare(a.text(), b.text())
        }

        return sortedElements.mapIndexed { index, element ->
            val chapterUrl = buildFolderUrl(element.attr("href"))

            SChapter.create().apply {
                name = element.text().removeSuffix("/").trim()
                url = chapterUrl
                chapter_number = (index + 1).toFloat()
            }
        }.reversed()
    }

    // ─── Page List ───────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()

        val pageElements = document.select("a[href]")
            .filter { element ->
                val href = element.attr("href")
                !looksLikeParentLink(href) && looksLikeImage(href)
            }

        val comparator = naturalSortComparator()
        val sortedPages = pageElements.sortedWith { a, b ->
            comparator.compare(a.text(), b.text())
        }

        return sortedPages.mapIndexed { index, element ->
            val absUrl = element.absUrl("href")
            val validUrl = sanitizeAbsoluteUrl(absUrl)
            Page(index, "", validUrl)
        }
    }

    // ─── Abstract Methods ────────────────────────────────────────────────────

    override fun popularMangaSelector(): String = ""
    override fun popularMangaFromElement(element: Element): SManga = SManga.create()
    override fun popularMangaNextPageSelector(): String? = null

    override fun searchMangaSelector(): String = ""
    override fun searchMangaFromElement(element: Element): SManga = SManga.create()
    override fun searchMangaNextPageSelector(): String? = null

    override fun chapterFromElement(element: Element): SChapter = SChapter.create()
    override fun chapterListSelector(): String = ""

    override fun imageUrlParse(document: Document): String = ""
    override fun pageListParse(document: Document): List<Page> =
        throw UnsupportedOperationException("Not used")

    override fun latestUpdatesRequest(page: Int): Request =
        throw UnsupportedOperationException("Not used")
    override fun latestUpdatesSelector(): String = ""
    override fun latestUpdatesFromElement(element: Element): SManga = SManga.create()
    override fun latestUpdatesNextPageSelector(): String? = null

    companion object {
        private const val BASE_URL_PREF = "BASE_URL_PREF_LOCALSTREAM_MANGA"
        private const val COVER_NAME_PREF = "COVER_NAME_PREF_LOCALSTREAM_MANGA"
    }
}