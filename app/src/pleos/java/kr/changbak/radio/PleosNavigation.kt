package kr.changbak.radio

import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.DestinationArrivedInfo
import ai.pleos.playground.navi.data.DestinationInfo
import ai.pleos.playground.navi.data.NavigationScreenRequest
import ai.pleos.playground.navi.data.RouteStartInfo
import ai.pleos.playground.navi.data.RouteStateInfo
import ai.pleos.playground.navi.data.TBTInfo
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log

class PleosNavigation(private val context: Context, private val update: (NavigationState) -> Unit,
    private val invalidate: () -> Unit) : Navigation {
    private val main = Handler(Looper.getMainLooper())
    private var helper: NaviHelper? = null
    private var state = NavigationState()
    private var lastPoint: Point? = null
    private var lastRouteId: String? = null
    private var receivesLocation = false

    private val listener = object : NaviHelperEventListener {
        override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) = onMain {
            val point = Point(currentLocationInfo.latitude, currentLocationInfo.longitude)
            if (point.lat !in -90.0..90.0 || point.lon !in -180.0..180.0 ||
                point == Point(0.0, 0.0)) return@onMain
            val changed = lastPoint != point
            lastPoint = point
            receivesLocation = true
            val routeId = currentLocationInfo.routeId
            if (lastRouteId != null && routeId != lastRouteId) invalidate()
            lastRouteId = routeId
            // 3.0.5 reports no measurement timestamp or accuracy in CurrentLocationInfo.
            Log.i(TAG, "sdk_current_location changed=$changed")
            publish(state.copy(locationObserved = receivesLocation))
        }

        override fun onDestinationInfo(destinationInfo: DestinationInfo) = onMain {
            Log.i(TAG, "sdk_destination")
            publish(state.copy(destination = destinationInfo.name.takeIf { it.isNotBlank() }))
        }

        override fun onRouteStateInfo(routeStateInfo: RouteStateInfo) = onMain {
            Log.i(TAG, "sdk_route_state routing=${routeStateInfo.isRouting}")
            publish(state.copy(route = if (routeStateInfo.isRouting) "내비 경로 안내 중" else "내비 경로 안내 없음",
                maneuverMeters = if (routeStateInfo.isRouting) state.maneuverMeters else null))
        }

        override fun onTBTInfo(tbtInfo: List<TBTInfo>) = onMain {
            Log.i(TAG, "sdk_tbt count=${tbtInfo.size}")
            val meters = tbtInfo.firstOrNull()?.distance?.takeIf { it.isFinite() && it >= 0 }?.toInt()
            publish(state.copy(maneuverMeters = meters))
        }

        override fun onRouteStarted(routeStartInfo: RouteStartInfo) = routeChanged("sdk_route_started", "내비 경로 탐색 중")
        override fun onRoutingCompleted() = routeChanged("sdk_routing_completed", "내비 경로 안내 중")
        override fun onReRouteEnded() = routeChanged("sdk_reroute_completed", "내비 경로 갱신")
        override fun onRouteCancelled() = routeChanged("sdk_route_cancelled", "내비 경로 안내 없음", true)
        override fun onRouteEnded() = routeChanged("sdk_route_ended", "내비 경로 안내 종료", true)
        override fun onDestinationArrived(destinationArrivedInfo: DestinationArrivedInfo) =
            routeChanged("sdk_destination_arrived", "내비 목적지 도착")
        override fun onDestinationChanged() = onMain {
            invalidate()
            publish(state.copy(destination = null))
            if (allowed(ROUTE)) helper?.getDestinationInfo()
        }
        override fun onError(errorCode: NaviErrorCode) = onMain {
            Log.w(TAG, "sdk_request_error=$errorCode")
        }
    }

    override fun start() {
        if (helper != null) return
        val navi = NaviHelper(context)
        helper = navi
        navi.initialize(
            onConnected = { onMain {
                if (helper !== navi) return@onMain
                navi.addListener(listener)
                Log.i(TAG, "sdk_connected route_permission=${allowed(ROUTE)} screen_permission=${allowed(SCREEN)}")
                publish(NavigationState(connected = true, canOpen = allowed(SCREEN)))
                poll()
            } },
            onFailed = { error -> onMain {
                if (helper !== navi) return@onMain
                Log.w(TAG, "sdk_connection_failed=${error.javaClass.simpleName}")
                publish(NavigationState())
            } }
        )
    }

    override fun poll() {
        if (!state.connected || !allowed(ROUTE)) return
        runCatching {
            helper?.apply {
                getCurrentLocationInfo()
                getDestinationInfo()
                getRouteStateInfo()
                getTBTInfo()
            }
        }.onFailure { Log.w(TAG, "sdk_poll_failed=${it.javaClass.simpleName}") }
    }

    override fun open() {
        if (!state.canOpen || !allowed(SCREEN)) return
        runCatching { helper?.requestNavigationScreen(NavigationScreenRequest.Foreground) }
            .onSuccess { Log.i(TAG, "sdk_screen_requested") }
            .onFailure { Log.w(TAG, "sdk_screen_failed=${it.javaClass.simpleName}") }
    }

    override fun stop() {
        helper?.let { navi ->
            runCatching { navi.removeListener(listener) }
            runCatching { navi.release() }
        }
        helper = null
        lastPoint = null
        lastRouteId = null
        receivesLocation = false
        publish(NavigationState())
    }

    private fun routeChanged(event: String, route: String, clearDestination: Boolean = false) = onMain {
        Log.i(TAG, event)
        invalidate()
        publish(state.copy(route = route, destination = if (clearDestination) null else state.destination,
            maneuverMeters = null))
    }

    private fun allowed(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun onMain(action: () -> Unit) {
        main.post { if (helper != null) action() }
    }

    private fun publish(next: NavigationState) {
        state = next
        update(next)
    }

    companion object {
        private const val TAG = "RadioPleos"
        private const val ROUTE = "pleos.car.permission.NAVI_ROUTE"
        private const val SCREEN = "pleos.car.permission.NAVI_CUSTOM_MAP"
    }
}

