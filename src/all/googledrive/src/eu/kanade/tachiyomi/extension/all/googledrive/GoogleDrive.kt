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

class GoogleDrive : HttpSource(), ConfigurableSource {

    override val name = "Google Drive"
    // baseUrl diubah agar tombol WebView (Bola Dunia) membuka Google Drive untuk proses Login
    override val baseUrl = "https://drive.google.com" 
    override val lang = "all" // Diubah menjadi multi-bahasa
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
            summary = "API Key tetap dibutuhkan untuk membaca susunan/struktur folder dengan cepat."
            dialogTitle = "API Key"
        }
        screen.addPreference(apiKeyPref)

        val pathListPref = EditTextPreference(screen.context).apply {
            key = PATH_LIST_PREF
            title = "Path list"
            summary = "Pisahkan dengan titik koma (;). Contoh: [Naruto]https://drive.google.com/drive/folders/ID_FOLDER"
            dialogTitle = "Enter drive paths"
        }
        screen.addPreference(pathListPref)
    }

    private fun checkApiKey() {
        if (apiKey.isBlank()) throw Exception("Harap masukkan API Key di Pengaturan Ekstensi.")
    }

    // ==========================================================
    // 1. POPULAR MANGA (Mengambil dari Path List Pengaturan)
    // ==========================================================
    override fun popularMangaRequest(page: Int): Request {
        // Kita tidak menembak API di sini, cukup memicu fungsi parse
        return GET(baseUrl, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        if (pathList.isBlank()) throw Exception("Path list kosong. Masukkan link folder di Pengaturan Ekstensi.")
        
        val mangas = mutableListOf<SManga>()
        // Membedah Path List: [Nama]URL;[Nama]URL
        val paths = pathList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        
        val regex = Regex("\\[(.*?)\\](.*folders/([a-zA-Z0-9_-]+))")

        for (path in paths) {
            val matchResult = regex.find(path)
            if (matchResult != null) {
                val titleName = matchResult.groupValues[1]
                val folderId = matchResult.groupValues[3]
                
                mangas.add(SManga.create().apply {
                    title = titleName
                    url = folderId
                    status = SManga.UNKNOWN
                    thumbnail_url = getCoverUrlForManga(folderId)
                })
            }
        }
        return MangasPage(mangas, false)
    }

    // Fungsi pencarian dimatikan sementara karena kita menggunakan Path List statis
    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = popularMangaRequest(page)
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==========================================================
    // 2. BACA STRUKTUR FOLDER (Menggunakan API Key)
    // ==========================================================
    private fun getCoverUrlForManga(mangaFolderId: String): String {
        return try {
            val query = "'$mangaFolderId' in parents and mimeType contains 'image/' and trashed = false"
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val url = "$apiUrl?q=$encodedQuery&pageSize=1&fields=files(id)&key=$apiKey"
            
            val request = GET(url, headers)
            val res = client.newCall(request).execute()
            val json = JSONObject(res.body!!.string())
            val files = json.optJSONArray("files")
            
            if (files != null && files.length() > 0) {
                val fileId = files.getJSONObject(0).getString("id")
                // URL gambar menggunakan endpoint Google Drive web
                "$baseUrl/uc?export=view&id=$fileId"
            } else {
                ""
            }
        } catch (e: Exception) { "" }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        checkApiKey()
        val url = "$apiUrl/${manga.url}?fields=id,name&key=$apiKey"
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val json = JSONObject(response.body!!.string())
        return SManga.create().apply {
            title = json.getString("name")
            description = "Manga di-streaming dari Google Drive Anda.\nPastikan Anda sudah Login via WebView (Ikon Bola Dunia)."
            thumbnail_url = getCoverUrlForManga(json.getString("id"))
            initialized = true
        }
    }

    override fun chapterListRequest(manga: SManga): Request {
        checkApiKey()
        val mangaFolderId = manga.url
        val query = "'$mangaFolderId' in parents and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl?q=$encodedQuery&orderBy=name&fields=files(id,name)&key=$apiKey"
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

    // ==========================================================
    // 3. STREAMING GAMBAR (MENGGUNAKAN WEBVIEW COOKIES)
    // ==========================================================
    override fun pageListRequest(chapter: SChapter): Request {
        checkApiKey()
        val chapterFolderId = chapter.url
        val query = "'$chapterFolderId' in parents and mimeType contains 'image/' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val url = "$apiUrl?q=$encodedQuery&orderBy=name&fields=files(id)&key=$apiKey"
        return GET(url, headers)
    }

    override fun pageListParse(response: Response): List<Page> {
        val json = JSONObject(response.body!!.string())
        val files = json.optJSONArray("files") ?: return emptyList()
        val pages = mutableListOf<Page>()

        for (i in 0 until files.length()) {
            val fileId = files.getJSONObject(i).getString("id")
            // Endpoint ini akan otomatis menggunakan cookies dari WebView Aniyomi
            val imageUrl = "$baseUrl/uc?export=view&id=$fileId"
            pages.add(Page(i, imageUrl, imageUrl))
        }
        return pages
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used")
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    companion object {
        private const val API_KEY_PREF = "API_KEY_PREF"
        private const val PATH_LIST_PREF = "PATH_LIST_PREF"
    }
}
