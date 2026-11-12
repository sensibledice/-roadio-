package kr.changbak.radio

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.core.view.WindowCompat
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : ComponentActivity() {
    private val engine get() = (application as RadioApplication).radio
    private var controller: ListenableFuture<MediaController>? = null
    private var startAfterPermission = false
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (it[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) engine.begin()
            else startAfterPermission = true
        } else engine.permissionDenied()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val token = SessionToken(this, ComponentName(this, RadioService::class.java))
        controller = MediaController.Builder(this, token).buildAsync()
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
            RadioScreen(engine, onStart = { demo ->
                if (!demo && engine.connection.valid(BuildConfig.DEBUG) &&
                    engine.selectedPosition == PositionMode.DEVICE &&
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else engine.begin(demo)
            }, openSource = { source ->
                if (engine.state.value.detailsAllowed) runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(source.url)))
                }
            })
        }
    }

    override fun onResume() {
        super.onResume()
        engine.visible(true)
        if (startAfterPermission) {
            startAfterPermission = false
            engine.begin()
        }
    }

    override fun onPause() {
        engine.visible(false)
        super.onPause()
    }

    override fun onDestroy() {
        controller?.let(MediaController::releaseFuture)
        super.onDestroy()
    }
}

