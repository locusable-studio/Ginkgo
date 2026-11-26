package com.locusable.ginkgo

import java.text.BreakIterator
import java.util.Locale

internal fun coverTitlePreview(name: String): String {
    val title = name.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ').trim()
    val breaks = BreakIterator.getCharacterInstance(Locale.ROOT).apply { setText(title) }
    var end = breaks.first()
    repeat(15) {
        val next = breaks.next()
        if (next == BreakIterator.DONE) return title
        end = next
    }
    return title.substring(0, end)
}
