package kr.changbak.radio

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures

class RadioApplication : Application() {
    val radio by lazy { RadioEngine(this) }
}

class RadioService : MediaLibraryService() {
    private lateinit var player: ExoPlayer
    private lateinit var session: MediaLibrarySession
    private val radio get() = (application as RadioApplication).radio

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), false)
            setHandleAudioBecomingNoisy(true)
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaLibrarySession.Builder(this, player, Browser())
            .setSessionActivity(activity).build()
        radio.attach(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo) = session

    override fun onDestroy() {
        radio.detach()
        session.release()
        player.release()
        super.onDestroy()
    }

    private inner class Browser : MediaLibrarySession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName != packageName && !controller.isTrusted) {
                return MediaSession.ConnectionResult.reject()
            }
            val allowed = Player.Commands.Builder()
                .addAll(Player.COMMAND_PLAY_PAUSE, Player.COMMAND_STOP, Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
                    Player.COMMAND_GET_METADATA, Player.COMMAND_GET_TIMELINE).build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailablePlayerCommands(allowed).build()
        }

        override fun onGetLibraryRoot(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            params: LibraryParams?) = Futures.immediateFuture(LibraryResult.ofItem(
            MediaItem.Builder().setMediaId("radio").setMediaMetadata(MediaMetadata.Builder()
                .setTitle(getString(R.string.app_name)).setIsBrowsable(true).setIsPlayable(false).build())
                .build(), params))

        override fun onGetChildren(session: MediaLibrarySession, browser: MediaSession.ControllerInfo,
            parentId: String, page: Int, pageSize: Int, params: LibraryParams?) =
            Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of<MediaItem>(), params))

        @Suppress("DEPRECATION")
        override fun onPlayerCommandRequest(session: MediaSession, controller: MediaSession.ControllerInfo,
            playerCommand: Int): Int {
            when (playerCommand) {
                Player.COMMAND_PLAY_PAUSE -> if (player.playWhenReady) radio.pause() else radio.resume()
                Player.COMMAND_STOP -> radio.end()
                else -> return SessionResult.RESULT_SUCCESS
            }
            // The engine checks session, position, focus and restrictions before touching the player.
            return SessionResult.RESULT_ERROR_NOT_SUPPORTED
        }
    }
}

