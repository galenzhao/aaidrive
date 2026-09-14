package me.hufman.androidautoidrive.utils

import android.location.Address
import android.location.Geocoder
import android.os.Build
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object GeocoderCompat {
	fun getFromLocationName(geocoder: Geocoder, locationName: String, maxResults: Int): List<Address> {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			val latch = CountDownLatch(1)
			val results = mutableListOf<Address>()
			geocoder.getFromLocationName(locationName, maxResults, object : Geocoder.GeocodeListener {
				override fun onGeocode(addresses: MutableList<Address>) {
					results.addAll(addresses)
					latch.countDown()
				}

				override fun onError(errorMessage: String?) {
					latch.countDown()
				}
			})
			latch.await(5, TimeUnit.SECONDS)
			results
		} else {
			@Suppress("DEPRECATION")
			geocoder.getFromLocationName(locationName, maxResults) ?: emptyList()
		}
	}
}
