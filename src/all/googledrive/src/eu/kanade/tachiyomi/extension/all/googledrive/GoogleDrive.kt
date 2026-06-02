package eu.kanade.tachiyomi.extension.all.googledrive

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
import eu.kanade.tachiyomi.source.online.HttpSource
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

class GoogleDrive : HttpSource(), ConfigurableSource {

    override val name = "Google Drive"
    override val baseUrl = "https://drive.google.com"
    override val lang = "all"
    override val supportsLatest = false

    private val apiUrl = "https://www.googleapis.com/drive/v3/files"

    private val listFields = "nextPageToken,files(id,name)"
    private val chapterFields = "nextPageToken,files(id,name,modifiedTime)"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val apiKey: String
        get() = preferences.getString(API_KEY_PREF, "") ?: ""

    private val pathList: String
        get() = preferences.getString(PATH_LIST_PREF, "") ?: ""

    private val browsePageTokens = mutableMapOf<Int, String>()
    private var lastBrowseQuery = ""

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val apiKeyPref = EditTextPreference(screen.context).apply {
            key = API_KEY_PREF
            title = "API Key Google Cloud"
            summary = "Gunakan API Key yang sudah dibatasi untuk Google Drive API."
        }
        screen.addPreference(apiKeyPref)

        val pathListPref = EditTextPreference(screen.context).apply {
            key = PATH_LIST_PREF
            title = "Path list"
            summary = "Format: [Nama]URL;[Nama]URL"
        }
        screen.addPreference(pathListPref)
    }

    private fun checkPreferences() {
        if (apiKey.isBlank()) throw Exception("API Key belum diisi di pengaturan extension!")
        if (pathList.isBlank()) throw Exception("Path List belum diisi di pengaturan extension!")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun extractFolderId(path: String): String? =
        Regex("folders/([a-zA-Z0-9_-]+)").find(path)?.groupValues?.get(1)

    private fun buildParentClauses(): List<String> =
        pathList.split(";")
            .filter { it.isNotBlank() }
            .mapNotNull { path -> extractFolderId(path)?.let { "'$it' in parents" } }

    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return try {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault())
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            try {
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault())
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse(dateStr)?.time ?: 0L
            } catch (e2: Exception) { 0L }
        }
    }

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

    private fun buildApiUrl(
        query: String,
        orderBy: String = "name",
        pageSize: Int = PAGE_SIZE_BROWSE,
        fields: String = listFields,
        pageToken: String? = null,
    ): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val sb = StringBuilder(
            "$apiUrl?q=$encodedQuery&orderBy=$orderBy&pageSize=$pageSize&fields=$fields&key=$apiKey",
        )
        if (pageToken != null) sb.append("&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}")
        return sb.toString()
    }

    // ─── Metadata & Cover ────────────────────────────────────────────────────

    private fun getComicInfoXml(mangaFolderId: String): String {
        return try {
            // Menggunakan 'contains' agar pencarian nama file menjadi Case-Insensitive di API GDrive
            val query = "'$mangaFolderId' in parents and (name contains 'comicinfo' or name contains 'ComicInfo') and trashed = false"
            val url = buildApiUrl(query, pageSize = 10, fields = "files(id,name)")
            val responseBody = client.newCall(GET(url, headers)).execute()
                .body?.string() ?: return ""
            val files = JSONObject(responseBody).optJSONArray("files") ?: return ""

            for (i in 0 until files.length()) {
                val file = files.getJSONObject(i)
                val fileName = file.optString("name", "")
                // Memastikan nama file sangat akurat tanpa mempedulikan huruf besar/kecil
                if (fileName.equals("comicinfo.xml", ignoreCase = true)) {
                    val fileId = file.getString("id")
                    // Menggunakan endpoint /uc?export=download karena terbukti aman dilewati tanpa kendala hak akses API stream murni
                    val downloadUrl = "$baseUrl/uc?export=download&id=$fileId"
                    val response = client.newCall(GET(downloadUrl, headers)).execute()
                    return response.body?.string() ?: ""
                }
            }
            ""
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

        // Jika tag <Series> ada, gunakan ini sebagai judul utama komik menggantikan nama folder Drive
        if (!series.isNullOrBlank()) {
            manga.title = series
        }
        
        manga.description = summary ?: "Tidak ada deskripsi."

        // Ambil pembuat komik yang tersedia (Writer / Penciller / Publisher)
        val authorsList = listOfNotNull(writer, penciller, publisher).distinct()
        manga.author = authorsList.joinToString(" | ").takeIf { it.isNotBlank() } ?: "Unknown"
        manga.artist = penciller ?: writer ?: "Unknown"

        // Menggabungkan tag <Genre> bawaan dan <ty:Categories> khas Tachiyomi/Aniyomi
        val allGenres = listOfNotNull(genre, categories)
            .flatMap { it.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            
        if (allGenres.isNotEmpty()) {
            manga.genre = allGenres.joinToString(", ")
        }

        manga.status = when (statusStr?.lowercase()) {
            "ongoing", "1" -> SManga.ONGOING
            "completed", "end", "2" -> SManga.COMPLETED
            "cancelled", "abandoned", "6" -> SManga.CANCELLED
            "hiatus" -> SManga.ON_HIATUS
            else -> SManga.UNKNOWN
        }
    }

    private fun getCoverUrlForManga(mangaFolderId: String): String {
        return try {
            val query = "'$mangaFolderId' in parents and trashed = false and " +
                "(name = 'cover.jpg' or name = 'cover.jpeg' or name = 'cover.png' or name = 'cover.webp' or name = 'cover.avif')"
            val url = buildApiUrl(query, pageSize = 1, fields = "files(id)")
            val responseBody = client.newCall(GET(url, headers)).execute()
                .body?.string() ?: return fetchFirstImageAsCover(mangaFolderId)
            val files = JSONObject(responseBody).optJSONArray("files")

            if (files != null && files.length() > 0) {
                "$baseUrl/uc?export=view&id=${files.getJSONObject(0).getString("id")}"
            } else {
                fetchFirstImageAsCover(mangaFolderId)
            }
        } catch (e: Exception) { "" }
    }

    private fun fetchFirstImageAsCover(mangaFolderId: String): String {
        return try {
            val query = "'$mangaFolderId' in parents and (mimeType contains 'image/' or fileExtension = 'avif' or name contains '.avif') and trashed = false"
            val url = buildApiUrl(query, pageSize = 1, fields = "files(id)")
            val responseBody = client.newCall(GET(url, headers)).execute()
                .body?.string() ?: return ""
            val files = JSONObject(responseBody).optJSONArray("files")
            if (files != null && files.length() > 0) {
                "$baseUrl/uc?export=view&id=${files.getJSONObject(0).getString("id")}"
            } else { "" }
        } catch (e: Exception) { "" }
    }

    // ─── Browse / Popular ────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request {
        checkPreferences()
        val parentClauses = buildParentClauses()
        if (parentClauses.isEmpty()) throw Exception("Tidak ada URL folder valid di Path List.")

        val joinedParents = parentClauses.joinToString(" or ")
        val query = "($joinedParents) and mimeType = 'application/vnd.google-apps.folder' and trashed = false"

        if (page == 1) {
            browsePageTokens.clear()
            lastBrowseQuery = query
        }

        val token = browsePageTokens[page]
        return GET(buildApiUrl(query, pageToken = token), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val json = JSONObject(body)
        val files = json.optJSONArray("files") ?: return MangasPage(emptyList(), false)

        val nextToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        val currentPage = browsePageTokens.size + 1
        if (nextToken != null) {
            browsePageTokens[currentPage + 1] = nextToken
        }

        val mangas = (0 until files.length()).map { i ->
            val file = files.getJSONObject(i)
            SManga.create().apply {
                title = file.getString("name")
                url = file.getString("id")
                thumbnail_url = null
            }
        }
        return MangasPage(mangas, hasNextPage = nextToken != null)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        checkPreferences()
        val parentClauses = buildParentClauses()
        if (parentClauses.isEmpty()) throw Exception("Tidak ada URL folder valid di Path List.")

        val joinedParents = parentClauses.joinToString(" or ")
        val nameFilter = if (query.isNotBlank()) {
            val safeQuery = query.replace("\\", "\\\\").replace("'", "\\'")
            " and name contains '$safeQuery'"
        } else { "" }

        val driveQuery = "($joinedParents) and mimeType = 'application/vnd.google-apps.folder'" +
            "$nameFilter and trashed = false"

        if (page == 1) {
            browsePageTokens.clear()
            lastBrowseQuery = driveQuery
        }

        val token = browsePageTokens[page]
        return GET(buildApiUrl(driveQuery, pageToken = token), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ─── Manga Details ───────────────────────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$apiUrl/${manga.url}?fields=id,name&key=$apiKey", headers)

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body?.string() ?: return SManga.create()
        val json = JSONObject(body)
        val mangaId = json.getString("id")

        val manga = SManga.create().apply {
            title = json.getString("name")
            thumbnail_url = getCoverUrlForManga(mangaId)
            initialized = true
        }

        val comicInfoXml = getComicInfoXml(mangaId)
        if (comicInfoXml.isNotBlank()) {
            parseComicInfo(comicInfoXml, manga)
        } else {
            manga.description = "Tidak ada deskripsi."
            manga.author = "Unknown"
            manga.status = SManga.UNKNOWN
        }

        return manga
    }

    // ─── State sementara untuk pagination chapter & page ─────────────────────

    private var currentChapterQuery = ""
    private var currentPageQuery = ""

    // ─── Chapter List ────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request {
        val rawQuery = "'${manga.url}' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        currentChapterQuery = "q=${URLEncoder.encode(rawQuery, "UTF-8")}&orderBy=name"
        return GET(buildApiUrl(rawQuery, pageSize = PAGE_SIZE_LARGE, fields = chapterFields), headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)

        val allFiles = mutableListOf<JSONObject>()
        val firstBatch = json.optJSONArray("files") ?: return emptyList()
        for (i in 0 until firstBatch.length()) allFiles.add(firstBatch.getJSONObject(i))

        var pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        while (pageToken != null) {
            val nextUrl = "$apiUrl?${currentChapterQuery}&pageSize=$PAGE_SIZE_LARGE&fields=$chapterFields&key=$apiKey&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}"
            val nextBody = client.newCall(GET(nextUrl, headers)).execute()
                .body?.string() ?: break
            val nextJson = JSONObject(nextBody)
            val nextFiles = nextJson.optJSONArray("files") ?: break
            for (i in 0 until nextFiles.length()) allFiles.add(nextFiles.getJSONObject(i))
            pageToken = nextJson.optString("nextPageToken").takeIf { it.isNotEmpty() }
        }

        val comparator = naturalSortComparator()
        allFiles.sortWith { a, b -> comparator.compare(a.getString("name"), b.getString("name")) }

        return allFiles.mapIndexed { i, file ->
            SChapter.create().apply {
                name = file.getString("name")
                url = file.getString("id")
                chapter_number = (i + 1).toFloat()
                date_upload = parseDate(file.optString("modifiedTime"))
            }
        }.reversed()
    }

    // ─── Page List ───────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request {
        val query = "'${chapter.url}' in parents and (mimeType contains 'image/' or fileExtension = 'avif' or name contains '.avif') and trashed = false"
        currentPageQuery = "q=${URLEncoder.encode(query, "UTF-8")}&orderBy=name"
        return GET(buildApiUrl(query, pageSize = PAGE_SIZE_LARGE, fields = "nextPageToken,files(id,name)"), headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)

        val allFiles = mutableListOf<JSONObject>()
        val firstBatch = json.optJSONArray("files") ?: return emptyList()
        for (i in 0 until firstBatch.length()) allFiles.add(firstBatch.getJSONObject(i))

        var pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        while (pageToken != null) {
            val nextUrl = "$apiUrl?${currentPageQuery}&pageSize=$PAGE_SIZE_LARGE&fields=nextPageToken,files(id,name)&key=$apiKey&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}"
            val nextBody = client.newCall(GET(nextUrl, headers)).execute()
                .body?.string() ?: break
            val nextJson = JSONObject(nextBody)
            val nextFiles = nextJson.optJSONArray("files") ?: break
            for (i in 0 until nextFiles.length()) allFiles.add(nextFiles.getJSONObject(i))
            pageToken = nextJson.optString("nextPageToken").takeIf { it.isNotEmpty() }
        }

        val comparator = naturalSortComparator()
        allFiles.sortWith { a, b ->
            comparator.compare(
                a.optString("name", a.optString("id")),
                b.optString("name", b.optString("id"))
            )
        }

        return allFiles.mapIndexed { i, file ->
            Page(i, "", "$baseUrl/uc?export=view&id=${file.getString("id")}")
        }
    }

    // ─── Unused ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used")
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ─── Constants ───────────────────────────────────────────────────────────

    companion object {
        private const val API_KEY_PREF = "API_KEY_PREF"
        private const val PATH_LIST_PREF = "PATH_LIST_PREF"
        private const val PAGE_SIZE_LARGE = 1000
        private const val PAGE_SIZE_BROWSE = 50
    }
}
