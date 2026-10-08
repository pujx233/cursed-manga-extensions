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
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy

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
            var galleryCredentialReads = 0
            val client = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns { listOf(InetAddress.getByName("127.0.0.1")) }
                .cookieJar(object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) = Unit
                    override fun loadForRequest(url: HttpUrl) = listOf(
                        Cookie.Builder().name("cf_clearance").value("webview-clearance").domain(url.host).build(),
                        Cookie.Builder().name("sk").value("webview-settings").domain(url.host).build(),
                        Cookie.Builder().name("igneous").value("jar-session").domain(url.host).build(),
                    )
                })
                .addGalleryCookies { forceEh ->
                    galleryCredentialReads++
                    val site = if (forceEh) "eh" else "ex"
                    GalleryCredentials("$site-member", "$site-pass", session)
                }
                .build()
            assertEquals(0, galleryCredentialReads)
            val request = Request.Builder().url("http://exhentai.org:${server.address.port}/watched").build()
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            session = "refreshed-session"
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertTrue(receivedCookies.all { "cf_clearance=webview-clearance" in it && "sk=webview-settings" in it })
            assertTrue("igneous=first-session" in receivedCookies[0])
            assertTrue("igneous=refreshed-session" in receivedCookies[1])
            assertTrue(receivedCookies.all { "uconfig=prn_n" in it && "nw=1" in it })
            assertTrue(receivedCookies.all { "ipb_member_id=ex-member" in it && "ipb_pass_hash=ex-pass" in it })
            assertTrue(receivedCookies.all { cookie -> cookie.split("; ").count { it.startsWith("igneous=") } == 1 })

            session = ""
            client.newCall(request).execute().use { assertEquals(200, it.code) }
            assertTrue("igneous=jar-session" in receivedCookies[2])

            client.newCall(request.newBuilder().url("http://e-hentai.org:${server.address.port}/watched").build())
                .execute().use { assertEquals(200, it.code) }
            assertTrue("ipb_member_id=eh-member" in receivedCookies[3])
            assertEquals(4, galleryCredentialReads)

            var credentialReads = 0
            val unrelatedClient = OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                .dns { listOf(InetAddress.getByName("127.0.0.1")) }
                .addGalleryCookies {
                    credentialReads++
                    GalleryCredentials("test-member", "test-pass", "ex-session")
                }
                .build()
            val initializationReads = credentialReads
            val imageRequest = request.newBuilder().url("http://images.test:${server.address.port}/image").build()
            unrelatedClient.newCall(imageRequest).execute().use { assertEquals(200, it.code) }
            assertEquals("", receivedCookies[4])
            assertEquals(initializationReads, credentialReads)
        } finally {
            server.stop(0)
        }
    }
}
