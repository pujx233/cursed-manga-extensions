package eu.kanade.tachiyomi.extension.all.ehentai

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.webkit.CookieManager
import androidx.preference.CheckBoxPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceScreen
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.asObservableSuccess
import eu.kanade.tachiyomi.source.ConfigurableSource
import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.Filter.CheckBox
import eu.kanade.tachiyomi.source.model.Filter.Select
import eu.kanade.tachiyomi.source.model.Filter.Text
import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.MangasPage
import eu.kanade.tachiyomi.source.model.Page
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.model.UpdateStrategy
import eu.kanade.tachiyomi.source.online.HttpSource
import keiyoushi.annotation.Source
import keiyoushi.utils.asJsoup
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParseDateTime
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Document
import rx.Observable
import rx.schedulers.Schedulers
import java.time.ZoneOffset

@Source
abstract class EHentai :
    HttpSource(),
    ConfigurableSource {

    private val preferences: SharedPreferences by getPreferencesLazy()

    private val webViewCookieManager: CookieManager by lazy { CookieManager.getInstance() }
    private val forceEh: Boolean get() = getForceEhPref()

    override val baseUrl: String
        get() {
            if (System.getenv("CI") == "true" || forceEh) return "https://e-hentai.org"
            val credentials = getGalleryCredentials(false)
            return if (credentials.memberId.isNotEmpty() && credentials.passHash.isNotEmpty()) {
                "https://exhentai.org"
            } else {
                "https://e-hentai.org"
            }
        }

    override val supportsLatest = true

    override fun toString() = name

    private val latestPagination = GalleryPagination()
    private val searchPagination = GalleryPagination()
    private val galleryPages = GalleryPageLoader()

    private fun genericMangaParse(response: Response, pagination: GalleryPagination? = null): MangasPage {
        val doc = response.asJsoup()
        val listing = GalleryList(doc)
        val hasNextPage = pagination?.update(response.request, listing.nextPageUrl) == true
        return MangasPage(listing.galleries, hasNextPage)
    }

    override fun chapterListRequest(manga: SManga) = exGet("$baseUrl${manga.url}")

    override fun fetchMangaDetails(manga: SManga): Observable<SManga> = galleryPage(mangaDetailsRequest(manga), GalleryPageUse.DETAILS)
        .map { mangaDetailsParse(it).apply { initialized = true } }

    override fun fetchChapterList(manga: SManga): Observable<List<SChapter>> = galleryPage(chapterListRequest(manga), GalleryPageUse.CHAPTERS)
        .map(::chapterListParse)

    override fun chapterListParse(response: Response): List<SChapter> = chapterListParse(response.asJsoup())

    private fun chapterListParse(document: Document): List<SChapter> = listOf(
        SChapter.create().apply {
            url = ExGalleryMetadata.normalizeUrl(document.location().toHttpUrl().encodedPath)
            name = "Chapter"
            chapter_number = 1f
            date_upload = document.galleryPostedDate()
        },
    )

    override fun fetchPageList(chapter: SChapter): Observable<List<Page>> = galleryPage(exGet("$baseUrl${chapter.url}"), GalleryPageUse.READER)
        .map { it.galleryReaderPages() }

    override fun fetchImageUrl(page: Page): Observable<String> {
        val url = page.url.toHttpUrl()
        val imagePage = if (url.pathSegments.firstOrNull() == "g") {
            galleryPage(exGet(page.url), GalleryPageUse.IMAGE_PAGES)
                .map { it.galleryImagePage(page.index) }
        } else {
            Observable.just(page.url)
        }
        return imagePage.flatMap { chapterPageCall(exGet(it)).map(::imageUrlParse) }
    }

    private fun galleryPage(request: Request, use: GalleryPageUse): Observable<Document> {
        val key = request.url.newBuilder().removeAllQueryParameters("nw").build().toString()
        return galleryPages.load(key, use) {
            chapterPageCall(request).map { response ->
                val document = response.asJsoup()
                if (use == GalleryPageUse.IMAGE_PAGES) {
                    val previews = document.getElementById("gdt")
                    check(previews?.selectFirst("a[href]") != null) { "No image pages found" }
                    // Retain only the previews needed for reading, not the gallery's tags and comments.
                    Document(document.location()).apply { body().appendChild(previews) }
                } else {
                    document
                }
            }
        }
    }

    private fun chapterPageCall(request: Request) = client.newCall(request).asObservableSuccess()
        .subscribeOn(Schedulers.io())

    // The website's Popular list has no next-page link.
    override fun popularMangaRequest(page: Int) = exGet("$baseUrl/popular")

    override fun searchMangaRequest(page: Int, query: String, filters: FilterList): Request {
        val firstPageUrl = gallerySearchUrl(baseUrl, query, filters)
        if (firstPageUrl.encodedPath == "/popular") return exGet(firstPageUrl.toString())
        return searchPagination.request(exGet(firstPageUrl.toString()), page)
    }

    override fun latestUpdatesRequest(page: Int) = latestPagination.request(exGet(baseUrl), page)

    override fun popularMangaParse(response: Response) = genericMangaParse(response)
    override fun searchMangaParse(response: Response) = genericMangaParse(response, searchPagination.takeUnless { response.request.url.encodedPath == "/popular" })
    override fun latestUpdatesParse(response: Response) = genericMangaParse(response, latestPagination)

    private fun exGet(url: String): Request = GET(url, headers)

    /**
     * Parse gallery page to metadata model
     */
    @SuppressLint("DefaultLocale")
    override fun mangaDetailsParse(response: Response) = mangaDetailsParse(response.asJsoup())

    private fun mangaDetailsParse(document: Document) = with(document) {
        with(ExGalleryMetadata()) {
            url = document.location().toHttpUrl().encodedPath
            title = getElementById("gn")?.text().nullIfBlank()

            altTitle = getElementById("gj")?.text().nullIfBlank()

            // Thumbnail is set as background of element in style attribute
            thumbnailUrl = selectFirst("#gd1 div")?.attr("style").nullIfBlank()?.let {
                it.substring(it.indexOf('(') + 1 until it.lastIndexOf(')'))
            }
            category = selectFirst("#gdc div")?.text().nullIfBlank()?.lowercase()

            uploader = getElementById("gdn")?.text().nullIfBlank()

            select("#gdd tr").forEach { row ->
                val label = row.selectFirst(".gdt1")?.text()?.removeSuffix(":")?.lowercase()
                val value = row.selectFirst(".gdt2")?.text().nullIfBlank() ?: return@forEach
                when (label) {
                    "posted" -> datePosted = EX_DATE_FORMAT.tryParseDateTime(value, ZoneOffset.UTC)
                    "visible" -> visible = value
                    "language" -> {
                        language = value.removeSuffix(TR_SUFFIX).trim().nullIfBlank()
                        translated = value.endsWith(TR_SUFFIX, true)
                    }
                    "file size" -> size = ignore { parseHumanReadableByteCount(value)?.toLong() }
                    "length" -> length = value.substringBefore(' ').replace(",", "").toIntOrNull()
                    "favorited" -> favorites = value.substringBefore(' ').replace(",", "").toIntOrNull()
                }
            }

            averageRating = getElementById("rating_label")?.text()?.removePrefix("Average:")?.trim()?.toDoubleOrNull()
            ratingCount = getElementById("rating_count")?.text()?.replace(",", "")?.toIntOrNull()

            // Parse tags
            tags.clear()
            select("#taglist tr").forEach {
                val namespace = it.select(".tc").text().removeSuffix(":")
                val currentTags = it.select("div").map { element ->
                    Tag(
                        element.text(),
                        element.hasClass("gtl"),
                    )
                }
                tags[namespace] = currentTags
            }

            // Copy metadata to manga
            SManga.create().apply {
                copyTo(this)
                update_strategy = UpdateStrategy.ONLY_FETCH_ONCE
            }
        }
    }

    private fun searchMangaByIdRequest(id: String) = exGet("$baseUrl/g/${id.trimEnd('/')}/")

    private fun searchMangaByIdParse(document: Document): MangasPage {
        val details = mangaDetailsParse(document)
        details.initialized = true
        return MangasPage(listOf(details), false)
    }

    override fun fetchSearchManga(page: Int, query: String, filters: FilterList): Observable<MangasPage> = if (query.startsWith("https://")) {
        val url = query.toHttpUrl()
        if (url.pathSegments.size < 3) {
            throw Exception("Unsupported url")
        }
        val id = url.pathSegments[1]
        val key = url.pathSegments[2]
        fetchSearchManga(page, "${PREFIX_ID_SEARCH}$id/$key", filters)
    } else if (query.startsWith(PREFIX_ID_SEARCH)) {
        val id = query.removePrefix(PREFIX_ID_SEARCH)
        galleryPage(searchMangaByIdRequest(id), GalleryPageUse.DETAILS)
            .map(::searchMangaByIdParse)
    } else {
        super.fetchSearchManga(page, query, filters)
    }

    override fun pageListParse(response: Response) = throw UnsupportedOperationException()

    override fun imageUrlParse(response: Response): String = imageUrlParse(response, true)

    private fun imageUrlParse(response: Response, isGetBakImageUrl: Boolean): String = response.asJsoup()
        .galleryImageUrl(response.request.url, getOriginalImagePref(), isGetBakImageUrl)

    override val client by lazy {
        network.client.newBuilder()
            .addGalleryCookies(::getGalleryCredentials)
            .addGalleryImageRetry({ headers }) { imageUrlParse(it, false) }
            .build()
    }

    // Filters
    override fun getFilterList() = FilterList(
        GalleryListFilter(),
        GalleryLanguageFilter(),
        Filter.Header("Watched tags and Favorites use your website account."),
        Filter.Header("Popular is the website's fixed list; filters apply to other lists."),
        GenreGroup(),
        Filter.Header("Separate tags with commas (,)"),
        Filter.Header("Prepend with dash (-) to exclude"),
        Filter.Header("Use 'Female Tags' or 'Male Tags' for specific categories. 'Tags' searches all categories."),
        TextFilter("Tags", "tag"),
        TextFilter("Female Tags", "female"),
        TextFilter("Male Tags", "male"),
        AdvancedGroup(),
    )

    internal open class TextFilter(name: String, val type: String, val specific: String = "") : Text(name)

    class GenreOption(name: String, private val genreId: String) :
        CheckBox(name, false),
        UriFilter {
        override fun addToUri(builder: HttpUrl.Builder) = addToUri(builder, state)

        fun addToUri(builder: HttpUrl.Builder, selected: Boolean) {
            builder.addQueryParameter("f_$genreId", if (selected) "1" else "0")
        }
    }

    class GenreGroup :
        UriGroup<GenreOption>(
            "Genres",
            listOf(
                GenreOption("Dōjinshi", "doujinshi"),
                GenreOption("Manga", "manga"),
                GenreOption("Artist CG", "artistcg"),
                GenreOption("Game CG", "gamecg"),
                GenreOption("Western", "western"),
                GenreOption("Non-H", "non-h"),
                GenreOption("Image Set", "imageset"),
                GenreOption("Cosplay", "cosplay"),
                GenreOption("Asian Porn", "asianporn"),
                GenreOption("Misc", "misc"),
            ),
        ) {
        override fun addToUri(builder: HttpUrl.Builder) {
            val allCategories = state.none { it.state }
            state.forEach { it.addToUri(builder, allCategories || it.state) }
        }
    }

    class AdvancedOption(name: String, private val param: String, defValue: Boolean = false) :
        CheckBox(name, defValue),
        UriFilter {
        override fun addToUri(builder: HttpUrl.Builder) {
            if (state) {
                builder.addQueryParameter(param, "on")
            }
        }
    }

    open class PageOption(name: String, private val queryKey: String) :
        Text(name),
        UriFilter {
        override fun addToUri(builder: HttpUrl.Builder) {
            if (state.isNotBlank()) {
                if (builder.build().queryParameter("f_sp") == null) {
                    builder.addQueryParameter("f_sp", "on")
                }

                builder.addQueryParameter(queryKey, state.trim())
            }
        }
    }

    class MinPagesOption : PageOption("Minimum Pages", "f_spf")
    class MaxPagesOption : PageOption("Maximum Pages", "f_spt")

    class RatingOption :
        Select<String>(
            "Minimum Rating",
            arrayOf(
                "Any",
                "2 stars",
                "3 stars",
                "4 stars",
                "5 stars",
            ),
        ),
        UriFilter {
        override fun addToUri(builder: HttpUrl.Builder) {
            if (state > 0) {
                builder.addQueryParameter("f_srdd", (state + 1).toString())
                builder.addQueryParameter("f_sr", "on")
            }
        }
    }

    // Explicit type arg for listOf() to workaround this: KT-16570
    class AdvancedGroup :
        UriGroup<Filter<*>>(
            "Advanced Options",
            listOf(
                AdvancedOption("Search Gallery Name", "f_sname", true),
                AdvancedOption("Search Gallery Tags", "f_stags", true),
                AdvancedOption("Search Gallery Description", "f_sdesc"),
                AdvancedOption("Search Torrent Filenames", "f_storr"),
                AdvancedOption("Only Show Galleries With Torrents", "f_sto"),
                AdvancedOption("Search Low-Power Tags", "f_sdt1"),
                AdvancedOption("Search Downvoted Tags", "f_sdt2"),
                AdvancedOption("Show Expunged Galleries", "f_sh"),
                RatingOption(),
                MinPagesOption(),
                MaxPagesOption(),
            ),
        )

    companion object {
        const val PREFIX_ID_SEARCH = "id:"
        const val TR_SUFFIX = "TR"

        // Preferences vals
        private const val ORIGINAL_IMAGE_PREF_KEY = "ORIGINAL_IMAGE"
        private const val ORIGINAL_IMAGE_PREF_TITLE = "Original Image"
        private const val ORIGINAL_IMAGE_PREF_SUMMARY = "If checked, if your account has permission, it will use the original image and the image enhancement process will be slower"
        private const val ORIGINAL_IMAGE_PREF_DEFAULT_VALUE = false

        private const val MEMBER_ID_PREF_KEY = "MEMBER_ID"
        private const val MEMBER_ID_PREF_TITLE = "ipb_member_id"
        private const val MEMBER_ID_PREF_SUMMARY = "ipb_member_id value"
        private const val MEMBER_ID_PREF_DEFAULT_VALUE = ""

        private const val PASS_HASH_PREF_KEY = "PASS_HASH"
        private const val PASS_HASH_PREF_TITLE = "ipb_pass_hash"
        private const val PASS_HASH_PREF_SUMMARY = "ipb_pass_hash value"
        private const val PASS_HASH_PREF_DEFAULT_VALUE = ""

        private const val IGNEOUS_PREF_KEY = "IGNEOUS"
        private const val IGNEOUS_PREF_TITLE = "igneous"
        private const val IGNEOUS_PREF_SUMMARY = "igneous value override"
        private const val IGNEOUS_PREF_DEFAULT_VALUE = ""

        private const val FORCE_EH = "FORCE_EH"
        private const val FORCE_EH_TITLE = "Force e-hentai"
        private const val FORCE_EH_SUMMARY = "Force e-hentai to avoid content on exhentai"
        private const val FORCE_EH_DEFAULT_VALUE = true
    }

    // Preferences

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        val forceEhPref = CheckBoxPreference(screen.context).apply {
            key = FORCE_EH
            title = FORCE_EH_TITLE
            summary = FORCE_EH_SUMMARY
            setDefaultValue(FORCE_EH_DEFAULT_VALUE)
        }

        val originalImagePref = CheckBoxPreference(screen.context).apply {
            key = "${ORIGINAL_IMAGE_PREF_KEY}_$lang"
            title = ORIGINAL_IMAGE_PREF_TITLE
            summary = ORIGINAL_IMAGE_PREF_SUMMARY
            setDefaultValue(ORIGINAL_IMAGE_PREF_DEFAULT_VALUE)
        }

        val memberIdPref = EditTextPreference(screen.context).apply {
            key = MEMBER_ID_PREF_KEY
            title = MEMBER_ID_PREF_TITLE
            summary = MEMBER_ID_PREF_SUMMARY

            setDefaultValue(MEMBER_ID_PREF_DEFAULT_VALUE)
        }

        val passHashPref = EditTextPreference(screen.context).apply {
            key = PASS_HASH_PREF_KEY
            title = PASS_HASH_PREF_TITLE
            summary = PASS_HASH_PREF_SUMMARY

            setDefaultValue(PASS_HASH_PREF_DEFAULT_VALUE)
        }

        val igneousPref = EditTextPreference(screen.context).apply {
            key = IGNEOUS_PREF_KEY
            title = IGNEOUS_PREF_TITLE
            summary = IGNEOUS_PREF_SUMMARY

            setDefaultValue(IGNEOUS_PREF_DEFAULT_VALUE)
        }

        screen.addPreference(forceEhPref)
        screen.addPreference(memberIdPref)
        screen.addPreference(passHashPref)
        screen.addPreference(igneousPref)
        screen.addPreference(originalImagePref)
    }

    private fun getOriginalImagePref(): Boolean = preferences.getBoolean("${ORIGINAL_IMAGE_PREF_KEY}_$lang", ORIGINAL_IMAGE_PREF_DEFAULT_VALUE)

    private fun getGalleryCredentials(forceEh: Boolean): GalleryCredentials = galleryCredentials(forceEh, webViewCookieManager::getCookie) { cookieTitle ->
        val preferenceKey = when (cookieTitle) {
            MEMBER_ID_PREF_TITLE -> MEMBER_ID_PREF_KEY
            PASS_HASH_PREF_TITLE -> PASS_HASH_PREF_KEY
            else -> IGNEOUS_PREF_KEY
        }
        preferences.getString(preferenceKey, "").orEmpty()
    }

    private fun getForceEhPref(): Boolean = preferences.getBoolean(FORCE_EH, FORCE_EH_DEFAULT_VALUE)
}
