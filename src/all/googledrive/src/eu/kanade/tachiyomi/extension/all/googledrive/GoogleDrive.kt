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
        if (apiKey.isBlank() || pathList.isBlank()) throw Exception("API Key atau Path List belum diisi!")
    }

    // Fungsi untuk mengubah format waktu Google Drive (RFC 3339) ke Milliseconds
    private fun parseDate(dateStr: String?): Long {
        if (dateStr.isNullOrEmpty()) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.getDefault()).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            format.parse(dateStr)?.time ?: 0L
        } catch (e: Exception) {
            try {
                val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.getDefault()).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                format.parse(dateStr)?.time ?: 0L
            } catch (e2: Exception) {
                0L
            }
        }
    }

    private fun getMetadata(mangaFolderId: String): JSONObject {
        try {
            val query = "'$mangaFolderId' in parents and name = 'metadata.json' and trashed = false"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$apiUrl?q=$encodedQuery&pageSize=1&fields=files(id)&key=$apiKey"
            
            val responseBody = client.newCall(GET(url, headers)).execute().body?.string() ?: return JSONObject()
            val files = JSONObject(responseBody).optJSONArray("files")
            
            if (files != null && files.length() > 0) {
                val fileId = files.getJSONObject(0).getString("id")
                val contentBody = client.newCall(GET("$apiUrl/$fileId?alt=media&key=$apiKey", headers)).execute().body?.string() ?: return JSONObject()
                return JSONObject(contentBody)
            }
        } catch (e: Exception) {
            // Abaikan error dan kembalikan JSON kosong
        }
        return JSONObject()
    }

    override fun popularMangaRequest(page: Int): Request {
        checkPreferences()
        val paths = pathList.split(";").filter { it.isNotBlank() }
        val queries = paths.mapNotNull { path ->
            Regex("folders/([a-zA-Z0-9_-]+)").find(path)?.groupValues?.get(1)?.let { "'$it' in parents" }
        }
        if (queries.isEmpty()) throw Exception("URL Folder tidak valid.")
        
        val joinedQueries = queries.joinToString(" or ")
        val query = "($joinedQueries) and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl?q=$encodedQuery&orderBy=name&fields=files(id,name)&key=$apiKey"
        
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val responseBody = response.body?.string() ?: return MangasPage(emptyList(), false)
        val files = JSONObject(responseBody).optJSONArray("files") ?: return MangasPage(emptyList(), false)
        
        val mangas = mutableListOf<SManga>()
        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            mangas.add(SManga.create().apply {
                title = file.getString("name")
                url = file.getString("id")
                thumbnail_url = getCoverUrlForManga(url)
            })
        }
        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = popularMangaRequest(page)
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsParse(response: Response): SManga {
        val responseBody = response.body?.string() ?: return SManga.create()
        val json = JSONObject(responseBody)
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
            thumbnail_url = getCoverUrlForManga(mangaId)
            initialized = true
        }
    }

    private fun getCoverUrlForManga(mangaFolderId: String): String {
        return try {
            val query = "'$mangaFolderId' in parents and mimeType contains 'image/' and trashed = false"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$apiUrl?q=$encodedQuery&pageSize=1&fields=files(id)&key=$apiKey"
            
            val responseBody = client.newCall(GET(url, headers)).execute().body?.string() ?: return ""
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

    override fun chapterListRequest(manga: SManga): Request {
        val query = "'${manga.url}' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        // Meminta field modifiedTime untuk mendeteksi perubahan isi folder
        return GET("$apiUrl?q=$encodedQuery&orderBy=name&fields=files(id,name,modifiedTime)&key=$apiKey", headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val responseBody = response.body?.string() ?: return emptyList()
        val files = JSONObject(responseBody).optJSONArray("files") ?: return emptyList()
        
        return (0 until files.length()).map { i ->
            val file = files.getJSONObject(i)
            SChapter.create().apply {
                name = file.getString("name")
                url = file.getString("id")
                chapter_number = (i + 1).toFloat()
                // Menyematkan waktu update folder ke date_upload
                date_upload = parseDate(file.optString("modifiedTime"))
            }
        }.reversed()
    }

    override fun pageListRequest(chapter: SChapter): Request {
        val query = "'${chapter.url}' in parents and mimeType contains 'image/' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        return GET("$apiUrl?q=$encodedQuery&orderBy=name&fields=files(id)&key=$apiKey", headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val responseBody = response.body?.string() ?: return emptyList()
        val files = JSONObject(responseBody).optJSONArray("files") ?: return emptyList()
        
        return (0 until files.length()).map { i ->
            Page(i, "", "$baseUrl/uc?export=view&id=${files.getJSONObject(i).getString("id")}")
        }
    }

    override fun mangaDetailsRequest(manga: SManga): Request = GET("$apiUrl/${manga.url}?fields=id,name&key=$apiKey", headers)

    override fun latestUpdatesRequest(page: Int): Request = throw Exception("Not used")
    override fun latestUpdatesParse(response: Response): MangasPage = throw Exception("Not used")
    override fun imageUrlParse(response: Response): String = throw Exception("Not used")

    companion object {
        private const val API_KEY_PREF = "API_KEY_PREF"
        private const val PATH_LIST_PREF = "PATH_LIST_PREF"
    }
}
