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

    // Polls rather than posting one long delay: Handler delays don't count deep sleep,
    // elapsedRealtime() does, so a late check still closes on time once the CPU wakes.
    private val backgroundCheck = object : Runnable {
        override fun run() {
            if (backgroundExpired()) shutdown() else handler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super<Application>.onCreate()
        instance = this
        PreferenceManager.setDefaultValues(this, R.xml.preferences, false)
        registerActivityLifecycleCallbacks(ActivityTracker())
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        handler.removeCallbacks(backgroundCheck)
        releaseWakeLock()
        val expired = backgroundExpired()
        backgroundSince = 0L
        KeepAliveService.start(this)
        if (expired) {
            // The timer couldn't fire while the phone slept; treat this as a fresh start.
            WebViewHolder.reload()
        } else {
            WebViewHolder.resumeCams()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundSince = SystemClock.elapsedRealtime()
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

    /** Closes the page, the notification, every screen, and finally the process. */
    fun shutdown() {
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
