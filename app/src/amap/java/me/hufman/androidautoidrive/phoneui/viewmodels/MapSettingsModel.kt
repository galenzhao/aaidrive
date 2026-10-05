package me.hufman.androidautoidrive.phoneui.viewmodels

import android.content.Context
import androidx.lifecycle.*
import me.hufman.androidautoidrive.*
import me.hufman.androidautoidrive.maps.AmapTokenValidation
import me.hufman.androidautoidrive.phoneui.FunctionalLiveData
import me.hufman.androidautoidrive.phoneui.LiveDataHelpers.combine
import me.hufman.androidautoidrive.phoneui.LiveDataHelpers.map

class MapSettingsModel(appContext: Context, carCapabilitiesSummarized: LiveData<CarCapabilitiesSummarized>): ViewModel() {
	class Factory(val appContext: Context): ViewModelProvider.Factory {
		val carInformation = CarInformationObserver()

		@Suppress("UNCHECKED_CAST")
		override fun <T : ViewModel> create(modelClass: Class<T>): T {
			val carCapabilitiesSummarized = MutableLiveData<CarCapabilitiesSummarized>()
			carInformation.callback = {
				carCapabilitiesSummarized.postValue(CarCapabilitiesSummarized(carInformation))
			}
			carCapabilitiesSummarized.value = CarCapabilitiesSummarized(carInformation)

			return MapSettingsModel(appContext, carCapabilitiesSummarized) as T
		}

		fun unsubscribe() {
			carInformation.callback = {}
		}
	}

	val mapEnabled = BooleanLiveSetting(appContext, AppSettings.KEYS.ENABLED_MAPS)
	val showAdvancedSettings = BooleanLiveSetting(appContext, AppSettings.KEYS.SHOW_ADVANCED_SETTINGS)
	val mapWidescreen = BooleanLiveSetting(appContext, AppSettings.KEYS.MAP_WIDESCREEN)
	val mapDisplayWidth = StringLiveSetting(appContext, AppSettings.KEYS.MAP_DISPLAY_WIDTH)
	val mapDisplayHeight = StringLiveSetting(appContext, AppSettings.KEYS.MAP_DISPLAY_HEIGHT)
	val mapDisplayOffsetX = StringLiveSetting(appContext, AppSettings.KEYS.MAP_DISPLAY_OFFSET_X)
	val mapDisplayOffsetY = StringLiveSetting(appContext, AppSettings.KEYS.MAP_DISPLAY_OFFSET_Y)
	val mapFps = StringLiveSetting(appContext, AppSettings.KEYS.MAP_FPS)
	val mapInvertZoom = BooleanLiveSetting(appContext, AppSettings.KEYS.MAP_INVERT_SCROLL)
	val mapTilt = BooleanLiveSetting(appContext, AppSettings.KEYS.MAP_TILT)
	val mapBuildings = BooleanLiveSetting(appContext, AppSettings.KEYS.MAP_BUILDINGS)
	val mapSatellite = BooleanLiveSetting(appContext, AppSettings.KEYS.MAP_SATELLITE)
	val mapTraffic = BooleanLiveSetting(appContext, AppSettings.KEYS.MAP_TRAFFIC)
	val amapAvoidCongestion = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_AVOID_CONGESTION)
	val amapAvoidHighway = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_AVOID_HIGHWAY)
	val amapAvoidCost = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_AVOID_COST)
	val amapPreferHighway = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_PREFER_HIGHWAY)
	val amapRouteStrategy = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_ROUTE_STRATEGY)
	val amapCarNumber = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_CAR_NUMBER)
	val amapCarRestriction = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_CAR_RESTRICTION)
	val amapMultipleRoute = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_MULTIPLE_ROUTE)
	val amapRenderScale = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_RENDER_SCALE)
	val amapEmulatorSpeed = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_EMULATOR_SPEED)
	val amapSelectRoute = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_SELECT_ROUTE)
	val amapUseInnerVoice = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_USE_INNER_VOICE)
	val amapBroadcastMode = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_BROADCAST_MODE)
	val amapNaviMode = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_NAVI_MODE)
	val amapTiltDeg = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_TILT_DEG)
	val amapLockZoom = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_LOCK_ZOOM)
	val amapNightAuto = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_NIGHT_AUTO)
	val amapNight = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_NIGHT)
	val amapLayout = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_LAYOUT)
	val amapLaneInfo = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_LANE_INFO)
	val amapCrossView = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_CROSS_VIEW)
	val amapTrafficBar = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_TRAFFIC_BAR)
	val amapCompass = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_COMPASS)
	val amapScale = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_SCALE)
	val amapMapText = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_MAP_TEXT)
	val amapLockCar = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_LOCK_CAR)
	val amapAutoZoom = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_AUTO_ZOOM)
	val amapCameras = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_CAMERAS)
	val amapCameraDistance = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_CAMERA_DISTANCE)
	val amapTrafficLine = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_TRAFFIC_LINE)
	val amapEagle = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_EAGLE)
	val amapAutoOverview = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_AUTO_OVERVIEW)
	val amapNaviArrow = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_NAVI_ARROW)
	val amapSecondAction = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_SECOND_ACTION)
	val amapDrawBackupRoute = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_DRAW_BACKUP_ROUTE)
	val amapRouteAutoGray = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_ROUTE_AUTO_GRAY)
	val amapOverspeedPulse = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_OVERSPEED_PULSE)
	val amapEyrieCross = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_EYRIE_CROSS)
	val amapNaviPopTips = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_NAVI_POP_TIPS)
	val amapCarOverlay = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_CAR_OVERLAY)
	val amapTrafficLights = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_TRAFFIC_LIGHTS)
	val amapTrafficLightView = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_TRAFFIC_LIGHT_VIEW)
	val amapDriveCongestion = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_DRIVE_CONGESTION)
	val amapTrafficStatusUpdate = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_TRAFFIC_STATUS_UPDATE)
	val amapTrafficInfoUpdate = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_TRAFFIC_INFO_UPDATE)
	val amapServiceAreaDetails = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_SERVICE_AREA_DETAILS)
	val amapRestrictAreaInfo = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_RESTRICT_AREA_INFO)
	val amapStopTtsOnExit = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_STOP_TTS_ON_EXIT)
	val amapLockMapDelayMs = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_LOCK_MAP_DELAY_MS)
	val amapAutoZoomMin = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_AUTO_ZOOM_MIN)
	val amapAutoZoomMax = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_AUTO_ZOOM_MAX)
	val amapWgs84ToGcj02 = BooleanLiveSetting(appContext, AppSettings.KEYS.wgs84ToGcj02)
	val amapExtraGpsGcj02 = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_EXTRA_GPS_GCJ02)
	val amapGpsChinaBounds = BooleanLiveSetting(appContext, AppSettings.KEYS.AMAP_GPS_CHINA_BOUNDS)
	val mapCompressQuality = StringLiveSetting(appContext, AppSettings.KEYS.compressQuality)
	val amapCustomStyle = BooleanLiveSetting(appContext, AppSettings.KEYS.MAP_CUSTOM_STYLE)
	val amapStyleUrl = StringLiveSetting(appContext, AppSettings.KEYS.AMAP_STYLE_URL)
	val showAmapCustomField = FunctionalLiveData {
		// use a FunctionalLiveData so that
		// we keep showing this field if it was set at onResume
		// and while the user toggles it
		showAdvancedSettings.value == true || amapCustomStyle.value == true
	}.combine(amapCustomStyle) { sticky, enabled ->
		// also show the option if the option is toggled in the car menu
		sticky || enabled
	}

	val mapWidescreenSupported = carCapabilitiesSummarized.map(false) {
		it.mapWidescreenSupported
	}
	val mapWidescreenUnsupported = carCapabilitiesSummarized.map(false) {
		it.mapWidescreenUnsupported
	}
	val mapWidescreenCrashes = carCapabilitiesSummarized.map(false) {
		it.mapWidescreenCrashes
	}

	val invalidAccessToken: LiveData<Boolean?> = liveData {
		// Check if AMap API key is valid
		val apiKey = System.getenv("AndroidAutoIdrive_AmapApiKey") ?: "unset"
		emit(AmapTokenValidation.validateToken(apiKey) == false)
	}
}
