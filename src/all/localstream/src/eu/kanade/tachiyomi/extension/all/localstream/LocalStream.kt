package eu.kanade.tachiyomi.extension.all.localstream

import android.app.Application
import android.content.SharedPreferences
import android.net.Uri
import android.util.LruCache
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
import okhttp3.Cache
import okhttp3.CacheControl
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.logging.HttpLoggingInterceptor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

class LocalStream : ParsedHttpSource(), ConfigurableSource {

    override val name = "LocalStream"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    // ─── Caches ──────────────────────────────────────────────────────────────
    
    /** Cache metadata manga agar tidak fetch ComicInfo.xml berulang kali */
    private val mangaMetaCache = LruCache<String, MangaMeta>(50)
    
    /** Cache hasil parsing elemen valid per URL */
    private val elementCache = LruCache<String, List<Element>>(100)
    
    /** Cache daftar chapter per manga URL */
    private val chapterCache = LruCache<String, List<SChapter>>(50)

    // ─── Precompiled Regex & Constants ───────────────────────────────────────
    
    private val htmlEntityRegex = Regex("&(amp|quot|apos|#39|lt|gt);")
    private val tokenizeRegex = Regex("(\\d+|\\D+)")
    private val comicInfoTagRegex = Regex(
        "<(?:\\w+:)?(Series|Summary|Writer|Penciller|Publisher|PublishingStatusTachiyomi|Status|Genre|Categories)(?:\\s+[^>]*)?>(.*?)</(?:\\w+:)?\\1>",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    private val imageExtensions = listOf(".jpg", ".jpeg", ".png", ".webp", ".avif", ".gif", ".heic", ".jxl", ".bmp")
    private val coverExtensions = listOf("jpg", "jpeg", "png", "webp", "avif", "gif")
    
    private val htmlEntityMap = mapOf(
        "amp" to "&",
        "quot" to "\"",
        "apos" to "'",
        "#39" to "'",
        "lt" to "<",
        "gt" to ">"
    )

    // ─── Optimized OkHttpClient ──────────────────────────────────────────────
    
    override val client: OkHttpClient by lazy {
        val cacheDir = File(Injekt.get<Application>().cacheDir, "localstream_cache")
        val cacheSize = 50L * 1024 * 1024 // 50 MB
        
        network.client.newBuilder()
            .cache(Cache(cacheDir, cacheSize))
            .connectionPool(ConnectionPool(10, 2, TimeUnit.MINUTES))
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                // Gunakan cache untuk request GET biasa
                val newRequest = if (request.method == "GET") {
                    request.newBuilder()
                        .cacheControl(CacheControl.Builder().maxStale(1, TimeUnit.HOURS).build())
                        .build()
                } else request
                chain.proceed(newRequest)
            }
            .addInterceptor { chain ->
                val originalRequest = chain.request()
                val urlString = originalRequest.url.toString()
                
                // Fast path: bukan request cover
                val coverBase = defaultCoverName.substringBeforeLast(".")
                if (!urlString.contains("/$coverBase.", ignoreCase = true)) {
                    return@addInterceptor chain.proceed(originalRequest)
                }
                
                var response = chain.proceed(originalRequest)
                if (response.isSuccessful) return@addInterceptor response
                
                // Cover fallback dengan HEAD request (lebih ringan)
                if (response.code == 404) {
                    val currentExt = urlString.substringAfterLast(".").lowercase(Locale.ROOT)
                    val baseUrlNoExt = urlString.substringBeforeLast(".")
                    
                    for (ext in coverExtensions) {
                        if (ext == currentExt) continue
                        response.close()
                        
                        val headUrl = "$baseUrlNoExt.$ext"
                        val headRequest = originalRequest.newBuilder()
                            .url(headUrl)
                            .head()
                            .build()
                        
                        val headResponse = chain.proceed(headRequest)
                        if (headResponse.isSuccessful) {
                            headResponse.close()
                            // Baru fetch dengan GET
                            val getRequest = originalRequest.newBuilder().url(headUrl).build()
                            return@addInterceptor chain.proceed(getRequest)
                        }
                        headResponse.close()
                    }
                }
                response
            }
            .build()
    }

    // ─── Preferences ─────────────────────────────────────────────────────────
    
    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, "http://127.0.0.1:8080")?.trimEnd('/') ?: "http://127.0.0.1:8080"

    private val defaultCoverName: String
        get() = preferences.getString(COVER_NAME_PREF, "cover.jpg") ?: "cover.jpg"

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Server URL (Round Sync / Termux)"
            summary = "Masukkan URL lokal dari Rclone Serve."
            setDefaultValue("http://127.0.0.1:8080")
        })
        screen.addPreference(EditTextPreference(screen.context).apply {
            key = COVER_NAME_PREF
            title = "Nama File Cover Default"
            summary = "Nama dasar cover (Contoh: cover.jpg)."
            setDefaultValue("cover.jpg")
        })
    }

    // ─── Fast String Cleaner ─────────────────────────────────────────────────
    
    private fun String.cleanTitle(): String {
        var result = this.trim().removeSuffix("/")
        // Replace HTML entities menggunakan precompiled regex
        result = htmlEntityRegex.replace(result) { match ->
            htmlEntityMap[match.groupValues[1]] ?: match.value
        }
        return result
    }

    // ─── Optimized URL Helper ────────────────────────────────────────────────
    
    private fun getSafeAbsoluteUrl(element: Element, base: String = baseUrl): String {
        val rawHref = element.attr("href")
        if (rawHref.isBlank()) return ""
        
        val safeHref = Uri.encode(Uri.decode(rawHref), "/:")
        element.attr("href", safeHref)
        
        val absUrl = element.absUrl("href")
        return if (absUrl.isNotBlank()) absUrl else {
            val sep = if (base.endsWith("/") || safeHref.startsWith("/")) "" else "/"
            "$base$sep$safeHref"
        }
    }

    // ─── Optimized Hierarchy Validation ──────────────────────────────────────
    
    private fun getValidChildElements(
        document: Document,
        requireText: Boolean = true,
        condition: ((String) -> Boolean)? = null
    ): List<Element> {
        val cacheKey = "${document.baseUri()}|${requireText}|${condition != null}"
        elementCache.get(cacheKey)?.let { return it }
        
        val baseUri = document.baseUri().substringBefore("#").substringBefore("?")
        val decodedBaseUri = Uri.decode(baseUri)
        val parentPath = if (decodedBaseUri.endsWith("/")) decodedBaseUri else "$decodedBaseUri/"
        
        val allLinks = document.select("a[href]")
        val result = ArrayList<Element>(allLinks.size)
        
        for (element in allLinks) {
            if (requireText && element.text().trim().isEmpty()) continue
            
            val absUrl = getSafeAbsoluteUrl(element, baseUri)
            if (absUrl.isBlank()) continue
            
            val cleanAbsUrl = absUrl.substringBefore("#").substringBefore("?")
            val decodedAbsUrl = Uri.decode(cleanAbsUrl)
            
            if (!decodedAbsUrl.startsWith(parentPath, ignoreCase = true)) continue
            if (decodedAbsUrl.length <= parentPath.length) continue
            
            if (condition != null && !condition(decodedAbsUrl.lowercase(Locale.ROOT))) continue
            
            result.add(element)
        }
        
        val finalResult = result.toList()
        elementCache.put(cacheKey, finalResult)
        return finalResult
    }

    // ─── Natural Sort (Optimized) ────────────────────────────────────────────
    
    private val naturalComparator = Comparator<String> { a, b ->
        val tokensA = tokenizeRegex.findAll(a).map { it.value }.toList()
        val tokensB = tokenizeRegex.findAll(b).map { it.value }.toList()
        val len = minOf(tokensA.size, tokensB.size)
        
        for (i in 0 until len) {
            val ta = tokensA[i]
            val tb = tokensB[i]
            val cmp = if (ta[0].isDigit() && tb[0].isDigit()) {
                // Parse angka tanpa BigInteger untuk performa lebih baik
                val na = ta.toLongOrNull() ?: 0L
                val nb = tb.toLongOrNull() ?: 0L
                na.compareTo(nb)
            } else {
                ta.lowercase().compareTo(tb.lowercase())
            }
            if (cmp != 0) return@Comparator cmp
        }
        tokensA.size.compareTo(tokensB.size)
    }

    // ─── ComicInfo Parser (Cached & Optimized) ───────────────────────────────
    
    private data class MangaMeta(
        val title: String? = null,
        val description: String? = null,
        val author: String? = null,
        val artist: String? = null,
        val genre: String? = null,
        val status: Int = SManga.UNKNOWN
    )
    
    private fun getComicInfoXml(document: Document): String {
        val baseUri = document.baseUri()
        
        // Coba langsung di folder ini
        val comicInfoLink = getValidChildElements(document, requireText = false) { 
            it.endsWith("comicinfo.xml") 
        }.firstOrNull()
        
        if (comicInfoLink != null) {
            return downloadUrlContent(getSafeAbsoluteUrl(comicInfoLink, baseUri))
        }
        
        // Coba di subfolder pertama (hanya 1 level, tidak recursive)
        val subFolder = getValidChildElements(document) { it.endsWith("/") }.firstOrNull()
        if (subFolder != null) {
            try {
                val subUrl = getSafeAbsoluteUrl(subFolder, baseUri)
                val subDoc = client.newCall(GET(subUrl, headers)).execute().use { it.asJsoup() }
                val subComicInfo = getValidChildElements(subDoc, requireText = false) { 
                    it.endsWith("comicinfo.xml") 
                }.firstOrNull()
                
                if (subComicInfo != null) {
                    return downloadUrlContent(getSafeAbsoluteUrl(subComicInfo, subUrl))
                }
            } catch (_: Exception) { }
        }
        return ""
    }
    
    private fun downloadUrlContent(url: String): String {
        return try {
            client.newCall(GET(url, headers)).execute().use { response ->
                if (response.isSuccessful) response.body?.string() ?: "" else ""
            }
        } catch (_: Exception) { "" }
    }
    
    private fun parseComicInfo(xml: String): MangaMeta {
        val matches = comicInfoTagRegex.findAll(xml)
        val tags = mutableMapOf<String, String>()
        
        for (match in matches) {
            val tagName = match.groupValues[1].lowercase(Locale.ROOT)
            val value = match.groupValues[2].trim()
            if (value.isNotBlank()) tags[tagName] = value
        }
        
        val series = tags["series"]?.cleanTitle()
        val summary = tags["summary"]?.cleanTitle()
        val writer = tags["writer"]
        val penciller = tags["penciller"]
        val publisher = tags["publisher"]
        val statusStr = tags["publishingstatustachiyomi"] ?: tags["status"]
        val genre = tags["genre"]
iyomi"] ?: tags["status"]
        val genre = tags["genre"]
        val categories = tags["categories"]
        
        val authorsList = listOfNotNull(writer, penciller, publisher).distinct()
        val allGenres = listOfNotNull(genre, categories)
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        
        val status = when (statusStr?.lowercase(Locale.ROOT)) {
            "ongoing", "1" -> SManga.ONGOING
            "completed", "end", "2" -> SManga.COMPLETED
            "cancelled", "abandoned", "6" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
        
        return MangaMeta(
            title = series,
            description = summary,
            author = authorsList.joinToString(" | ").takeIf { it.isNotBlank() },
            artist = penciller ?: writer,
            genre = allGenres.joinToString(", ").takeIf { it.isNotBlank() },
            status = status
        )
    }

    // ─── Browse / Popular (Optimized) ────────────────────────────────────────
    
    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/#page=$page", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val currentPage = response.request.url.fragment?.substringAfter("page=")?.toIntOrNull() ?: 1
        val itemsPerPage = 24
        
        val document = response.asJsoup()
        val allElements = getValidChildElements(document) { it.endsWith("/") }
        
        val startIndex = (currentPage - 1) * itemsPerPage
        if (startIndex >= allElements.size) {
            return MangasPage(emptyList(), false)
        }
        
        val endIndex = minOf(startIndex + itemsPerPage, allElements.size)
        val pagedElements = allElements.subList(startIndex, endIndex)
        
        val mangas = ArrayList<SManga>(pagedElements.size)
        for (element in pagedElements) {
            val fixedAbsUrl = getSafeAbsoluteUrl(element)
            val relPath = fixedAbsUrl.removePrefix(baseUrl)
            val cleanAbsUrl = fixedAbsUrl.removeSuffix("/")
            
            mangas.add(SManga.create().apply {
                title = element.text().cleanTitle()
                url = if (relPath.startsWith("/")) relPath else "/$relPath"
                thumbnail_url = "$cleanAbsUrl/${defaultCoverName.trim()}"
            })
        }
        
        return MangasPage(mangas, endIndex < allElements.size)
    }

    // ─── Search (Optimized) ──────────────────────────────────────────────────
    
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/#query=${query.lowercase(Locale.ROOT)}&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val fragment = response.request.url.fragment ?: ""
        val query = fragment.substringAfter("query=").substringBefore("&page=").lowercase(Locale.ROOT)
        val currentPage = fragment.substringAfter("page=").toIntOrNull() ?: 1
        val itemsPerPage = 24
        
        val document = response.asJsoup()
        val filteredElements = getValidChildElements(document) { it.endsWith("/") }
            .filter { it.text().cleanTitle().lowercase(Locale.ROOT).contains(query) }
        
        val startIndex = (currentPage - 1) * itemsPerPage
        if (startIndex >= filteredElements.size) {
            return MangasPage(emptyList(), false)
        }
        
        val endIndex = minOf(startIndex + itemsPerPage, filteredElements.size)
        val pagedElements = filteredElements.subList(startIndex, endIndex)
        
        val mangas = ArrayList<SManga>(pagedElements.size)
        for (element in pagedElements) {
            val fixedAbsUrl = getSafeAbsoluteUrl(element)
            val relPath = fixedAbsUrl.removePrefix(baseUrl)
            val cleanAbsUrl = fixedAbsUrl.removeSuffix("/")
            
            mangas.add(SManga.create().apply {
                title = element.text().cleanTitle()
                url = if (relPath.startsWith("/")) relPath else "/$relPath"
                thumbnail_url = "$cleanAbsUrl/${defaultCoverName.trim()}"
            })
        }
        
        return MangasPage(mangas, endIndex < filteredElements.size)
    }

    // ─── Manga Details (Cached & Fast) ───────────────────────────────────────
    
    override fun mangaDetailsParse(document: Document): SManga {
        val mangaUrl = document.baseUri()
        
        // Cek cache dulu
        mangaMetaCache.get(mangaUrl)?.let { cachedMeta ->
            return SManga.create().apply {
                title = cachedMeta.title ?: ""
                description = cachedMeta.description ?: "Folder manga lokal dari Server LocalStream."
                author = cachedMeta.author ?: "Unknown"
                artist = cachedMeta.artist ?: "Unknown"
                genre = cachedMeta.genre
                status = cachedMeta.status
                initialized = true
            }
        }
        
        // Cari cover
        val imageLinks = getValidChildElements(document, requireText = false) { href ->
            imageExtensions.any { href.endsWith(it) }
        }
        
        val coverElement = imageLinks.find { 
            val href = it.attr("href").lowercase(Locale.ROOT)
            href.contains("cover") || href.contains("poster") || href.contains("thumb")
        } ?: imageLinks.firstOrNull()
        
        val manga = SManga.create().apply {
            description = "Folder manga lokal dari Server LocalStream (WebDAV/HTTP)."
            status = SManga.UNKNOWN
            if (coverElement != null) {
                thumbnail_url = getSafeAbsoluteUrl(coverElement, mangaUrl)
            }
            initialized = true
        }
        
        // Parse ComicInfo.xml
        val comicInfoXml = getComicInfoXml(document)
        if (comicInfoXml.isNotBlank()) {
            val meta = parseComicInfo(comicInfoXml)
            mangaMetaCache.put(mangaUrl, meta)
            
            meta.title?.let { manga.title = it }
            meta.description?.let { manga.description = it }
            meta.author?.let { manga.author = it }
            meta.artist?.let { manga.artist = it }
            meta.genre?.let { manga.genre = it }
            manga.status = meta.status
        } else {
            mangaMetaCache.put(mangaUrl, MangaMeta())
        }
        
        return manga
    }

    // ─── Chapter List (Cached & Optimized) ───────────────────────────────────
    
    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val mangaUrl = response.request.url.toString()
        
        // Cek cache chapter
        chapterCache.get(mangaUrl)?.let { return it }
        
        val document = response.asJsoup()
        val chapterElements = getValidChildElements(document) { href ->
            href.endsWith("/") || href.endsWith(".cbz") || href.endsWith(".zip")
        }
        
        val sortedElements = chapterElements.sortedWith { a, b -> 
            naturalComparator.compare(a.text(), b.text()) 
        }
        
        val total = sortedElements.size
        val chapters = ArrayList<SChapter>(total)
        
        // Hindari reversed() yang membuat copy list baru
        for (i in sortedElements.indices.reversed()) {
            val element = sortedElements[i]
            val fixedAbsUrl = getSafeAbsoluteUrl(element)
            val relPath = fixedAbsUrl.removePrefix(baseUrl)
            
            chapters.add(SChapter.create().apply {
                name = element.text().cleanTitle()
                url = if (relPath.startsWith("/")) relPath else "/$relPath"
                chapter_number = (total - i).toFloat()
            })
        }
        
        chapterCache.put(mangaUrl, chapters)
        return chapters
    }

    // ─── Page List (Optimized) ───────────────────────────────────────────────
    
    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val pageElements = getValidChildElements(document, requireText = false) { href ->
            imageExtensions.any { href.endsWith(it) }
        }
        
        val sortedPages = pageElements.sortedWith { a, b -> 
            naturalComparator.compare(a.text(), b.text()) 
        }
        
        return sortedPages.mapIndexed { index, element ->
            Page(index, "", getSafeAbsoluteUrl(element))
        }
    }

    // ─── Unused Overrides ────────────────────────────────────────────────────
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
    }
}
