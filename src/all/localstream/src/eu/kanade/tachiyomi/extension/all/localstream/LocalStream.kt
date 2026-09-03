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
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class LocalStream : ParsedHttpSource(), ConfigurableSource {

    override val name = "LocalStream"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // Pastikan base URL selalu BERSIH tanpa trailing slash ganda
    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, "http://127.0.0.1:8080")?.trimEnd('/') ?: "http://127.0.0.1:8080"

    private val defaultCoverName: String
        get() = preferences.getString(COVER_NAME_PREF, "cover.jpg")?.trim() ?: "cover.jpg"

    // ─── Client: disetel untuk server lokal/LAN yang cepat ───────────────────
    // - Timeout dipersingkat karena server biasanya ada di localhost/LAN, jadi
    //   kalau server mati sebaiknya gagal cepat, bukan menggantung lama.
    // - maxRequestsPerHost dinaikkan: SEMUA request menuju host yang sama
    //   (server lokal), padahal default OkHttp cuma izinkan 5 request paralel
    //   per host. Itu bikin grid cover (mis. 24 thumbnail sekaligus) antre
    //   dan terasa lambat padahal servernya lokal & sanggup lebih cepat.
    override val client: OkHttpClient = network.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 32
            },
        )
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            var response = chain.proceed(originalRequest)

            val urlString = originalRequest.url.toString()
            val coverBase = defaultCoverName.substringBeforeLast(".").trim()

            if (response.code == 404 && urlString.contains("/$coverBase.", ignoreCase = true)) {
                val currentExtOriginal = urlString.substringAfterLast(".").lowercase(Locale.ROOT)
                for (ext in COVER_FALLBACK_EXTENSIONS) {
                    if (ext == currentExtOriginal) continue
                    response.close()

                    val newUrl = urlString.substringBeforeLast(".") + "." + ext
                    val newRequest = originalRequest.newBuilder().url(newUrl).build()
                    response = chain.proceed(newRequest)

                    if (response.isSuccessful) break
                }
            }
            response
        }
        .build()

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Server URL (Round Sync / Termux)"
            summary = "Masukkan URL lokal dari Rclone Serve."
            setDefaultValue("http://127.0.0.1:8080")
        }
        screen.addPreference(baseUrlPref)

        val coverNamePref = EditTextPreference(screen.context).apply {
            key = COVER_NAME_PREF
            title = "Nama File Cover Default"
            summary = "Nama dasar cover (Contoh: cover.jpg)."
            setDefaultValue("cover.jpg")
        }
        screen.addPreference(coverNamePref)
    }

    // ─── Cache ringan untuk listing direktori & ComicInfo.xml ────────────────
    // Kenapa perlu: Mihon sering memanggil ulang popular/search/chapter list
    // untuk URL yang PERSIS SAMA (mis. ganti halaman hanya mengubah #fragment,
    // bukan URL asli yang dikirim ke server; buka detail manga lalu kembali;
    // refresh library). Tanpa cache, semua itu memicu request+parse HTML dari
    // nol setiap kali. TTL pendek (1 menit) menjaga data tetap relevan tapi
    // menghapus kerja berulang yang sia-sia.
    private data class CachedDoc(val document: Document, val fetchedAt: Long)
    private val documentCache = ConcurrentHashMap<String, CachedDoc>()

    private data class CachedComicInfo(val xml: String, val fetchedAt: Long)
    private val comicInfoCache = ConcurrentHashMap<String, CachedComicInfo>()

    private fun isFresh(fetchedAt: Long): Boolean =
        System.currentTimeMillis() - fetchedAt < CACHE_TTL_MS

    /** Dipakai saat kita BELUM punya Response siap pakai (mis. cek subfolder ComicInfo.xml). */
    private fun fetchDocument(url: String): Document {
        documentCache[url]?.let { cached -> if (isFresh(cached.fetchedAt)) return cached.document }
        val document = client.newCall(GET(url, headers)).execute().asJsoup()
        documentCache[url] = CachedDoc(document, System.currentTimeMillis())
        return document
    }

    /**
     * Dipakai saat framework SUDAH mengeksekusi network call dan memberi kita Response
     * (popularMangaParse/searchMangaParse/chapterListParse/pageListParse). Kalau cache
     * masih segar, kita buang response yang baru (tutup koneksinya) dan pakai hasil parse
     * lama supaya tidak membangun ulang DOM tree dari HTML yang identik.
     */
    private fun resolveDocument(response: Response): Document {
        val key = response.request.url.toString().substringBefore("#")
        documentCache[key]?.let { cached ->
            if (isFresh(cached.fetchedAt)) {
                response.close()
                return cached.document
            }
        }
        val document = response.asJsoup()
        documentCache[key] = CachedDoc(document, System.currentTimeMillis())
        return document
    }

    // ─── Helpers: Text Cleaner (Pembersih &amp;) ─────────────────────────────

    /**
     * Membersihkan nama dari kode HTML (seperti &amp;) yang dihasilkan server
     * dan menghapus garis miring di akhir judul.
     */
    private fun String.cleanTitle(): String {
        val trimmed = this.trim().removeSuffix("/")
        // Fast path: kalau tidak ada "&" sama sekali, tidak perlu 6x replace().
        if (trimmed.indexOf('&') == -1) return trimmed
        return trimmed
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&#39;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    // ─── Helpers: URL Sanitizer ──────────────────────────────────────────────

    private fun getSafeAbsoluteUrl(element: Element): String {
        val rawHref = element.attr("href")
        if (rawHref.isBlank()) return ""

        val decodedHref = Uri.decode(rawHref)
        val safeHref = Uri.encode(decodedHref, "/:")

        element.attr("href", safeHref)
        val absUrl = element.absUrl("href")

        return if (absUrl.isNotBlank()) absUrl else {
            val sep = if (baseUrl.endsWith("/") || safeHref.startsWith("/")) "" else "/"
            "$baseUrl$sep$safeHref"
        }
    }

    // ─── Hierarchy Validation (Pencegah Breadcrumb & Folder Kosong) ──────────
    // LinkEntry menyimpan absUrl yang SUDAH dihitung sekali. Sebelumnya
    // getSafeAbsoluteUrl(element) dipanggil sekali untuk memfilter, lalu
    // dipanggil LAGI di pemanggil (popularMangaParse, chapterListParse, dst)
    // untuk membangun hasil akhir — dua kali kerja Uri.decode/encode per
    // elemen. Untuk folder berisi ratusan entri, itu langsung dua kali lipat
    // biaya parsing tanpa manfaat. Sekarang dihitung sekali dan dipakai ulang.
    private data class LinkEntry(val element: Element, val text: String, val absUrl: String)

    private fun getValidLinkEntries(
        document: Document,
        requireText: Boolean = true,
        condition: ((String) -> Boolean)? = null,
    ): List<LinkEntry> {
        val baseUri = document.baseUri().substringBefore("#").substringBefore("?")
        val decodedBaseUri = Uri.decode(baseUri)
        val parentPath = if (decodedBaseUri.endsWith("/")) decodedBaseUri else "$decodedBaseUri/"

        val entries = ArrayList<LinkEntry>()
        for (element in document.select("a[href]")) {
            val text = element.text().trim()
            if (requireText && text.isEmpty()) continue

            val absUrl = getSafeAbsoluteUrl(element)
            if (absUrl.isBlank()) continue

            val cleanAbsUrl = absUrl.substringBefore("#").substringBefore("?")
            val decodedAbsUrl = Uri.decode(cleanAbsUrl)

            val isChild = decodedAbsUrl.startsWith(parentPath, ignoreCase = true) && decodedAbsUrl.length > parentPath.length
            if (!isChild) continue

            if (condition != null && !condition(decodedAbsUrl.lowercase(Locale.ROOT))) continue

            entries.add(LinkEntry(element, text, absUrl))
        }
        return entries
    }

    // ─── Helpers: Natural Sort (kunci sort dihitung sekali di depan) ─────────
    // Sebelumnya sortedWith { comparator.compare(a.text(), b.text()) } memanggil
    // tokenize() — yang menjalankan regex — pada KEDUA operand di SETIAP
    // perbandingan, jadi total pemanggilan regex ada di kisaran O(n log n).
    // Dengan menghitung token setiap elemen sekali di awal (Schwartzian
    // transform), regex hanya dijalankan O(n) kali. Untuk folder dengan
    // banyak chapter/halaman, ini yang paling terasa di responsivitas.
    private fun tokenize(s: String): List<String> =
        Regex("(\\d+|\\D+)").findAll(s).map { it.value }.toList()

    private fun compareTokens(tokensA: List<String>, tokensB: List<String>): Int {
        val len = minOf(tokensA.size, tokensB.size)
        for (i in 0 until len) {
            val ta = tokensA[i]
            val tb = tokensB[i]
            val cmp = if (ta.first().isDigit() && tb.first().isDigit()) {
                ta.toBigInteger().compareTo(tb.toBigInteger())
            } else {
                ta.lowercase().compareTo(tb.lowercase())
            }
            if (cmp != 0) return cmp
        }
        return tokensA.size.compareTo(tokensB.size)
    }

    private fun List<LinkEntry>.naturalSorted(): List<LinkEntry> =
        this.map { it to tokenize(it.text) }
            .sortedWith { (_, ta), (_, tb) -> compareTokens(ta, tb) }
            .map { it.first }

    // ─── Helpers: ComicInfo Metadata Parser ──────────────────────────────────

    /** Cache per-manga: membuka detail manga berulang kali tidak lagi memicu ulang 1-2 request. */
    private fun getComicInfoXml(document: Document, cacheKey: String): String {
        comicInfoCache[cacheKey]?.let { cached -> if (isFresh(cached.fetchedAt)) return cached.xml }
        val xml = resolveComicInfoXml(document)
        comicInfoCache[cacheKey] = CachedComicInfo(xml, System.currentTimeMillis())
        return xml
    }

    private fun resolveComicInfoXml(document: Document): String {
        val comicInfoLink = getValidLinkEntries(document, requireText = false) {
            it.endsWith("comicinfo.xml")
        }.firstOrNull()

        if (comicInfoLink != null) {
            return downloadUrlContent(comicInfoLink.absUrl)
        }

        val subFolder = getValidLinkEntries(document) { it.endsWith("/") }.firstOrNull()
        if (subFolder != null) {
            try {
                val subDoc = fetchDocument(subFolder.absUrl)
                val subComicInfoLink = getValidLinkEntries(subDoc, requireText = false) {
                    it.endsWith("comicinfo.xml")
                }.firstOrNull()

                if (subComicInfoLink != null) {
                    return downloadUrlContent(subComicInfoLink.absUrl)
                }
            } catch (e: Exception) { }
        }
        return ""
    }

    private fun downloadUrlContent(url: String): String {
        return try {
            client.newCall(GET(url, headers)).execute().body?.string() ?: ""
        } catch (e: Exception) { "" }
    }

    private fun extractXmlTag(xml: String, tag: String): String? {
        val regex = Regex(
            "<(?:\\w+:)?$tag(?:\\s+[^>]*)?>(.*?)</(?:\\w+:)?$tag>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        return regex.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseComicInfo(xml: String, manga: SManga) {
        val series = extractXmlTag(xml, "Series")
        val summary = extractXmlTag(xml, "Summary")?.cleanTitle()

        val writer = extractXmlTag(xml, "Writer")
        val penciller = extractXmlTag(xml, "Penciller")
        val publisher = extractXmlTag(xml, "Publisher")
        val statusStr = extractXmlTag(xml, "PublishingStatusTachiyomi") ?: extractXmlTag(xml, "Status")
        val genre = extractXmlTag(xml, "Genre")
        val categories = extractXmlTag(xml, "Categories")

        if (!series.isNullOrBlank()) {
            manga.title = series.cleanTitle()
        }
        if (!summary.isNullOrBlank()) {
            manga.description = summary
        }

        val authorsList = listOfNotNull(writer, penciller, publisher).distinct()
        manga.author = authorsList.joinToString(" | ").takeIf { it.isNotBlank() } ?: "Unknown"
        manga.artist = penciller ?: writer ?: "Unknown"

        val allGenres = listOfNotNull(genre, categories)
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()

        if (allGenres.isNotEmpty()) {
            manga.genre = allGenres.joinToString(", ")
        }

        manga.status = when (statusStr?.lowercase(Locale.ROOT)) {
            "ongoing", "1" -> SManga.ONGOING
            "completed", "end", "2" -> SManga.COMPLETED
            "cancelled", "abandoned", "6" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    // ─── Browse / Popular ────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/#page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val fragment = response.request.url.fragment ?: ""
        val currentPage = fragment.substringAfter("page=").toIntOrNull() ?: 1

        val document = resolveDocument(response)
        val allEntries = getValidLinkEntries(document) { it.endsWith("/") }

        return buildMangasPage(allEntries, currentPage)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/#query=${query.lowercase(Locale.ROOT)}&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val fragment = response.request.url.fragment ?: ""
        val query = fragment.substringAfter("query=").substringBefore("&page=").lowercase(Locale.ROOT)
        val currentPage = fragment.substringAfter("page=").toIntOrNull() ?: 1

        val document = resolveDocument(response)
        val filteredEntries = getValidLinkEntries(document) { it.endsWith("/") }
            .filter { it.text.cleanTitle().lowercase(Locale.ROOT).contains(query) }

        return buildMangasPage(filteredEntries, currentPage)
    }

    private fun buildMangasPage(entries: List<LinkEntry>, currentPage: Int): MangasPage {
        val itemsPerPage = 24
        val startIndex = (currentPage - 1) * itemsPerPage
        if (startIndex >= entries.size) return MangasPage(emptyList(), false)

        val endIndex = minOf(startIndex + itemsPerPage, entries.size)
        val pagedEntries = entries.subList(startIndex, endIndex)

        val mangas = pagedEntries.map { entry ->
            SManga.create().apply {
                title = entry.text.cleanTitle()

                val relPath = entry.absUrl.removePrefix(baseUrl)
                url = if (relPath.startsWith("/")) relPath else "/$relPath"

                val cleanAbsUrl = entry.absUrl.removeSuffix("/")
                thumbnail_url = "$cleanAbsUrl/$defaultCoverName"
            }
        }

        return MangasPage(mangas, endIndex < entries.size)
    }

    // ─── Manga Details ───────────────────────────────────────────────────────

    override fun mangaDetailsParse(document: Document): SManga {
        val imageLinks = getValidLinkEntries(document, requireText = false) { href ->
            IMAGE_EXTENSIONS.any { href.endsWith(it) }
        }

        val coverEntry = imageLinks.find { entry ->
            val href = entry.element.attr("href").lowercase(Locale.ROOT)
            href.contains("cover") || href.contains("poster") || href.contains("thumb")
        } ?: imageLinks.firstOrNull()

        val manga = SManga.create().apply {
            description = "Folder manga lokal dari Server LocalStream (WebDAV/HTTP)."
            status = SManga.UNKNOWN
            if (coverEntry != null) {
                thumbnail_url = coverEntry.absUrl
            }
            initialized = true
        }

        val comicInfoXml = getComicInfoXml(document, document.baseUri())
        if (comicInfoXml.isNotBlank()) {
            parseComicInfo(comicInfoXml, manga)
        }

        return manga
    }

    // ─── Chapter List ────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = resolveDocument(response)

        val chapterEntries = getValidLinkEntries(document) { href ->
            href.endsWith("/") || href.endsWith(".cbz") || href.endsWith(".zip")
        }.naturalSorted()

        return chapterEntries.mapIndexed { index, entry ->
            SChapter.create().apply {
                name = entry.text.cleanTitle()

                val relPath = entry.absUrl.removePrefix(baseUrl)
                url = if (relPath.startsWith("/")) relPath else "/$relPath"

                chapter_number = (index + 1).toFloat()
            }
        }.asReversed()
    }

    // ─── Page List ───────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = resolveDocument(response)

        val pageEntries = getValidLinkEntries(document, requireText = false) { href ->
            PAGE_IMAGE_EXTENSIONS.any { href.endsWith(it) }
        }.naturalSorted()

        return pageEntries.mapIndexed { index, entry -> Page(index, "", entry.absUrl) }
    }

    // ─── Unused / Overrides ──────────────────────────────────────────────────
    override fun popularMangaSelector(): String = ""
    override fun popularMangaFromElement(element: Element): SManga = SManga.create()
    override fun popularMangaNextPageSelector(): String? = null
    override fun searchMangaSelector(): String = ""
    override fun searchMangaFromElement(element: Element): SManga = SManga.create()
    override fun searchMangaNextPageSelector(): String? = null
    override fun chapterFromElement(element: Element): SChapter = SChapter.create()
    override fun chapterListSelector(): String = ""
    override fun imageUrlParse(document: Document): String = ""
    override fun pageListParse(document: Document): List<Page> = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesSelector(): String = ""
    override fun latestUpdatesFromElement(element: Element): SManga = SManga.create()
    override fun latestUpdatesNextPageSelector(): String? = null

    companion object {
        private const val BASE_URL_PREF = "BASE_URL_PREF_LOCALSTREAM_MANGA"
        private const val COVER_NAME_PREF = "COVER_NAME_PREF_LOCALSTREAM_MANGA"

        // Berapa lama listing direktori / ComicInfo.xml dianggap "masih segar"
        // sebelum diambil ulang dari server. 60 detik cukup untuk menghapus
        // hampir semua request berulang dalam satu sesi browsing, tapi tetap
        // pendek supaya perubahan file di server lokal cepat terlihat.
        private const val CACHE_TTL_MS = 60_000L

        private val COVER_FALLBACK_EXTENSIONS = listOf("jpg", "jpeg", "png", "webp", "avif", "gif")
        private val IMAGE_EXTENSIONS = listOf(".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif", ".heic", ".jxl")
        private val PAGE_IMAGE_EXTENSIONS = listOf(".jpg", ".png", ".jpeg", ".webp", ".avif", ".gif", ".heic", ".jxl", ".bmp")
    }
}
