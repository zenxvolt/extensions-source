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
        get() = preferences.getString(BASE_URL_PREF, "http://127.0.0.1:8080")?.removeSuffix("/") ?: "http://127.0.0.1:8080"

    private val defaultCoverName: String
        get() = preferences.getString(COVER_NAME_PREF, "cover.jpg") ?: "cover.jpg"

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
            summary = "Harus SAMA PERSIS dengan nama file di dalam folder (case-sensitive)."
            setDefaultValue("cover.jpg")
        }
        screen.addPreference(coverNamePref)
    }

    // ─── Helpers: The Ultimate URL Encoder ───────────────────────────────────

    // Membongkar, membersihkan, dan merakit ulang URL setara Google Chrome
    private fun getEncodedCoverUrl(rawAbsUrl: String, coverName: String): String {
        val cleanedUrl = rawAbsUrl.removeSuffix("/")
        val segments = cleanedUrl.split("/")
        
        val encodedSegments = segments.mapIndexed { index, segment ->
            if (index < 3) { 
                // Biarkan "http:", "", dan "127.0.0.1:8080" apa adanya
                segment 
            } else {
                // 1. Decode dulu untuk mencegah double-encoding dari server
                val decodedSegment = try {
                    java.net.URLDecoder.decode(segment, "UTF-8")
                } catch (e: Exception) {
                    segment // Fallback jika string kacau
                }
                // 2. Encode ulang dengan ketat
                java.net.URLEncoder.encode(decodedSegment, "UTF-8").replace("+", "%20")
            }
        }
        
        val encodedBase = encodedSegments.joinToString("/")
        val encodedCover = java.net.URLEncoder.encode(coverName.trim(), "UTF-8").replace("+", "%20")
        
        return "$encodedBase/$encodedCover"
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

    // ─── Browse / Popular ────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a[href]")
            .filter { !it.attr("href").contains("..") && it.attr("href").endsWith("/") }
            .map { element ->
                SManga.create().apply {
                    val rawHref = element.attr("href").removeSuffix("/")
                    // Dapatkan judul asli langsung dari href agar tidak kena truncate HTML
                    title = try {
                        java.net.URLDecoder.decode(rawHref.split("/").last(), "UTF-8")
                    } catch (e: Exception) {
                        element.text().removeSuffix("/")
                    }
                    
                    val absUrl = element.absUrl("href")
                    
                    var relUrl = absUrl.removePrefix(baseUrl)
                    if (!relUrl.startsWith("/")) relUrl = "/$relUrl"
                    url = relUrl
                    
                    // Gunakan peluru perak kita
                    thumbnail_url = getEncodedCoverUrl(absUrl, defaultCoverName)
                }
            }
        return MangasPage(mangas, false)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        return GET("$baseUrl/#query=${query.lowercase()}", headers)
    }

    override fun searchMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val query = response.request.url.fragment?.lowercase() ?: ""
        
        val mangas = document.select("a[href]")
            .filter { !it.attr("href").contains("..") && it.attr("href").endsWith("/") }
            .filter { 
                val decodedName = try { java.net.URLDecoder.decode(it.attr("href"), "UTF-8") } else { it.text() }
                decodedName.lowercase().contains(query) 
            }
            .map { element ->
                SManga.create().apply {
                    val rawHref = element.attr("href").removeSuffix("/")
                    title = try {
                        java.net.URLDecoder.decode(rawHref.split("/").last(), "UTF-8")
                    } catch (e: Exception) {
                        element.text().removeSuffix("/")
                    }
                    
                    val absUrl = element.absUrl("href")
                    
                    var relUrl = absUrl.removePrefix(baseUrl)
                    if (!relUrl.startsWith("/")) relUrl = "/$relUrl"
                    url = relUrl
                    
                    thumbnail_url = getEncodedCoverUrl(absUrl, defaultCoverName)
                }
            }
        return MangasPage(mangas, false)
    }

    // ─── Manga Details ───────────────────────────────────────────────────────

    override fun mangaDetailsParse(document: Document): SManga {
        val links = document.select("a[href]")
        val imageLinks = links.filter { 
            val href = it.attr("href").lowercase()
            !href.contains("..") && (href.endsWith(".jpg") || href.endsWith(".jpeg") || 
                                     href.endsWith(".png") || href.endsWith(".webp") || 
                                     href.endsWith(".avif") || href.endsWith(".gif") || 
                                     href.endsWith(".heic") || href.endsWith(".jxl"))
        }

        val coverElement = imageLinks.find { 
            val href = it.attr("href").lowercase()
            href.contains("cover") || href.contains("poster") || href.contains("thumb")
        } ?: imageLinks.firstOrNull()

        return SManga.create().apply {
            description = "Folder manga lokal dari Server LocalStream (WebDAV/HTTP)."
            status = SManga.UNKNOWN
            if (coverElement != null) {
                // Terapkan sistem yang sama persis agar konsisten
                val absUrl = coverElement.absUrl("href")
                val filename = absUrl.split("/").last()
                val parentUrl = absUrl.substringBeforeLast("/")
                thumbnail_url = getEncodedCoverUrl(parentUrl, filename)
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

        val comparator = naturalSortComparator()
        val sortedElements = chapterElements.sortedWith { a, b -> 
            comparator.compare(a.text(), b.text()) 
        }

        return sortedElements.mapIndexed { index, element ->
                SChapter.create().apply {
                    val rawHref = element.attr("href").removeSuffix("/")
                    name = try {
                        java.net.URLDecoder.decode(rawHref.split("/").last(), "UTF-8")
                    } catch (e: Exception) {
                        element.text().removeSuffix("/")
                    }
                    val absUrl = element.absUrl("href")
                    var relUrl = absUrl.removePrefix(baseUrl)
                    if (!relUrl.startsWith("/")) relUrl = "/$relUrl"
                    url = relUrl
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

        val comparator = naturalSortComparator()
        val sortedPages = pageElements.sortedWith { a, b -> 
            comparator.compare(a.text(), b.text()) 
        }

        return sortedPages.mapIndexed { index, element ->
            val absUrl = element.absUrl("href")
            val filename = absUrl.split("/").last()
            val parentUrl = absUrl.substringBeforeLast("/")
            val validUrl = getEncodedCoverUrl(parentUrl, filename)
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
