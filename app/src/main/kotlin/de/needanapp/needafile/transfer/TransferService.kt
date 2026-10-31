package de.needanapp.needafile.transfer

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import de.needanapp.needafile.R
import de.needanapp.needafile.core.formatSize
import de.needanapp.needafile.core.inBackground
import de.needanapp.needafile.core.rescanPaths
import de.needanapp.needafile.errors.describeError
import de.needanapp.needafile.home.HomeActivity
import de.needanapp.needafile.remote.TransferCancelledException
import de.needanapp.needafile.transfer.TransferManager.TransferState
import java.util.Date

/**
 * Runs the copy (or the packing or unpacking) that [TransferManager] started as a foreground service with a progress notification, so it
 * survives leaving the app, switching it off screen or the screen turning off. Keeps the CPU and Wi-Fi awake meanwhile,
 * except while paused. The notification can pause, resume and cancel the copy.
 */
class TransferService : Service() {
    @Volatile
    private var isWorking = false
    private var lastNotificationUpdate = 0L
    private var startedAt = 0L

    @Volatile
    private var lastProgress: TransferProgress? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val stateListener: (TransferManager.Event) -> Unit = { event ->
        if (event is TransferManager.Event.StateChanged) onStateChanged()
    }

    /** A waiting copy tries again as soon as a network is back. */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = TransferManager.onNetworkAvailable()
    }
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> TransferManager.cancel()
            ACTION_PAUSE -> TransferManager.pause()
            ACTION_RESUME -> TransferManager.resume()
        }
        if (intent?.action != null) {
            // a button of a notification left over from a copy that is gone
            if (!isWorking) stopSelf(startId)
            return START_NOT_STICKY
        }

        createChannel()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID_PROGRESS,
            buildProgressNotification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (isWorking || !TransferManager.isRunning || !TransferManager.hasWork) {
            if (!isWorking) {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        isWorking = true
        startedAt = SystemClock.elapsedRealtime()
        acquireLocks()
        TransferManager.addListener(stateListener)
        connectivity.registerDefaultNetworkCallback(networkCallback)
        inBackground {
            val result = runCatching { TransferManager.runPending(this, ::onProgress) }

            result.getOrNull()?.takeIf { it.isNotEmpty() }?.let { downloadedFiles ->
                rescanPaths(downloadedFiles.map { it.absolutePath })
            }

            val error = result.exceptionOrNull()
            if (!TransferManager.isUiVisible) {
                showResultNotification(TransferManager.finishedCount, TransferManager.kind, error, TransferManager.finishedBytes)
            }

            releaseLocks()
            runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
            mainHandler.post { TransferManager.removeListener(stateListener) }
            isWorking = false
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
            // last, a new copy can only be started once this one is reported as finished
            TransferManager.reportFinished(error)
        }
        return START_NOT_STICKY
    }

    /** Android 15+ limits data sync services to a few hours a day, stop cleanly when that runs out. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        TransferManager.cancel()
    }

    override fun onDestroy() {
        releaseLocks()
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        TransferManager.removeListener(stateListener)
        super.onDestroy()
    }

    private fun onProgress(progress: TransferProgress) {
        TransferManager.reportProgress(progress)
        lastProgress = progress
        val now = SystemClock.elapsedRealtime()
        // most deletions are over in a moment, their notification would only flash up
        val isQuiet = TransferManager.kind == TransferKind.DELETE && now - startedAt < QUIET_DELETE_MS
        if (!isQuiet && now - lastNotificationUpdate >= NOTIFICATION_INTERVAL_MS) {
            lastNotificationUpdate = now
            notificationManager.notify(NOTIFICATION_ID_PROGRESS, buildProgressNotification(progress))
        }
    }

    /** Pausing frees the CPU and Wi-Fi; waiting for the connection keeps them, so tries and the network's return are noticed. */
    private fun onStateChanged() {
        if (!isWorking) return
        if (TransferManager.state == TransferState.PAUSED) releaseLocks() else acquireLocks()
        notificationManager.notify(NOTIFICATION_ID_PROGRESS, buildProgressNotification(lastProgress))
    }

    private fun buildProgressNotification(progress: TransferProgress?) = NotificationCompat.Builder(this, CHANNEL_ID).apply {
        setSmallIcon(R.drawable.ic_notification_needafile)
        setOngoing(true)
        setOnlyAlertOnce(true)
        setSilent(true)
        setCategory(NotificationCompat.CATEGORY_PROGRESS)
        val kind = TransferManager.kind
        setForegroundServiceBehavior(
            if (kind == TransferKind.DELETE) NotificationCompat.FOREGROUND_SERVICE_DEFERRED else NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE,
        )
        setContentIntent(getOpenAppIntent())
        val state = TransferManager.state
        when {
            state == TransferState.PAUSED -> addAction(0, getString(R.string.transfer_resume), getServiceIntent(ACTION_RESUME, REQUEST_RESUME))
            state == TransferState.WAITING -> addAction(0, getString(R.string.transfer_retry_now), getServiceIntent(ACTION_RESUME, REQUEST_RESUME))
            TransferManager.canPause -> addAction(0, getString(R.string.transfer_pause), getServiceIntent(ACTION_PAUSE, REQUEST_PAUSE))
        }
        addAction(0, getString(R.string.cancel), getServiceIntent(ACTION_CANCEL, REQUEST_CANCEL))

        val detail = progress?.let {
            if (kind == TransferKind.DELETE) it.fileName else getString(R.string.transfer_progress_detail, it.fileName, it.bytesDone.formatSize(), it.bytesTotal.formatSize())
        }
        when (state) {
            TransferState.WAITING -> {
                setContentTitle(getString(R.string.transfer_waiting))
                setContentText(getString(R.string.transfer_waiting_until, formatTime(TransferManager.waitingUntil)))
            }

            TransferState.PAUSED -> {
                val problem = TransferManager.problem
                setContentTitle(getString(if (problem != null) R.string.transfer_paused_no_connection else R.string.transfer_paused))
                setContentText(problem?.let { describeError(it) } ?: detail)
            }

            TransferState.RUNNING -> if (progress == null) {
                setContentTitle(getString(R.string.transfer_preparing))
            } else {
                setContentTitle(getString(kind.progress, progress.fileIndex, progress.fileCount))
                setContentText(detail)
            }
        }
        val fraction = kind.fraction(progress)
        when {
            fraction != null -> setProgress(PROGRESS_MAX, (fraction * PROGRESS_MAX).toInt(), false)
            state == TransferState.RUNNING -> setProgress(0, 0, true)
        }
    }.build()

    private fun formatTime(time: Long): String = DateFormat.getTimeFormat(this).format(Date(time))

    private fun showResultNotification(itemCount: Int, kind: TransferKind, error: Throwable?, bytes: Long) {
        val title = describeTransferResult(itemCount, kind, error, bytes) ?: return

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_needafile)
            .setContentTitle(title)
            .setAutoCancel(true)
            .setContentIntent(getOpenAppIntent())
            .build()
        notificationManager.notify(NOTIFICATION_ID_RESULT, notification)
    }

    private fun getOpenAppIntent(): PendingIntent {
        val intent = Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun getServiceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, TransferService::class.java).setAction(action)
        return PendingIntent.getService(this, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.transfer_channel_name), NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock?.isHeld == true) return
        wakeLock = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:transfer")
            .apply { acquire() }

        @Suppress("DEPRECATION")
        wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$packageName:transfer")
            .apply { acquire() }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock = null
    }

    private val notificationManager get() = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    private val connectivity get() = getSystemService(ConnectivityManager::class.java)

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID_PROGRESS = 7301
        private const val NOTIFICATION_ID_RESULT = 7302
        private const val NOTIFICATION_INTERVAL_MS = 500L

        /** How long a deletion runs before its progress shows as notification. */
        private const val QUIET_DELETE_MS = 3000L
        private const val PROGRESS_MAX = 1000
        private const val ACTION_CANCEL = "de.needanapp.needafile.action.CANCEL_TRANSFER"
        private const val ACTION_PAUSE = "de.needanapp.needafile.action.PAUSE_TRANSFER"
        private const val ACTION_RESUME = "de.needanapp.needafile.action.RESUME_TRANSFER"
        private const val REQUEST_CANCEL = 1
        private const val REQUEST_PAUSE = 2
        private const val REQUEST_RESUME = 3
    }
}

/**
 * How a finished job went, for the result notification and the message in the app; null when there is nothing to
 * tell, e.g. when every archive to extract still waits for its password. A deletion names the [bytes] it freed.
 */
fun Context.describeTransferResult(itemCount: Int, kind: TransferKind, error: Throwable?, bytes: Long = 0): String? = when {
    error is TransferCancelledException -> getString(kind.cancelled)
    error != null -> getString(kind.failed, describeError(error))
    itemCount == 0 -> null
    kind == TransferKind.DELETE && bytes > 0 -> resources.getQuantityString(R.plurals.delete_done_freed, itemCount, itemCount, bytes.formatSize())
    else -> resources.getQuantityString(kind.done, itemCount, itemCount)
}
