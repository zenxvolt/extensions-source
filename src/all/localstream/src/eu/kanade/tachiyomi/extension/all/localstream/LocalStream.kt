package eu.kanade.tachiyomi.extension.all.localstream

import android.app.Application
import android.content.SharedPreferences
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

class LocalStream : ParsedHttpSource(), ConfigurableSource {

    override val name = "LocalStream"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, "http://127.0.0.1:8080")?.removeSuffix("/")
            ?: "http://127.0.0.1:8080"

    private val defaultCoverName: String
        get() = preferences.getString(COVER_NAME_PREF, "cover.jpg") ?: "cover.jpg"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Server URL (Round Sync / Termux)"
            summary = "Masukkan URL lokal dari server file kamu."
            setDefaultValue("http://127.0.0.1:8080")
        }
        screen.addPreference(baseUrlPref)

        val coverNamePref = EditTextPreference(screen.context).apply {
            key = COVER_NAME_PREF
            title = "Nama File Cover Default"
            summary = "Harus sama persis dengan nama file cover di folder."
            setDefaultValue("cover.jpg")
        }
        screen.addPreference(coverNamePref)
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

    // Mengambil relative path yang aman tanpa menghilangkan struktur folder
    private fun getRelativePath(element: Element): String {
        val absUrl = element.absUrl("href")
        return if (absUrl.startsWith(baseUrl)) {
            absUrl.removePrefix(baseUrl)
        } else {
            absUrl.toHttpUrlOrNull()?.encodedPath ?: element.attr("href")
        }
    }

    // Membangun URL cover secara aman tanpa double-encoding
    private fun buildThumbnailUrl(mangaUrl: String): String {
        val folderUrl = (baseUrl + mangaUrl).trimEnd('/')
        val url = folderUrl.toHttpUrlOrNull() ?: return "$folderUrl/$defaultCoverName"
        return url.newBuilder().addPathSegment(defaultCoverName.trim()).build().toString()
    }

    // Cache Regex di luar fungsi agar tidak membebani performa saat sorting
    private val tokenRegex = Regex("(\\d+|\\D+)")

    private fun tokenize(s: String): List<String> =
        tokenRegex.findAll(s).map { it.value }.toList()

    private fun compareTokens(tokensA: List<String>, tokensB: List<String>): Int {
        val len = minOf(tokensA.size, tokensB.size)
        for (i in 0 until len) {
            val ta = tokensA[i]
            val tb = tokensB[i]
            val cmp = if (ta.firstOrNull()?.isDigit() == true && tb.firstOrNull()?.isDigit() == true) {
                ta.toBigInteger().compareTo(tb.toBigInteger())
            } else {
                ta.compareTo(tb, ignoreCase = true)
            }
            if (cmp != 0) return cmp
        }
        return tokensA.size.compareTo(tokensB.size)
    }

    // ─── Browse / Popular ───────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()

        val mangas = document.select("a[href]")
            .filter { !it.attr("href").contains("..") && it.attr("href").endsWith("/") }
            .map { element ->
                val relativePath = getRelativePath(element)

                SManga.create().apply {
                    title = element.text().removeSuffix("/")
                    url = relativePath
                    thumbnail_url = buildThumbnailUrl(relativePath)
                }
            }

        return MangasPage(mangas, false)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        // OkHttp HttpUrl akan meng-handle parameter query dengan aman
        val url = baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("search", query.lowercase())
            ?.build()?.toString() ?: "$baseUrl/?search=$query"
            
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val query = response.request.url.queryParameter("search")?.lowercase().orEmpty()

        val mangas = document.select("a[href]")
            .filter { !it.attr("href").contains("..") && it.attr("href").endsWith("/") }
            .filter { it.text().lowercase().contains(query) }
            .map { element ->
                val relativePath = getRelativePath(element)

                SManga.create().apply {
                    title = element.text().removeSuffix("/")
                    url = relativePath
                    thumbnail_url = buildThumbnailUrl(relativePath)
                }
            }

        return MangasPage(mangas, false)
    }

    // ─── Manga Details ───────────────────────────────────────────────────────

    override fun mangaDetailsParse(document: Document): SManga {
        val links = document.select("a[href]")
        val imageLinks = links.filter {
            val href = it.attr("href").lowercase()
            !href.contains("..") && (
                href.endsWith(".jpg") || href.endsWith(".jpeg") ||
                href.endsWith(".png") || href.endsWith(".webp") ||
                href.endsWith(".avif") || href.endsWith(".gif") ||
                href.endsWith(".heic") || href.endsWith(".jxl")
            )
        }

        val coverElement = imageLinks.find {
            val href = it.attr("href").lowercase()
            href.contains("cover") || href.contains("poster") || href.contains("thumb")
        } ?: imageLinks.firstOrNull()

        return SManga.create().apply {
            description = "Folder manga lokal dari Server LocalStream."
            status = SManga.UNKNOWN

            if (coverElement != null) {
                // Jsoup absUrl sudah cukup kuat tanpa butuh sanitize manual
                thumbnail_url = coverElement.absUrl("href")
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
                val href = element.attr("href").lowercase()
                !href.contains("..") && (href.endsWith("/") || href.endsWith(".cbz") || href.endsWith(".zip"))
            }

        // Optimasi sorting agar tidak memakan CPU / menyebabkan ngelag
        val sortedElements = chapterElements
            .map { it to tokenize(it.text()) }
            .sortedWith { a, b -> compareTokens(a.second, b.second) }
            .map { it.first }

        return sortedElements.mapIndexed { index, element ->
            SChapter.create().apply {
                name = element.text().removeSuffix("/")
                url = getRelativePath(element) // Sekarang parent folder akan dipertahankan
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
                val href = element.attr("href").lowercase()
                val isImage = href.endsWith(".jpg") || href.endsWith(".png") ||
                    href.endsWith(".jpeg") || href.endsWith(".webp") ||
                    href.endsWith(".avif") || href.endsWith(".gif") ||
                    href.endsWith(".heic") || href.endsWith(".jxl") ||
                    href.endsWith(".bmp")
                !href.contains("..") && isImage
            }

        // Optimasi sorting untuk page list
        val sortedPages = pageElements
            .map { it to tokenize(it.text()) }
            .sortedWith { a, b -> compareTokens(a.second, b.second) }
            .map { it.first }

        return sortedPages.mapIndexed { index, element ->
            Page(index, "", element.absUrl("href"))
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
    override fun pageListParse(document: Document): List<Page> = emptyList()

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesSelector(): String = ""
    override fun latestUpdatesFromElement(element: Element): SManga = SManga.create()
    override fun latestUpdatesNextPageSelector(): String? = null

    companion object {
        private const val BASE_URL_PREF = "BASE_URL_PREF_LOCALSTREAM_MANGA"
        private const val COVER_NAME_PREF = "COVER_NAME_PREF_LOCALSTREAM_MANGA"
    }
}
