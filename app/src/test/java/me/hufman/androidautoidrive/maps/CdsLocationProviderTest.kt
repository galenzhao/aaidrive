package me.hufman.androidautoidrive.maps

import com.google.gson.JsonObject
import io.bimmergestalt.idriveconnectkit.CDS
import me.hufman.androidautoidrive.cds.CDSDataProvider
import me.hufman.androidautoidrive.cds.subscriptions
import org.junit.Assert.*
import org.junit.Test

class CdsLocationProviderTest {
	val cdsData = CDSDataProvider()

	val gpsPosition = JsonObject().apply {
		add("GPSPosition", JsonObject().apply {
			addProperty("latitude", 12.345678)
			addProperty("longitude", -12.345678)
		})
	}
	val gpsHeading = JsonObject().apply {
		add("GPSExtendedInfo", JsonObject().apply {
			addProperty("altitude", 65530)
			addProperty("heading", 144)
			addProperty("quality", 443)
			addProperty("speed", 32768)
		})
	}

	@Test
	fun testParseEmpty() {
		val provider = CdsLocationProvider(cdsData, false)
		assertNull(provider.currentLatLong)
		assertNull(provider.currentHeading)
		assertNull(provider.currentLocation)
	}

	@Test
	fun testParseNan() {
		listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach { value ->
			listOf("latitude", "longitude").forEach { key ->
				val position = JsonObject().apply {
					add("GPSPosition", JsonObject().apply {
						if (key == "latitude") {
							addProperty("latitude", value)
						} else {
							addProperty("latitude", 12.345678)
						}
						if (key == "longitude") {
							addProperty("longitude", value)
						} else {
							addProperty("longitude", -12.345678)
						}

					})
				}

				cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, position)

				val provider = CdsLocationProvider(cdsData, false)
				assertNull(provider.currentLatLong)
				assertNull(provider.currentHeading)
				assertNull(provider.currentLocation)
			}
		}
	}

	@Test
	fun testRejectNullIsland() {
		val nullIsland = JsonObject().apply {
			add("GPSPosition", JsonObject().apply {
				addProperty("latitude", 0.0)
				addProperty("longitude", 0.0)
			})
		}
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, nullIsland)
		val provider = CdsLocationProvider(cdsData, false)
		assertNull(provider.currentLatLong)
		assertNull(provider.currentLocation)
		assertTrue(CdsLocationProvider.isNullIsland(0.0, 0.0))
		assertTrue(CdsLocationProvider.isNullIsland(1e-7, -1e-7))
		// equator / prime meridian alone are valid
		assertFalse(CdsLocationProvider.isNullIsland(0.0, 121.61))
		assertFalse(CdsLocationProvider.isNullIsland(38.91, 0.0))
		assertTrue(CdsLocationProvider.isPlausibleWgs84(38.91, 121.61))
		assertFalse(CdsLocationProvider.isPlausibleWgs84(0.0, 0.0))
	}

	@Test
	fun testDeferFirstLockUntilAltitudeValid() {
		val provider = CdsLocationProvider(cdsData, false)
		provider.start()
		assertTrue(provider.isSimulated)

		// no-fix altitude sentinel first (CDS example uses 65530)
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSEXTENDEDINFO, gpsHeading)
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, gpsPosition)
		assertNull(provider.currentLatLong)
		assertTrue(provider.isSimulated)

		val fixedHeading = JsonObject().apply {
			add("GPSExtendedInfo", JsonObject().apply {
				addProperty("altitude", 42)
				addProperty("heading", 144)
				addProperty("quality", 443)
				addProperty("speed", 32768)
			})
		}
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSEXTENDEDINFO, fixedHeading)
		assertEquals(12.345678, provider.currentLatLong?.latitude)
		assertEquals(-12.345678, provider.currentLatLong?.longitude)
		assertFalse(provider.isSimulated)
	}

	@Test
	fun testDecodeCdsGpsSpeed() {
		assertNull(CdsLocationProvider.decodeCdsGpsSpeedMs(32768.0))
		assertNull(CdsLocationProvider.decodeCdsGpsSpeedMs(0.0))
		// ~100 km/h → raw ≈ 100/0.036 - 32768
		val raw100 = 100.0 / 0.036 - 32768.0
		val ms = CdsLocationProvider.decodeCdsGpsSpeedMs(raw100)
		assertNotNull(ms)
		assertEquals(100f / 3.6f, ms!!, 0.5f)
		// ~300 km/h boundary used by CDSMetrics
		val ms300 = CdsLocationProvider.decodeCdsGpsSpeedMs(-24434.0)
		assertNotNull(ms300)
		assertEquals(300f / 3.6f, ms300!!, 1f)
	}

	@Test
	fun testParse() {
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, gpsPosition)
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSEXTENDEDINFO, gpsHeading)

		val provider = CdsLocationProvider(cdsData, false)
		assertNotNull(provider.currentLatLong)
		assertNotNull(provider.currentHeading)
		assertNotNull(provider.currentLocation)

		assertEquals(12.345678, provider.currentLatLong?.latitude)
		assertEquals(-12.345678, provider.currentLatLong?.longitude)
		assertEquals(216f, provider.currentHeading?.heading) // -144 normalized to 0..360
		assertEquals(0f, provider.currentHeading?.speed)

		assertEquals(12.345678, provider.currentLocation?.latitude)
		assertEquals(-12.345678, provider.currentLocation?.longitude)
		assertEquals(216f, provider.currentLocation?.bearing)
		assertEquals(0f, provider.currentLocation?.speed)
	}

	@Test
	fun testParseId4() {
		// ID4's heading ranges from 0-255
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, gpsPosition)
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSEXTENDEDINFO, gpsHeading)

		val provider = CdsLocationProvider(cdsData, true)
		assertNotNull(provider.currentLatLong)
		assertNotNull(provider.currentHeading)
		assertNotNull(provider.currentLocation)

		assertEquals(12.345678, provider.currentLatLong?.latitude)
		assertEquals(-12.345678, provider.currentLatLong?.longitude)
		// -202.5 → 157.5 after normalize
		assertEquals(157.5f, provider.currentHeading?.heading)
		assertEquals(0f, provider.currentHeading?.speed)

		assertEquals(12.345678, provider.currentLocation?.latitude)
		assertEquals(-12.345678, provider.currentLocation?.longitude)
		assertEquals(157.5f, provider.currentLocation?.bearing)
		assertEquals(0f, provider.currentLocation?.speed)
	}

	@Test
	fun testStart() {
		val provider = CdsLocationProvider(cdsData, false)
		assertNull(provider.currentLatLong)
		assertNull(provider.currentHeading)
		assertNull(provider.currentLocation)

		provider.start()
		assertNotNull(cdsData.subscriptions[CDS.NAVIGATION.GPSPOSITION])
		assertNotNull(cdsData.subscriptions[CDS.NAVIGATION.GPSEXTENDEDINFO])
		assertTrue(provider.isSimulated)
		assertEquals(SimulatedCarLocation.LATITUDE, provider.currentLocation?.latitude)
		assertEquals(SimulatedCarLocation.LONGITUDE, provider.currentLocation?.longitude)

		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, gpsPosition)
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSEXTENDEDINFO, gpsHeading)

		assertEquals(12.345678, provider.currentLatLong?.latitude)
		assertEquals(-12.345678, provider.currentLatLong?.longitude)
		assertEquals(216f, provider.currentHeading?.heading)
		assertEquals(0f, provider.currentHeading?.speed)

		assertEquals(12.345678, provider.currentLocation?.latitude)
		assertEquals(-12.345678, provider.currentLocation?.longitude)
		assertEquals(216f, provider.currentLocation?.bearing)
		assertEquals(0f, provider.currentLocation?.speed)
		assertFalse(provider.isSimulated)

		// test if location updates still come through
		provider.stop()
		assertNull(cdsData.subscriptions[CDS.NAVIGATION.GPSPOSITION])
		assertNull(cdsData.subscriptions[CDS.NAVIGATION.GPSEXTENDEDINFO])

		val gpsPositionNew = JsonObject().apply {
			add("GPSPosition", JsonObject().apply {
				// nearby update (not a teleport) — event handlers still fire after stop()
				addProperty("latitude", 12.355678)
				addProperty("longitude", -12.345678)
			})
		}
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, gpsPositionNew)
		assertEquals(12.355678, provider.currentLatLong?.latitude)
		assertEquals(-12.345678, provider.currentLatLong?.longitude)
		assertEquals(12.355678, provider.currentLocation?.latitude)
		assertEquals(-12.345678, provider.currentLocation?.longitude)
	}

	@Test
	fun testChinaBoundsFilter() {
		assertTrue(CdsLocationProvider.isInChinaServiceRegion(38.91, 121.61)) // Dalian
		assertTrue(CdsLocationProvider.isInChinaServiceRegion(31.23, 121.47)) // Shanghai
		assertFalse(CdsLocationProvider.isInChinaServiceRegion(12.345678, -12.345678))
		assertFalse(CdsLocationProvider.isInChinaServiceRegion(51.5, -0.12)) // London

		val settings = object : me.hufman.androidautoidrive.AppSettings {
			val map = mutableMapOf(
				me.hufman.androidautoidrive.AppSettings.KEYS.AMAP_GPS_CHINA_BOUNDS to "true",
				me.hufman.androidautoidrive.AppSettings.KEYS.wgs84ToGcj02 to "false",
			)
			override fun get(key: me.hufman.androidautoidrive.AppSettings.KEYS): String =
				map[key] ?: key.default
		}
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, gpsPosition) // mid-Atlantic test coords
		val provider = CdsLocationProvider(settings, cdsData, false)
		assertNull(provider.currentLatLong)

		val dalian = JsonObject().apply {
			add("GPSPosition", JsonObject().apply {
				addProperty("latitude", 38.91)
				addProperty("longitude", 121.61)
			})
		}
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, dalian)
		assertEquals(38.91, provider.currentLatLong?.latitude)
		assertEquals(121.61, provider.currentLatLong?.longitude)
	}

	@Test
	fun testRejectTeleport() {
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, gpsPosition)
		val provider = CdsLocationProvider(cdsData, false)
		assertEquals(12.345678, provider.currentLatLong?.latitude)

		val farAway = JsonObject().apply {
			add("GPSPosition", JsonObject().apply {
				addProperty("latitude", 38.91)
				addProperty("longitude", 121.61)
			})
		}
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, farAway)
		// single flash — keep previous
		assertEquals(12.345678, provider.currentLatLong?.latitude)
		assertEquals(-12.345678, provider.currentLatLong?.longitude)
	}

	@Test
	fun testRecoverFromBadLockWithConsistentFarSamples() {
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, gpsPosition)
		val provider = CdsLocationProvider(cdsData, false)
		assertEquals(12.345678, provider.currentLatLong?.latitude)

		fun far(lat: Double, lng: Double) = JsonObject().apply {
			add("GPSPosition", JsonObject().apply {
				addProperty("latitude", lat)
				addProperty("longitude", lng)
			})
		}
		// three consistent Dalian-area samples should replace the bad mid-ocean lock
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, far(38.91, 121.61))
		assertEquals(12.345678, provider.currentLatLong?.latitude)
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, far(38.911, 121.612))
		assertEquals(12.345678, provider.currentLatLong?.latitude)
		cdsData.onPropertyChangedEvent(CDS.NAVIGATION.GPSPOSITION, far(38.912, 121.611))
		assertEquals(38.912, provider.currentLatLong?.latitude)
		assertEquals(121.611, provider.currentLatLong?.longitude)
	}
}
