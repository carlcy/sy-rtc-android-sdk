package com.sy.rtc.sdk

import android.app.Notification
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
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicReference

/**
 * 屏幕共享用的 `mediaProjection` 前台服务。
 *
 * SDK 的 AndroidManifest 已声明本服务和 `FOREGROUND_SERVICE` /
 * `FOREGROUND_SERVICE_MEDIA_PROJECTION` 权限，经 manifest 合并进宿主 App，客户不用再声明。
 * Android 10（API 29）起，`RtcEngine.startScreenCapture` 会先启动本服务，服务进入前台后
 * 才创建 MediaProjection；`stopScreenCapture`、系统结束投屏或离开频道时自动停止。
 *
 * 宿主 App 已有自己的 mediaProjection 前台服务时，把 [enabled] 设为 false。
 * Android 13+ 未授予 `POST_NOTIFICATIONS` 时通知栏不显示，但服务照常运行。
 */
class ScreenCaptureService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val callback = pending.getAndSet(null)
        try {
            ensureChannel(this)
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                buildNotification(this),
                foregroundServiceType(Build.VERSION.SDK_INT)
            )
            running = true
            if (callback == null) {
                // 没有等待中的共享（例如进程被系统重建），不占着前台。
                stopSelf()
            } else {
                callback(null)
            }
        } catch (e: Exception) {
            Log.e(TAG, "屏幕共享前台服务启动失败", e)
            running = false
            stopSelf()
            callback?.invoke(e)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SyScreenCaptureService"
        const val CHANNEL_ID = "sy_rtc_screen_capture"
        const val NOTIFICATION_ID = 0x5352

        /** 为 false 时 SDK 不启动本服务，由宿主自行保证 mediaProjection 前台服务。 */
        @JvmStatic
        @Volatile
        var enabled: Boolean = true

        /** 通知标题，默认「<应用名> 正在共享屏幕」。 */
        @JvmStatic
        @Volatile
        var notificationTitle: CharSequence? = null

        /** 通知正文，默认「屏幕内容正在实时传输」。 */
        @JvmStatic
        @Volatile
        var notificationText: CharSequence? = null

        /** 通知小图标资源 id，0 表示用应用图标。 */
        @JvmStatic
        @Volatile
        var smallIcon: Int = 0

        /** 服务当前是否处于前台。 */
        @JvmStatic
        @Volatile
        var running: Boolean = false
            private set

        private val pending = AtomicReference<((Throwable?) -> Unit)?>(null)

        /** Android 10（API 29）起 MediaProjection 必须运行在 mediaProjection 前台服务里。 */
        @JvmStatic
        fun isRequired(sdkInt: Int = Build.VERSION.SDK_INT): Boolean =
            sdkInt >= Build.VERSION_CODES.Q

        @JvmStatic
        fun foregroundServiceType(sdkInt: Int): Int =
            if (isRequired(sdkInt)) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0

        /**
         * 启动服务；进入前台后在主线程回调 `onResult(null)`，失败时回调异常。
         */
        internal fun start(context: Context, onResult: (Throwable?) -> Unit) {
            pending.set(onResult)
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, ScreenCaptureService::class.java)
                )
            } catch (e: Exception) {
                // 例如 Android 12+ 后台启动前台服务受限。
                pending.set(null)
                onResult(e)
            }
        }

        internal fun stop(context: Context) {
            pending.set(null)
            try {
                context.stopService(Intent(context, ScreenCaptureService::class.java))
            } catch (e: Exception) {
                Log.w(TAG, "停止屏幕共享前台服务失败", e)
            }
            running = false
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "屏幕共享", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "屏幕共享进行中时显示"
                    setShowBadge(false)
                }
            )
        }

        private fun buildNotification(context: Context): Notification {
            val label = context.applicationInfo.loadLabel(context.packageManager)
            val icon = smallIcon.takeIf { it != 0 }
                ?: context.applicationInfo.icon.takeIf { it != 0 }
                ?: android.R.drawable.ic_menu_camera
            val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val contentIntent = launch?.let {
                PendingIntent.getActivity(
                    context,
                    0,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(icon)
                .setContentTitle(notificationTitle ?: "$label 正在共享屏幕")
                .setContentText(notificationText ?: "屏幕内容正在实时传输")
                .setOngoing(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .apply { contentIntent?.let { setContentIntent(it) } }
                .build()
        }
    }
}
