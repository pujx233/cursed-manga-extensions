package eu.kanade.tachiyomi.extension.all.ehentai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GalleryCookiesTest {
    @Test
    fun readsIgneousFromExhentaiRatherThanTheForum() {
        val domains = mutableListOf<String>()
        val result = galleryCookieValue("igneous", false) { url ->
            domains += url
            when (url) {
                "https://exhentai.org" -> "ipb_member_id=42; igneous=ex-session"
                else -> null
            }
        }
        assertEquals("ex-session", result)
        assertEquals(listOf("https://exhentai.org"), domains)
    }

    @Test
    fun choosesTheActiveSiteAccountBeforeForumCookies() {
        val cookies = mapOf(
            "https://exhentai.org" to "ipb_member_id=ex-account",
            "https://e-hentai.org" to "ipb_member_id=eh-account",
            "https://forums.e-hentai.org" to "ipb_member_id=forum-account",
        )
        assertEquals("ex-account", galleryCookieValue("ipb_member_id", false, cookies::get))
        assertEquals("eh-account", galleryCookieValue("ipb_member_id", true, cookies::get))
    }

    @Test
    fun observesWebViewLoginAndCookieRefreshWithoutRestarting() {
        var cookies: String? = null
        val getCookies: (String) -> String? = { cookies }
        assertNull(galleryCookieValue("igneous", false, getCookies))
        cookies = "igneous=first-session"
        assertEquals("first-session", galleryCookieValue("igneous", false, getCookies))
        cookies = "igneous=refreshed-session"
        assertEquals("refreshed-session", galleryCookieValue("igneous", false, getCookies))
    }

    @Test
    fun acceptsCookieSeparatorsAndKeepsEqualsInValues() {
        assertEquals(
            "value=tail",
            galleryCookieValue("ipb_pass_hash", false) { " unrelated=1;ipb_pass_hash=value=tail ;other=2" },
        )
        assertNull(galleryCookieValue("igneous", false) { "igneous=; unrelated=1" })
    }

    @Test
    fun memberCookiesCanComeFromAnExistingForumLogin() {
        assertEquals(
            "forum-account",
            galleryCookieValue("ipb_member_id", false) { url ->
                "ipb_member_id=forum-account".takeIf { url == "https://forums.e-hentai.org" }
            },
        )
    }
}
