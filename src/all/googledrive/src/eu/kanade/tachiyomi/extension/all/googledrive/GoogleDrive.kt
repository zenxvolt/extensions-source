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
import okhttp3.FormBody
import okhttp3.Headers
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

    private val clientId: String
        get() = preferences.getString(CLIENT_ID_PREF, "") ?: ""

    private val clientSecret: String
        get() = preferences.getString(CLIENT_SECRET_PREF, "") ?: ""

    private val refreshToken: String
        get() = preferences.getString(REFRESH_TOKEN_PREF, "") ?: ""

    private val pathList: String
        get() = preferences.getString(PATH_LIST_PREF, "") ?: ""

    @Volatile
    private var accessToken: String? = null
    @Volatile
    private var tokenExpiresAt: Long = 0L

    // Menyimpan pemetaan nomor halaman Aniyomi → nextPageToken Google Drive.
    private val browsePageTokens = mutableMapOf<Int, String>()
    private var lastBrowseQuery = ""

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val clientIdPref = EditTextPreference(screen.context).apply {
            key = CLIENT_ID_PREF
            title = "Client ID OAuth"
            summary = "Masukkan Client ID proyek Google Cloud Console Anda."
        }
        screen.addPreference(clientIdPref)

        val clientSecretPref = EditTextPreference(screen.context).apply {
            key = clientSecretPref
            title = "Client Secret OAuth"
            summary = "Masukkan Client Secret proyek Google Cloud Console Anda."
        }
        screen.addPreference(clientSecretPref)

        val refreshTokenPref = EditTextPreference(screen.context).apply {
            key = REFRESH_TOKEN_PREF
            title = "Refresh Token OAuth"
            summary = "Masukkan Kunci Master (Refresh Token) dari rclone atau OAuth Playground."
        }
        screen.addPreference(refreshTokenPref)

        val pathListPref = EditTextPreference(screen.context).apply {
            key = PATH_LIST_PREF
            title = "Path list"
            summary = "Format: [Nama]URL;[Nama]URL"
        }
        screen.addPreference(pathListPref)
    }

    private fun checkPreferences() {
        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            throw Exception("Kredensial OAuth 2.0 belum diisi lengkap di pengaturan extension!")
        }
        if (pathList.isBlank()) throw Exception("Path List belum diisi di pengaturan extension!")
    }

    // ─── OAuth Token Manager ──────────────────────────────────────────────────

    @Synchronized
    private fun getValidAccessToken(): String {
        val now = System.currentTimeMillis()
        // Jika token sudah ada dan belum kedaluwarsa (diberi buffer aman 1 menit), gunakan token cache
        if (accessToken != null && now < tokenExpiresAt - 60000) {
            return accessToken!!
        }

        try {
            val body = FormBody.Builder()
                .add("grant_type", "refresh_token")
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("refresh_token", refreshToken)
                .build()

            val request = Request.Builder()
                .url("https://oauth2.googleapis.com/token")
                .post(body)
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                throw Exception("HTTP ${response.code}: Gagal menukar Refresh Token.")
            }

            val jsonResponse = JSONObject(response.body?.string() ?: "")
            val token = jsonResponse.getString("access_token")
            val expiresIn = jsonResponse.optLong("expires_in", 3600)

            accessToken = token
            tokenExpiresAt = now + (expiresIn * 1000)

            return token
        } catch (e: Exception) {
            throw Exception("Autentikasi Google Gagal: ${e.message}")
        }
    }

    private fun getAuthHeaders(): Headers {
        return headers.newBuilder()
            .add("Authorization", "Bearer ${getValidAccessToken()}")
            .build()
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
            "$apiUrl?q=$encodedQuery&orderBy=$orderBy&pageSize=$pageSize&fields=$fields",
        )
        if (pageToken != null) sb.append("&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}")
        return sb.toString()
    }

    // ─── Metadata & Cover ────────────────────────────────────────────────────

    private fun getMetadata(mangaFolderId: String): JSONObject {
        return try {
            val query = "'$mangaFolderId' in parents and name = 'metadata.json' and trashed = false"
            val url = buildApiUrl(query, pageSize = 1, fields = "files(id)")
            val responseBody = client.newCall(GET(url, getAuthHeaders())).execute()
                .body?.string() ?: return JSONObject()
            val files = JSONObject(responseBody).optJSONArray("files") ?: return JSONObject()

            if (files.length() > 0) {
                val fileId = files.getJSONObject(0).getString("id")
                val contentBody = client.newCall(
                    GET("$apiUrl/$fileId?alt=media", getAuthHeaders()),
                ).execute().body?.string() ?: return JSONObject()
                JSONObject(contentBody)
            } else {
                JSONObject()
            }
        } catch (e: Exception) { JSONObject() }
    }

    private fun getCoverUrlForManga(mangaFolderId: String): String {
        return try {
            val query = "'$mangaFolderId' in parents and trashed = false and " +
                "(name = 'cover.jpg' or name = 'cover.jpeg' or name = 'cover.png' or name = 'cover.webp')"
            val url = buildApiUrl(query, pageSize = 1, fields = "files(id)")
            val responseBody = client.newCall(GET(url, getAuthHeaders())).execute()
                .body?.string() ?: return fetchFirstImageAsCover(mangaFolderId)
            val files = JSONObject(responseBody).optJSONArray("files")

            if (files != null && files.length() > 0) {
                // Token disuntikkan langsung ke parameter URL agar mempermudah pemuat gambar internal Aniyomi
                "$apiUrl/${files.getJSONObject(0).getString("id")}?alt=media&access_token=${getValidAccessToken()}"
            } else {
                fetchFirstImageAsCover(mangaFolderId)
            }
        } catch (e: Exception) { "" }
    }

    private fun fetchFirstImageAsCover(mangaFolderId: String): String {
        return try {
            val query = "'$mangaFolderId' in parents and mimeType contains 'image/' and trashed = false"
            val url = buildApiUrl(query, pageSize = 1, fields = "files(id)")
            val responseBody = client.newCall(GET(url, getAuthHeaders())).execute()
                .body?.string() ?: return ""
            val files = JSONObject(responseBody).optJSONArray("files")
            if (files != null && files.length() > 0) {
                "$apiUrl/${files.getJSONObject(0).getString("id")}?alt=media&access_token=${getValidAccessToken()}"
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
        return GET(buildApiUrl(query, pageToken = token), getAuthHeaders())
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
        return GET(buildApiUrl(driveQuery, pageToken = token), getAuthHeaders())
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ─── Manga Details ───────────────────────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$apiUrl/${manga.url}?fields=id,name", getAuthHeaders())

    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body?.string() ?: return SManga.create()
        val json = JSONObject(body)
        val mangaId = json.getString("id")
        val metadata = getMetadata(mangaId)

        val authorName = metadata.optString("author", "Unknown")
        val publisherName = metadata.optString("publisher", "")

        return SManga.create().apply {
            title = json.getString("name")
            description = metadata.optString("description", "Manga di-streaming dari Google Drive Privat via OAuth2.")
            author = if (publisherName.isNotEmpty()) "$authorName | $publisherName" else authorName
            status = when (metadata.optInt("status", 0)) {
                1 -> SManga.ONGOING
                2 -> SManga.COMPLETED
                6 -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            thumbnail_url = getCoverUrlForManga(mangaId)
            initialized = true
        }
    }

    // ─── State sementara untuk pagination chapter & page ─────────────────────

    private var currentChapterQuery = ""
    private var currentPageQuery = ""

    // ─── Chapter List ────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request {
        val rawQuery = "'${manga.url}' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        currentChapterQuery = "q=${URLEncoder.encode(rawQuery, "UTF-8")}&orderBy=name"
        return GET(buildApiUrl(rawQuery, pageSize = PAGE_SIZE_LARGE, fields = chapterFields), getAuthHeaders())
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)

        val allFiles = mutableListOf<JSONObject>()
        val firstBatch = json.optJSONArray("files") ?: return emptyList()
        for (i in 0 until firstBatch.length()) allFiles.add(firstBatch.getJSONObject(i))

        var pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        while (pageToken != null) {
            val nextUrl = "$apiUrl?${currentChapterQuery}&pageSize=$PAGE_SIZE_LARGE&fields=$chapterFields&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}"
            val nextBody = client.newCall(GET(nextUrl, getAuthHeaders())).execute()
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
        currentPageQuery = "q=${URLEncoder.encode("'${chapter.url}' in parents and mimeType contains 'image/' and trashed = false", "UTF-8")}&orderBy=name"
        val query = "'${chapter.url}' in parents and mimeType contains 'image/' and trashed = false"
        return GET(buildApiUrl(query, pageSize = PAGE_SIZE_LARGE, fields = "nextPageToken,files(id,name)"), getAuthHeaders())
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body?.string() ?: return emptyList()
        val json = JSONObject(body)

        val allFiles = mutableListOf<JSONObject>()
        val firstBatch = json.optJSONArray("files") ?: return emptyList()
        for (i in 0 until firstBatch.length()) allFiles.add(firstBatch.getJSONObject(i))

        var pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
        while (pageToken != null) {
            val nextUrl = "$apiUrl?${currentPageQuery}&pageSize=$PAGE_SIZE_LARGE&fields=nextPageToken,files(id,name)&pageToken=${URLEncoder.encode(pageToken, "UTF-8")}"
            val nextBody = client.newCall(GET(nextUrl, getAuthHeaders())).execute()
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
            // Menggunakan parameter access_token di URL agar Glide/internal downloader Aniyomi dapat memuat data mentah privat tanpa cookie
            Page(i, "", "$apiUrl/${file.getString("id")}?alt=media&access_token=${getValidAccessToken()}")
        }
    }

    // ─── Unused ──────────────────────────────────────────────────────────────

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used")
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    // ─── Constants ───────────────────────────────────────────────────────────

    companion object {
        private const val CLIENT_ID_PREF = "CLIENT_ID_PREF"
        private const val CLIENT_SECRET_PREF = "CLIENT_SECRET_PREF"
        private const val REFRESH_TOKEN_PREF = "REFRESH_TOKEN_PREF"
        private const val PATH_LIST_PREF = "PATH_LIST_PREF"
        private const val PAGE_SIZE_LARGE = 1000
        private const val PAGE_SIZE_BROWSE = 50
    }
}
