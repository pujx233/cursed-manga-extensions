package eu.kanade.tachiyomi.extension.all.ehentai

import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryMetadataTest {
    @Test
    fun galleryLanguageComesFromItsDetailsEvenWhenTheSourceSupportsAllLanguages() {
        val source = TestSource()
        assertEquals("all", source.lang)
        assertEquals("E-Hentai", source.toString())
        listOf("Chinese", "English", "Japanese").forEach { language ->
            val response = htmlResponse(
                Request.Builder().url("https://exhentai.org/g/1/abcdef/").build(),
                """
                <h1 id="gn">Example gallery</h1>
                <div id="gdd"><table><tr>
                  <td class="gdt1">Language:</td><td class="gdt2">$language <span>TR</span></td>
                </tr></table></div>
                <div id="taglist"><table><tr>
                  <td class="tc">language:</td><td><div class="gt">${language.lowercase()}</div></td>
                </tr></table></div>
                """,
            )
            val manga = response.use(source::mangaDetailsParse)
            assertTrue(manga.description.orEmpty().contains("Language: $language TR"))
            assertFalse(manga.description.orEmpty().contains("Language: all", ignoreCase = true))
            assertEquals("language:${language.lowercase()}", manga.genre)
        }
    }

    @Test
    fun missingGalleryLanguageDoesNotFallBackToTheSourceLanguage() {
        val response = htmlResponse(
            Request.Builder().url("https://exhentai.org/g/1/abcdef/").build(),
            """<h1 id="gn">Example gallery</h1><div id="gdd"><table></table></div>""",
        )
        val manga = response.use(TestSource()::mangaDetailsParse)
        assertFalse(manga.description.orEmpty().contains("Language:"))
    }
}
