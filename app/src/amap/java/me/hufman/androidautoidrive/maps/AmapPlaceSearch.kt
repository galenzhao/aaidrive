package me.hufman.androidautoidrive.maps

import android.content.Context
import android.util.Log
import cn.hutool.core.util.CoordinateUtil
import com.amap.api.services.core.AMapException
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.core.PoiItem
import com.amap.api.services.help.Inputtips
import com.amap.api.services.help.InputtipsQuery
import com.amap.api.services.help.Tip
import com.amap.api.services.poisearch.PoiResult
import com.amap.api.services.poisearch.PoiSearch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import me.hufman.androidautoidrive.carapp.maps.AmapSdkBootstrap
import me.hufman.androidautoidrive.carapp.maps.TAG

fun LatLonPoint.toLatLong(): LatLong {
	return LatLong(this.latitude, this.longitude)
}

/** AMapNavi.setExtraGPSData type: 1 = WGS-84 / system GPS, 2 = AMap / GCJ-02 */
const val AMAP_EXTRA_GPS_TYPE_WGS84 = 1
const val AMAP_EXTRA_GPS_TYPE_GCJ02 = 2

/** App-internal car locations are WGS-84. Map tiles, search, and drive-route APIs want GCJ-02 in China. */
fun LatLong.toGcj02(): LatLong {
	if (CoordinateUtil.outOfChina(longitude, latitude)) {
		return this
	}
	val coord = CoordinateUtil.wgs84ToGcj02(longitude, latitude)
	return LatLong(coord.lat, coord.lng)
}

fun MapResult(poi: PoiItem, origin: LatLong?): MapResult {
	val featureLocation = poi.latLonPoint?.toLatLong()
	val distanceKm = poi.distance.takeIf { it > 0 }?.toFloat()?.div(1000)
			?: if (origin != null && featureLocation != null) {
				featureLocation.distanceFrom(origin).toFloat()
			} else {
				null
			}
	val address = poi.snippet?.takeIf { it.isNotBlank() }
			?: poi.adName?.takeIf { it.isNotBlank() }

	return MapResult(
			poi.poiId ?: "",
			poi.title ?: "",
			address,
			featureLocation,
			distanceKm
	)
}

fun MapResult(tip: Tip, origin: LatLong?): MapResult {
	val featureLocation = tip.point?.toLatLong()
	val distanceKm = if (origin != null && featureLocation != null) {
		featureLocation.distanceFrom(origin).toFloat()
	} else {
		null
	}
	val address = tip.address?.takeIf { it.isNotBlank() }
			?: tip.district?.takeIf { it.isNotBlank() }

	return MapResult(
			tip.poiID ?: "",
			tip.name ?: "",
			address,
			featureLocation,
			distanceKm
	)
}

class AmapPlaceSearch(
		private val context: Context,
		val locationProvider: CarLocationProvider
): MapPlaceSearch {
	companion object {
		/** Match radius when resolving Inputtips that lack poiID. */
		private const val POI_ENRICH_BOUND_M = 300
		private const val POI_ENRICH_MAX_KM = 0.2

		fun getInstance(context: Context, locationProvider: CarLocationProvider): AmapPlaceSearch {
			AmapSdkBootstrap.prepare(context)
			locationProvider.start()
			return AmapPlaceSearch(context.applicationContext, locationProvider)
		}
	}

	private val inFlightSearches = mutableSetOf<Any>()

	private fun retain(search: Any) {
		synchronized(inFlightSearches) {
			inFlightSearches.add(search)
		}
	}

	private fun release(search: Any) {
		synchronized(inFlightSearches) {
			inFlightSearches.remove(search)
		}
	}

	override fun searchLocationsAsync(query: String): Deferred<List<MapResult>> {
		if (query.isBlank()) {
			return CompletableDeferred(emptyList())
		}
		val results = CompletableDeferred<List<MapResult>>()
		val latLong = locationProvider.currentLocation?.let { LatLong(it.latitude, it.longitude).toGcj02() }
		Log.i(TAG, "Starting AMap Inputtips search for $query near $latLong")

		try {
			val tipsQuery = InputtipsQuery(query, "")
			if (latLong != null) {
				tipsQuery.setLocation(LatLonPoint(latLong.latitude, latLong.longitude))
			}

			val inputTips = Inputtips(context, tipsQuery)
			retain(inputTips)
			inputTips.setInputtipsListener { tipList, errorCode ->
				release(inputTips)
				if (errorCode == AMapException.CODE_AMAP_SUCCESS) {
					val resultPlaces = tipList.orEmpty()
							.filter { !it.name.isNullOrBlank() }
							.map { MapResult(it, latLong) }
					Log.i(TAG, "Received ${resultPlaces.size} AMap input tips for query $query")
					results.complete(resultPlaces)
				} else {
					Log.w(TAG, "Unsuccessful AMap Inputtips search for $query: errorCode=$errorCode")
					results.complete(emptyList())
				}
			}
			inputTips.requestInputtipsAsyn()
		} catch (e: Exception) {
			Log.w(TAG, "Failed to start AMap Inputtips search for $query: $e")
			results.complete(emptyList())
		}

		return results
	}

	override fun resultInformationAsync(resultId: String): Deferred<MapResult?> {
		val result = CompletableDeferred<MapResult?>()
		if (resultId.isBlank()) {
			result.complete(null)
			return result
		}
		val latLong = locationProvider.currentLocation?.let { LatLong(it.latitude, it.longitude).toGcj02() }
		Log.i(TAG, "Looking up AMap POI id $resultId")

		try {
			val poiSearch = PoiSearch(context, PoiSearch.Query("", "", ""))
			retain(poiSearch)
			poiSearch.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
				override fun onPoiSearched(poiResult: PoiResult?, errorCode: Int) {
					// ID lookup uses onPoiItemSearched
				}

				override fun onPoiItemSearched(poiItem: PoiItem?, errorCode: Int) {
					release(poiSearch)
					if (errorCode == AMapException.CODE_AMAP_SUCCESS && poiItem != null) {
						val mapResult = MapResult(poiItem, latLong)
						if (mapResult.location == null) {
							Log.w(TAG, "AMap POI $resultId does not have a location")
							result.complete(null)
						} else {
							Log.i(TAG, "Received AMap POI info for $resultId: ${mapResult.name}")
							result.complete(mapResult)
						}
					} else {
						Log.w(TAG, "Did not find AMap POI info for $resultId: errorCode=$errorCode")
						result.complete(null)
					}
				}
			})
			poiSearch.searchPOIIdAsyn(resultId)
		} catch (e: Exception) {
			Log.w(TAG, "Failed to start AMap POI id lookup for $resultId: $e")
			result.complete(null)
		}

		return result
	}

	/**
	 * Inputtips often return lat/lng without [Tip.getPoiID]. Re-running tips rarely helps;
	 * keyword+bound [PoiSearch] usually returns a real poiId near the tip point.
	 */
	override fun enrichMissingPoiIdAsync(result: MapResult): Deferred<MapResult> {
		val loc = result.location
		if (result.id.isNotBlank() || result.name.isBlank() || loc == null) {
			return CompletableDeferred(result)
		}
		val deferred = CompletableDeferred<MapResult>()
		// Tip/search coordinates from AMap are already GCJ-02.
		val point = LatLonPoint(loc.latitude, loc.longitude)
		try {
			val query = PoiSearch.Query(result.name, "", "")
			query.pageSize = 10
			query.pageNum = 1
			query.setDistanceSort(true)
			query.location = point
			val poiSearch = PoiSearch(context, query)
			poiSearch.bound = PoiSearch.SearchBound(point, POI_ENRICH_BOUND_M)
			retain(poiSearch)
			Log.i(TAG, "Enriching blank poiId for ${result.name} near $loc")
			poiSearch.setOnPoiSearchListener(object : PoiSearch.OnPoiSearchListener {
				override fun onPoiSearched(poiResult: PoiResult?, errorCode: Int) {
					release(poiSearch)
					if (errorCode == AMapException.CODE_AMAP_SUCCESS) {
						val pois = poiResult?.pois.orEmpty()
						val match = pois.firstOrNull { poi ->
							val id = poi.poiId
							val pLoc = poi.latLonPoint?.toLatLong()
							!id.isNullOrBlank() && pLoc != null && pLoc.distanceFrom(loc) <= POI_ENRICH_MAX_KM
						} ?: pois.firstOrNull { poi ->
							!poi.poiId.isNullOrBlank() && poi.title.equals(result.name, ignoreCase = true)
						} ?: pois.firstOrNull { !it.poiId.isNullOrBlank() }
						val poiId = match?.poiId
						if (!poiId.isNullOrBlank()) {
							Log.i(TAG, "Enriched poiId=$poiId for ${result.name}")
							deferred.complete(
								result.copy(
									id = poiId,
									address = result.address
										?: match.snippet?.takeIf { it.isNotBlank() }
										?: match.adName?.takeIf { it.isNotBlank() },
								)
							)
							return
						}
						Log.w(TAG, "No POI match to enrich poiId for ${result.name} (${pois.size} candidates)")
					} else {
						Log.w(TAG, "POI enrich search failed for ${result.name}: errorCode=$errorCode")
					}
					deferred.complete(result)
				}

				override fun onPoiItemSearched(poiItem: PoiItem?, errorCode: Int) {
					// keyword bound search uses onPoiSearched
				}
			})
			poiSearch.searchPOIAsyn()
		} catch (e: Exception) {
			Log.w(TAG, "Failed to start POI enrich for ${result.name}: $e")
			deferred.complete(result)
		}
		return deferred
	}
}
