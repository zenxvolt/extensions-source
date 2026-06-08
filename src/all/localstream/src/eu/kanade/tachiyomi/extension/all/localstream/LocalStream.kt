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
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.util.Locale

class LocalStream : ParsedHttpSource(), ConfigurableSource {

    override val name = "LocalStream"
    override val lang = "all"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    override val baseUrl: String
        get() = preferences.getString(BASE_URL_PREF, "http://127.0.0.1:8080")?.removeSuffix("/") ?: "http://127.0.0.1:8080"

    private val defaultCoverName: String
        get() = preferences.getString(COVER_NAME_PREF, "cover.jpg") ?: "cover.jpg"

    // ─── FIX: Dynamic Cover Fallback Interceptor ─────────────────────────────
    override val client: OkHttpClient = network.client.newBuilder()
        .addInterceptor { chain ->
            val originalRequest = chain.request()
            var response = chain.proceed(originalRequest)
            
            val urlString = originalRequest.url.toString()
            val coverBase = defaultCoverName.substringBeforeLast(".").trim()
            
            // Jika request gambar cover menghasilkan 404, cari ekstensi alternatif di latar belakang
            if (response.code == 404 && urlString.contains("/$coverBase.", ignoreCase = true)) {
                val extensions = listOf("jpg", "jpeg", "png", "webp", "avif", "gif")
                val currentExt = urlString.substringAfterLast(".").lowercase(Locale.ROOT)
                
                for (ext in extensions) {
                    if (ext == currentExt) continue
                    response.close() // Tutup response 404 sebelumnya
                    
                    val newUrl = urlString.substringBeforeLast(".") + "." + ext
                    val newRequest = originalRequest.newBuilder().url(newUrl).build()
                    response = chain.proceed(newRequest)
                    
                    if (response.isSuccessful) {
                        break // Berhenti jika gambar dengan format ini sukses dimuat
                    }
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
            summary = "Nama dasar cover (Contoh: cover.jpg). Jika format berbeda (.png/.webp), sistem otomatis mendeteksinya."
            setDefaultValue("cover.jpg")
        }
        screen.addPreference(coverNamePref)
    }

    // ─── Helpers: Natural Sort ───────────────────────────────────────────────

    private fun naturalSortComparator(): Comparator<String> = Comparator { a, b ->
        val tokensA = tokenize(a)
        val tokensB = tokenize(b)
        val len = minOf(tokensA.size, tokensB.size)
        for (i in 0 until len) {
            val ta = tokensA[i]
            val tb = tokensB[i]
            val cmp = if (ta.first().isDigit() && tb.first().isDigit()) {
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

    // ─── Helpers: ComicInfo Metadata Parser ──────────────────────────────────

    private fun getComicInfoXml(document: Document, currentUrl: String): String {
        val links = document.select("a")
        
        val comicInfoLink = links.find { it.text().lowercase(Locale.ROOT).endsWith("comicinfo.xml") }
        if (comicInfoLink != null) {
            val encodedName = URLEncoder.encode(comicInfoLink.text(), "UTF-8").replace("+", "%20")
            return downloadUrlContent("${currentUrl.removeSuffix("/")}/$encodedName")
        }

        val subFolder = links.find { 
            val text = it.text()
            text.isNotBlank() && text != "../" && text != ".." && text.endsWith("/") 
        }
        
        if (subFolder != null) {
            try {
                val cleanSubName = subFolder.text().removeSuffix("/")
                val encodedFolder = URLEncoder.encode(cleanSubName, "UTF-8").replace("+", "%20")
                val subUrl = "${currentUrl.removeSuffix("/")}/$encodedFolder/"
                
                val subDoc = client.newCall(GET(subUrl, headers)).execute().asJsoup()
                val subComicInfoLink = subDoc.select("a").find { it.text().lowercase(Locale.ROOT).endsWith("comicinfo.xml") }
                
                if (subComicInfoLink != null) {
                    val encodedSubName = URLEncoder.encode(subComicInfoLink.text(), "UTF-8").replace("+", "%20")
                    return downloadUrlContent("${subUrl.removeSuffix("/")}/$encodedSubName")
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
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        return regex.find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun parseComicInfo(xml: String, manga: SManga) {
        val series = extractXmlTag(xml, "Series")
        val summary = extractXmlTag(xml, "Summary")
            ?.replace("&amp;", "&")
            ?.replace("&lt;", "<")
            ?.replace("&gt;", ">")
            ?.replace("&quot;", "\"")
            ?.replace("&apos;", "'")

        val writer = extractXmlTag(xml, "Writer")
        val penciller = extractXmlTag(xml, "Penciller")
        val publisher = extractXmlTag(xml, "Publisher")
        val statusStr = extractXmlTag(xml, "PublishingStatusTachiyomi") ?: extractXmlTag(xml, "Status")
        val genre = extractXmlTag(xml, "Genre")
        val categories = extractXmlTag(xml, "Categories")

        if (!series.isNullOrBlank()) {
            manga.title = series
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
        val itemsPerPage = 24

        val document = response.asJsoup()
        val allElements = document.select("a")
            .filter { 
                val text = it.text()
                text.isNotBlank() && text != "../" && text != ".." && text.endsWith("/") 
            }

        val startIndex = (currentPage - 1) * itemsPerPage
        val endIndex = minOf(startIndex + itemsPerPage, allElements.size)

        if (startIndex >= allElements.size) {
            return MangasPage(emptyList(), false)
        }

        val pagedElements = allElements.subList(startIndex, endIndex)
        val mangas = pagedElements.map { element ->
            SManga.create().apply {
                val folderName = element.text()
                val cleanName = folderName.removeSuffix("/")
                title = cleanName
                
                val encodedFolder = URLEncoder.encode(cleanName, "UTF-8").replace("+", "%20")
                url = "/$encodedFolder/"
                
                val cleanBaseUrl = baseUrl.removeSuffix("/")
                thumbnail_url = "$cleanBaseUrl/$encodedFolder/${defaultCoverName.trim()}"
            }
        }

        val hasNextPage = endIndex < allElements.size
        return MangasPage(mangas, hasNextPage)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/#query=${query.lowercase(Locale.ROOT)}&page=$page", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val fragment = response.request.url.fragment ?: ""
        val query = fragment.substringAfter("query=").substringBefore("&page=").lowercase(Locale.ROOT)
        val currentPage = fragment.substringAfter("page=").toIntOrNull() ?: 1
        val itemsPerPage = 24

        val document = response.asJsoup()
        val filteredElements = document.select("a")
            .filter { 
                val text = it.text()
                text.isNotBlank() && text != "../" && text != ".." && text.endsWith("/") 
            }
            .filter { it.text().lowercase(Locale.ROOT).contains(query) }

        val startIndex = (currentPage - 1) * itemsPerPage
        val endIndex = minOf(startIndex + itemsPerPage, filteredElements.size)

        if (startIndex >= filteredElements.size) {
            return MangasPage(emptyList(), false)
        }

        val pagedElements = filteredElements.subList(startIndex, endIndex)
        val mangas = pagedElements.map { element ->
            SManga.create().apply {
                val folderName = element.text()
                val cleanName = folderName.removeSuffix("/")
                title = cleanName
                
                val encodedFolder = URLEncoder.encode(cleanName, "UTF-8").replace("+", "%20")
                url = "/$encodedFolder/"
                
                val cleanBaseUrl = baseUrl.removeSuffix("/")
                thumbnail_url = "$cleanBaseUrl/$encodedFolder/${defaultCoverName.trim()}"
            }
        }

        val hasNextPage = endIndex < filteredElements.size
        return MangasPage(mangas, hasNextPage)
    }

    // ─── Manga Details ───────────────────────────────────────────────────────

    override fun mangaDetailsParse(document: Document): SManga {
        val currentUrl = document.baseUri()
        val links = document.select("a")
        val imageLinks = links.filter { 
            val text = it.text().lowercase(Locale.ROOT)
            text.isNotBlank() && text != "../" && text != ".." && 
            (text.endsWith(".jpg") || text.endsWith(".jpeg") || 
             text.endsWith(".png") || text.endsWith(".webp") || 
             text.endsWith(".avif") || text.endsWith(".gif") || 
             text.endsWith(".heic") || text.endsWith(".jxl"))
        }

        val coverElement = imageLinks.find { 
            val text = it.text().lowercase(Locale.ROOT)
            text.contains("cover") || text.contains("poster") || text.contains("thumb")
        } ?: imageLinks.firstOrNull()

        val manga = SManga.create().apply {
            description = "Folder manga lokal dari Server LocalStream (WebDAV/HTTP)."
            status = SManga.UNKNOWN
            if (coverElement != null) {
                val encodedCover = URLEncoder.encode(coverElement.text(), "UTF-8").replace("+", "%20")
                thumbnail_url = "${currentUrl.removeSuffix("/")}/$encodedCover"
            }
            initialized = true
        }

        val comicInfoXml = getComicInfoXml(document, currentUrl)
        if (comicInfoXml.isNotBlank()) {
            parseComicInfo(comicInfoXml, manga)
        }

        return manga
    }

    // ─── Chapter List ────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        val currentPath = response.request.url.encodedPath 
        
        val chapterElements = document.select("a")
            .filter { element ->
                val text = element.text()
                text.isNotBlank() && text != "../" && text != ".." && 
                (text.endsWith("/") || text.endsWith(".cbz", true) || text.endsWith(".zip", true))
            }

        val comparator = naturalSortComparator()
        val sortedElements = chapterElements.sortedWith { a, b -> 
            comparator.compare(a.text(), b.text()) 
        }

        return sortedElements.mapIndexed { index, element ->
            SChapter.create().apply {
                val itemName = element.text()
                val cleanName = itemName.removeSuffix("/")
                name = cleanName
                
                val encodedItem = URLEncoder.encode(cleanName, "UTF-8").replace("+", "%20")
                val suffix = if (itemName.endsWith("/")) "/" else ""
                
                url = "${currentPath.removeSuffix("/")}/$encodedItem$suffix"
                chapter_number = (index + 1).toFloat()
            }
        }.reversed() 
    }

    // ─── Page List ───────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request = GET(baseUrl + chapter.url, headers)

    override fun pageListParse(response: Response): List<Page> {
        val document = response.asJsoup()
        val currentUrl = response.request.url.toString()
        
        val pageElements = document.select("a")
            .filter { element ->
                val text = element.text().lowercase(Locale.ROOT)
                text.isNotBlank() && text != "../" && text != ".." && 
                (text.endsWith(".jpg") || text.endsWith(".png") || text.endsWith(".jpeg") || 
                 text.endsWith(".webp") || text.endsWith(".avif") || text.endsWith(".gif") || 
                 text.endsWith(".heic") || text.endsWith(".jxl") || text.endsWith(".bmp"))
            }

        val comparator = naturalSortComparator()
        val sortedPages = pageElements.sortedWith { a, b -> 
            comparator.compare(a.text(), b.text()) 
        }

        return sortedPages.mapIndexed { index, element ->
            val itemName = element.text()
            val encodedItem = URLEncoder.encode(itemName, "UTF-8").replace("+", "%20")
            
            val validUrl = "${currentUrl.removeSuffix("/")}/$encodedItem"
            Page(index, "", validUrl)
        }
    }

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
