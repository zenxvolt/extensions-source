package eu.kanade.tachiyomi.extension.all.localstream

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import eu.kanade.tachiyomi.util.asJsoup
import okhttp3.CacheControl
import okhttp3.Dispatcher
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.asResponseBody
import okio.buffer
import okio.source
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class LocalStream : HttpSource(), ConfigurableSource {

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

    /** Jumlah halaman yang di-prefetch di SETIAP arah (sebelum & sesudah). 0 = nonaktif. */
    private val prefetchRadius: Int
        get() = preferences.getString(PREFETCH_PREF, DEFAULT_PREFETCH_RADIUS.toString())
            ?.trim()
            ?.toIntOrNull()
            ?.coerceIn(0, MAX_PREFETCH_RADIUS)
            ?: DEFAULT_PREFETCH_RADIUS

    /** true = cek isi folder tiap chapter saat daftar chapter dimuat, supaya gambar baru terdeteksi. */
    private val detectChanges: Boolean
        get() = preferences.getBoolean(DETECT_CHANGES_PREF, true)

    /**
     * Sumber judul entri:
     * - comicinfo: <Series> di ComicInfo.xml menimpa nama folder (perilaku lama).
     * - folder   : selalu nama folder.
     * - auto     : nama folder diprioritaskan. Pengecualian: <Series> dipakai kalau memuat simbol
     *              khusus (yang tidak bisa ada di nama folder) DAN cocok dengan nama folder
     *              selain simbolnya. Kalau tidak cocok, <Series> dianggap usang dan nama folder
     *              yang dipakai.
     */
    private val titleSource: String
        get() = preferences.getString(TITLE_SOURCE_PREF, TITLE_SOURCE_AUTO) ?: TITLE_SOURCE_AUTO

    // ─── Client: disetel untuk server lokal/LAN yang cepat ───────────────────
    // - Timeout dipersingkat karena server biasanya ada di localhost/LAN, jadi
    //   kalau server mati sebaiknya gagal cepat, bukan menggantung lama.
    // - maxRequestsPerHost dinaikkan: SEMUA request menuju host yang sama
    //   (server lokal), padahal default OkHttp cuma izinkan 5 request paralel
    //   per host. Itu bikin grid cover (mis. 24 thumbnail sekaligus) antre
    //   dan terasa lambat padahal servernya lokal & sanggup lebih cepat.
    private val baseClient: OkHttpClient = network.client.newBuilder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .dispatcher(
            Dispatcher().apply {
                maxRequests = 64
                maxRequestsPerHost = 32
            },
        )
        .build()

    // ─── Prefetch halaman ────────────────────────────────────────────────────
    // Reader Mihon hanya preload beberapa halaman ke depan dan mengunduhnya
    // satu per satu; itu tidak bisa diubah dari extension. Jadi prefetch
    // dilakukan di lapisan OkHttp: begitu reader meminta halaman N, kita
    // unduh N±radius secara PARALEL ke cache disk. Saat reader meminta
    // halaman tetangga, jawabannya langsung dari disk (hampir instan).
    //
    // Client prefetch dipisah (tanpa interceptor prefetch) supaya request
    // prefetch tidak memicu prefetch lagi (rekursi).
    private val prefetcher: PagePrefetcher by lazy {
        PagePrefetcher(
            client = baseClient.newBuilder().cache(null).build(),
            headersProvider = { headers },
            cacheDirProvider = { Injekt.get<Application>().cacheDir },
        )
    }

    override val client: OkHttpClient = baseClient.newBuilder()
        .addInterceptor { chain -> coverFallback(chain) }
        .addInterceptor { chain -> prefetcher.intercept(chain, prefetchRadius) }
        .build()

    // ─── Fallback ekstensi cover ─────────────────────────────────────────────
    // Thumbnail selalu diminta sebagai "<folder>/<nama cover>" (mis. cover.jpg). Kalau file
    // aslinya png/webp, server menjawab 404 dan kita mencoba ekstensi lain. Dulu itu terjadi
    // lagi di SETIAP thumbnail, SETIAP kali grid dimuat. Sekarang ekstensi yang berhasil
    // diingat per folder dan dicoba duluan, jadi 404 dan percobaan ulangnya hilang.
    //
    // Hanya folder yang pernah butuh fallback yang diingat, dan hanya request yang nama
    // filenya persis nama cover yang disentuh, jadi halaman chapter bernama "cover.jpg"
    // tidak ikut tertukar.
    private val coverExtCache = ConcurrentHashMap<String, String>()

    private fun coverFallback(chain: Interceptor.Chain): Response {
        val request = chain.request()

        val path = request.url.encodedPath
        val slash = path.lastIndexOf('/')
        val fileName = path.substring(slash + 1)
        val dot = fileName.lastIndexOf('.')
        val coverBase = defaultCoverName.substringBeforeLast(".").trim()
        if (dot <= 0 || !fileName.substring(0, dot).equals(coverBase, ignoreCase = true)) {
            return chain.proceed(request)
        }

        val dirPath = path.substring(0, slash + 1)
        val fileBase = fileName.substring(0, dot)
        val requestedExt = fileName.substring(dot + 1).lowercase(Locale.ROOT)
        val folderKey = request.url.toString().substringBeforeLast('/')

        // Urutan coba: ekstensi yang dulu berhasil, ekstensi yang diminta, lalu sisanya.
        val candidates = LinkedHashSet<String>()
        coverExtCache[folderKey]?.let { candidates.add(it) }
        candidates.add(requestedExt)
        candidates.addAll(COVER_FALLBACK_EXTENSIONS)

        var last: Response? = null
        for (ext in candidates) {
            last?.close()
            val attempt = if (ext == requestedExt) {
                request
            } else {
                request.newBuilder()
                    .url(request.url.newBuilder().encodedPath("$dirPath$fileBase.$ext").build())
                    .build()
            }
            val response = chain.proceed(attempt)

            if (response.isSuccessful) {
                if (ext == requestedExt) {
                    coverExtCache.remove(folderKey)
                } else {
                    if (coverExtCache.size >= MAX_COVER_EXT_CACHE) coverExtCache.clear()
                    coverExtCache[folderKey] = ext
                }
                return response
            }
            // Selain 404 (server error, dll.) bukan soal ekstensi, jadi jangan dicoba ulang.
            if (response.code != 404) return response
            last = response
        }

        coverExtCache.remove(folderKey)
        return last!!
    }

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Server URL (Round Sync / Termux)"
            summary = "Enter the local URL of your Rclone Serve instance."
            setDefaultValue("http://127.0.0.1:8080")
        }
        screen.addPreference(baseUrlPref)

        val coverNamePref = EditTextPreference(screen.context).apply {
            key = COVER_NAME_PREF
            title = "Default Cover File Name"
            summary = "Base name of the cover file (e.g. cover.jpg)."
            setDefaultValue("cover.jpg")
        }
        screen.addPreference(coverNamePref)

        val prefetchPref = EditTextPreference(screen.context).apply {
            key = PREFETCH_PREF
            title = "Page Prefetch (before & after)"
            summary = "Number of pages around the current page that are downloaded in parallel " +
                "(0 = disabled, max $MAX_PREFETCH_RADIUS). Default: $DEFAULT_PREFETCH_RADIUS."
            setDefaultValue(DEFAULT_PREFETCH_RADIUS.toString())
        }
        screen.addPreference(prefetchPref)

        val detectChangesPref = SwitchPreferenceCompat(screen.context).apply {
            key = DETECT_CHANGES_PREF
            title = "Detect new pages in chapters"
            summary = "When the chapter list is refreshed, each chapter folder is checked so added or " +
                "removed images are picked up right away. Turn this off if your library is large and " +
                "refreshing feels slow."
            setDefaultValue(true)
        }
        screen.addPreference(detectChangesPref)

        val titleSourcePref = ListPreference(screen.context).apply {
            key = TITLE_SOURCE_PREF
            title = "Entry Title Source"
            entries = arrayOf(
                "ComicInfo first (<Series> overrides the folder name)",
                "Folder name only",
                "Automatic (folder name, unless the title has special symbols)",
            )
            entryValues = arrayOf(TITLE_SOURCE_COMICINFO, TITLE_SOURCE_FOLDER, TITLE_SOURCE_AUTO)
            setDefaultValue(TITLE_SOURCE_AUTO)
            summary = "%s"
        }
        screen.addPreference(titleSourcePref)
    }

    // ─── Cache ringan untuk listing direktori & ComicInfo.xml ────────────────
    // Kenapa perlu: Mihon sering memanggil ulang popular/search/chapter list
    // untuk URL yang PERSIS SAMA (mis. ganti halaman hanya mengubah #fragment,
    // bukan URL asli yang dikirim ke server; buka detail manga lalu kembali;
    // refresh library). Tanpa cache, semua itu memicu request+parse HTML dari
    // nol setiap kali. TTL pendek (1 menit) menjaga data tetap relevan tapi
    // menghapus kerja berulang yang sia-sia.
    // [folders] = hasil getValidLinkEntries untuk subfolder di dokumen ini, dihitung sekali
    // saat pertama dibutuhkan lalu dipakai ulang oleh semua halaman Browse/Search. Dokumen
    // yang sama dipakai selama masih segar, jadi hasil ini ikut basi bersamanya.
    private class CachedDoc(val document: Document, val fetchedAt: Long) {
        @Volatile
        var folders: List<LinkEntry>? = null
    }
    private val documentCache = ConcurrentHashMap<String, CachedDoc>()

    private data class CachedComicInfo(val xml: String, val fetchedAt: Long)
    private val comicInfoCache = ConcurrentHashMap<String, CachedComicInfo>()

    /**
     * <Series> per folder, used for titles in Browse/Search. Persisted (so it survives the app
     * being killed) and valid for [TITLE_CACHE_TTL_MS]. Stored as "<epochMillis>|<series>".
     * An empty series means "no <Series> found"; that is only kept for [TITLE_CACHE_MISS_TTL_MS],
     * so a server that failed for a moment doesn't leave folder names in the list for long.
     */
    private class CachedSeries(val series: String?)

    private val seriesPrefs: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_series_titles", 0x0000)
    }

    private val seriesPruned = AtomicBoolean(false)

    private fun readSeriesCache(key: String): CachedSeries? {
        val raw = seriesPrefs.getString(key, null) ?: return null
        val fetchedAt = raw.substringBefore('|').toLongOrNull() ?: return null
        val age = System.currentTimeMillis() - fetchedAt
        val series = raw.substringAfter('|').takeIf { it.isNotEmpty() }
        val ttl = if (series != null) TITLE_CACHE_TTL_MS else TITLE_CACHE_MISS_TTL_MS
        if (age < 0 || age >= ttl) return null
        return CachedSeries(series)
    }

    private fun writeSeriesCache(key: String, series: String?) {
        seriesPrefs.edit().putString(key, "${System.currentTimeMillis()}|${series.orEmpty()}").apply()
    }

    /** Once per app process: remove expired entries so the file doesn't keep growing. */
    private fun pruneSeriesCacheOnce() {
        if (!seriesPruned.compareAndSet(false, true)) return
        val now = System.currentTimeMillis()
        val expired = seriesPrefs.all.filter { (_, value) ->
            val fetchedAt = (value as? String)?.substringBefore('|')?.toLongOrNull()
            val age = if (fetchedAt == null) -1L else now - fetchedAt
            fetchedAt == null || age < 0 || age >= TITLE_CACHE_TTL_MS
        }.keys
        if (expired.isEmpty()) return
        val editor = seriesPrefs.edit()
        expired.forEach { editor.remove(it) }
        editor.apply()
    }

    /** Same key for the same folder, whether it comes from the browse list or the details page. */
    private fun cacheKeyFor(url: String): String {
        val clean = url.substringBefore("#")
        return clean.toHttpUrlOrNull()?.toString() ?: clean
    }

    /** Folder documents are cached per manga now, so drop expired entries to keep memory bounded. */
    private fun evictStaleCaches() {
        documentCache.values.removeIf { !isFresh(it.fetchedAt) }
        comicInfoCache.values.removeIf { !isFresh(it.fetchedAt) }
        pruneSeriesCacheOnce()
    }

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
     * Dipakai untuk listing root (popularMangaParse/searchMangaParse). Daftar chapter dan
     * daftar halaman SENGAJA tidak lewat sini: keduanya harus selalu segar. Kalau cache
     * masih segar, kita buang response yang baru (tutup koneksinya) dan pakai hasil parse
     * lama supaya tidak membangun ulang DOM tree dari HTML yang identik.
     */
    private fun resolveDocument(response: Response): CachedDoc {
        val key = response.request.url.toString().substringBefore("#")
        documentCache[key]?.let { cached ->
            if (isFresh(cached.fetchedAt)) {
                response.close()
                return cached
            }
        }
        val fresh = CachedDoc(response.asJsoup(), System.currentTimeMillis())
        documentCache[key] = fresh
        return fresh
    }

    /**
     * Subfolder di listing root, dengan hasil parse yang ikut di-cache bersama dokumennya.
     * Sebelumnya setiap ganti halaman Browse/Search menjalankan ulang Uri.decode/encode untuk
     * SELURUH entri (ratusan, bahkan ribuan) hanya untuk mengambil 24 di antaranya.
     */
    private fun resolveFolderEntries(response: Response): List<LinkEntry> {
        val cached = resolveDocument(response)
        cached.folders?.let { return it }
        val entries = getValidLinkEntries(cached.document) { it.endsWith("/") }
        cached.folders = entries
        return entries
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
    // tokenize() pada KEDUA operand di SETIAP perbandingan. Dengan menghitung token
    // setiap elemen sekali di awal (Schwartzian transform), tokenisasi hanya berjalan
    // O(n) kali. Untuk folder dengan banyak chapter/halaman, ini yang paling terasa.
    //
    // Tokenizer ditulis manual (tanpa Regex) dan token teks sudah di-lowercase di sini,
    // jadi perbandingan tidak lagi membuat objek baru. Angka dibandingkan sebagai string
    // (buang nol di depan, bandingkan panjang, lalu isi) tanpa BigInteger; hasilnya sama.
    //
    // Hanya digit ASCII (0-9) yang dianggap angka, sama seperti \d di regex sebelumnya.
    // Memakai Char.isDigit() akan menganggap "１" (fullwidth) sebagai angka dan
    // toBigInteger() pada token seperti "１話" melempar NumberFormatException.
    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    private fun tokenize(s: String): List<String> {
        val tokens = ArrayList<String>()
        var start = 0
        while (start < s.length) {
            val digit = isAsciiDigit(s[start])
            var end = start + 1
            while (end < s.length && isAsciiDigit(s[end]) == digit) end++
            val piece = s.substring(start, end)
            tokens.add(if (digit) piece else piece.lowercase(Locale.ROOT))
            start = end
        }
        return tokens
    }

    private fun compareNumeric(a: String, b: String): Int {
        val na = a.trimStart('0')
        val nb = b.trimStart('0')
        if (na.length != nb.length) return na.length.compareTo(nb.length)
        return na.compareTo(nb)
    }

    private fun compareTokens(tokensA: List<String>, tokensB: List<String>): Int {
        val len = minOf(tokensA.size, tokensB.size)
        for (i in 0 until len) {
            val ta = tokensA[i]
            val tb = tokensB[i]
            val cmp = if (isAsciiDigit(ta[0]) && isAsciiDigit(tb[0])) {
                compareNumeric(ta, tb)
            } else {
                ta.compareTo(tb)
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

    // Satu ComicInfo dibaca ±10 tag dan 24 entri per halaman Browse; Regex dibangun sekali per tag.
    private val tagRegexCache = ConcurrentHashMap<String, Regex>()

    private fun extractXmlTag(xml: String, tag: String): String? {
        if (xml.isEmpty()) return null
        val regex = tagRegexCache.getOrPut(tag) {
            Regex(
                "<(?:\\w+:)?$tag(?:\\s+[^>]*)?>(.*?)</(?:\\w+:)?$tag>",
                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
            )
        }
        return regex.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    /** Judul TIDAK diatur di sini; lihat [resolveTitle] yang dipanggil dari mangaDetailsParse. */
    private fun parseComicInfo(xml: String, manga: SManga) {
        val summary = extractXmlTag(xml, "Summary")?.cleanTitle()

        val writer = extractXmlTag(xml, "Writer")
        val penciller = extractXmlTag(xml, "Penciller")
        val publisher = extractXmlTag(xml, "Publisher")
        val statusStr = extractXmlTag(xml, "PublishingStatusTachiyomi") ?: extractXmlTag(xml, "Status")
        val genre = extractXmlTag(xml, "Genre")
        val categories = extractXmlTag(xml, "Categories")

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

        return buildMangasPage(resolveFolderEntries(response), currentPage)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/#query=${query.lowercase(Locale.ROOT)}&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val fragment = response.request.url.fragment ?: ""
        val query = fragment.substringAfter("query=").substringBefore("&page=").lowercase(Locale.ROOT)
        val currentPage = fragment.substringAfter("page=").toIntOrNull() ?: 1

        val filteredEntries = filterByQuery(resolveFolderEntries(response), query)

        return buildMangasPage(filteredEntries, currentPage)
    }

    /**
     * Matches the query against entry names. Besides a plain "contains" on the folder name, both
     * sides are normalized (NFKC, lowercase, letters/digits only), so "Re:Zero" or "Re：Zero" still
     * finds the folder "Re Zero". This is the same rule "Automatic" mode uses to decide that
     * <Series> and the folder name are the same title, so whatever title is shown in the list
     * can also be found by searching for it (this is what library migration relies on).
     * In "ComicInfo first" mode <Series> may differ completely from the folder name, so it is
     * searched too (read in parallel and cached, so large libraries are slower on the first search).
     */
    private fun filterByQuery(entries: List<LinkEntry>, query: String): List<LinkEntry> {
        val rawQuery = query.trim()
        if (rawQuery.isEmpty()) return entries
        val normalizedQuery = normalizeTitle(rawQuery)

        fun matches(name: String): Boolean =
            name.lowercase(Locale.ROOT).contains(rawQuery) ||
                (normalizedQuery.isNotEmpty() && normalizeTitle(name).contains(normalizedQuery))

        val matched = BooleanArray(entries.size) { i ->
            val entry = entries[i]
            matches(folderTitleFromUrl(entry.absUrl) ?: entry.text.cleanTitle())
        }

        if (titleSource == TITLE_SOURCE_COMICINFO) {
            val pending = entries.indices.filter { !matched[it] }
            if (pending.isNotEmpty()) {
                val seriesTitles = fetchSeriesTitles(pending.map { entries[it] })
                pending.forEachIndexed { i, index ->
                    val series = seriesTitles[i] ?: return@forEachIndexed
                    if (matches(series)) matched[index] = true
                }
            }
        }

        // Keep the original (listing) order.
        return entries.filterIndexed { i, _ -> matched[i] }
    }

    private fun buildMangasPage(entries: List<LinkEntry>, currentPage: Int): MangasPage {
        val itemsPerPage = 24
        val startIndex = (currentPage - 1) * itemsPerPage
        if (startIndex >= entries.size) return MangasPage(emptyList(), false)

        val endIndex = minOf(startIndex + itemsPerPage, entries.size)
        val pagedEntries = entries.subList(startIndex, endIndex)

        // Resolve titles with the same rules as the details page ("Entry Title Source").
        evictStaleCaches()
        val seriesTitles = fetchSeriesTitles(pagedEntries)
        warmNextPage(entries, endIndex, itemsPerPage)

        val mangas = pagedEntries.mapIndexed { index, entry ->
            SManga.create().apply {
                val folderName = folderTitleFromUrl(entry.absUrl) ?: entry.text.cleanTitle()
                title = resolveTitle(seriesTitles[index], folderName) ?: folderName

                val relPath = entry.absUrl.removePrefix(baseUrl)
                url = if (relPath.startsWith("/")) relPath else "/$relPath"

                val cleanAbsUrl = entry.absUrl.removeSuffix("/")
                thumbnail_url = "$cleanAbsUrl/$defaultCoverName"
            }
        }

        return MangasPage(mangas, endIndex < entries.size)
    }

    // ─── Manga Details ───────────────────────────────────────────────────────

    override fun mangaDetailsParse(response: Response): SManga {
        val document = response.asJsoup()

        val imageLinks = getValidLinkEntries(document, requireText = false) { href ->
            IMAGE_EXTENSIONS.any { href.endsWith(it) }
        }

        val coverEntry = imageLinks.find { entry ->
            val href = entry.element.attr("href").lowercase(Locale.ROOT)
            href.contains("cover") || href.contains("poster") || href.contains("thumb")
        } ?: imageLinks.firstOrNull()

        val manga = SManga.create().apply {
            description = "No description"
            status = SManga.UNKNOWN
            if (coverEntry != null) {
                thumbnail_url = coverEntry.absUrl
            }
            initialized = true
        }

        val comicInfoXml = getComicInfoXml(document, cacheKeyFor(document.baseUri()))
        if (comicInfoXml.isNotBlank()) {
            parseComicInfo(comicInfoXml, manga)
        }

        // Judul ditentukan sesuai pengaturan "Sumber judul entri". Kalau hasilnya null (mode
        // comicinfo tanpa <Series>), judul tidak dikirim dan aplikasi mempertahankan judul lamanya.
        val series = if (comicInfoXml.isNotBlank()) extractXmlTag(comicInfoXml, "Series")?.cleanTitle() else null
        resolveTitle(series, folderTitle(document))?.let { manga.title = it }

        return manga
    }

    /** Nama folder entri, diambil dari segmen terakhir URL halaman detail. */
    private fun folderTitle(document: Document): String? = folderTitleFromUrl(document.baseUri())

    /** Same derivation for browse and details, so both always produce an identical folder name. */
    private fun folderTitleFromUrl(url: String): String? {
        val path = url.substringBefore("#").substringBefore("?").trimEnd('/')
        return Uri.decode(path.substringAfterLast('/')).cleanTitle().takeIf { it.isNotBlank() }
    }

    private fun resolveTitle(series: String?, folderName: String?): String? = when (titleSource) {
        TITLE_SOURCE_FOLDER -> folderName
        TITLE_SOURCE_AUTO -> when {
            series != null && folderName != null &&
                hasSpecialChars(series) && isSameTitle(series, folderName) -> series
            else -> folderName ?: series
        }
        else -> series
    }

    /** true jika ada karakter selain huruf, angka, dan spasi (mis. : ? / ! ' . ～). */
    private fun hasSpecialChars(s: String): Boolean = s.any { !it.isLetterOrDigit() && !it.isWhitespace() }

    /**
     * Nama folder tidak boleh memuat sebagian simbol (: ? * " < > | /), jadi <Series> yang benar
     * bisa berbeda dari nama folder HANYA di simbol/spasi/huruf besar-kecil. Kedua judul
     * dibandingkan setelah NFKC (mis. "：" lebar-penuh menjadi ":") dan setelah membuang semua
     * karakter selain huruf/angka.
     */
    private fun isSameTitle(a: String, b: String): Boolean {
        val normalizedA = normalizeTitle(a)
        // Judul yang seluruhnya simbol jadi kosong setelah normalisasi; jangan dianggap cocok.
        return normalizedA.isNotEmpty() && normalizedA == normalizeTitle(b)
    }

    private fun normalizeTitle(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKC)
            .lowercase(Locale.ROOT)
            .filter { it.isLetterOrDigit() }

    // ─── Titles in Browse / Search ───────────────────────────────────────────
    // The directory listing only knows folder names. To match the details page, the
    // <Series> of every entry on the current page is fetched in parallel (and cached).
    // "Folder name only" mode needs no extra requests.

    private val titleExecutor: ExecutorService by lazy {
        ThreadPoolExecutor(
            TITLE_PARALLELISM,
            TITLE_PARALLELISM,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(),
            ThreadFactory { runnable -> Thread(runnable, "LocalStream-title").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }
    }

    // Titles of the NEXT page are read in the background (a few at a time, so covers and the
    // current page aren't starved), so scrolling down usually finds them already cached.
    private val titleWarmExecutor: ExecutorService by lazy {
        ThreadPoolExecutor(
            TITLE_WARM_PARALLELISM,
            TITLE_WARM_PARALLELISM,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(),
            ThreadFactory { runnable -> Thread(runnable, "LocalStream-title-warm").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }
    }

    private fun warmNextPage(entries: List<LinkEntry>, from: Int, count: Int) {
        if (titleSource == TITLE_SOURCE_FOLDER || from >= entries.size) return
        entries.subList(from, minOf(from + count, entries.size))
            .map { it.absUrl }
            .forEach { url -> titleWarmExecutor.execute { fetchSeriesTitle(url) } }
    }

    private fun fetchSeriesTitles(entries: List<LinkEntry>): List<String?> {
        if (titleSource == TITLE_SOURCE_FOLDER) return List(entries.size) { null }

        val futures = titleExecutor.invokeAll(
            entries.map { entry -> Callable { fetchSeriesTitle(entry.absUrl) } },
        )
        return futures.map { future ->
            try {
                future.get()
            } catch (e: Exception) {
                null
            }
        }
    }

    private fun fetchSeriesTitle(folderUrl: String): String? {
        val key = cacheKeyFor(folderUrl)
        readSeriesCache(key)?.let { return it.series }

        val series = try {
            val xml = comicInfoCache[key]?.takeIf { isFresh(it.fetchedAt) }?.xml
                ?: getComicInfoXml(client.newCall(GET(folderUrl, headers)).execute().asJsoup(), key)
            extractXmlTag(xml, "Series")?.cleanTitle()
        } catch (e: Exception) {
            return null // transient failure: fall back to the folder name, but don't cache it
        }
        writeSeriesCache(key, series)
        return series
    }

    // ─── Deteksi perubahan isi chapter ───────────────────────────────────────
    // Masalah: Mihon menyimpan daftar halaman chapter di ChapterCache dengan kunci
    // "mangaId + chapter.url", lalu membacanya LEBIH DULU sebelum bertanya ke
    // extension. Selama chapter.url tidak berubah, extension tidak pernah ditanya
    // lagi, jadi gambar baru di folder tidak akan muncul (sampai cache dibersihkan).
    //
    // Solusi: hitung "sidik jari" (hash nama file gambar) tiap folder chapter.
    // Begitu isinya berubah, URL chapter ikut berubah: "/Manga/Ch1/#v=<sidik jari>".
    // Fragment tidak dikirim ke server, tapi kunci cache Mihon jadi berbeda sehingga
    // daftar halaman diambil ulang. Di sisi Mihon, chapter lama dan baru dengan nomor
    // yang sama dianggap SATU chapter yang sama: status baca & bookmark dipertahankan
    // dan tidak memicu notifikasi "chapter baru".
    //
    // "Baseline": sidik jari pertama yang pernah terlihat per chapter disimpan, dan
    // selama isi folder sama dengan baseline, URL dibiarkan polos (tanpa #v=). Jadi
    // chapter yang sudah ada di library TIDAK berganti URL saat extension ini
    // diperbarui (kalau berganti, riwayat baca semuanya ikut terhapus oleh Mihon).

    private val fingerprintPrefs: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_${id}_chapter_fingerprints", 0x0000)
    }

    /** Sidik jari terakhir yang berhasil dihitung; dipakai kalau satu folder gagal dicek sesaat. */
    private val lastSeenFingerprint = ConcurrentHashMap<String, String>()

    private val fingerprintExecutor: ExecutorService by lazy {
        ThreadPoolExecutor(
            FINGERPRINT_PARALLELISM,
            FINGERPRINT_PARALLELISM,
            30L,
            TimeUnit.SECONDS,
            LinkedBlockingQueue<Runnable>(),
            ThreadFactory { runnable -> Thread(runnable, "LocalStream-fingerprint").apply { isDaemon = true } },
        ).apply { allowCoreThreadTimeOut(true) }
    }

    private fun LinkEntry.relativePath(): String {
        val relPath = absUrl.removePrefix(baseUrl)
        return if (relPath.startsWith("/")) relPath else "/$relPath"
    }

    /** Hash nama-nama file gambar di folder chapter. Selalu ke jaringan (tanpa cache). */
    private fun fetchFolderFingerprint(folderUrl: String): String? = try {
        client.newCall(GET(folderUrl, headers, CacheControl.FORCE_NETWORK)).execute().use { response ->
            if (!response.isSuccessful) return@use null

            val names = getValidLinkEntries(response.asJsoup(), requireText = false) { href ->
                PAGE_IMAGE_EXTENSIONS.any { href.endsWith(it) }
            }
                .map { Uri.decode(it.absUrl.substringBefore("#").substringBefore("?")).substringAfterLast('/') }
                .sorted()

            md5Hex(names.joinToString("\n")).take(FINGERPRINT_LENGTH)
        }
    } catch (e: Exception) {
        null
    }

    /**
     * Peta absUrl chapter → token versi. Hanya berisi chapter yang isinya SUDAH berbeda
     * dari baseline; chapter yang tidak berubah tidak dapat token (URL tetap polos).
     * Folder dicek paralel (maks [FINGERPRINT_PARALLELISM] sekaligus).
     */
    private fun chapterVersionTokens(entries: List<LinkEntry>): Map<String, String> {
        // Arsip (.cbz/.zip) tidak punya isi folder yang bisa dicek.
        val folders = entries.filter { it.absUrl.substringBefore("#").substringBefore("?").endsWith("/") }
        if (folders.isEmpty()) return emptyMap()

        val results = fingerprintExecutor.invokeAll(
            folders.map { entry -> Callable { fetchFolderFingerprint(entry.absUrl) } },
        )

        val tokens = HashMap<String, String>()
        val newBaselines = HashMap<String, String>()

        folders.forEachIndexed { i, entry ->
            val path = entry.relativePath()
            val fresh = try {
                results[i].get()
            } catch (e: Exception) {
                null
            }
            val fingerprint = fresh ?: lastSeenFingerprint[path] ?: return@forEachIndexed
            lastSeenFingerprint[path] = fingerprint

            val baseline = fingerprintPrefs.getString(path, null)
            if (baseline == null) {
                newBaselines[path] = fingerprint
            } else if (baseline != fingerprint) {
                tokens[entry.absUrl] = fingerprint
            }
        }

        if (newBaselines.isNotEmpty()) {
            val editor = fingerprintPrefs.edit()
            newBaselines.forEach { (key, value) -> editor.putString(key, value) }
            editor.apply()
        }
        return tokens
    }

    // ─── Chapter List ────────────────────────────────────────────────────────

    // FORCE_NETWORK: GET() bawaan Mihon mengizinkan respons cache OkHttp sampai 10 menit.
    override fun chapterListRequest(manga: SManga): Request =
        GET(baseUrl + manga.url, headers, CacheControl.FORCE_NETWORK)

    override fun chapterListParse(response: Response): List<SChapter> {
        // Sengaja TIDAK lewat resolveDocument(): daftar chapter harus selalu segar.
        val document = response.asJsoup()

        val chapterEntries = getValidLinkEntries(document) { href ->
            href.endsWith("/") || href.endsWith(".cbz") || href.endsWith(".zip")
        }.naturalSorted()

        val tokens = if (detectChanges) chapterVersionTokens(chapterEntries) else emptyMap()

        return chapterEntries.mapIndexed { index, entry ->
            SChapter.create().apply {
                name = entry.text.cleanTitle()

                val path = entry.relativePath()
                url = tokens[entry.absUrl]?.let { "$path#v=$it" } ?: path

                chapter_number = (index + 1).toFloat()
            }
        }.asReversed()
    }

    // ─── Page List ───────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request =
        GET(baseUrl + chapter.url, headers, CacheControl.FORCE_NETWORK)

    override fun pageListParse(response: Response): List<Page> {
        // Sengaja TIDAK lewat resolveDocument(): jangan pernah memakai daftar halaman basi.
        val document = response.asJsoup()

        val pageEntries = getValidLinkEntries(document, requireText = false) { href ->
            PAGE_IMAGE_EXTENSIONS.any { href.endsWith(it) }
        }.naturalSorted()

        // Daftarkan URUTAN halaman chapter ini supaya interceptor tahu siapa
        // "5 sebelum / 5 sesudah" dari halaman yang sedang diminta reader.
        prefetcher.registerChapter(pageEntries.map { it.absUrl })

        return pageEntries.mapIndexed { index, entry -> Page(index, "", entry.absUrl) }
    }

    // ─── Unused / Overrides ──────────────────────────────────────────────────
    // Halaman sudah membawa URL gambar sendiri (Page.imageUrl), dan supportsLatest = false.
    override fun imageUrlParse(response: Response): String = ""
    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used")

    companion object {
        private const val BASE_URL_PREF = "BASE_URL_PREF_LOCALSTREAM_MANGA"
        private const val COVER_NAME_PREF = "COVER_NAME_PREF_LOCALSTREAM_MANGA"
        private const val PREFETCH_PREF = "PREFETCH_PREF_LOCALSTREAM_MANGA"
        private const val DETECT_CHANGES_PREF = "DETECT_CHANGES_PREF_LOCALSTREAM_MANGA"
        private const val TITLE_SOURCE_PREF = "TITLE_SOURCE_PREF_LOCALSTREAM_MANGA"
        private const val TITLE_SOURCE_COMICINFO = "comicinfo"
        private const val TITLE_SOURCE_FOLDER = "folder"
        private const val TITLE_SOURCE_AUTO = "auto"

        /** Folder chapter yang dicek bersamaan saat daftar chapter dimuat. */
        private const val FINGERPRINT_PARALLELISM = 8
        private const val FINGERPRINT_LENGTH = 10

        /** Entry folders whose ComicInfo.xml is read at the same time (one browse page = 24 entries). */
        private const val TITLE_PARALLELISM = 24

        /** Background readers for the next page's titles. */
        private const val TITLE_WARM_PARALLELISM = 4

        /**
         * How long a <Series> read from ComicInfo.xml is reused for the Browse list (also across
         * app restarts). Editing a ComicInfo.xml shows up in Browse after this long; the details
         * page always re-reads it.
         */
        private const val TITLE_CACHE_TTL_MS = 30 * 60_000L

        /** Same, for folders where no <Series> was found. */
        private const val TITLE_CACHE_MISS_TTL_MS = 5 * 60_000L

        /** Batas jumlah folder yang ekstensi covernya diingat (hanya yang pernah butuh fallback). */
        private const val MAX_COVER_EXT_CACHE = 2000

        private const val DEFAULT_PREFETCH_RADIUS = 5
        private const val MAX_PREFETCH_RADIUS = 15

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

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/**
 * MD5 sebagai 32 karakter hex huruf kecil. Dipakai untuk sidik jari chapter DAN nama file cache
 * prefetch; format keluarannya identik dengan versi lama ("%02x"), jadi baseline sidik jari
 * yang sudah tersimpan tetap cocok.
 */
private fun md5Hex(s: String): String {
    val bytes = MessageDigest.getInstance("MD5").digest(s.toByteArray(Charsets.UTF_8))
    val out = CharArray(bytes.size * 2)
    for (i in bytes.indices) {
        val v = bytes[i].toInt() and 0xFF
        out[i * 2] = HEX_DIGITS[v ushr 4]
        out[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
    }
    return String(out)
}

/**
 * Prefetch halaman manga secara paralel ke cache disk.
 *
 * Alur:
 * 1. pageListParse() memanggil [registerChapter] → kita ingat urutan URL halaman.
 * 2. Reader meminta gambar halaman N → [intercept] mengenali URL itu, lalu
 *    mengantrekan unduhan N+1..N+radius dan N-1..N-radius ke thread pool
 *    (maks [PARALLELISM] unduhan bersamaan; arah depan didahulukan).
 * 3. Saat reader meminta halaman yang sudah/sedang di-prefetch:
 *    - sudah selesai  → dilayani langsung dari disk (tanpa jaringan),
 *    - sedang jalan   → ditunggu (tidak mengunduh dua kali),
 *    - masih antre    → antrean dibatalkan, request asli langsung jalan.
 *
 * Kenapa bukan OkHttp Cache: Mihon memanggil getImage() lewat
 * newCachelessCallWithProgress() yang memang mematikan cache OkHttp, jadi kita
 * pakai cache sendiri. Salinan di disk dihapus setelah dilayani karena Mihon
 * menyimpan gambar itu di ChapterCache miliknya.
 */
private class PagePrefetcher(
    private val client: OkHttpClient,
    private val headersProvider: () -> Headers,
    private val cacheDirProvider: () -> File,
) {

    private class PageRef(val pages: List<String>, val index: Int) {
        /** true = reader sudah pernah meminta halaman ini (berarti sudah ada di ChapterCache Mihon). */
        @Volatile
        var requested: Boolean = false
    }

    private val refs = ConcurrentHashMap<String, PageRef>()
    private val chapters = LinkedHashMap<String, List<String>>() // dijaga lewat synchronized(chapters)
    private val tasks = ConcurrentHashMap<String, Task>()

    private val executor = ThreadPoolExecutor(
        PARALLELISM,
        PARALLELISM,
        30L,
        TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(),
        ThreadFactory { runnable -> Thread(runnable, "LocalStream-prefetch").apply { isDaemon = true } },
    ).apply { allowCoreThreadTimeOut(true) }

    private val dir: File by lazy { File(cacheDirProvider(), DIR_NAME).also { it.mkdirs() } }

    @Volatile
    private var lastTrimAt = 0L

    // ─── Indeks urutan halaman ───────────────────────────────────────────────

    fun registerChapter(rawUrls: List<String>) {
        // Normalisasi lewat HttpUrl supaya PERSIS sama dengan request.url.toString() di interceptor.
        val urls = rawUrls.map { it.toHttpUrlOrNull()?.toString() ?: it }
        if (urls.isEmpty()) return

        synchronized(chapters) {
            chapters.remove(urls.first())?.forEach { refs.remove(it) }
            chapters[urls.first()] = urls
            urls.forEachIndexed { i, u -> refs[u] = PageRef(urls, i) }

            // Batasi memori: simpan indeks beberapa chapter terakhir saja.
            while (chapters.size > MAX_INDEXED_CHAPTERS) {
                val eldest = chapters.keys.first()
                chapters.remove(eldest)?.forEach { refs.remove(it) }
            }
        }
    }

    // ─── Interceptor ─────────────────────────────────────────────────────────

    fun intercept(chain: Interceptor.Chain, radius: Int): Response {
        val request = chain.request()
        if (radius <= 0) return chain.proceed(request)

        val url = request.url.toString()
        // Bukan halaman chapter (cover, listing, dll.) → lewat tanpa overhead.
        val ref = refs[url] ?: return chain.proceed(request)

        ref.requested = true
        // Antrekan tetangga DULU, supaya berjalan paralel dengan unduhan halaman ini.
        scheduleNeighbors(ref, radius)

        return serveFromPrefetch(request, url) ?: chain.proceed(request)
    }

    private fun neighborUrls(ref: PageRef, radius: Int): List<String> {
        val result = ArrayList<String>(radius * 2)

        fun add(i: Int) {
            val u = ref.pages.getOrNull(i) ?: return
            if (refs[u]?.requested == true) return // sudah pernah diminta reader
            result.add(u)
        }

        for (d in 1..radius) add(ref.index + d) // arah baca dominan: ke depan dulu
        for (d in 1..radius) add(ref.index - d)
        return result
    }

    private fun scheduleNeighbors(ref: PageRef, radius: Int) {
        val targets = neighborUrls(ref, radius)
        val wanted = HashSet(targets)

        // User melompat jauh → buang antrean yang sudah tidak relevan (yang sudah jalan dibiarkan).
        for ((queuedUrl, task) in tasks) {
            if (queuedUrl !in wanted) task.cancelIfQueued()
        }

        for (target in targets) {
            if (fileFor(target).exists()) continue
            val task = Task(target)
            if (tasks.putIfAbsent(target, task) == null) {
                try {
                    executor.execute(task)
                } catch (e: RejectedExecutionException) {
                    tasks.remove(target, task)
                }
            }
        }

        trimIfNeeded()
    }

    private fun serveFromPrefetch(request: Request, url: String): Response? {
        tasks[url]?.let { task ->
            // Belum mulai → batalkan, request asli langsung ke jaringan (lebih cepat daripada antre).
            // Sedang berjalan → tunggu hasilnya, jangan unduh dua kali.
            if (!task.cancelIfQueued()) task.await(WAIT_RUNNING_SECONDS)
        }

        val file = fileFor(url)
        if (!file.isFile) return null

        if (System.currentTimeMillis() - file.lastModified() > FILE_TTL_MS) {
            file.delete()
            return null
        }

        val length = file.length()
        val body = try {
            file.inputStream().source().buffer().asResponseBody(mediaTypeFor(url), length)
        } catch (e: IOException) {
            return null
        }
        // Aman di Linux/Android: file yang sudah dibuka tetap terbaca meski di-unlink.
        // Mihon menyimpan gambar ke ChapterCache sendiri, jadi salinan ini tidak diperlukan lagi.
        file.delete()

        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }

    // ─── Unduhan ─────────────────────────────────────────────────────────────

    private inner class Task(val url: String) : Runnable {
        private val state = AtomicInteger(QUEUED)
        private val done = CountDownLatch(1)

        /** true jika berhasil dibatalkan (belum sempat jalan). */
        fun cancelIfQueued(): Boolean {
            if (!state.compareAndSet(QUEUED, CANCELLED)) return false
            tasks.remove(url, this)
            done.countDown()
            return true
        }

        fun await(seconds: Long) {
            try {
                done.await(seconds, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        override fun run() {
            if (!state.compareAndSet(QUEUED, RUNNING)) return // sudah dibatalkan
            try {
                download(url)
            } catch (e: Exception) {
                // Prefetch itu best-effort: kalau gagal, reader akan mengunduh sendiri.
            } finally {
                state.set(DONE)
                tasks.remove(url, this)
                done.countDown()
            }
        }
    }

    private fun download(url: String) {
        val target = fileFor(url)
        if (target.exists()) return
        val tmp = File(target.parentFile, target.name + ".part")

        try {
            client.newCall(GET(url, headersProvider())).execute().use { response ->
                if (!response.isSuccessful) return
                val body = response.body ?: return
                tmp.outputStream().use { out -> body.byteStream().copyTo(out, 64 * 1024) }
            }
            // Rename atomik: reader tidak pernah melihat file yang setengah jadi.
            if (!tmp.renameTo(target)) tmp.delete()
        } catch (e: IOException) {
            tmp.delete()
        }
    }

    // ─── Cache disk ──────────────────────────────────────────────────────────

    private fun fileFor(url: String): File = File(dir, md5Hex(url))

    private fun mediaTypeFor(url: String) =
        when (url.substringBefore('?').substringAfterLast('.').lowercase(Locale.ROOT)) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "avif" -> "image/avif"
            "heic" -> "image/heic"
            "jxl" -> "image/jxl"
            "bmp" -> "image/bmp"
            else -> null
        }?.toMediaTypeOrNull()

    /** Hapus file basi & jaga total ukuran folder prefetch di bawah batas (dibatasi tiap 30 detik). */
    private fun trimIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastTrimAt < TRIM_INTERVAL_MS) return
        lastTrimAt = now

        val files = dir.listFiles() ?: return
        val alive = ArrayList<File>(files.size)
        var total = 0L
        for (f in files) {
            if (now - f.lastModified() > FILE_TTL_MS) {
                f.delete()
            } else {
                alive.add(f)
                total += f.length()
            }
        }
        if (total <= MAX_DISK_BYTES) return

        alive.sortBy { it.lastModified() }
        for (f in alive) {
            if (total <= MAX_DISK_BYTES) break
            total -= f.length()
            f.delete()
        }
    }

    private companion object {
        const val DIR_NAME = "localstream_prefetch"

        /** Jumlah unduhan prefetch yang berjalan bersamaan. */
        const val PARALLELISM = 5

        /** Indeks urutan halaman disimpan untuk N chapter terakhir saja. */
        const val MAX_INDEXED_CHAPTERS = 4

        const val MAX_DISK_BYTES = 150L * 1024 * 1024
        const val FILE_TTL_MS = 10 * 60_000L
        const val TRIM_INTERVAL_MS = 30_000L

        /** Batas tunggu jika halaman yang diminta reader sedang diunduh oleh prefetch. */
        const val WAIT_RUNNING_SECONDS = 20L

        const val QUEUED = 0
        const val RUNNING = 1
        const val DONE = 2
        const val CANCELLED = 3
    }
}
