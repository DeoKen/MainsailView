package io.github.deoken.mainsailview

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.preference.PreferenceManager

/**
 * Owns the app-wide lifecycle: starts the keep-alive service while the app is open,
 * pauses webcams when no activity is visible, and closes everything after the
 * configured time in the background.
 */
class App : Application(), DefaultLifecycleObserver {

    companion object {
        lateinit var instance: App
            private set

        private const val CHECK_INTERVAL_MS = 15_000L
        private const val WATCHDOG_INTERVAL_MS = 10_000L
    }

    private val handler = Handler(Looper.getMainLooper())
    private val activities = mutableSetOf<Activity>()

    /** elapsedRealtime() when the app went to the background, or 0 while in the foreground. */
    private var backgroundSince = 0L

    // Keeps the CPU running while in the background with the screen off, so the page
    // keeps reading the websocket. Held only until the app returns or closes itself.
    private val wakeLock: PowerManager.WakeLock by lazy {
        getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MainsailView:keepConnected")
            .apply { setReferenceCounted(false) }
    }

    // While the app is alive (foreground, or background until the close timer), watch for
    // Mainsail losing its connection and recover it, so a blip while you're in another app
    // is already repaired when you come back.
    private val connectionWatchdog = object : Runnable {
        override fun run() {
            WebViewHolder.checkConnection(immediate = false)
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    val isInForeground: Boolean
        get() = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)

    // Polls rather than posting one long delay: Handler delays don't count deep sleep,
    // elapsedRealtime() does, so a late check still closes on time once the CPU wakes.
    private val backgroundCheck = object : Runnable {
        override fun run() {
            if (backgroundExpired()) shutdown("close timer") else handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super<Application>.onCreate()
        instance = this
        Diag.init(this)
        // Record any crash in the connection log before the app closes, so it can be fixed.
        val crashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            Diag.log("CRASH: " + error.stackTraceToString().lines().take(12).joinToString(" | "))
            crashHandler?.uncaughtException(thread, error)
        }
        Diag.log("App started (a fresh process: if it was open before, it had been closed or killed)")
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        registerActivityLifecycleCallbacks(ActivityTracker())
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        Shortcuts.update(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.removeCallbacks(backgroundCheck)
        releaseWakeLock()
        val expired = backgroundExpired()
        Diag.log(
            if (backgroundSince == 0L) "Opened"
            else "Back in the app after ${(SystemClock.elapsedRealtime() - backgroundSince) / 1000} s"
        )
        backgroundSince = 0L
        KeepAliveService.start(this)
        if (expired) {
            // The timer couldn't fire while the phone slept; treat this as a fresh start.
            WebViewHolder.reload("close timer had passed while the phone slept")
        } else {
            WebViewHolder.resumeCams()
            // Back from the background (e.g. the phone was folded and locked): if Mainsail
            // gave up on the connection meanwhile, reconnect now rather than show its error.
            WebViewHolder.checkConnection(immediate = true)
        }
        handler.removeCallbacks(connectionWatchdog)
        handler.postDelayed(connectionWatchdog, WATCHDOG_INTERVAL_MS)
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundSince = SystemClock.elapsedRealtime()
        Diag.log("Went to the background")
        WebViewHolder.suspendCams()
        val minutes = Prefs.killMinutes(this)
        if (minutes > 0) {
            handler.postDelayed(backgroundCheck, CHECK_INTERVAL_MS)
            wakeLock.acquire(minutes * 60_000L + CHECK_INTERVAL_MS)
        } else {
            wakeLock.acquire()
        }
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) wakeLock.release()
    }

    private fun backgroundExpired(): Boolean {
        val minutes = Prefs.killMinutes(this)
        if (backgroundSince == 0L || minutes == 0) return false
        return SystemClock.elapsedRealtime() - backgroundSince >= minutes * 60_000L
    }

    private var inPip = false
    private var pipEndedAt = 0L

    /** Tracks picture-in-picture so closing its window isn't mistaken for swiping the app away. */
    fun notePip(active: Boolean) {
        inPip = active
        if (!active) pipEndedAt = SystemClock.elapsedRealtime()
    }

    /** True in PiP or just after it, when a removed task means the PiP window was closed. */
    fun pipJustDismissed() = inPip || SystemClock.elapsedRealtime() - pipEndedAt < 5_000

    // Android asking apps to free memory: a sign the page could be killed next.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW && level != android.content.ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) Diag.log("Android reports low memory (level $level)")
    }

    /** Closes the page, the notification, every screen, and finally the process. */
    fun shutdown(reason: String) {
        Diag.log("Closed: $reason")
        handler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        WebViewHolder.destroy()
        activities.toList().forEach { it.finishAndRemoveTask() }
        stopService(Intent(this, KeepAliveService::class.java))
        handler.postDelayed({ Process.killProcess(Process.myPid()) }, 500)
    }

    private inner class ActivityTracker : ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            activities.add(activity)
        }

        override fun onActivityDestroyed(activity: Activity) {
            activities.remove(activity)
        }

        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    }
}
