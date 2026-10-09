package eu.kanade.tachiyomi.extension.all.ehentai

import com.sun.net.httpserver.HttpServer
import keiyoushi.utils.asJsoup
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GalleryImageTest {
    @Test
    fun resizedOriginalAndReloadLinksKeepTheirDifferentSemantics() {
        val page = "https://exhentai.org/s/hash/1-3".toHttpUrl()
        val document = Jsoup.parse(
            """<img id="img" src="https://images.test/3.jpg">
                <a href="/fullimg/1/3">Original</a><a id="loadfail" onclick="return nl('reload-key')">Retry</a>""",
            page.toString(),
        )
        assertEquals("https://images.test/3.jpg#https://exhentai.org/s/hash/1-3?nl=reload-key", document.galleryImageUrl(page, false, true))
        assertEquals("https://images.test/3.jpg", document.galleryImageUrl(page, false, false))
        assertEquals("https://exhentai.org/fullimg/1/3?nl=reload-key", document.galleryImageUrl(page, true, true))
    }

    @Test
    fun missingImagesFailInsteadOfReturningABlankPage() {
        assertThrows(IllegalStateException::class.java) {
            Jsoup.parse("<p>Image unavailable</p>").galleryImageUrl("https://exhentai.org/s/hash/1-3".toHttpUrl(), false, true)
        }
    }

    @Test
    fun successfulImagesDoNotContactTheReloadServer() {
        ImageServer().use { server ->
            server.client.newCall(server.request("/ok")).execute().use { assertEquals("image", it.body.string()) }
            assertEquals(listOf("/ok"), server.attempts)
        }
    }

    @Test
    fun failedImagesResolveTheAlternateServerOnceAndRetainHeaders() {
        ImageServer().use { server ->
            server.client.newCall(server.request("/failed")).execute().use { assertEquals("image", it.body.string()) }
            assertEquals(listOf("/failed", "/reload", "/ok"), server.attempts)
            assertTrue(server.referers.all { it == "https://exhentai.org/" })
        }
    }

    @Test
    fun cancellingAnImageDoesNotStartAReloadRequest() {
        ImageServer().use { server ->
            val finished = CountDownLatch(1)
            val call = server.client.newCall(server.request("/blocked"))
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) = finished.countDown()
                override fun onResponse(call: Call, response: Response) {
                    response.close()
                    finished.countDown()
                }
            })
            assertTrue(server.started.await(5, TimeUnit.SECONDS))
            call.cancel()
            server.gate.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))
            assertEquals(listOf("/blocked"), server.attempts)
        }
    }

    private class ImageServer : AutoCloseable {
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        private val executor = Executors.newFixedThreadPool(2)
        val attempts = CopyOnWriteArrayList<String>()
        val referers = CopyOnWriteArrayList<String>()
        val started = CountDownLatch(1)
        val gate = CountDownLatch(1)
        private val baseUrl = "http://127.0.0.1:${server.address.port}"
        private val headers = Headers.headersOf("Referer", "https://exhentai.org/")
        val client = OkHttpClient.Builder()
            .addGalleryImageRetry({ headers }) { it.asJsoup().galleryImageUrl(it.request.url, false, false) }
            .addInterceptor {
                attempts += it.request().url.encodedPath
                it.proceed(it.request())
            }
            .build()

        init {
            server.executor = executor
            server.createContext("/") { exchange ->
                val path = exchange.requestURI.path
                referers += exchange.requestHeaders.getFirst("Referer").orEmpty()
                try {
                    if (path == "/blocked") {
                        started.countDown()
                        gate.await(5, TimeUnit.SECONDS)
                    }
                    val body = if (path == "/reload") "<img id='img' src='$baseUrl/ok'>" else "image"
                    val bytes = body.toByteArray()
                    exchange.sendResponseHeaders(if (path == "/failed") 503 else 200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                } catch (_: IOException) {
                    // The cancellation test deliberately closes the connection.
                } finally {
                    exchange.close()
                }
            }
            server.start()
        }

        fun request(path: String): Request = Request.Builder().url("$baseUrl$path#$baseUrl/reload").headers(headers).build()

        override fun close() {
            gate.countDown()
            server.stop(0)
            executor.shutdownNow()
        }
    }
}
