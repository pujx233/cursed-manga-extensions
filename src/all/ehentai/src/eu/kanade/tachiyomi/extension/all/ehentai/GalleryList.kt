package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.SManga
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.jsoup.nodes.Document
import java.io.IOException

internal class GalleryList(document: Document) {
    val nextPageUrl: String? = document.selectFirst("a#unext[href]")
        ?.takeIf { it.attr("href").isNotBlank() }
        ?.absUrl("href")
        ?.takeIf(String::isNotBlank)

    val galleries: List<SManga> = document.select(".itg .glink").map { titleElement ->
        val galleryLink = titleElement.closest("a")!!
        val gallery = titleElement.closest("tr, .gl1t")!!
        SManga.create().apply {
            title = titleElement.text()
            url = ExGalleryMetadata.normalizeUrl(galleryLink.absUrl("href").toHttpUrl().encodedPath)
            thumbnail_url = gallery.selectFirst(".glthumb img, .gl1e img, .gl3t img")?.let {
                it.attr("data-src").nullIfBlank() ?: it.absUrl("src").nullIfBlank()
            }
        }
    }
}

internal class GalleryPagination {
    private class Session(firstPage: String) {
        // Browsing only needs recent cursors for retries, not every previously loaded page.
        val pages = object : LinkedHashMap<Int, String>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>) = size > 8
        }.apply { put(1, firstPage) }
    }
    private class PageRequest(val key: String, val session: Session, val page: Int)

    private val sessions = object : LinkedHashMap<String, Session>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Session>): Boolean {
            if (size <= 16) return false
            eldest.value.pages.clear()
            return true
        }
    }

    @Synchronized
    fun request(firstPage: Request, page: Int): Request {
        val key = firstPage.url.toString()
        val session = if (page == 1) {
            Session(key).also { sessions.put(key, it)?.pages?.clear() }
        } else {
            sessions[key]
        }
        val url = session?.pages?.get(page)
            ?: throw IOException("No next page cursor; refresh the list")
        return firstPage.newBuilder()
            .url(url)
            .tag(PageRequest::class.java, PageRequest(key, session, page))
            .build()
    }

    @Synchronized
    fun update(request: Request, nextPageUrl: String?): Boolean {
        val page = request.tag(PageRequest::class.java) ?: return false
        // A response from before a refresh must not replace the new session's cursor.
        if (sessions[page.key] !== page.session) return false
        if (nextPageUrl == null) {
            // Retire only exhausted searches; interleaved searches may still be paged.
            sessions.remove(page.key)
            page.session.pages.clear()
        } else {
            page.session.pages[page.page + 1] = nextPageUrl
        }
        return nextPageUrl != null
    }
}

internal class GalleryListFilter :
    Filter.Select<String>(
        "Gallery list",
        arrayOf("All galleries", "Watched tags", "Favorites", "Popular"),
    ) {
    val path: String
        get() = when (state) {
            1 -> "watched"
            2 -> "favorites.php"
            3 -> "popular"
            else -> ""
        }
}

private val galleryLanguages = arrayOf(
    "全部语言" to null,
    "中文" to "chinese",
    "日文" to "japanese",
    "英文" to "english",
    "韩文" to "korean",
    "法文" to "french",
    "德文" to "german",
    "西班牙文" to "spanish",
    "葡萄牙文" to "portuguese",
    "意大利文" to "italian",
    "荷兰文" to "dutch",
    "俄文" to "russian",
    "波兰文" to "polish",
    "匈牙利文" to "hungarian",
    "泰文" to "thai",
    "越南文" to "vietnamese",
)

internal class GalleryLanguageFilter : Filter.Select<String>("语言", galleryLanguages.map { it.first }.toTypedArray()) {
    fun addToQuery(query: String): String = galleryLanguages[state].second
        ?.let { "$query language:$it".trim() }
        ?: query
}
