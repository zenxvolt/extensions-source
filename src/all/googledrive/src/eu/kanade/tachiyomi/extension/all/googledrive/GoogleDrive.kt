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

    // Field yang diminta ke API dibatasi seminimal mungkin agar response lebih kecil dan cepat.
    // Tambahkan field hanya jika memang dibutuhkan.
    private val listFields = "files(id,name)"
    private val chapterFields = "files(id,name,modifiedTime)"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val apiKey: String
        get() = preferences.getString(API_KEY_PREF, "") ?: ""

    private val pathList: String
        get() = preferences.getString(PATH_LIST_PREF, "") ?: ""

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

    // ─── Helper ──────────────────────────────────────────────────────────────

    /**
     * Mengekstrak folder ID dari URL Google Drive.
     * Mendukung format: https://drive.google.com/drive/folders/FOLDER_ID
     */
    private fun extractFolderId(path: String): String? =
        Regex("folders/([a-zA-Z0-9_-]+)").find(path)?.groupValues?.get(1)

    /**
     * Membuat klausa "in parents" untuk setiap folder yang dikonfigurasi.
     * Digunakan bersama-sama dengan OR agar semua folder tercakup dalam satu query.
     */
    private fun buildParentClauses(): List<String> {
        return pathList
            .split(";")
            .filter { it.isNotBlank() }
            .mapNotNull { path -> extractFolderId(path)?.let { "'$it' in parents" } }
    }

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
            } catch (e2: Exception) {
                0L
            }
        }
    }

    /**
     * Mengambil metadata.json dari folder manga.
     * Dipanggil hanya saat mangaDetailsParse, bukan saat list/browse.
     */
    private fun getMetadata(mangaFolderId: String): JSONObject {
        return try {
            val query = "'$mangaFolderId' in parents and name = 'metadata.json' and trashed = false"
            val url = buildApiUrl(query, pageSize = 1, fields = "files(id)")
            val responseBody = client.newCall(GET(url, headers)).execute().body?.string()
                ?: return JSONObject()
            val files = JSONObject(responseBody).optJSONArray("files")
                ?: return JSONObject()

            if (files.length() > 0) {
                val fileId = files.getJSONObject(0).getString("id")
                val contentBody = client.newCall(
                    GET("$apiUrl/$fileId?alt=media&key=$apiKey", headers),
                ).execute().body?.string() ?: return JSONObject()
                JSONObject(contentBody)
            } else {
                JSONObject()
            }
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /**
     * PERBAIKAN: Cover URL hanya diambil saat mangaDetailsParse.
     * Tidak boleh dipanggil di dalam popularMangaParse/searchMangaParse
     * karena akan membuat N request tambahan (1 per manga) yang memperlambat browse.
     *
     * Menggunakan query `name = 'cover.*'` agar lebih spesifik dan cepat
     * dibanding mencari semua gambar di folder.
     */
    private fun getCoverUrlForManga(mangaFolderId: String): String {
        return try {
            // Prioritaskan file bernama cover.jpg/png/jpeg/webp (konvensi penamaan)
            val query = "'$mangaFolderId' in parents and trashed = false and " +
                "(name = 'cover.jpg' or name = 'cover.jpeg' or name = 'cover.png' or name = 'cover.webp')"
            val url = buildApiUrl(query, pageSize = 1, fields = "files(id)")
            val responseBody = client.newCall(GET(url, headers)).execute().body?.string()
                ?: return fetchFirstImageAsCover(mangaFolderId)
            val files = JSONObject(responseBody).optJSONArray("files")

            if (files != null && files.length() > 0) {
                "$baseUrl/uc?export=view&id=${files.getJSONObject(0).getString("id")}"
            } else {
                // Fallback: ambil gambar pertama apa pun di folder
                fetchFirstImageAsCover(mangaFolderId)
            }
        } catch (e: Exception) {
            ""
        }
    }

    /** Fallback cover: ambil gambar pertama di folder jika tidak ada file 'cover.*' */
    private fun fetchFirstImageAsCover(mangaFolderId: String): String {
        return try {
            val query = "'$mangaFolderId' in parents and mimeType contains 'image/' and trashed = false"
            val url = buildApiUrl(query, pageSize = 1, fields = "files(id)")
            val responseBody = client.newCall(GET(url, headers)).execute().body?.string()
                ?: return ""
            val files = JSONObject(responseBody).optJSONArray("files")
            if (files != null && files.length() > 0) {
                "$baseUrl/uc?export=view&id=${files.getJSONObject(0).getString("id")}"
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Helper terpusat untuk membangun URL API agar tidak ada duplikasi logika
     * dan semua parameter (pageSize, fields, key) konsisten.
     */
    private fun buildApiUrl(
        query: String,
        orderBy: String = "name",
        pageSize: Int = 100,
        fields: String = listFields,
        pageToken: String? = null,
    ): String {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val sb = StringBuilder("$apiUrl?q=$encodedQuery&orderBy=$orderBy&pageSize=$pageSize&fields=$fields&key=$apiKey")
        if (pageToken != null) sb.append("&pageToken=$pageToken")
        return sb.toString()
    }

    // ─── Browse / Popular ────────────────────────────────────────────────────

    override fun popularMangaRequest(page: Int): Request {
        checkPreferences()
        val parentClauses = buildParentClauses()
        if (parentClauses.isEmpty()) throw Exception("Tidak ada URL folder valid di Path List.")

        val joinedParents = parentClauses.joinToString(" or ")
        val query = "($joinedParents) and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        return GET(buildApiUrl(query), headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val body = response.body?.string() ?: return MangasPage(emptyList(), false)
        val files = JSONObject(body).optJSONArray("files") ?: return MangasPage(emptyList(), false)

        val mangas = (0 until files.length()).map { i ->
            val file = files.getJSONObject(i)
            SManga.create().apply {
                title = file.getString("name")
                url = file.getString("id")
                // PERBAIKAN: thumbnail_url TIDAK diisi di sini.
                // Mengisi thumbnail_url di sini berarti memanggil getCoverUrlForManga()
                // sebanyak N kali secara sinkron → N HTTP request ekstra → browse jadi lambat.
                // Cover akan dimuat saat pengguna membuka halaman detail manga.
                thumbnail_url = null
            }
        }
        return MangasPage(mangas, false)
    }

    // ─── Search ──────────────────────────────────────────────────────────────

    /**
     * PERBAIKAN: Search kini benar-benar memfilter berdasarkan query string.
     * Sebelumnya hanya memanggil popularMangaRequest dan mengabaikan query,
     * sehingga semua entry selalu muncul.
     */
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        checkPreferences()
        val parentClauses = buildParentClauses()
        if (parentClauses.isEmpty()) throw Exception("Tidak ada URL folder valid di Path List.")

        val joinedParents = parentClauses.joinToString(" or ")

        // Jika query kosong, gunakan logika yang sama dengan popularMangaRequest
        val nameFilter = if (query.isNotBlank()) {
            // Escape single quote agar tidak merusak sintaks query Google Drive API
            val safeQuery = query.replace("\\", "\\\\").replace("'", "\\'")
            " and name contains '$safeQuery'"
        } else {
            ""
        }

        val driveQuery = "($joinedParents) and mimeType = 'application/vnd.google-apps.folder'" +
            "$nameFilter and trashed = false"
        return GET(buildApiUrl(driveQuery), headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ─── Manga Details ───────────────────────────────────────────────────────

    override fun mangaDetailsRequest(manga: SManga): Request =
        GET("$apiUrl/${manga.url}?fields=id,name&key=$apiKey", headers)

    /**
     * Cover dan metadata baru diambil di sini, yaitu saat pengguna membuka
     * halaman detail satu manga — bukan saat memuat seluruh daftar.
     */
    override fun mangaDetailsParse(response: Response): SManga {
        val body = response.body?.string() ?: return SManga.create()
        val json = JSONObject(body)
        val mangaId = json.getString("id")
        val metadata = getMetadata(mangaId)

        val authorName = metadata.optString("author", "Unknown")
        val publisherName = metadata.optString("publisher", "")

        return SManga.create().apply {
            title = json.getString("name")
            description = metadata.optString("description", "Manga di-streaming dari Google Drive.")
            author = if (publisherName.isNotEmpty()) "$authorName | $publisherName" else authorName
            status = when (metadata.optInt("status", 0)) {
                1 -> SManga.ONGOING
                2 -> SManga.COMPLETED
                6 -> SManga.CANCELLED
                else -> SManga.UNKNOWN
            }
            // Cover diambil di sini: hanya 1 request per manga yang dibuka, bukan N request sekaligus
            thumbnail_url = getCoverUrlForManga(mangaId)
            initialized = true
        }
    }

    // ─── Chapter List ────────────────────────────────────────────────────────

    override fun chapterListRequest(manga: SManga): Request {
        val query = "'${manga.url}' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        return GET(buildApiUrl(query, fields = chapterFields), headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val body = response.body?.string() ?: return emptyList()
        val files = JSONObject(body).optJSONArray("files") ?: return emptyList()
        val total = files.length()

        return (0 until total).map { i ->
            val file = files.getJSONObject(i)
            SChapter.create().apply {
                name = file.getString("name")
                url = file.getString("id")
                // Nomor chapter dihitung dari belakang setelah reverse agar
                // chapter terbaru (urutan atas) tetap mendapat nomor tertinggi
                chapter_number = (total - i).toFloat()
                date_upload = parseDate(file.optString("modifiedTime"))
            }
        }.reversed()
    }

    // ─── Page List ───────────────────────────────────────────────────────────

    override fun pageListRequest(chapter: SChapter): Request {
        val query = "'${chapter.url}' in parents and mimeType contains 'image/' and trashed = false"
        return GET(buildApiUrl(query, fields = "files(id)"), headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val body = response.body?.string() ?: return emptyList()
        val files = JSONObject(body).optJSONArray("files") ?: return emptyList()

        return (0 until files.length()).map { i ->
            Page(i, "", "$baseUrl/uc?export=view&id=${files.getJSONObject(i).getString("id")}")
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
    }
}
