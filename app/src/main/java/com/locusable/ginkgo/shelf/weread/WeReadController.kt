package com.locusable.ginkgo.shelf.weread

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.IOException
import java.util.concurrent.Executors

/** One account and one sync/poll queue across both Xiaomi launcher tasks. Main-thread state. */
internal class WeReadController private constructor(private val context: Context) {
    enum class State { LOADING, IDLE, SYNCING, STARTING_LOGIN, SCAN, VERIFY, VERIFYING, SAVING }
    enum class Notice { SYNC_FAILED, LOGIN_FAILED, LOGIN_STATUS_FAILED, LOGIN_TIMEOUT, CODE_EXPIRED, BAD_CODE }

    var state = State.LOADING
        private set
    var notice: Notice? = null
        private set
    var snapshot = WeReadSnapshot()
        private set
    var profile: WeReadWeb.Profile? = null
        private set
    var qr: Array<BooleanArray>? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val profileWorker by lazy { Executors.newSingleThreadExecutor() }
    private var profileLoading = false
    private var profileRequest = 0
    private var nextProfileAttempt = 0L
    private val store = WeReadStore(context)
    private val openHistory by lazy { WeReadOpenHistory(context) }
    private val observers = linkedSetOf<() -> Unit>()
    private var started = false
    private var login: WeReadWeb.Login? = null
    private var loginUntil = 0L
    private var rejectedPolls = 0
    private var generation = 0
    private val nextPoll = Runnable { poll(null) }
    private val expireLogin = Runnable { stopLogin(Notice.LOGIN_TIMEOUT) }

    private val loadedCallbacks = mutableListOf<() -> Unit>()

    fun observe(listener: () -> Unit) {
        if (!observers.add(listener)) return
        listener()
        whenLoaded()
    }

    fun whenLoaded(ready: (() -> Unit)? = null) {
        if (state != State.LOADING) {
            ready?.invoke()
            return
        }
        if (ready != null) loadedCallbacks.add(ready)
        if (started) return
        started = true
        runTask({
            val saved = store.load()
            saved to saved.account?.let(store::loadProfile)
        }) { result ->
            result.onSuccess { (saved, cachedProfile) ->
                snapshot = saved
                profile = cachedProfile
            }
            setState(State.IDLE)
            val callbacks = loadedCallbacks.toList()
            loadedCallbacks.clear()
            callbacks.forEach { it() }
        }
    }

    fun unobserve(listener: () -> Unit) {
        observers.remove(listener)
        if (observers.isEmpty() && state in LOGIN_STATES) stopLogin()
    }

    fun loadProfile() {
        val session = snapshot.session ?: return
        if (state == State.SAVING || profileLoading || SystemClock.elapsedRealtime() < nextProfileAttempt) return
        profileLoading = true
        val request = ++profileRequest
        profileWorker.execute {
            val loaded = try { WeReadWeb.profile(session) } catch (_: Exception) { null }
            main.post {
                if (request != profileRequest) return@post
                profileLoading = false
                if (snapshot.session?.account != session.account) return@post
                nextProfileAttempt = SystemClock.elapsedRealtime() +
                    if (loaded?.name != null && loaded.avatar != null) 900_000 else 60_000
                if (loaded == null) return@post
                val cached = profile
                val updated = WeReadWeb.Profile(loaded.name ?: cached?.name, loaded.avatar ?: cached?.avatar)
                if (updated.name == cached?.name && updated.avatar === cached?.avatar) return@post
                profile = updated
                worker.execute {
                    try { store.saveProfile(session.account, updated) } catch (_: Exception) { }
                }
                observers.toList().forEach { it() }
            }
        }
    }

    fun sync() {
        if (state != State.IDLE) return
        val before = snapshot
        val session = before.session ?: return
        setState(State.SYNCING)
        runTask({
            var active = session
            val updated = try {
                val (shelf, renewed) = try {
                    WeReadWeb.shelf(active, before.shelf)
                } catch (_: WeReadWeb.Expired) {
                    active = WeReadWeb.renew(active)
                    WeReadWeb.shelf(active, before.shelf)
                }
                WeReadSnapshot(before.account, renewed, shelf)
            } catch (_: WeReadWeb.Expired) {
                WeReadSnapshot(before.account, null, before.shelf)
            } catch (failure: Exception) {
                // Renewal may have rotated the cookies even when the shelf retry failed.
                if (active !== session) {
                    val renewed = WeReadSnapshot(before.account, active, before.shelf)
                    return@runTask persist(renewed) to Notice.SYNC_FAILED
                }
                throw failure
            }
            persist(updated) to null
        }) { result ->
            val update = result.getOrNull()
            if (update != null) {
                if (update.first.session == null) invalidateProfileRequest()
                snapshot = update.first
            }
            setState(State.IDLE, update?.second ?: if (result.isFailure) Notice.SYNC_FAILED else null)
        }
    }

    fun beginLogin() {
        if (state != State.IDLE || snapshot.session != null || observers.isEmpty()) return
        val request = ++generation
        setState(State.STARTING_LOGIN)
        runTask({
            val challenge = WeReadWeb.begin()
            challenge to (LoginQr.encode(challenge.confirmation) ?: throw IOException())
        }) { result ->
            if (request != generation) return@runTask
            val ready = result.getOrNull()
            if (ready == null) {
                stopLogin(Notice.LOGIN_FAILED)
            } else {
                login = ready.first
                qr = ready.second
                loginUntil = SystemClock.elapsedRealtime() + 240_000
                setState(State.SCAN)
                main.postDelayed(expireLogin, 240_000)
                main.postDelayed(nextPoll, 2_000)
            }
        }
    }

    fun submitCode(code: String) {
        if (state == State.VERIFY && code.length == 4 && code.all { it in '0'..'9' }) poll(code)
    }

    fun stopLogin(message: Notice? = null) {
        if (state !in LOGIN_STATES) return
        clearLogin()
        setState(State.IDLE, message)
    }

    fun forget() {
        if (state != State.IDLE) return
        invalidateProfileRequest()
        setState(State.SAVING)
        runTask({
            store.save(WeReadSnapshot())
            store.clearProfile()
        }) { result ->
            if (result.isSuccess) {
                WeReadCovers.get(context).clear()
                openHistory.clear()
                snapshot = WeReadSnapshot()
                profile = null
            }
            setState(State.IDLE)
        }
    }

    private fun poll(otp: String?) {
        val challenge = login ?: return
        if (if (otp == null) state != State.SCAN && state != State.VERIFYING else state != State.VERIFY) return
        if (SystemClock.elapsedRealtime() >= loginUntil) {
            stopLogin(Notice.LOGIN_TIMEOUT)
            return
        }
        val request = generation
        if (otp != null) setState(State.VERIFYING)
        runTask({ WeReadWeb.poll(challenge, otp) }) { result ->
            if (request != generation) return@runTask
            if (SystemClock.elapsedRealtime() >= loginUntil) {
                stopLogin(Notice.LOGIN_TIMEOUT)
                return@runTask
            }
            when (val response = result.getOrNull()) {
                is WeReadWeb.Poll.Confirmed -> completeLogin(response.session)
                is WeReadWeb.Poll.Verification -> setState(State.VERIFY, if (response.invalid) Notice.BAD_CODE else null)
                WeReadWeb.Poll.VerificationExpired -> stopLogin(Notice.CODE_EXPIRED)
                WeReadWeb.Poll.RequestRejected -> {
                    if (state == State.SCAN && rejectedPolls < 2) {
                        rejectedPolls++
                        main.postDelayed(nextPoll, 3_000)
                    } else stopLogin(Notice.LOGIN_STATUS_FAILED)
                }
                WeReadWeb.Poll.Waiting -> main.postDelayed(nextPoll, 2_000)
                null -> when (result.exceptionOrNull()) {
                    is WeReadWeb.Rejected -> stopLogin(Notice.LOGIN_STATUS_FAILED)
                    is IOException -> if (otp == null) main.postDelayed(nextPoll, 3_000) else setState(State.VERIFY, Notice.LOGIN_FAILED)
                    else -> stopLogin(Notice.LOGIN_STATUS_FAILED)
                }
            }
        }
    }

    private fun completeLogin(session: WeReadWeb.Session) {
        clearLogin()
        val changedAccount = snapshot.account != session.account
        if (changedAccount) {
            invalidateProfileRequest()
            profile = null
        }
        setState(State.SAVING)
        val after = WeReadSnapshot(session.account, session, snapshot.shelf.takeUnless { changedAccount })
        // Do not show the old account's shelf with new account credentials, even if the sync fails.
        runTask({
            if (changedAccount) store.clearProfile()
            store.save(after)
            val reopened = store.load().session
            if (reopened?.account != session.account || reopened.cookies != session.cookies) throw IOException()
        }) {
            if (snapshot.account != null && changedAccount) {
                WeReadCovers.get(context).clear()
                openHistory.clear()
            }
            snapshot = after
            setState(State.IDLE)
            // Successful authorization should still open the online shelf if local storage is temporarily unavailable.
            sync()
        }
    }

    private fun persist(snapshot: WeReadSnapshot): WeReadSnapshot {
        // A storage problem must not take the currently loaded bookshelf away.
        try { store.save(snapshot) } catch (_: Exception) { }
        return snapshot
    }

    private fun clearLogin() {
        generation++
        main.removeCallbacks(nextPoll)
        main.removeCallbacks(expireLogin)
        login = null
        rejectedPolls = 0
        qr = null
    }

    private fun invalidateProfileRequest() {
        profileRequest++
        profileLoading = false
        nextProfileAttempt = 0L
    }

    private fun setState(value: State, message: Notice? = null) {
        state = value
        notice = message
        observers.toList().forEach { it() }
    }

    private fun <T> runTask(task: () -> T, completion: (Result<T>) -> Unit) {
        worker.execute {
            val result = try { Result.success(task()) } catch (e: Exception) { Result.failure(e) }
            main.post { completion(result) }
        }
    }

    companion object {
        private val LOGIN_STATES = setOf(State.STARTING_LOGIN, State.SCAN, State.VERIFY, State.VERIFYING)
        private var instance: WeReadController? = null
        fun get(context: Context): WeReadController = instance ?: WeReadController(context.applicationContext).also { instance = it }
    }
}
