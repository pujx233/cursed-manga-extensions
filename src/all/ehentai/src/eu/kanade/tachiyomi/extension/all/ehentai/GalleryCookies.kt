package eu.kanade.tachiyomi.extension.all.ehentai

import okhttp3.OkHttpClient

internal class GalleryCredentials(val memberId: String, val passHash: String, val igneous: String)

internal fun OkHttpClient.Builder.addGalleryCookies(
    credentialsForSite: (Boolean) -> GalleryCredentials,
): OkHttpClient.Builder = addNetworkInterceptor { chain ->
    val request = chain.request()
    val host = request.url.host
    val forceEh = when {
        host == "e-hentai.org" || host.endsWith(".e-hentai.org") -> true
        host == "exhentai.org" || host.endsWith(".exhentai.org") -> false
        else -> return@addNetworkInterceptor chain.proceed(request)
    }
    val credentials = credentialsForSite(forceEh)
    val cookies = linkedMapOf("uconfig" to "prn_n", "nw" to "1")
    if (credentials.memberId.isNotEmpty()) cookies["ipb_member_id"] = credentials.memberId
    if (credentials.passHash.isNotEmpty()) cookies["ipb_pass_hash"] = credentials.passHash
    if (credentials.igneous.isNotEmpty()) cookies["igneous"] = credentials.igneous
    // WebView owns the live session. Merge it into this request without writing it back per request.
    val cookieHeader = buildList {
        request.header("Cookie")?.splitToSequence(';')?.map(String::trim)
            ?.filter { it.isNotEmpty() && it.substringBefore('=') !in cookies }?.let(::addAll)
        cookies.forEach { (name, value) -> add("$name=$value") }
    }.joinToString("; ")
    chain.proceed(request.newBuilder().header("Cookie", cookieHeader).build())
}

private val ehCookieUrls = listOf("https://e-hentai.org", "https://forums.e-hentai.org")
private val exCookieUrls = listOf("https://exhentai.org") + ehCookieUrls
private val credentialNames = setOf("ipb_member_id", "ipb_pass_hash", "igneous")

internal fun galleryCredentials(
    forceEh: Boolean,
    cookiesForUrl: (String) -> String?,
    storedCookie: (String) -> String,
): GalleryCredentials {
    val snapshots = mutableMapOf<String, Map<String, String>>()
    fun cookies(url: String): Map<String, String> = snapshots.getOrPut(url) {
        buildMap {
            cookiesForUrl(url)?.splitToSequence(';')?.forEach {
                val entry = it.trim()
                val name = entry.substringBefore('=')
                val value = entry.substringAfter('=', "")
                if (name in credentialNames && value.isNotEmpty() && name !in this) put(name, value)
            }
        }
    }
    val sites = if (forceEh) ehCookieUrls else exCookieUrls
    val account = sites.firstNotNullOfOrNull { url ->
        val values = cookies(url)
        val member = values["ipb_member_id"]
        val pass = values["ipb_pass_hash"]
        if (member != null && pass != null) member to pass else null
    } ?: run {
        val member = storedCookie("ipb_member_id")
        val pass = storedCookie("ipb_pass_hash")
        if (member.isNotEmpty() && pass.isNotEmpty()) member to pass else "" to ""
    }
    val igneous = if (forceEh) "" else cookies("https://exhentai.org")["igneous"] ?: storedCookie("igneous")
    return GalleryCredentials(account.first, account.second, igneous)
}
