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
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParseDateTime
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.Response
import rx.Observable
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

    private fun genericMangaParse(response: Response, pagination: GalleryPagination? = null): MangasPage {
        val doc = response.asJsoup()
        val listing = GalleryList(doc)
        pagination?.update(response.request, listing.nextPageUrl)
        return MangasPage(listing.galleries.map { it.toSManga() }, pagination != null && listing.nextPageUrl != null)
    }

    override fun chapterListRequest(manga: SManga) = exGet("$baseUrl${manga.url}")

    override fun chapterListParse(response: Response): List<SChapter> = listOf(
        SChapter.create().apply {
            url = ExGalleryMetadata.normalizeUrl(response.request.url.encodedPath)
            name = "Chapter"
            chapter_number = 1f
            date_upload = response.asJsoup().galleryPostedDate()
        },
    )

    override fun fetchPageList(chapter: SChapter) = fetchChapterPage(chapter, "$baseUrl${chapter.url}").map {
        it.mapIndexed { i, s ->
            Page(i, s)
        }
    }!!

    /**
     * Recursively fetch chapter pages
     */
    private fun fetchChapterPage(
        chapter: SChapter,
        np: String,
        pastUrls: List<String> = emptyList(),
    ): Observable<List<String>> {
        val urls = ArrayList(pastUrls)
        return chapterPageCall(np).flatMap {
            val jsoup = it.asJsoup()
            urls += jsoup.galleryImagePages()
            jsoup.nextGalleryPageUrl()?.let { string ->
                fetchChapterPage(chapter, string, urls)
            } ?: Observable.just(urls)
        }
    }

    private fun chapterPageCall(np: String) = client.newCall(chapterPageRequest(np)).asObservableSuccess()
    private fun chapterPageRequest(np: String) = exGet(np)

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
    override fun mangaDetailsParse(response: Response) = with(response.asJsoup()) {
        with(ExGalleryMetadata()) {
            url = response.request.url.encodedPath
            title = select("#gn").text().nullIfBlank()?.trim()

            altTitle = select("#gj").text().nullIfBlank()?.trim()

            // Thumbnail is set as background of element in style attribute
            thumbnailUrl = select("#gd1 div").attr("style").nullIfBlank()?.let {
                it.substring(it.indexOf('(') + 1 until it.lastIndexOf(')'))
            }
            category = select("#gdc div").text().nullIfBlank()?.trim()?.lowercase()

            uploader = select("#gdn").text().nullIfBlank()?.trim()

            // Parse the table
            select("#gdd tr").forEach {
                it.select(".gdt1")
                    .text()
                    .nullIfBlank()
                    ?.trim()
                    ?.let { left ->
                        it.select(".gdt2")
                            .text()
                            .nullIfBlank()
                            ?.trim()
                            ?.let { right ->
                                ignore {
                                    when (
                                        left.removeSuffix(":")
                                            .lowercase()
                                    ) {
                                        "posted" -> datePosted = EX_DATE_FORMAT.tryParseDateTime(right, ZoneOffset.UTC)

                                        "visible" -> visible = right.nullIfBlank()

                                        "language" -> {
                                            language = right.removeSuffix(TR_SUFFIX).trim().nullIfBlank()
                                            translated = right.endsWith(TR_SUFFIX, true)
                                        }

                                        "file size" -> size = parseHumanReadableByteCount(right)?.toLong()

                                        "length" -> length = right.removeSuffix("pages").trim().nullIfBlank()?.toInt()

                                        "favorited" -> favorites = right.removeSuffix("times").trim().nullIfBlank()?.toInt()
                                    }
                                }
                            }
                    }
            }

            // Parse ratings
            ignore {
                averageRating = select("#rating_label")
                    .text()
                    .removePrefix("Average:")
                    .trim()
                    .nullIfBlank()
                    ?.toDouble()
                ratingCount = select("#rating_count")
                    .text()
                    .trim()
                    .nullIfBlank()
                    ?.toInt()
            }

            // Parse tags
            tags.clear()
            select("#taglist tr").forEach {
                val namespace = it.select(".tc").text().removeSuffix(":")
                val currentTags = it.select("div").map { element ->
                    Tag(
                        element.text().trim(),
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

    private fun searchMangaByIdRequest(id: String) = GET("$baseUrl/g/$id", headers)

    private fun searchMangaByIdParse(response: Response, id: String): MangasPage {
        val details = mangaDetailsParse(response)
        details.url = "/g/$id/"
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
        client.newCall(searchMangaByIdRequest(id))
            .asObservableSuccess()
            .map { response -> searchMangaByIdParse(response, id) }
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
            .addInterceptor { chain ->
                val request = chain.request()
                val result = runCatching { chain.proceed(request) }
                val bakUrl = request.url.fragment
                    ?: return@addInterceptor result.getOrThrow()

                if (result.isFailure || result.getOrNull()?.isSuccessful != true) {
                    result.getOrNull()?.close()
                    val newRequest = GET(bakUrl, headers)
                    val newImageUrl = imageUrlParse(chain.proceed(newRequest), false)
                    val newImageRequest = request.newBuilder()
                        .url(newImageUrl)
                        .build()

                    chain.proceed(newImageRequest)
                } else {
                    result.getOrThrow()
                }
            }
            .build()
    }

    // Filters
    override fun getFilterList() = FilterList(
        GalleryListFilter(),
        GalleryLanguageFilter(),
        Filter.Header("Watched tags and Favorites use your website account."),
        Filter.Header("Popular uses website account filters; search filters do not apply."),
        Filter.Header("Website tag blocking does not apply to Favorites."),
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

    class GenreOption(name: String, val mask: Int) : CheckBox(name, false)

    class GenreGroup :
        UriGroup<GenreOption>(
            "Categories (website defaults unless selected)",
            listOf(
                GenreOption("Dōjinshi", 2),
                GenreOption("Manga", 4),
                GenreOption("Artist CG", 8),
                GenreOption("Game CG", 16),
                GenreOption("Western", 512),
                GenreOption("Non-H", 256),
                GenreOption("Image Set", 32),
                GenreOption("Cosplay", 64),
                GenreOption("Asian Porn", 128),
                GenreOption("Misc", 1),
            ),
        ) {
        override fun addToUri(builder: HttpUrl.Builder) {
            if (state.any { it.state }) {
                builder.addQueryParameter("f_cats", state.filterNot { it.state }.sumOf { it.mask }.toString())
            }
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
            }
        }
    }

    // Explicit type arg for listOf() to workaround this: KT-16570
    class AdvancedGroup :
        UriGroup<Filter<*>>(
            "Advanced Options",
            listOf(
                AdvancedOption("Only Show Galleries With Torrents", "f_sto"),
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

    private fun getGalleryCredentials(forceEh: Boolean, requestCookie: String? = null): GalleryCredentials = galleryCredentials(forceEh, webViewCookieManager::getCookie, requestCookie) { cookieTitle ->
        val preferenceKey = when (cookieTitle) {
            MEMBER_ID_PREF_TITLE -> MEMBER_ID_PREF_KEY
            PASS_HASH_PREF_TITLE -> PASS_HASH_PREF_KEY
            else -> IGNEOUS_PREF_KEY
        }
        preferences.getString(preferenceKey, "").orEmpty()
    }

    private fun getForceEhPref(): Boolean = preferences.getBoolean(FORCE_EH, FORCE_EH_DEFAULT_VALUE)
}
