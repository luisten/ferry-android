package fi.ferry.messenger

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * E8 (the user's choice, 2026-10-22): a foreground service keeps the engine's Instagram connections open while Ferry
 * is closed, so messages arrive and notify. Its notification is silent and collapsed. Type `remoteMessaging`: no daily
 * limit (Android 15 caps `dataSync`), and it may start at boot. Runs while there's an account.
 */
class EngineService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifier.channels(this)
        val notification = Notifier.connection(this)
        // Before Android 14 the platform doesn't know the type: the manifest's is used.
        if (Build.VERSION.SDK_INT >= 34) startForeground(Notifier.CONNECTION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING)
        else startForeground(Notifier.CONNECTION_ID, notification)
        AppModel.start(applicationContext, demo = false) // once per process: a no-op when the app started it
        // The demo connects nothing. (Android sends BOOT_COMPLETED when a force-stopped app next starts, demo or not.)
        if (AppModel.demo) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, EngineService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, EngineService::class.java))
        }
    }
}

/** After a restart: back to connected, if there's an account. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && Sessions(context).accounts().isNotEmpty()) EngineService.start(context)
    }
}
