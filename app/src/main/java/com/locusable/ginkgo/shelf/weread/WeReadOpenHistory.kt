package com.locusable.ginkgo.shelf.weread

import android.content.Context

/** Local open times for the current WeRead account. */
internal class WeReadOpenHistory(context: Context) {
    private val prefs = context.getSharedPreferences("weread_open_times", Context.MODE_PRIVATE)

    fun times(account: String?): Map<String, Long> {
        if (account == null || prefs.getString("account", null) != account) return emptyMap()
        return prefs.all.mapNotNull { (key, time) ->
            (time as? Long)?.let { key.removePrefix("book:") to it }
        }.toMap()
    }

    fun record(account: String, bookId: String) {
        val edit = prefs.edit()
        if (prefs.getString("account", null) != account) edit.clear().putString("account", account)
        edit.putLong("book:$bookId", System.currentTimeMillis()).apply()
    }

    fun clear() = prefs.edit().clear().apply()
}
