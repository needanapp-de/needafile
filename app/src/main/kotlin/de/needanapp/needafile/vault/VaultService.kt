package de.needanapp.needafile.vault

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import de.needanapp.needafile.R
import de.needanapp.needafile.home.HomeActivity
import de.needanapp.needafile.settings.config
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Shows the unlocked vaults in a notification as long as one is unlocked, with a button that locks them. While the app
 * is in the background and they lock after a set time, a bar and a countdown show how long they stay unlocked. Runs
 * as a foreground service meanwhile, so Android doesn't freeze the app and the vaults lock on time.
 */
class VaultService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { update() }
    private val vaultListener: (String) -> Unit = { update() }
    private val lockListener: () -> Unit = { update() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        VaultSessions.addListener(vaultListener)
        VaultAutoLock.addListener(lockListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        // always first: a service started in the foreground has to show its notification at once
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (intent?.action == ACTION_LOCK) VaultAutoLock.lockNow()
        update()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        VaultSessions.removeListener(vaultListener)
        VaultAutoLock.removeListener(lockListener)
        super.onDestroy()
    }

    /** Shows the current state, counting down every second while the lock time runs; ends when no vault is unlocked. */
    private fun update() {
        handler.removeCallbacks(tick)
        if (!VaultSessions.hasUnlocked()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        val remaining = remainingMillis()
        // the timer of VaultAutoLock stands still while the phone sleeps, the clock here doesn't
        if (remaining == 0L) {
            VaultAutoLock.lockNow()
            if (!VaultSessions.hasUnlocked()) return
        }
        notificationManager.notify(NOTIFICATION_ID, buildNotification())
        // next when the shown second changes
        if (remaining != null && remaining > 0) handler.postDelayed(tick, (remaining - 1) % SECOND + 1)
    }

    /** How long until the vaults lock by themselves, while that time runs; null otherwise. */
    private fun remainingMillis(): Long? {
        if (VaultAutoLock.isWaitingForTransfer) return null
        val lockAt = VaultAutoLock.lockAt ?: return null
        return (lockAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
    }

    private fun buildNotification(): Notification {
        val names = VaultSessions.unlockedLocations().map(::vaultName)
        return NotificationCompat.Builder(this, CHANNEL_ID).apply {
            setSmallIcon(R.drawable.ic_notification_vault)
            setOngoing(true)
            setOnlyAlertOnce(true)
            setShowWhen(false)
            setCategory(NotificationCompat.CATEGORY_STATUS)
            setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            setContentIntent(openAppIntent())
            if (names.size > 1) setSubText(names.joinToString(", "))
            addAction(0, getString(if (names.size == 1) R.string.vault_lock else R.string.vault_lock_all), lockIntent())

            val minutes = config.vaultLockMinutes
            val total = TimeUnit.MINUTES.toMillis(minutes.toLong())
            val remaining = remainingMillis()
            // a collapsed notification with a bar shows only the title, so the countdown goes there
            setContentTitle(
                when {
                    remaining != null -> {
                        // rounded up, so it shows 00:00 only at the very end
                        val left = DateUtils.formatElapsedTime((remaining + SECOND - 1) / SECOND)
                        if (names.size == 1) {
                            getString(R.string.vault_notification_countdown_one, names.single(), left)
                        } else {
                            resources.getQuantityString(R.plurals.vault_notification_countdown_several, names.size, names.size, left)
                        }
                    }
                    names.size == 1 -> getString(R.string.vault_notification_one, names.single())
                    else -> resources.getQuantityString(R.plurals.vault_notification_several, names.size, names.size)
                }
            )
            when {
                VaultAutoLock.isWaitingForTransfer -> setContentText(getString(R.string.vault_notification_after_transfer))
                remaining != null -> {
                    val lockTime = System.currentTimeMillis() + remaining
                    setContentText(getString(R.string.vault_notification_lock_time, DateFormat.getTimeFormat(this@VaultService).format(Date(lockTime))))
                    if (total > 0) setProgress(PROGRESS_MAX, (remaining * PROGRESS_MAX / total).toInt().coerceIn(0, PROGRESS_MAX), false)
                }
                // the time only runs in the background, in the app it stays full
                minutes > 0 -> {
                    setContentText(resources.getQuantityString(R.plurals.vault_notification_after_leaving, minutes, minutes))
                    setProgress(PROGRESS_MAX, PROGRESS_MAX, false)
                }
                minutes == 0 -> setContentText(getString(R.string.vault_notification_on_leaving))
                config.vaultLockOnScreenOff -> setContentText(getString(R.string.vault_notification_on_screen_off))
                else -> setContentText(getString(R.string.vault_notification_by_hand))
            }
        }.build()
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, REQUEST_OPEN, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun lockIntent(): PendingIntent {
        val intent = Intent(this, VaultService::class.java).setAction(ACTION_LOCK)
        return PendingIntent.getService(this, REQUEST_LOCK, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannel() {
        // not among the silent ones, so the padlock also shows in the status bar; it never makes a sound
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.vault_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private val notificationManager get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        private const val CHANNEL_ID = "unlocked_vaults"
        private const val NOTIFICATION_ID = 7401
        private const val PROGRESS_MAX = 1000
        private const val SECOND = 1000L
        private const val ACTION_LOCK = "de.needanapp.needafile.action.LOCK_VAULTS"
        private const val REQUEST_OPEN = 10
        private const val REQUEST_LOCK = 11

        /**
         * Shows the notification while a vault is unlocked, or shows it anew, e.g. once notifications are allowed or
         * after it was swiped away. Android only allows starting it while the app is on screen; otherwise it starts the
         * next time the app is opened.
         */
        fun show(context: Context) {
            if (!VaultSessions.hasUnlocked()) return
            runCatching { ContextCompat.startForegroundService(context, Intent(context, VaultService::class.java)) }
        }
    }
}
