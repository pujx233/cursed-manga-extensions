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

    val galleries: List<Gallery> = document.select(".itg .glink").map { titleElement ->
        val galleryLink = titleElement.closest("a")!!
        val gallery = titleElement.closest("tr, .gl1t")!!
        Gallery(
            title = titleElement.text(),
            creator = galleryCreator(titleElement.text(), gallery.select("[title^=artist:]").map { it.attr("title").removePrefix("artist:") }.distinct()),
            url = ExGalleryMetadata.normalizeUrl(galleryLink.absUrl("href").toHttpUrl().encodedPath),
            thumbnailUrl = gallery.selectFirst(".glthumb img, .gl1e img, .gl3t img")?.let {
                it.attr("data-src").nullIfBlank() ?: it.absUrl("src").nullIfBlank()
            },
        )
    }
}

internal class Gallery(val title: String, val url: String, val thumbnailUrl: String?, val creator: String?) {
    fun toSManga() = SManga.create().apply {
        title = this@Gallery.title
        url = this@Gallery.url
        thumbnail_url = thumbnailUrl
        author = creator
        artist = creator
    }
}

internal class GalleryPagination {
    private class Session(val pages: MutableMap<Int, String> = mutableMapOf())
    private class PageRequest(val key: String, val session: Session, val page: Int)

    private val sessions = mutableMapOf<String, Session>()

    @Synchronized
    fun request(firstPage: Request, page: Int): Request {
        val key = firstPage.url.toString()
        val session = if (page == 1) {
            Session(mutableMapOf(1 to key)).also { sessions[key] = it }
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
    fun update(request: Request, nextPageUrl: String?) {
        val page = request.tag(PageRequest::class.java) ?: return
        // A response from before a refresh must not replace the new session's cursor.
        if (sessions[page.key] !== page.session) return
        if (nextPageUrl == null) {
            page.session.pages.remove(page.page + 1)
        } else {
            page.session.pages[page.page + 1] = nextPageUrl
        }
    }
}

internal class GalleryListFilter :
    Filter.Select<String>(
        "Gallery list",
        arrayOf("All galleries", "Watched tags", "Favorites"),
    ) {
    val path: String
        get() = when (state) {
            1 -> "watched"
            2 -> "favorites.php"
            else -> ""
        }
}

private val galleryLanguages = arrayOf(
    "All languages" to null,
    "Japanese" to "japanese",
    "English" to "english",
    "Chinese" to "chinese",
    "Korean" to "korean",
    "French" to "french",
    "German" to "german",
    "Spanish" to "spanish",
    "Portuguese" to "portuguese",
    "Italian" to "italian",
    "Dutch" to "dutch",
    "Russian" to "russian",
    "Polish" to "polish",
    "Hungarian" to "hungarian",
    "Thai" to "thai",
    "Vietnamese" to "vietnamese",
)

internal class GalleryLanguageFilter :
    Filter.Select<String>(
        "Language",
        galleryLanguages.map { it.first }.toTypedArray(),
    ) {
    fun addToQuery(query: String): String = galleryLanguages[state].second
        ?.let { "$query language:$it".trim() }
        ?: query
}
