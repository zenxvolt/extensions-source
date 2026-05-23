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
    override val baseUrl = "https://drive.google.com" 
    override val lang = "all" 
    override val supportsLatest = false

    private val apiUrl = "https://www.googleapis.com/drive/v3/files"
    
    // TODO: Ganti teks di bawah ini dengan API Key Anda yang asli!
    private val apiKey = "ISI_API_KEY_ANDA_DISINI"

    private val preferences: SharedPreferences by lazy {
        Injekt.get<Application>().getSharedPreferences("source_$id", 0x0000)
    }

    private val pathList: String
        get() = preferences.getString(PATH_LIST_PREF, "") ?: ""

    // Kolom API Key Dihapus, UI menjadi sangat bersih
    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val pathListPref = EditTextPreference(screen.context).apply {
            key = PATH_LIST_PREF
            title = "Path list"
            summary = "Pisahkan dengan titik koma (;). Contoh: [Collection]https://drive.google.com/drive/folders/ID_FOLDER"
            dialogTitle = "Enter drive paths"
        }
        screen.addPreference(pathListPref)
    }

    // ==========================================================
    // 1. POPULAR MANGA (Membaca Isi Folder Utama dari Path List)
    // ==========================================================
    override fun popularMangaRequest(page: Int): Request {
        val paths = pathList.split(";").map { it.trim() }.filter { it.isNotEmpty() }
        if (paths.isEmpty()) throw Exception("Path list kosong. Masukkan link folder di Pengaturan.")

        val parentQueries = mutableListOf<String>()
        val regex = Regex("folders/([a-zA-Z0-9_-]+)")
        
        for (path in paths) {
            val match = regex.find(path)
            if (match != null) {
                parentQueries.add("'${match.groupValues[1]}' in parents")
            }
        }
        
        if (parentQueries.isEmpty()) throw Exception("URL Folder tidak valid. Pastikan format URL Drive benar.")
        
        // Gabungkan semua ID folder dari Path List
        val combinedParents = parentQueries.joinToString(" or ")
        val query = "($combinedParents) and mimeType = 'application/vnd.google-apps.folder' and trashed = false"
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        
        val url = "$apiUrl?q=$encodedQuery&orderBy=name&fields=files(id,name)&key=$apiKey"
        return GET(url, headers)
    }

    override fun popularMangaParse(response: Response): MangasPage {
        val json = JSONObject(response.body!!.string())
        val files = json.optJSONArray("files") ?: return MangasPage(emptyList(), false)
        val mangas = mutableListOf<SManga>()

        for (i in 0 until files.length()) {
            val file = files.getJSONObject(i)
            val mangaId = file.getString("id")
            mangas.add(SManga.create().apply {
                title = file.getString("name") // Judul komik = Nama sub-folder
                url = mangaId
                status = SManga.UNKNOWN
                thumbnail_url = getCoverUrlForManga(mangaId)
            })
        }
        return MangasPage(mangas, false)
    }

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request = popularMangaRequest(page)
    override fun searchMangaParse(response: Response): MangasPage = popularMangaParse(response)

    // ==========================================================
    // 2. FUNGSI COVER & DETAIL MANGA
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
                "$baseUrl/uc?export=view&id=$fileId"
            } else { "" }
        } catch (e: Exception) { "" }
    }

    override fun mangaDetailsRequest(manga: SManga): Request {
        val url = "$apiUrl/${manga.url}?fields=id,name&key=$apiKey"
        return GET(url, headers)
    }

    override fun mangaDetailsParse(response: Response): SManga {
        val json = JSONObject(response.body!!.string())
        return SManga.create().apply {
            title = json.getString("name")
            description = "Manga di-streaming dari Google Drive.\nPastikan Anda sudah Login via WebView (Ikon Bola Dunia)."
            thumbnail_url = getCoverUrlForManga(json.getString("id"))
            initialized = true
        }
    }

    // ==========================================================
    // 3. DAFTAR CHAPTER (Membaca Sub-Folder dari Folder Komik)
    // ==========================================================
    override fun chapterListRequest(manga: SManga): Request {
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
            chapters.add(SChapter.create().apply {
                name = file.getString("name")
                url = file.getString("id")
                chapter_number = (i + 1).toFloat()
            })
        }
        return chapters.reversed() // Diurutkan dari atas ke bawah (terbaru)
    }

    // ==========================================================
    // 4. STREAMING HALAMAN (Terintegrasi Cookies WebView)
    // ==========================================================
    override fun pageListRequest(chapter: SChapter): Request {
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
            // Endpoint uc?export otomatis menyedot cookies login Anda dari Aniyomi
            val imageUrl = "$baseUrl/uc?export=view&id=$fileId"
            pages.add(Page(i, imageUrl, imageUrl))
        }
        return pages
    }

    override fun latestUpdatesRequest(page: Int): Request = throw UnsupportedOperationException("Not used")
    override fun latestUpdatesParse(response: Response): MangasPage = throw UnsupportedOperationException("Not used")
    override fun imageUrlParse(response: Response): String = throw UnsupportedOperationException("Not used")

    companion object {
        private const val PATH_LIST_PREF = "PATH_LIST_PREF"
    }
}
