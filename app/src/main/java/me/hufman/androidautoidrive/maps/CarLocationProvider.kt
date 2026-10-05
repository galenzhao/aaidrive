package me.hufman.androidautoidrive.maps

import android.location.Location
import android.util.Log
import com.google.gson.JsonObject
import com.soywiz.kmem.isNanOrInfinite
import io.bimmergestalt.idriveconnectkit.CDS
import io.bimmergestalt.idriveconnectkit.CDSProperty
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.cds.CDSData
import me.hufman.androidautoidrive.cds.CDSEventHandler
import me.hufman.androidautoidrive.cds.subscriptions
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsDouble
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsJsonObject
import me.hufman.androidautoidrive.utils.GsonNullable.tryAsJsonPrimitive
import java.io.Serializable
import kotlin.math.*
import cn.hutool.core.util.CoordinateUtil

data class LatLong(val latitude: Double, val longitude: Double): Serializable {
	/**
	 * Returns the distance to the other point in KM
	 */
	fun distanceFrom(other: LatLong): Double {
		// from https://stackoverflow.com/a/1253545
		// hilariously inaccurate, but probably good enough
		val latDistance = abs(other.latitude - this.latitude) * 110.574
		val latRadians = this.latitude * PI / 180
		val longDistance = abs(other.longitude - this.longitude) * 111.320 * cos(latRadians)
		return sqrt(latDistance*latDistance + longDistance*longDistance)
	}

	/**
	 * Returns angle towards the other point
	 * 0 pointing North, increasing clockwise
	 */
	fun bearingTowards(other: LatLong): Float {
		// From https://stackoverflow.com/a/69822454/169035
		val currentLat = Math.toRadians(latitude)
		val currentLong = Math.toRadians(longitude)
		val destLat = Math.toRadians(other.latitude)
		val destLong = Math.toRadians(other.longitude)

		val x = cos(destLat) * sin(destLong - currentLong)
		val y = (cos(currentLat) * sin(destLat)) -
				(sin(currentLat) * cos(destLat) * cos(destLong - currentLong))

		val radBearing = atan2(x, y)
		return (Math.toDegrees(radBearing).toFloat() + 360f) % 360f
	}

	override fun toString(): String {
		return "%.6f,%.6f".format(latitude, longitude)
	}
}
data class CarHeading(val heading: Float, val speed: Float): Serializable

object SimulatedCarLocation {
	const val PROVIDER = "SimulatedLocationProvider"
	const val LATITUDE = 38.91
	const val LONGITUDE = 121.61

	fun matches(location: Location?): Boolean {
		return location?.provider == PROVIDER
	}

	fun create(): Location {
		return Location(PROVIDER).also {
			it.latitude = LATITUDE
			it.longitude = LONGITUDE
			it.time = System.currentTimeMillis()
			it.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
			it.accuracy = 8f
			it.bearing = 0f
			it.speed = 0f
		}
	}
}

abstract class CarLocationProvider {
	/**
	 * Whether CDS GPSPosition is already GCJ-02 (China). Setting key kept as wgs84ToGcj02.
	 * When true, coordinates are converted to WGS-84 so [currentLocation] is always GPS/WGS-84.
	 */
	public var wgs84ToGcj02: Boolean = false

	var currentLocation: Location? = null
		protected set

	val isSimulated: Boolean
		get() = SimulatedCarLocation.matches(currentLocation)

	var callback: ((Location) -> Unit)? = null

	protected fun sendCallback() {
		currentLocation?.also { location -> callback?.invoke(location) }
	}

	abstract fun start()
	abstract fun stop()
}

class CdsLocationProvider(val appSettings: AppSettings?, val cdsData: CDSData, val id4: Boolean): CarLocationProvider() {
	companion object {
		private const val TAG = "CdsLocationProvider"
		/** CDS GPSExtendedInfo.speed uses a 32768-centered encoding; see CDSMetrics.speedGPS */
		private const val CDS_GPS_SPEED_STATIONARY = 32768.0
		private const val CDS_GPS_SPEED_MAX_RAW = -24434.0 // ~300 km/h after decode
		private const val CDS_GPS_SPEED_SCALE = 0.036 // (raw + 32768) * scale → km/h
		private const val MAX_SPEED_MS = 84f // ~300 km/h; matches CDSMetrics GPS speed ceiling
		/** Reject null-island / unset GPS that would teleport Extra GPS across the planet */
		private const val NULL_ISLAND_EPS = 0.01
		/** Ignore single-sample teleports (CDS glitches / 0,0 flashes) */
		private const val MAX_JUMP_KM = 1.5
		private const val MAX_JUMP_WINDOW_MS = 3000L

		fun parseCdsIsGcj02(raw: String?): Boolean {
			val v = raw?.trim().orEmpty()
			if (v.equals("true", ignoreCase = true) || v.equals("yes", ignoreCase = true) || v == "1") {
				return true
			}
			if (v.equals("false", ignoreCase = true) || v.equals("no", ignoreCase = true) || v == "0" || v.isEmpty()) {
				return false
			}
			// legacy numeric toggle in dimensions settings (>10 meant "car is GCJ-02")
			return (v.toIntOrNull() ?: 0) > 10
		}

		/** Decode CDS GPSExtendedInfo.speed to m/s for Android Location, or null if stationary/invalid. */
		fun decodeCdsGpsSpeedMs(raw: Double): Float? {
			if (raw > CDS_GPS_SPEED_MAX_RAW) {
				// includes 32768 stationary sentinel and positive garbage
				return null
			}
			val kmh = (raw + CDS_GPS_SPEED_STATIONARY) * CDS_GPS_SPEED_SCALE
			if (kmh < 0.0 || kmh.isNanOrInfinite()) {
				return null
			}
			val ms = (kmh / 3.6).toFloat()
			return ms.coerceAtMost(MAX_SPEED_MS)
		}

		fun isNullIsland(latitude: Double, longitude: Double): Boolean {
			return latitude.absoluteValue < NULL_ISLAND_EPS && longitude.absoluteValue < NULL_ISLAND_EPS
		}
	}

	constructor(cdsData: CDSData, id4: Boolean): this(null, cdsData, id4)

	var currentLatLong: LatLong? = null
	var currentHeading: CarHeading? = null
	private var hasCdsPosition = false
	private var lastAcceptedTimeMs = 0L

	init {
		refreshCoordinateMode()
		parseGPS()
		parseHeading()
		// slow background updates, start() subscribes for fast updates while the map is in use
		cdsData.addEventHandler(CDS.NAVIGATION.GPSPOSITION, 10000, object: CDSEventHandler {
			override fun onPropertyChangedEvent(property: CDSProperty, propertyValue: JsonObject) {
				parseGPS()
			}
		})
		cdsData.addEventHandler(CDS.NAVIGATION.GPSEXTENDEDINFO, 10000, object: CDSEventHandler {
			override fun onPropertyChangedEvent(property: CDSProperty, propertyValue: JsonObject) {
				parseHeading()
			}
		})
	}

	override fun start() {
		refreshCoordinateMode()
		cdsData.subscriptions[CDS.NAVIGATION.GPSPOSITION] = {
			parseGPS()
		}
		cdsData.subscriptions[CDS.NAVIGATION.GPSEXTENDEDINFO] = {
			parseHeading()
		}
		if (hasCdsPosition) {
			parseGPS()
		} else {
			applySimulatedLocation()
		}
	}

	private fun refreshCoordinateMode() {
		wgs84ToGcj02 = parseCdsIsGcj02(appSettings?.get(AppSettings.KEYS.wgs84ToGcj02))
	}

	private fun parseGPS() {
		refreshCoordinateMode()
		val gpsPosition = cdsData[CDS.NAVIGATION.GPSPOSITION] ?: return
		val position = gpsPosition.tryAsJsonObject("GPSPosition")
		val latitude = position?.tryAsJsonPrimitive("latitude")?.tryAsDouble
		val longitude = position?.tryAsJsonPrimitive("longitude")?.tryAsDouble
		if (longitude == null || latitude == null || longitude.isNanOrInfinite() || latitude.isNanOrInfinite() ||
				longitude.absoluteValue >= 180 || latitude.absoluteValue >= 90) {
			return
		}
		if (isNullIsland(latitude, longitude)) {
			Log.w(TAG, "Ignoring CDS GPS null-island $latitude,$longitude")
			return
		}
		val next = if (CoordinateUtil.outOfChina(longitude, latitude)) {
			LatLong(latitude, longitude)
		} else if (wgs84ToGcj02) {
			val coord = CoordinateUtil.gcj02ToWgs84(longitude, latitude)
			LatLong(coord.lat, coord.lng)
		} else {
			LatLong(latitude, longitude)
		}
		val previous = currentLatLong
		val now = System.currentTimeMillis()
		if (previous != null && hasCdsPosition && now - lastAcceptedTimeMs < MAX_JUMP_WINDOW_MS) {
			val jumpKm = previous.distanceFrom(next)
			if (jumpKm > MAX_JUMP_KM) {
				Log.w(TAG, "Ignoring CDS GPS teleport ${"%.3f".format(jumpKm)}km to $next (was $previous)")
				return
			}
		}
		currentLatLong = next
		hasCdsPosition = true
		lastAcceptedTimeMs = now
		onLocationUpdate()
	}

	private fun parseHeading() {
		val gpsHeading = cdsData[CDS.NAVIGATION.GPSEXTENDEDINFO] ?: return
		val position = gpsHeading.tryAsJsonObject("GPSExtendedInfo")
		val heading = position?.tryAsJsonPrimitive("heading")?.tryAsDouble   // in degrees, needs to be negated for Location usage
		val headingAdj = if (id4) -1.40625f else -1f
		val rawSpeed = position?.tryAsJsonPrimitive("speed")?.tryAsDouble
		val speedMs = rawSpeed?.let { decodeCdsGpsSpeedMs(it) } ?: 0f
		if (heading != null) {
			var bearing = heading.toFloat() * headingAdj
			// normalize to 0..360 like CDSMetrics.heading
			bearing = ((bearing % 360f) + 360f) % 360f
			currentHeading = CarHeading(bearing, speedMs)
			if (hasCdsPosition) {
				onLocationUpdate()
			}
		}
	}

	private fun applySimulatedLocation() {
		Log.i(TAG, "No CDS GPS yet, using simulated location ${SimulatedCarLocation.LATITUDE},${SimulatedCarLocation.LONGITUDE}")
		currentLocation = SimulatedCarLocation.create()
		sendCallback()
	}

	private fun onLocationUpdate() {
		val latLong = currentLatLong
		val heading = currentHeading
		currentLocation = if (latLong != null) {
			Location("CdsLocationProvider").also {
				it.latitude = latLong.latitude
				it.longitude = latLong.longitude
				it.time = System.currentTimeMillis()
				it.elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
				it.accuracy = 8f
				if (heading != null) {
					it.bearing = heading.heading
					it.speed = heading.speed
				}
			}
		} else {
			null
		}
		sendCallback()
	}

	override fun stop() {
		cdsData.subscriptions[CDS.NAVIGATION.GPSPOSITION] = null
		cdsData.subscriptions[CDS.NAVIGATION.GPSEXTENDEDINFO] = null
	}
}