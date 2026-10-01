package eu.kanade.tachiyomi.extension.all.localstream

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
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
import java.util.concurrent.atomic.AtomicInteger

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
        .addInterceptor { chain -> prefetcher.intercept(chain, prefetchRadius) }
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

        val prefetchPref = EditTextPreference(screen.context).apply {
            key = PREFETCH_PREF
            title = "Prefetch Halaman (sebelum & sesudah)"
            summary = "Jumlah halaman di sekitar halaman aktif yang diunduh paralel " +
                "(0 = nonaktif, maks $MAX_PREFETCH_RADIUS). Default: $DEFAULT_PREFETCH_RADIUS."
            setDefaultValue(DEFAULT_PREFETCH_RADIUS.toString())
        }
        screen.addPreference(prefetchPref)

        val detectChangesPref = SwitchPreferenceCompat(screen.context).apply {
            key = DETECT_CHANGES_PREF
            title = "Deteksi halaman baru di chapter"
            summary = "Saat daftar chapter dimuat ulang, isi tiap folder chapter dicek supaya gambar " +
                "yang ditambah/dihapus langsung terbaca. Matikan jika library besar dan refresh terasa lambat."
            setDefaultValue(true)
        }
        screen.addPreference(detectChangesPref)
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
     * Dipakai untuk listing root (popularMangaParse/searchMangaParse). Daftar chapter dan
     * daftar halaman SENGAJA tidak lewat sini: keduanya harus selalu segar. Kalau cache
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

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
        private const val PREFETCH_PREF = "PREFETCH_PREF_LOCALSTREAM_MANGA"
        private const val DETECT_CHANGES_PREF = "DETECT_CHANGES_PREF_LOCALSTREAM_MANGA"

        /** Folder chapter yang dicek bersamaan saat daftar chapter dimuat. */
        private const val FINGERPRINT_PARALLELISM = 8
        private const val FINGERPRINT_LENGTH = 10

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

    private fun md5Hex(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

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
