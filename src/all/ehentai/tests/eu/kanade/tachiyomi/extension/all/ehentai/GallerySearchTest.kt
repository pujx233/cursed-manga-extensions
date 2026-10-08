package eu.kanade.tachiyomi.extension.all.ehentai

import eu.kanade.tachiyomi.source.model.FilterList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GallerySearchTest {
    private val source = TestSource()

    @Test
    fun popularIsAvailableInTheFilterListWithoutASyntheticSearchOrPagination() {
        val filters = source.getFilterList()
        val list = filters.filterIsInstance<GalleryListFilter>().single()
        list.state = list.values.indexOf("Popular")
        assertEquals(3, list.state)
        assertEquals("https://exhentai.org/popular", source.searchMangaRequest(1, "", filters).url.toString())
    }

    @Test
    fun watchedLanguageAndCategoryFiltersAreIncludedInTheActualRequest() {
        val filters = filters(language = 1, category = "Manga")
        val url = source.searchMangaRequest(1, "", filters).url
        assertEquals("/watched", url.encodedPath)
        assertEquals("language:chinese", url.queryParameter("f_search"))
        assertEquals("1", url.queryParameter("f_manga"))
        assertEquals("0", url.queryParameter("f_doujinshi"))
    }

    @Test
    fun allLanguagesAndUntouchedCategoriesDoNotNarrowWatched() {
        val filters = filters()
        val genres = filters.filterIsInstance<EHentai.GenreGroup>().single().state
        val url = source.searchMangaRequest(1, "", filters).url
        assertEquals("", url.queryParameter("f_search"))
        assertEquals("1", url.queryParameter("f_manga"))
        assertEquals("1", url.queryParameter("f_cosplay"))
        assertFalse(genres.any { it.state })
        assertEquals(url, source.searchMangaRequest(1, "", filters).url)
        assertFalse(genres.any { it.state })
    }

    @Test
    fun tagQueriesStayEncodedWhilePagesAndRatingApplyToWatched() {
        val filters = FilterList(
            *(
                filters(language = 3) + listOf(
                    EHentai.TextFilter("Tags", "tag").apply { state = "full color, -comic" },
                    EHentai.MinPagesOption().apply { state = "50" },
                    EHentai.MaxPagesOption().apply { state = "100" },
                    EHentai.RatingOption().apply { state = 3 },
                )
                ).toTypedArray(),
        )
        val url = source.searchMangaRequest(1, "artist:example", filters).url
        assertEquals("artist:example language:english tag:\"full color\" -tag:\"comic\"", url.queryParameter("f_search"))
        assertEquals(listOf("on"), url.queryParameterValues("f_sp"))
        assertEquals("50", url.queryParameter("f_spf"))
        assertEquals("100", url.queryParameter("f_spt"))
        assertEquals("4", url.queryParameter("f_srdd"))
    }

    @Test
    fun selectingAndClearingACategoryAfterSearchingDoesNotChangeOtherCheckboxes() {
        val filters = filters()
        val genres = filters.filterIsInstance<EHentai.GenreGroup>().single().state
        source.searchMangaRequest(1, "", filters)
        val manga = genres.single { it.name == "Manga" }
        manga.state = true
        val selected = source.searchMangaRequest(1, "", filters).url
        assertEquals("1", selected.queryParameter("f_manga"))
        assertEquals("0", selected.queryParameter("f_doujinshi"))
        assertEquals(listOf("Manga"), genres.filter { it.state }.map { it.name })

        manga.state = false
        val cleared = source.searchMangaRequest(1, "", filters).url
        assertEquals("1", cleared.queryParameter("f_manga"))
        assertEquals("1", cleared.queryParameter("f_doujinshi"))
        assertFalse(genres.any { it.state })
    }

    private fun filters(language: Int = 0, category: String? = null) = FilterList(
        GalleryListFilter().apply { state = 1 },
        GalleryLanguageFilter().apply { state = language },
        EHentai.GenreGroup().apply { state.forEach { it.state = it.name == category } },
        EHentai.AdvancedGroup(),
    )
}
