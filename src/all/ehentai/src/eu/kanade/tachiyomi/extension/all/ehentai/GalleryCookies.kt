package eu.kanade.tachiyomi.extension.all.ehentai

import keiyoushi.network.addCookie
import okhttp3.OkHttpClient

internal fun OkHttpClient.Builder.addGalleryCookies(
    domain: () -> String,
    memberId: () -> String,
    passHash: () -> String,
    igneous: () -> String,
): OkHttpClient.Builder = addCookie(domain) {
    listOf("uconfig" to "prn_n", "nw" to "1") + listOf(
        "ipb_member_id" to memberId(),
        "ipb_pass_hash" to passHash(),
        "igneous" to igneous(),
    ).filter { (_, value) -> value.isNotEmpty() }
}

internal fun galleryCookieValue(name: String, forceEh: Boolean, cookiesForUrl: (String) -> String?): String? {
    val urls = when {
        name == "igneous" -> listOf("https://exhentai.org")
        forceEh -> listOf("https://e-hentai.org", "https://forums.e-hentai.org")
        else -> listOf("https://exhentai.org", "https://e-hentai.org", "https://forums.e-hentai.org")
    }
    return urls.firstNotNullOfOrNull { url ->
        cookiesForUrl(url)?.split(';')?.firstNotNullOfOrNull { cookie ->
            val entry = cookie.trim()
            entry.takeIf { it.substringBefore('=') == name }
                ?.substringAfter('=')
                ?.takeIf(String::isNotEmpty)
        }
    }
}
