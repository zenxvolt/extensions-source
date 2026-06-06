package eu.kanade.tachiyomi.extension.all.localstream

import android.app.Application
import android.content.SharedPreferences
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.ParsedHttpSource
import eu.kanade.tachiyomi.network.GET
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

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val baseUrlPref = EditTextPreference(screen.context).apply {
            key = BASE_URL_PREF
            title = "Server URL (Round Sync / Termux)"
            summary = "Masukkan URL lokal dari Rclone Serve."
            setDefaultValue("http://127.0.0.1:8080")
        }
        screen.addPreference(baseUrlPref)
    }

    // Fungsi Pembantu: Mengintip cover langsung ke dalam folder manga
    private fun getCoverUrl(folderUrl: String): String? {
        return try {
            // Melakukan HTTP Request secara instan ke dalam folder manga
            val response = client.newCall(GET(folderUrl, headers)).execute()
            val document = response.asJsoup()
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

            coverElement?.absUrl("href")
        } catch (e: Exception) {
            null
        }
    }

    // ─── Browse / Popular ────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request = GET("$baseUrl/", headers)

    override fun popularMangaParse(response: Response): MangasPage {
        val document = response.asJsoup()
        val mangas = document.select("a[href]")
            .filter { !it.attr("href").contains("..") && it.attr("href").endsWith("/") }
            .map { element ->
                SManga.create().apply {
                    title = element.text().removeSuffix("/")
                    val absUrl = element.absUrl("href")
                    var relUrl = absUrl.removePrefix(baseUrl)
                    if (!relUrl.startsWith("/")) relUrl = "/$relUrl"
                    url = relUrl
                    // Menarik cover langsung saat list dimuat!
                    thumbnail_url = getCoverUrl(absUrl)
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
            .filter { it.text().lowercase().contains(query) }
            .map { element ->
                SManga.create().apply {
                    title = element.text().removeSuffix("/")
                    val absUrl = element.absUrl("href")
                    var relUrl = absUrl.removePrefix(baseUrl)
                    if (!relUrl.startsWith("/")) relUrl = "/$relUrl"
                    url = relUrl
                    // Menarik cover langsung saat list dimuat!
                    thumbnail_url = getCoverUrl(absUrl)
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
                thumbnail_url = coverElement.absUrl("href")
            }
            initialized = true
        }
    }

    // ─── Chapter List ────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request = GET(baseUrl + manga.url, headers)

    override fun chapterListParse(response: Response): List<SChapter> {
        val document = response.asJsoup()
        return document.select("a[href]")
            .filter { element ->
                val href = element.attr("href").lowercase()
                !href.contains("..") && (href.endsWith("/") || href.endsWith(".cbz") || href.endsWith(".zip"))
            }
            .mapIndexed { index, element ->
                SChapter.create().apply {
                    name = element.text().removeSuffix("/")
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
        return document.select("a[href]")
            .filter { element ->
                val href = element.attr("href").lowercase()
                val isImage = href.endsWith(".jpg") || href.endsWith(".png") || 
                              href.endsWith(".jpeg") || href.endsWith(".webp") || 
                              href.endsWith(".avif") || href.endsWith(".gif") || 
                              href.endsWith(".heic") || href.endsWith(".jxl") || 
                              href.endsWith(".bmp")
                !href.contains("..") && isImage
            }
            .mapIndexed { index, element ->
                val absUrl = element.absUrl("href")
                Page(index, "", absUrl)
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
    }
}
