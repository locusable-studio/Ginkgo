package com.locusable.ginkgo.shelf.weread

import android.content.Intent
import android.net.Uri

internal object WeReadBookLink {
    private const val PACKAGE = "com.tencent.weread.eink"

    fun intent(bookId: String): Intent = Intent(Intent.ACTION_VIEW, Uri.Builder()
        .scheme("weread").authority("reading").appendQueryParameter("bId", bookId).build())
        .setPackage(PACKAGE)

    fun fallback(readerLink: String?): Intent? {
        val uri = readerLink?.let(Uri::parse)?.takeIf {
            it.scheme == "https" && it.encodedAuthority == "weread.qq.com" && it.path.orEmpty().startsWith("/web/reader/")
        } ?: return null
        return Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(PACKAGE)
    }
}
