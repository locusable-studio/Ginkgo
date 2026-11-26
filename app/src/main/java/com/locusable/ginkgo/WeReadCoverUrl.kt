package com.locusable.ginkgo

import java.net.URI
import java.net.URISyntaxException

/** Only first-party image origins; image requests never carry a WeRead session, including on redirects. */
internal object WeReadCoverUrl {
    private val web = URI("$WEREAD_ORIGIN/")
    private val hosts = setOf(
        "weread.qq.com", "cdn.weread.qq.com", "res.weread.qq.com", "rescdn.qqmail.com", "static.reader.qq.com",
        "wfqqreader.3g.qq.com", "wfqqreader-1252317822.image.myqcloud.com", "wfqqreader-1252317822.file.myqcloud.com",
        "weread-1258476243.file.myqcloud.com",
    )

    fun normalize(source: String): URI? = resolve(web, source, upgrade = true)

    fun redirect(from: URI, location: String): URI? = resolve(from, location, upgrade = false)

    private fun resolve(base: URI, source: String, upgrade: Boolean): URI? {
        if (source.isEmpty() || source.length > WEREAD_COVER_URL_LIMIT) return null
        return try {
            val input = URI(source)
            val uri = base.resolve(input)
            val scheme = uri.scheme?.lowercase()
            val host = uri.host?.lowercase()?.takeIf { it in hosts } ?: return null
            if (uri.rawUserInfo != null || uri.port != -1 || uri.rawPath.isNullOrEmpty() || uri.rawPath == "/0") return null
            if (scheme != "https" && !(upgrade && scheme == "http")) return null
            // Match the web client's migration away from the legacy image server. Keep escaping/signed queries intact.
            val destination = if (host == "wfqqreader.3g.qq.com" && upgrade) "static.reader.qq.com" else host
            URI("https://$destination${uri.rawPath}" + (uri.rawQuery?.let { "?$it" } ?: ""))
        } catch (_: URISyntaxException) {
            null
        }
    }
}
