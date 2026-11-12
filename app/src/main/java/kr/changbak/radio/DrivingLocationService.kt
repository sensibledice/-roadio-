package kr.changbak.radio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class DrivingLocationService : Service() {
    private val radio get() = (application as RadioApplication).radio
    private var controller: ListenableFuture<MediaController>? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            radio.end()
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val active = radio.state.value
        if (!active.active || active.positionMode != PositionMode.DEVICE) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "여행 라디오 위치 사용", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val stop = PendingIntent.getService(this, 1, serviceIntent(this).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_radio)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("주변 이야기를 찾기 위해 주행 위치를 사용 중이에요")
                .setContentIntent(open)
                .addAction(Notification.Action.Builder(null, "종료", stop).build())
                .setOngoing(true)
                .build()
            ServiceCompat.startForeground(this, 21, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            if (controller == null) {
                val token = SessionToken(this, ComponentName(this, RadioService::class.java))
                controller = MediaController.Builder(this, token).buildAsync()
            }
            radio.locationServiceStarted()
        } catch (error: RuntimeException) {
            android.util.Log.w("RadioLocation", "Location service failed: ${error.javaClass.simpleName}")
            radio.locationServiceFailed()
            stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        radio.locationServiceFailed()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "travel_location"
        private const val STOP = "kr.changbak.radio.STOP_LOCATION"
        private fun serviceIntent(context: Context) = Intent(context, DrivingLocationService::class.java)
        fun start(context: Context) = runCatching {
            ContextCompat.startForegroundService(context, serviceIntent(context))
        }.isSuccess
        fun stop(context: Context) { context.stopService(serviceIntent(context)) }
    }
}
