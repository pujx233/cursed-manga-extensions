package eu.kanade.tachiyomi.extension.all.ehentai

import com.sun.net.httpserver.HttpServer
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetSocketAddress

class GalleryHttpCookiesTest {
    @Test
    fun requestsKeepWebViewCookiesAndRefreshTheGallerySession() {
        val receivedCookies = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            receivedCookies += exchange.requestHeaders.getFirst("Cookie").orEmpty()
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
        }
        server.start()
        try {
            var session = "first-session"
            val client = OkHttpClient.Builder()
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                    override fun loadForRequest(url: HttpUrl) = listOf(
                        Cookie.Builder().name("cf_clearance").value("webview-clearance").domain(url.host).build(),
                        Cookie.Builder().name("sk").value("webview-settings").domain(url.host).build(),
                        Cookie.Builder().name("igneous").value("jar-session").domain(url.host).build(),
                    )
                })
                .addGalleryCookies(
                    domain = { "127.0.0.1" },
                    memberId = { "test-member" },
                    passHash = { "test-pass" },
                    igneous = { galleryCookieValue("igneous", false) { "igneous=$session" }.orEmpty() },
                )
                .build()
            val request = Request.Builder().url("http://127.0.0.1:${server.address.port}/watched").build()
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            session = "refreshed-session"
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertTrue(receivedCookies.all { "cf_clearance=webview-clearance" in it && "sk=webview-settings" in it })
            assertTrue("igneous=first-session" in receivedCookies[0])
            assertTrue("igneous=refreshed-session" in receivedCookies[1])
            assertTrue(receivedCookies.all { "uconfig=prn_n" in it && "nw=1" in it })
            assertTrue(receivedCookies.all { "ipb_member_id=test-member" in it && "ipb_pass_hash=test-pass" in it })
            assertTrue(receivedCookies.all { cookie -> cookie.split("; ").count { it.startsWith("igneous=") } == 1 })

            session = ""
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertTrue("igneous=jar-session" in receivedCookies[2])

            val unrelatedClient = OkHttpClient.Builder()
                .addGalleryCookies({ "exhentai.org" }, { "test-member" }, { "test-pass" }, { "ex-session" })
                .build()
            unrelatedClient.newCall(request).execute().use { assertEquals(200, it.code) }
            assertEquals("", receivedCookies[3])
        } finally {
            server.stop(0)
        }
    }
}
