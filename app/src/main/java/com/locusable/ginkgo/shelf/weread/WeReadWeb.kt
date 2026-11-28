package com.locusable.ginkgo.shelf.weread

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import javax.net.ssl.HttpsURLConnection
import org.json.JSONObject

internal const val WEREAD_ORIGIN = "https://weread.qq.com"
internal const val WEREAD_SHELF_LIMIT = 8 * 1024 * 1024

/** Read-only shelf traffic and the service's own interactive web login. Never logs requests or bodies. */
internal object WeReadWeb {
    class Expired : IOException()
    class Rejected : IOException()

    class Session(val account: String, val cookies: Map<String, String>)
    class Profile(val name: String?, val avatar: Bitmap?)
    class Login(val uid: String, val cookies: MutableMap<String, String>) {
        val confirmation get() = Uri.parse("$WEREAD_ORIGIN/web/confirm").buildUpon().appendQueryParameter("uid", uid).build().toString()
    }

    sealed interface Poll {
        data object Waiting : Poll
        data object RequestRejected : Poll
        data object VerificationExpired : Poll
        data class Verification(val invalid: Boolean) : Poll
        class Confirmed(val session: Session) : Poll
    }

    fun begin(): Login {
        val cookies = linkedMapOf<String, String>()
        val json = jsonRequest("/api/auth/getLoginUid", cookies)
        if (json.errorCode() != 0) throw Rejected()
        val uid = json.value("uid", 150) ?: throw Rejected()
        return Login(uid, cookies)
    }

    fun poll(login: Login, otp: String?): Poll {
        val path = "/api/auth/getLoginInfo?uid=${Uri.encode(login.uid)}&otp=${Uri.encode(otp.orEmpty())}"
        val json = try {
            jsonRequest(path, login.cookies, timeout = 8_000)
        } catch (_: Expired) {
            return Poll.RequestRejected
        }
        val logic = json.optString("logicCode")
        when (logic) {
            "NEED_OTP" -> return Poll.Verification(false)
            "OTP_NOT_MATCH" -> return Poll.Verification(true)
            "OTP_EXPIRED" -> return Poll.VerificationExpired
        }
        val error = json.errorCode()
        if (error == -2012) return Poll.RequestRejected
        if (error != 0) throw Rejected()
        if (json.optBoolean("succeed")) {
            val account = json.value("webLoginVid", 64) ?: login.cookies["wr_vid"] ?: throw Rejected()
            val access = json.value("accessToken", 4096) ?: login.cookies["wr_skey"] ?: throw Rejected()
            login.cookies["wr_vid"] = account
            login.cookies["wr_skey"] = access
            json.value("refreshToken", 4096)?.let { login.cookies["wr_rt"] = Uri.encode(it) }
            login.cookies.putIfAbsent("wr_ql", "0")
            return Poll.Confirmed(session(account, login.cookies) ?: throw Rejected())
        }
        // A long-poll timeout is also returned while the phone is still waiting for confirmation.
        if (logic.isEmpty() || logic == "LOGIN_TIMEOUT") return Poll.Waiting
        throw Rejected()
    }

    fun shelf(session: Session, cached: WeReadShelf?): Pair<WeReadShelf, Session> {
        val cookies = session.cookies.toMutableMap()
        val json = jsonRequest("/web/shelf/sync", cookies, limit = WEREAD_SHELF_LIMIT)
        if (json.sessionRejected()) throw Expired()
        if (json.errorCode() != 0) throw Rejected()
        val updated = session(session.account, cookies) ?: throw Expired()
        return WeReadShelf.parse(json, cached) to updated
    }

    fun profile(session: Session): Profile? {
        val path = "/api/userInfo?userVid=${Uri.encode(session.account)}"
        val json = jsonRequest(path, session.cookies.toMutableMap(), timeout = 4_000, limit = 16 * 1024,
            profileSession = session)
        if (json.errorCode() != 0) return null
        val name = (json.opt("name") as? String)?.trim()?.takeIf { it.length in 1..128 }
        val avatar = json.value("avatar", 2048)?.let(::profileAvatar)
        return if (name != null || avatar != null) Profile(name, avatar) else null
    }

    private val avatarHosts = setOf("wx.qlogo.cn", "thirdwx.qlogo.cn", "q.qlogo.cn", "rescdn.qqmail.com", "cdn.weread.qq.com")

    private fun profileAvatar(source: String): Bitmap? {
        return try {
            val uri = URI(if (source.startsWith("//")) "https:$source" else source).normalize()
            val host = uri.host?.lowercase() ?: return null
            val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: return null
            if (uri.scheme?.lowercase() !in setOf("http", "https") || uri.rawUserInfo != null || uri.port != -1) return null
            if (host !in avatarHosts && !(host == "res.weread.qq.com" && path.startsWith("/wravatar/"))) return null
            val connection = URI("https:${uri.rawSchemeSpecificPart}").toURL().openConnection() as HttpsURLConnection
            val bytes = try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 3_000
                connection.readTimeout = 3_000
                if (connection.responseCode != 200) return null
                connection.inputStream.use { it.readLimited(512 * 1024) }
            } finally {
                connection.disconnect()
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            if (options.outWidth !in 1..4096 || options.outHeight !in 1..4096) return null
            options.inJustDecodeBounds = false
            options.inSampleSize = 1
            while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 96) options.inSampleSize *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        } catch (_: Exception) {
            null
        }
    }

    /** Metadata only, for uploaded books whose shelf row did not carry a usable cover. */
    fun uploadedBookCover(session: Session, bookId: String, timeout: Int = 5_000): String? {
        if (!bookId.isWeReadUploadId()) return null
        val json = jsonRequest("/web/book/info?bookId=${Uri.encode(bookId)}", session.cookies.toMutableMap(), timeout = timeout)
        if (json.errorCode() != 0) return null
        return json.value("cover", WEREAD_COVER_URL_LIMIT) ?: json.optJSONObject("bookInfo")?.value("cover", WEREAD_COVER_URL_LIMIT)
    }

    fun renew(session: Session): Session {
        if (session.cookies["wr_rt"].isNullOrEmpty()) throw Expired()
        val cookies = session.cookies.toMutableMap()
        val json = jsonRequest("/web/login/renewal", cookies, """{"rq":"%2Fweb%2Fbook%2Fread","ql":false}""")
        if (json.sessionRejected() || (json.has("succeed") && !json.optBoolean("succeed"))) throw Expired()
        if (json.errorCode() != 0) throw Rejected()
        // An HTTP success without a flag is inconclusive. The caller tests the returned
        // cookie jar against the shelf once before persisting the session as accepted.
        json.value("accessToken", 4096)?.let { cookies["wr_skey"] = it }
        json.value("refreshToken", 4096)?.let { cookies["wr_rt"] = Uri.encode(it) }
        return session(session.account, cookies) ?: throw Expired()
    }

    fun session(account: String, cookies: Map<String, String>): Session? {
        if (account.isEmpty() || account.length > 64 || cookies["wr_vid"] != account || cookies["wr_skey"].isNullOrEmpty()) return null
        if (!cookies.all { (name, value) -> cookieName.matches(name) && safeCookie(value) }) return null
        return Session(account, cookies.toMap())
    }

    private val cookieName = Regex("wr_[a-zA-Z0-9_]{1,32}")
    private fun safeCookie(value: String) = value.length <= 6144 && value.all { it.code in 33..126 && it !in ";,\\\"" }
    private fun JSONObject.errorCode() = optInt("errCode", optInt("errcode", 0))
    private fun JSONObject.sessionRejected() = errorCode() == -2010 || errorCode() == -2012

    private fun jsonRequest(
        path: String, cookies: MutableMap<String, String>, body: String? = null,
        timeout: Int = 15_000, limit: Int = 96 * 1024, profileSession: Session? = null,
    ): JSONObject {
        val connection = URL(WEREAD_ORIGIN + path).openConnection() as HttpsURLConnection
        try {
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.setRequestProperty("Accept", "application/json, text/plain, */*")
            connection.setRequestProperty("Origin", WEREAD_ORIGIN)
            connection.setRequestProperty("Referer", "$WEREAD_ORIGIN/")
            if (profileSession != null) {
                connection.setRequestProperty("x-vid", profileSession.account)
                connection.setRequestProperty("x-skey", profileSession.cookies.getValue("wr_skey"))
            }
            if (cookies.isNotEmpty()) connection.setRequestProperty("Cookie", cookies.entries.joinToString("; ") { "${it.key}=${it.value}" })
            if (body != null) {
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json;charset=UTF-8")
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            if (status == 401 || status == 403) throw Expired()
            if (status !in 200..299) throw IOException()
            connection.headerFields.forEach { (header, values) ->
                if (!header.equals("Set-Cookie", ignoreCase = true)) return@forEach
                for (raw in values.asReversed()) {
                    val part = raw.substringBefore(';')
                    val name = part.substringBefore('=').trim()
                    val value = part.substringAfter('=', "").trim()
                    if (cookieName.matches(name) && safeCookie(value) && (name in cookies || cookies.size < 16)) {
                        if (value.isEmpty()) cookies.remove(name) else cookies[name] = value
                    }
                }
            }
            return connection.inputStream.use { JSONObject(it.readLimited(limit).toString(Charsets.UTF_8)) }
        } finally {
            connection.disconnect()
        }
    }
}

internal fun InputStream.readLimited(limit: Int): ByteArray {
    val result = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    while (true) {
        val read = read(buffer)
        if (read < 0) return result.toByteArray()
        if (read > limit - result.size()) throw IOException()
        result.write(buffer, 0, read)
    }
}
