package eu.kanade.tachiyomi.extension.id.googledrive

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

class GoogleDrive : HttpSource(), ConfigurableSource {

    override val name = "Google Drive"
    override val baseUrl = "https://www.googleapis.com/drive/v3/files"
    override val lang = "id"
    override val supportsLatest = false

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val apiKey: String
        get() = preferences.getString(API_KEY_PREF, "") ?: ""

    private val rootFolderId: String
        get() = preferences.getString(FOLDER_ID_PREF, "") ?: ""

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val apiKeyPref = EditTextPreference(screen.context).apply {
            key = API_KEY_PREF
            title = "API Key Google Cloud"
            summary = "Masukkan API Key (Drive API v3) Anda."
            dialogTitle = "API Key"
            setDefaultValue("")
        }
        screen.addPreference(apiKeyPref)

        val folderIdPref = EditTextPreference(screen.context).apply {
            key = FOLDER_ID_PREF
            title = "ID Folder Utama (Root Folder ID)"
            summary = "ID folder Google Drive yang berisi daftar manga."
            dialogTitle = "Folder ID"
            setDefaultValue("")
        }
        screen.addPreference(folderIdPref)
    }

    private fun checkPreferences() {
        if (apiKey.isBlank() || rootFolderId.isBlank()) {
            throw Exception("Buka pengaturan ekstensi (ikon gir) untuk memasukkan API Key dan Folder ID.")
        }
    }

    private fun getCoverUrlForManga(mangaFolderId: String): String {
        return try {
            val query = "'$mangaFolderId' in parents and mimeType contains 'image/' and trashed = false"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$baseUrl?q=$encodedQuery&pageSize=1&fields=files(id)&key=$apiKey"
            
            val request = GET(url, headers)
            val response = client.newCall(request).execute()
            val json = JSONObject(response.body!!.string())
            val files = json.optJSONArray("files")
            
            if (files != null && files.length() > 0) {
                val fileId = files.getJSONObject(0).getString("id")
                "$baseUrl/$fileId?alt=media&key=$apiKey"
            } else {
                ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    override fun popularMangaRequest(page: Int): Request {
        checkPreferences()
        val query = "'$rootFolderId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl?q=$encodedQuery&orderBy=name&fields=files(id,name)&key=$apiKey"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val json = JSONObject(response.body!!.string())
        val files = json.optJSONArray("files") ?: return MangasPage(emptyList(), false)
        val mangas = mutableListOf<SManga>()

        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val mangaId = file.getString("id")
            val manga = SManga.create().apply {
                title = file.getString("name")
                url = mangaId
                status = SManga.UNKNOWN
                thumbnail_url = getCoverUrlForManga(mangaId) 
            }
            mangas.add(manga)
        }
        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        checkPreferences()
        val driveQuery = "'$rootFolderId' in parents and mimeType = 'application/vnd.google-apps.folder' and name contains '$query' and trashed = false"
        val encodedQuery = URLEncoder.encode(driveQuery, "UTF-8")
        val url = "$baseUrl?q=$encodedQuery&orderBy=name&fields=files(id,name)&key=$apiKey"
        return GET(url, headers)
    }

    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    override fun mangaDetailsRequest(manga: SManga): Request {
        checkPreferences()
        val url = "$baseUrl/${manga.url}?fields=id,name&key=$apiKey"
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val json = JSONObject(response.body!!.string())
        return SManga.create().apply {
            title = json.getString("name")
            description = "Manga di-streaming langsung dari Google Drive."
            thumbnail_url = getCoverUrlForManga(json.getString("id"))
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        checkPreferences()
        val mangaFolderId = manga.url
        val query = "'$mangaFolderId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl?q=$encodedQuery&orderBy=name&fields=files(id,name)&key=$apiKey"
        return GET(url, headers)
    }

    override fun chapterListParse(response: Response): List<SChapter> {
        val json = JSONObject(response.body!!.string())
        val files = json.optJSONArray("files") ?: return emptyList()
        val chapters = mutableListOf<SChapter>()

        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val chapter = SChapter.create().apply {
                name = file.getString("name")
                url = file.getString("id")
                chapter_number = (i + 1).toFloat()
            }
            chapters.add(chapter)
        }
        return chapters.reversed()
    }

    override fun pageListRequest(chapter: SChapter): Request {
        checkPreferences()
        val chapterFolderId = chapter.url
        val query = "'$chapterFolderId' in parents and mimeType contains 'image/' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$baseUrl?q=$encodedQuery&orderBy=name&fields=files(id)&key=$apiKey"
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val json = JSONObject(response.body!!.string())
        val files = json.optJSONArray("files") ?: return emptyList()
        val pages = mutableListOf<Page>()

        for (i in 0 until files.length()) {
            val fileId = files.getJSONObject(i).getString("id")
            val imageUrl = "$baseUrl/$fileId?alt=media&key=$apiKey"
            pages.add(Page(i, imageUrl, imageUrl))
        }
        return pages
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used")
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    companion object {
        private const val API_KEY_PREF = "API_KEY_PREF"
        private const val FOLDER_ID_PREF = "ROOT_FOLDER_ID_PREF"
    }
}
