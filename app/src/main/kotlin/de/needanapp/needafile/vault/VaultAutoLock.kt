package de.needanapp.needafile.vault

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import de.needanapp.needafile.base.BaseActivity
import de.needanapp.needafile.core.BusyWork
import de.needanapp.needafile.settings.config
import java.lang.ref.WeakReference
import java.util.concurrent.TimeUnit

/**
 * Locks the unlocked vaults when needafile has been in the background for the time set in the settings, and when the
 * screen turns off. A copy into or out of a vault that is running then finishes first. While a vault is unlocked,
 * [VaultService] shows it in a notification.
 */
object VaultAutoLock {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var application: Application
    private var startedActivities = 0
    private var backgroundSince = 0L
    private var lockAfterTransfer = false
    private val lockLater = Runnable { lockNow() }
    private val listeners = LinkedHashSet<() -> Unit>()
    private var resumedActivity: WeakReference<Activity>? = null

    /** When the vaults lock by themselves (in [SystemClock.elapsedRealtime]) while the app is in the background; null otherwise. */
    var lockAt: Long? = null
        private set

    /** Whether the vaults lock as soon as the running copy is done. */
    val isWaitingForTransfer get() = lockAfterTransfer

    fun start(application: Application) {
        this.application = application
        // what was decrypted for viewing before the app was ended last time
        VaultSessions.cleanCache(application)
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                val wasInBackground = startedActivities == 0 && backgroundSince > 0
                startedActivities++
                handler.removeCallbacks(lockLater)
                // the timer doesn't run while the phone sleeps, so the time is checked on coming back as well
                if (wasInBackground && isOverdue()) lockNow()
                lockAt = null
                // shown anew, e.g. if it couldn't start as the app was left right after unlocking
                VaultService.show(application)
                changed()
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities--
                if (startedActivities == 0) {
                    backgroundSince = SystemClock.elapsedRealtime()
                    val minutes = application.config.vaultLockMinutes
                    if (minutes >= 0) {
                        val delay = TimeUnit.MINUTES.toMillis(minutes.toLong())
                        lockAt = backgroundSince + delay
                        handler.postDelayed(lockLater, delay)
                    }
                    changed()
                }
            }

            override fun onActivityResumed(activity: Activity) {
                resumedActivity = WeakReference(activity)
            }

            override fun onActivityPaused(activity: Activity) {
                if (resumedActivity?.get() == activity) resumedActivity = null
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })

        val screenOff = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (application.config.vaultLockOnScreenOff) lockNow()
            }
        }
        ContextCompat.registerReceiver(application, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)

        BusyWork.addEndListener {
            if (lockAfterTransfer && !BusyWork.involvesVault()) {
                lockAfterTransfer = false
                lockNow()
                changed()
            }
        }

        VaultSessions.addListener { location ->
            // without the permission the notification of the unlocked vaults doesn't show
            if (VaultSessions.isUnlocked(location)) {
                (resumedActivity?.get() as? BaseActivity)?.withNotificationPermission { granted ->
                    if (granted) VaultService.show(application)
                }
            }
            VaultService.show(application)
        }
    }

    /** [listener] hears when [lockAt] or [isWaitingForTransfer] change, on the main thread. */
    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    private fun changed() = listeners.toList().forEach { it() }

    private fun isOverdue(): Boolean {
        val minutes = application.config.vaultLockMinutes
        return minutes >= 0 && SystemClock.elapsedRealtime() - backgroundSince >= TimeUnit.MINUTES.toMillis(minutes.toLong())
    }

    /** Locks every vault now, or once the running copy (or other [BusyWork]) is done if it involves one. */
    fun lockNow() {
        if (!VaultSessions.hasUnlocked()) return
        if (BusyWork.involvesVault()) {
            lockAfterTransfer = true
            changed()
            return
        }
        VaultSessions.lockAll(application)
    }
}
