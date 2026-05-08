package live.jss.jss_android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Foreground service that owns the Node thread.
 *
 * - Posts a persistent notification so Android won't kill the process when
 *   the app backgrounds.
 * - Spawns Node on a dedicated thread; that thread stays in `node::Start`
 *   for the lifetime of the pod.
 * - Polls `http://127.0.0.1:<port>/` until JSS is ready, then broadcasts
 *   READY so MainActivity can load the WebView.
 *
 * v1 caveats:
 *  - Stop action is not yet wired to a graceful Fastify shutdown; for now
 *    stopForeground / stopSelf kills the thread (not graceful, but the
 *    file I/O JSS does is atomic enough that it's safe).
 *  - Port is hardcoded to 4443. v2: bind to port 0 and read back the
 *    bound port over a JNI return value.
 */
class JssService : Service() {

    companion object {
        private const val TAG = "JssService"
        private const val NOTIFICATION_CHANNEL = "jss_pod"
        private const val NOTIFICATION_ID = 1
        const val PORT = 4443

        const val ACTION_READY = "live.jss.jss_android.READY"
        const val ACTION_STOP = "live.jss.jss_android.STOP"
    }

    @Volatile private var nodeStarted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        startInForeground()
        if (!nodeStarted) {
            nodeStarted = true
            thread(name = "jss-node", isDaemon = false) { runNode() }
            thread(name = "jss-readiness", isDaemon = true) { waitReadyAndBroadcast() }
        }
        return START_STICKY
    }

    private fun startInForeground() {
        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notif)
        }
    }

    private fun buildNotification() = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(getString(R.string.notification_text, PORT))
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setOngoing(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setContentIntent(launchActivityIntent())
        .addAction(
            android.R.drawable.ic_media_pause,
            getString(R.string.notification_action_stop),
            stopServiceIntent()
        )
        .build()

    private fun launchActivityIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun stopServiceIntent(): PendingIntent {
        val i = Intent(this, JssService::class.java).setAction(ACTION_STOP)
        return PendingIntent.getService(
            this, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val chan = NotificationChannel(
            NOTIFICATION_CHANNEL,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(chan)
    }

    // ----- Node lifecycle ---------------------------------------------------

    private fun runNode() {
        // 1. Materialize JSS into <filesDir>/jss/ (idempotent, skips on cached stamp)
        val jssRoot = AssetCopier.ensureAssets(
            this,
            assetSubpath = "jss",
            destRoot = File(filesDir, "jss")
        )

        // 2. Pod data lives in a sibling dir so we can wipe one without the other
        val dataRoot = File(filesDir, "data").apply { mkdirs() }

        // 3. Build args mirroring the spike-verified CLI surface
        val args = arrayOf(
            "node",
            File(jssRoot, "bin/jss.js").absolutePath,
            "start",
            "--single-user",
            "--port", PORT.toString(),
            "--host", "127.0.0.1",
            "--root", dataRoot.absolutePath,
            "--idp"
        )

        Log.i(TAG, "Starting Node with: ${args.joinToString(" ")}")
        val rc = NodeBridge.startNodeWithArguments(args)
        Log.w(TAG, "Node exited rc=$rc; stopping service")
        stopSelf()
    }

    private fun waitReadyAndBroadcast() {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            try {
                val conn = (URL("http://127.0.0.1:$PORT/").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 500
                    readTimeout = 500
                    requestMethod = "GET"
                }
                val code = conn.responseCode
                conn.disconnect()
                if (code in 200..399) {
                    Log.i(TAG, "JSS ready (HTTP $code)")
                    sendBroadcast(Intent(ACTION_READY).setPackage(packageName))
                    return
                }
            } catch (_: Exception) {
                // Not bound yet; keep polling
            }
            Thread.sleep(150)
        }
        Log.e(TAG, "JSS did not become ready within 30s")
    }
}
