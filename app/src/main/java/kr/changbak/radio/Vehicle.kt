package kr.changbak.radio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import java.lang.reflect.Proxy

data class NavigationState(
    val connected: Boolean = false,
    val locationObserved: Boolean = false,
    val canOpen: Boolean = false,
    val destination: String? = null,
    val route: String? = null,
    val maneuverMeters: Int? = null
)

interface Navigation {
    fun start()
    fun poll()
    fun stop()
    fun open()
    /** 이야기 속 장소를 내비 경유지로 추가한다. 내비가 없으면 아무 일도 하지 않는다. */
    fun addWaypoint(place: Place) {}
}

fun newNavigation(context: Context, update: (NavigationState) -> Unit,
    invalidate: () -> Unit): Navigation? {
    // The SDK adapter is only compiled when withPleos is enabled.
    return runCatching {
        Class.forName("kr.changbak.radio.PleosNavigation")
            .getConstructor(Context::class.java, Function1::class.java, Function0::class.java)
            .newInstance(context, update, invalidate) as Navigation
    }.getOrNull()
}

class VehicleRestrictions(private val context: Context, private val onChange: (Restriction) -> Unit) {
    val automotive = context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE)
    private var car: Any? = null
    private var manager: Any? = null

    fun connect() {
        if (!automotive) {
            onChange(Restriction.PARKED)
            return
        }
        runCatching {
            val carType = Class.forName("android.car.Car")
            val service = carType.getMethod("createCar", Context::class.java).invoke(null, context)
            car = service
            manager = carType.getMethod("getCarManager", String::class.java)
                .invoke(service, carType.getField("CAR_UX_RESTRICTION_SERVICE").get(null))
            val listenerType = Class.forName("android.car.drivingstate.CarUxRestrictionsManager\$OnUxRestrictionsChangedListener")
            val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { self, method, args ->
                when (method.name) {
                    "hashCode" -> System.identityHashCode(self)
                    "equals" -> self === args?.firstOrNull()
                    "toString" -> "Radio UX restrictions listener"
                    "onUxRestrictionsChanged" -> {
                        publish(args?.firstOrNull())
                        null
                    }
                    else -> null
                }
            }
            val restrictions = requireNotNull(manager)
            restrictions.javaClass.getMethod("registerListener", listenerType).invoke(restrictions, listener)
            read()
        }.onFailure { Log.w("RadioVehicle", "UX restrictions unavailable: ${it.javaClass.simpleName}") }
    }

    fun read() {
        if (!automotive) return
        val restrictions = runCatching {
            manager?.let { it.javaClass.getMethod("getCurrentCarUxRestrictions").invoke(it) }
        }.getOrNull()
        publish(restrictions)
    }

    private fun publish(value: Any?) {
        val optimized = runCatching {
            value?.javaClass?.getMethod("isRequiresDistractionOptimization")?.invoke(value) as? Boolean
        }.getOrNull()
        onChange(when (optimized) {
            true -> Restriction.RESTRICTED
            false -> Restriction.PARKED
            null -> Restriction.UNKNOWN
        })
    }

    fun disconnect() {
        runCatching { manager?.let { it.javaClass.getMethod("unregisterListener").invoke(it) } }
        runCatching { car?.let { it.javaClass.getMethod("disconnect").invoke(it) } }
        manager = null
        car = null
    }
}

class DeviceLocation(private val context: Context, private val onFix: (Fix) -> Unit) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val listener = object : LocationListener {
        override fun onLocationChanged(location: Location) = onFix(location.toFix())
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit

        @Suppress("OVERRIDE_DEPRECATION")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
    private var subscribed = false

    fun allowed() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
        PackageManager.PERMISSION_GRANTED

    fun start(): Boolean {
        if (subscribed) return true
        if (!allowed()) return false
        return runCatching {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000L, 3f, listener, Looper.getMainLooper())
            subscribed = true
            true
        }.getOrDefault(false)
    }

    fun stop() {
        if (subscribed) manager.removeUpdates(listener)
        subscribed = false
    }

    private fun Location.toFix() = Fix(Point(latitude, longitude), elapsedRealtimeNanos / 1_000_000,
        if (hasAccuracy()) accuracy else Float.POSITIVE_INFINITY,
        bearing.takeIf { hasBearing() && hasSpeed() && speed >= 2f && hasBearingAccuracy() &&
            bearingAccuracyDegrees <= 35f })
}

