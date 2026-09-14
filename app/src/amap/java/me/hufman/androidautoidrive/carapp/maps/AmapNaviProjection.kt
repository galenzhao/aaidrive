package me.hufman.androidautoidrive.carapp.maps

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.Context
import android.content.res.Configuration
import android.location.Location
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.View
import android.view.WindowManager
import kotlin.math.roundToInt
import com.amap.api.location.AMapLocation
import com.amap.api.navi.*
import com.amap.api.navi.enums.NaviType
import com.amap.api.navi.model.AMapCalcRouteResult
import com.amap.api.navi.model.AMapLaneInfo
import com.amap.api.navi.model.AMapModelCross
import com.amap.api.navi.model.AMapNaviCameraInfo
import com.amap.api.navi.model.AMapNaviCross
import com.amap.api.navi.model.AMapNaviLocation
import com.amap.api.navi.model.AMapNaviRouteNotifyData
import com.amap.api.navi.model.AMapNaviTrafficFacilityInfo
import com.amap.api.navi.model.AMapServiceAreaInfo
import com.amap.api.navi.model.AimLessModeCongestionInfo
import com.amap.api.navi.model.AimLessModeStat
import com.amap.api.navi.model.NaviInfo
import com.amap.api.navi.enums.AMapNaviParallelRoadStatus
import me.hufman.androidautoidrive.*
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.maps.CarLocationProvider
import me.hufman.androidautoidrive.maps.LatLong
import me.hufman.androidautoidrive.maps.SimulatedCarLocation
import me.hufman.androidautoidrive.maps.toGcj02

@SuppressLint("Lifecycle")
class AmapNaviProjection(
    val parentContext: Context, 
    display: Display, 
    private val appSettings: AppSettings,
    private val locationProvider: CarLocationProvider,
    private val mapAppMode: MapAppMode
) : Presentation(parentContext, display), AMapNaviListener, AMapNaviViewListener,
	ParallelRoadListener, AimlessModeListener {

    companion object {
        private const val TAG = "AmapNaviProjection"
        // give up on a route calculation that never calls back, so the route list doesn't spin forever
        private const val ROUTE_TIMEOUT_MS = 20000L
        /** Soft fallback when onInitNaviSuccess never arrives (common after warmUpNavi). */
        private const val INIT_RETRY_MS = 500L
        private const val NAVI_INFO_MIN_INTERVAL_MS = 800L
    }

    val naviView: AMapNaviView by lazy { findViewById(R.id.naviView) }
    val naviWrapper: View by lazy { findViewById(R.id.naviViewWrapper) }
    var mapListener: Runnable? = null

    init {
        // Privacy must be agreed before AMapNavi.getInstance, which also needs location permission
        AmapSdkBootstrap.prepare(parentContext)
    }

    // 导航相关对象（docs: getInstance 可能抛异常，使用前空判断）
    private val mAMapNavi: AMapNavi? by lazy { AmapSdkBootstrap.getNaviOrNull(parentContext) }
    var isNavigating = false
        private set
    private var currentDestination: LatLong? = null
    private var pendingDestination: LatLong? = null
    private var pendingDestName: String? = null
    private var pendingDestPoiId: String? = null
    private var pendingWaypoints: List<AmapRoutePoint> = emptyList()
    private var pendingStartBearingDeg: Float? = null
    private var naviStartRequested = false
    private var autoStartAfterCalc = false
    private var routesPublished = false
    private var awaitingSecondRouteCallback = false
    private var naviReady = false
    private var naviInitFailed = false
    private var naviListenerAdded = false
    private var lastNaviInfoPostMs = 0L
    private var lastSettings: AmapSettings? = null
    private var emulatorNavi = false
    private val handler = Handler(Looper.getMainLooper())
    private val initRetryRunnable = Runnable {
        if (!naviReady && pendingDestination != null && !naviStartRequested) {
            // Allow one soft retry even after onInitNaviFailure; permanent fail still needs a new Presentation.
            Log.w(TAG, "AMap navi init callback missing/failed, retrying route (initFailed=$naviInitFailed)")
            naviInitFailed = false
            naviReady = true
            tryStartRoute()
        }
    }
    private val secondRouteTimeoutRunnable = Runnable {
        if (!routesPublished && awaitingSecondRouteCallback) {
            Log.w(TAG, "Timed out waiting for second route callback, publishing empty routes")
            publishCalculatedRoutes(intArrayOf(), allowEmpty = true)
        }
    }
    private val routeTimeoutRunnable = Runnable {
        if (!routesPublished) {
            failPendingRoute("Route calculation did not finish within ${ROUTE_TIMEOUT_MS}ms")
        }
    }

    private fun startRouteTimeout() {
        handler.removeCallbacks(routeTimeoutRunnable)
        handler.postDelayed(routeTimeoutRunnable, ROUTE_TIMEOUT_MS)
    }

    private fun failPendingRoute(reason: String) {
        Log.w(TAG, "$reason, publishing empty routes")
        pendingDestination = null
        publishCalculatedRoutes(intArrayOf(), allowEmpty = true, force = true)
    }

    private fun postTryStartRoute() {
        handler.post {
            if (pendingDestination != null && !naviStartRequested) {
                tryStartRoute()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Privacy + native load before AMapNaviView inflate (avoids getTreadId UnsatisfiedLinkError)
        AmapSdkBootstrap.warmUpNavi(parentContext)

        window?.setType(WindowManager.LayoutParams.TYPE_PRIVATE_PRESENTATION)
        applyLandscapeConfiguration()
        setContentView(R.layout.amap_navi_projection)
        try {
            naviView.onCreate(savedInstanceState)
            applyNaviViewOptions(AmapSettings.build(appSettings, locationProvider.currentLocation?.toLatLong()))
            naviView.dispatchConfigurationChanged(resources.configuration)
            Log.i(TAG, "AMapNaviView landscape=${naviView.isOrientationLandscape} config=${resources.configuration.orientation}")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to initialize AMapNaviView", e)
        }
        initNavi()
    }

    /**
     * AMap inflates portrait vs landscape chrome from [Configuration.orientation].
     * Virtual displays often copy the phone's portrait configuration even when the
     * pixel size is wide, so force landscape before [AMapNaviView] is created.
     */
    private fun applyLandscapeConfiguration() {
        val res = resources
        val metrics = res.displayMetrics
        val config = Configuration(res.configuration)
        val widthDp = (metrics.widthPixels * 160f / metrics.densityDpi).roundToInt().coerceAtLeast(1)
        val heightDp = (metrics.heightPixels * 160f / metrics.densityDpi).roundToInt().coerceAtLeast(1)
        config.orientation = Configuration.ORIENTATION_LANDSCAPE
        config.screenWidthDp = widthDp
        config.screenHeightDp = heightDp
        config.smallestScreenWidthDp = minOf(widthDp, heightDp)
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, metrics)
        Log.i(TAG, "Presentation ${metrics.widthPixels}x${metrics.heightPixels} ${widthDp}x${heightDp}dp dpi=${metrics.densityDpi} ori=${config.orientation}")
    }

    override fun onStart() {
        super.onStart()
        Log.i(TAG, "NaviProjection Start")
        try {
            naviView.onResume()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resume AMapNaviView", e)
        }
        disableAmapBuiltinLocation()
        mapListener?.run()
    }

    override fun onStop() {
        Log.i(TAG, "NaviProjection Stopped")
        try {
            naviView.onPause()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to pause AMapNaviView", e)
        }
        super.onStop()
    }

    fun destroy() {
        Log.i(TAG, "NaviProjection Destroyed")
        handler.removeCallbacksAndMessages(null)
        naviReady = false
        naviInitFailed = false
        pendingDestination = null
        val shouldStop = isNavigating || naviStartRequested
        naviStartRequested = false
        emulatorNavi = false
        awaitingSecondRouteCallback = false
        try {
            if (shouldStop) {
                mAMapNavi?.stopNavi()
            }
            isNavigating = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop AMap navi", e)
        }
        try {
            if (naviListenerAdded) {
                mAMapNavi?.removeAMapNaviListener(this)
                mAMapNavi?.removeParallelRoadListener(this)
                mAMapNavi?.removeAimlessModeListener(this)
                naviListenerAdded = false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove AMap navi listener", e)
        }
        AmapNaviGuidance.clear()
        try {
            naviView.onDestroy()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to destroy AMapNaviView", e)
        }
    }

    fun saveInstanceState(outState: Bundle) {
        try {
            naviView.onSaveInstanceState(outState)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save AMapNaviView state", e)
        }
    }

    private fun initNavi() {
        ensureNavi()
        try {
            naviView.setAMapNaviViewListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to attach AMapNaviView listener", e)
        }
    }

    private fun ensureNavi() {
        val navi = mAMapNavi
        if (navi == null) {
            Log.e(TAG, "AMapNavi unavailable after privacy bootstrap")
            naviInitFailed = true
            if (pendingDestination != null) {
                failPendingRoute("AMapNavi.getInstance failed")
            }
            return
        }
        if (!naviListenerAdded) {
            navi.addAMapNaviListener(this)
            navi.addParallelRoadListener(this)
            navi.addAimlessModeListener(this)
            naviListenerAdded = true
        }
        // warmUpNavi / prior getInstance often finishes init before this listener exists,
        // so onInitNaviSuccess may never fire again. Treat a live instance as ready.
        if (!naviReady && !naviInitFailed) {
            naviReady = true
            Log.i(TAG, "Navi ready (instance live; init success may have fired during warm-up)")
        }
        applyNaviEngineSettings(AmapSettings.build(appSettings, locationProvider.currentLocation?.toLatLong()))
        disableAmapPhoneGps()
        (locationProvider.currentLocation ?: SimulatedCarLocation.create()).let { feedExtraGps(it) }
        if (!naviReady) {
            handler.removeCallbacks(initRetryRunnable)
            handler.postDelayed(initRetryRunnable, INIT_RETRY_MS)
        }
        Log.i(TAG, "Navigation initialized with extra GPS and phone GPS disabled")
    }

    private fun applyNaviEngineSettings(settings: AmapSettings) {
        val navi = mAMapNavi ?: return
        try {
            navi.setUseInnerVoice(settings.useInnerVoice)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set AMap inner voice", e)
        }
        try {
            navi.setMultipleRouteNaviMode(settings.multipleRoute)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set multiple route mode", e)
        }
        try {
            navi.setBroadcastMode(settings.broadcastMode)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set broadcast mode", e)
        }
        try {
            AmapRoutePlanning.applyCarInfo(navi, appSettings)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply AMapCarInfo", e)
        }
        try {
            navi.setTrafficStatusUpdateEnabled(settings.trafficStatusUpdate)
            navi.setTrafficInfoUpdateEnabled(settings.trafficInfoUpdate)
            navi.setServiceAreaDetailsEnable(settings.serviceAreaDetails)
            navi.setTrafficSignalEnable(settings.trafficLights)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set AMap traffic/service callbacks", e)
        }
        try {
            navi.setCameraInfoUpdateEnabled(settings.cameras && isNavigating)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set camera info updates", e)
        }
    }

    fun onCarLocationUpdate(location: Location) {
        if (!emulatorNavi) {
            feedExtraGps(location)
        }
        postTryStartRoute()
    }

    fun navigateTo(
        dest: LatLong,
        name: String? = null,
        poiId: String? = null,
        waypoints: List<AmapRoutePoint> = emptyList(),
        startBearingDeg: Float? = null,
    ) {
        Log.i(TAG, "Planning navigation to $dest name=$name poiId=$poiId ways=${waypoints.size}")
        // Stop any in-progress navi before a new calculateDriveRoute.
        val shouldStop = isNavigating || naviStartRequested
        if (shouldStop) {
            try {
                mAMapNavi?.stopNavi()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to stop previous AMap navi before new route", e)
            }
            isNavigating = false
        }
        currentDestination = dest
        pendingDestination = dest
        pendingDestName = name
        pendingDestPoiId = poiId
        pendingWaypoints = waypoints.take(AmapRoutePlanning.MAX_WAYPOINTS)
        pendingStartBearingDeg = startBearingDeg
        naviStartRequested = false
        autoStartAfterCalc = false
        routesPublished = false
        awaitingSecondRouteCallback = false
        applySettings(AmapSettings.build(appSettings, locationProvider.currentLocation?.toLatLong()), force = true)
        startRouteTimeout()
        locationProvider.start()
        ensureNavi()
        postTryStartRoute()
    }

    fun recalcNavigation() {
        val dest = currentDestination ?: return
        Log.i(TAG, "Recalculating navigation to $dest")
        pendingDestination = dest
        // keep pendingDestName / poiId / waypoints for recalc
        naviStartRequested = false
        autoStartAfterCalc = true
        routesPublished = false
        awaitingSecondRouteCallback = false
        startRouteTimeout()
        locationProvider.start()
        ensureNavi()
        postTryStartRoute()
    }

    fun selectRoute(routeId: Int) {
        Log.i(TAG, "Selecting route $routeId")
        naviStartRequested = false
        try {
            mAMapNavi?.selectRouteId(routeId)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to select route $routeId", e)
        }
        startActiveNavi()
    }

    fun stopNavigation() {
        Log.i(TAG, "Stopping navigation")
        val shouldStop = isNavigating || naviStartRequested
        pendingDestination = null
        pendingDestName = null
        pendingDestPoiId = null
        pendingWaypoints = emptyList()
        pendingStartBearingDeg = null
        naviStartRequested = false
        emulatorNavi = false
        autoStartAfterCalc = false
        routesPublished = false
        awaitingSecondRouteCallback = false
        handler.removeCallbacks(routeTimeoutRunnable)
        handler.removeCallbacks(secondRouteTimeoutRunnable)
        try {
            // startNavi can run before onStartNavi flips isNavigating; still stop the engine.
            if (shouldStop) {
                mAMapNavi?.stopNavi()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop AMap navi", e)
        }
        isNavigating = false
        currentDestination = null
        AmapNaviGuidance.clear()
        // stopNavi freezes guidance updates but leaves the navi chrome/route on screen
        resetIdleMap()
    }

    /** Drop frozen guidance UI and return the capture surface to a free map. */
    private fun resetIdleMap() {
        try {
            naviView.setShowMode(3) // 普通态 — unlock car-follow framing
        } catch (e: Exception) {
            Log.w(TAG, "Failed to set idle show mode", e)
        }
		try {
            // 5-arg API replaces deprecated 3-arg setRouteMarkerVisible
            naviView.setRouteMarkerVisible(false, false, false, false, false)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to hide route markers", e)
        }
        // Avoid map.clear()+reInit — they trigger EGL "no current context" and theme warnings
        // in Presentation after stopNavi. Hide markers + recenter is enough for idle capture.
        try {
            naviView.setCarOverlayVisible(true)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to show car overlay", e)
        }
        applySettings(AmapSettings.build(appSettings, locationProvider.currentLocation?.toLatLong()), force = true)
        disableAmapPhoneGps()
        try {
            val origin = locationProvider.currentLocation ?: SimulatedCarLocation.create()
            val amap = toAmapLocation(origin)
            naviView.map?.animateCamera(
                com.amap.api.maps.CameraUpdateFactory.newLatLngZoom(
                    com.amap.api.maps.model.LatLng(amap.latitude, amap.longitude),
                    16f
                )
            )
            feedExtraGps(origin)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to recenter idle map", e)
        }
    }

    private fun tryStartRoute() {
        val dest = pendingDestination ?: return
        if (!naviReady) {
            Log.i(TAG, "Holding route calculation until AMap navi is ready")
            return
        }
        val origin = locationProvider.currentLocation ?: SimulatedCarLocation.create()
        if (locationProvider.currentLocation == null) {
            Log.w(TAG, "No CDS location, using simulated origin ${origin.latitude},${origin.longitude}")
        }
        val amapOrigin = toAmapLocation(origin)
        try {
            // destinations come from AMap search or favorites saved from it, so they are already GCJ-02
            val bearing = pendingStartBearingDeg
                ?: origin.takeIf { it.hasBearing() }?.bearing
            val submitted = startRouteCalculation(
                LatLong(amapOrigin.latitude, amapOrigin.longitude),
                dest,
                pendingDestName,
                pendingDestPoiId,
                pendingWaypoints,
                bearing,
            )
            pendingDestination = null
            pendingDestName = null
            pendingDestPoiId = null
            pendingWaypoints = emptyList()
            pendingStartBearingDeg = null
            if (!submitted) {
                // no calculation callback will arrive
                failPendingRoute("AMap did not accept the route calculation")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to submit route calculation, will retry when navi is ready", e)
        }
    }

    private fun disableAmapPhoneGps() {
        try {
            mAMapNavi?.setIsUseExtraGPSData(true)
            mAMapNavi?.stopGPS()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable AMap phone GPS", e)
        }
        disableAmapBuiltinLocation()
    }

    private fun disableAmapBuiltinLocation() {
        try {
            val map = naviView.map
            map?.isMyLocationEnabled = false
            map?.uiSettings?.isMyLocationButtonEnabled = false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to disable AMap map location", e)
        }
        try {
            AMapNavi.releaseLocManager()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to release AMap location manager", e)
        }
    }

    private fun feedExtraGps(location: Location) {
        try {
            val amapLocation = toAmapLocation(location)
            mAMapNavi?.setIsUseExtraGPSData(true)
            mAMapNavi?.setExtraGPSData(AMapLocation.LOCATION_TYPE_GPS, amapLocation)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to feed extra GPS to AMap", e)
        }
    }

    private fun toAmapLocation(location: Location): Location {
        val amapLocation = Location(location)
        // CdsLocationProvider always provides WGS-84, AMap expects GCJ-02
        val gcj02 = location.toLatLong().toGcj02()
        amapLocation.latitude = gcj02.latitude
        amapLocation.longitude = gcj02.longitude
        if (amapLocation.time == 0L) {
            amapLocation.time = System.currentTimeMillis()
        }
        if (amapLocation.accuracy <= 0f) {
            amapLocation.accuracy = 8f
        }
        return amapLocation
    }

    /** Returns whether AMap accepted the calculation, otherwise no callback will arrive */
    private fun startRouteCalculation(
        start: LatLong,
        dest: LatLong,
        destName: String?,
        destPoiId: String?,
        waypoints: List<AmapRoutePoint>,
        startBearingDeg: Float?,
    ): Boolean {
        val navi = mAMapNavi ?: return false
        val request = AmapRoutePlanning.latLngRequest(
            start = start,
            end = dest,
            endName = destName,
            endPoiId = destPoiId,
            waypoints = waypoints,
            startBearingDeg = startBearingDeg,
        )
        return AmapRoutePlanning.calculate(navi, appSettings, request)
    }

    fun applySettings(settings: AmapSettings, force: Boolean = false) {
        // called on every car location update, so skip unless something changed
        if (!force && settings == lastSettings) {
            return
        }
        lastSettings = settings
        try {
            naviWrapper.setPadding(0, 0, 0, 0)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply navi view settings", e)
        }
        applyNaviViewOptions(settings)
        try {
            val map = naviView.map
            map?.isMyLocationEnabled = false
            map?.uiSettings?.isMyLocationButtonEnabled = false
            map?.isTrafficEnabled = settings.mapTraffic
            map?.showBuildings(settings.mapBuildings)
            map?.showMapText(settings.mapText)
            map?.uiSettings?.isScaleControlsEnabled = settings.scale
            map?.uiSettings?.isCompassEnabled = settings.compass
            map?.mapType = when {
                settings.mapSatellite -> com.amap.api.maps.AMap.MAP_TYPE_SATELLITE
                settings.nightAuto && !settings.mapDaytime -> com.amap.api.maps.AMap.MAP_TYPE_NIGHT
                settings.night && !settings.nightAuto -> com.amap.api.maps.AMap.MAP_TYPE_NIGHT
                else -> com.amap.api.maps.AMap.MAP_TYPE_NORMAL
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply AMap overlay settings", e)
        }
    }

    private fun applyNaviViewOptions(settings: AmapSettings) {
        try {
            applyNaviEngineSettings(settings)
            val options = naviView.viewOptions ?: AMapNaviViewOptions()
            // Phone sensors = phone attitude, not car heading. VirtualDisplay often has no
            // usable sensors either — keep false always (do not expose as App setting).
            // Heading comes from CDS / setExtraGPSData bearing.
            options.setSensorEnable(false)
            // Phone-only chrome stays off on the car Presentation
            options.setSettingMenuEnabled(false)
            options.setShowSettingsPanel(false)
            options.setShowRouteStrategyPreferencePanel(false)
            options.setRouteListButtonShow(false)
            options.setRefreshButtonEnabled(false)
            options.setNaviStatusBarEnabled(false)

            val planningOrGuiding = isNavigating || currentDestination != null || pendingDestination != null || naviStartRequested
            options.setLayoutVisible(settings.layout && isNavigating)
            options.setAutoDrawRoute(planningOrGuiding)
            options.setLaneInfoShow(settings.laneInfo && isNavigating)
            options.setRealCrossDisplayShow(settings.crossView && isNavigating)
            options.setModeCrossDisplayShow(settings.crossView && isNavigating)
            options.setEyrieCrossDisplay(settings.eyrieCross && isNavigating)
            options.setTrafficBarEnabled(settings.trafficBar && isNavigating)
            options.setCompassEnabled(settings.compass)
            options.setAutoLockCar(settings.lockCar && isNavigating)
            options.setAutoChangeZoom(settings.autoZoom && isNavigating)
            options.setCameraBubbleShow(settings.cameras && isNavigating)
            options.setShowCameraDistance(settings.cameraDistance && isNavigating)
            options.setTrafficLine(settings.trafficLine && planningOrGuiding)
            options.setTrafficLayerEnabled(settings.mapTraffic)
            options.setEagleMapVisible(settings.eagle && isNavigating)
            options.setAutoDisplayOverview(settings.autoOverview && isNavigating)
            options.setNaviArrowVisible(settings.naviArrow && isNavigating)
            options.setSecondActionVisible(settings.secondAction && isNavigating)
            options.setDrawBackUpOverlay(settings.drawBackupRoute && planningOrGuiding)
            options.setAfterRouteAutoGray(settings.routeAutoGray && isNavigating)
            options.setWidgetOverSpeedPulseEffective(settings.overspeedPulse && isNavigating)
            options.setShowNaviPopTips(settings.naviPopTips && isNavigating)
            options.setRestrictAreaInfoStatus(settings.restrictAreaInfo)
            options.setStopTtsWhenNaviExit(settings.stopTtsOnExit)
            options.setAutoNaviViewNightMode(settings.nightAuto)
            options.setNaviNight(if (settings.nightAuto) !settings.mapDaytime else settings.night)
            options.setTilt(if (settings.mapTilt) settings.tiltDeg else 0)
            settings.lockMapDelayMs?.let { options.setLockMapDelayed(it) }
            if (settings.autoZoomMin != null && settings.autoZoomMax != null &&
					settings.autoZoomMin < settings.autoZoomMax) {
                options.setAutoZoomRange(settings.autoZoomMin, settings.autoZoomMax)
            }
            if (settings.mapCustomStyle && settings.amapStyleUrl.isNotBlank()) {
                options.setCustomMapStylePath(settings.amapStyleUrl)
            }
            naviView.viewOptions = options
            try {
                naviView.setNaviMode(settings.naviMode)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set navi mode", e)
            }
            try {
                naviView.setLockTilt(if (settings.mapTilt) settings.tiltDeg else 0)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set lock tilt", e)
            }
            settings.lockZoom?.let { zoom ->
                try {
                    naviView.setLockZoom(zoom)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to set lock zoom", e)
                }
            }
            try {
                naviView.setCarOverlayVisible(settings.carOverlay)
                naviView.setTrafficLightsVisible(settings.trafficLights)
                naviView.setShowDriveCongestion(settings.driveCongestion && isNavigating)
                naviView.setShowTrafficLightView(settings.trafficLightView && isNavigating)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set AMapNaviView overlays", e)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to apply AMap navi view options", e)
        }
    }

    fun zoomIn(steps: Int = 1) {
        try {
            repeat(steps.coerceAtLeast(1)) {
                naviView.map?.animateCamera(com.amap.api.maps.CameraUpdateFactory.zoomIn())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to zoom in", e)
        }
    }

    fun zoomOut(steps: Int = 1) {
        try {
            repeat(steps.coerceAtLeast(1)) {
                naviView.map?.animateCamera(com.amap.api.maps.CameraUpdateFactory.zoomOut())
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to zoom out", e)
        }
    }

    // AMapNaviListener 回调方法
    override fun onInitNaviFailure() {
        Log.e(TAG, "Navigation initialization failed")
        naviReady = false
        naviInitFailed = true
        if (pendingDestination != null && !naviStartRequested) {
            handler.removeCallbacks(initRetryRunnable)
            handler.postDelayed(initRetryRunnable, INIT_RETRY_MS)
        }
    }

    override fun onInitNaviSuccess() {
        Log.i(TAG, "Navigation initialization success")
        handler.removeCallbacks(initRetryRunnable)
        naviInitFailed = false
        naviReady = true
        postTryStartRoute()
    }

    override fun onStartNavi(type: Int) {
        Log.i(TAG, "Navigation started, type: $type")
        isNavigating = true
        // Restore guidance chrome / auto-drawn route now that navi is active
        applySettings(AmapSettings.build(appSettings, locationProvider.currentLocation?.toLatLong()), force = true)
    }

    override fun onTrafficStatusUpdate() {
        AmapNaviGuidance.postTrafficUpdated()
    }

    override fun onCalculateRouteSuccess(ids: IntArray) {
        Log.i(TAG, "Route calculation success, route IDs: ${ids.contentToString()}")
        publishCalculatedRoutes(ids, allowEmpty = false)
    }

    override fun onCalculateRouteFailure(errorInfo: Int) {
        Log.w(TAG, "Route calculation failed, error: $errorInfo")
        publishCalculatedRoutes(intArrayOf(), allowEmpty = true)
    }

    override fun onReCalculateRouteForYaw() {
        Log.i(TAG, "Recalculating route for yaw")
    }

    override fun onReCalculateRouteForTrafficJam() {
        Log.i(TAG, "Recalculating route for traffic jam")
    }

    override fun onArrivedWayPoint(wayID: Int) {
        Log.i(TAG, "Arrived at waypoint: $wayID")
    }

    override fun onGpsOpenStatus(enabled: Boolean) {
        Log.i(TAG, "GPS status: $enabled")
    }

    override fun onNaviInfoUpdate(naviInfo: NaviInfo?) {
        val info = naviInfo ?: return
        val now = System.currentTimeMillis()
        if (now - lastNaviInfoPostMs < NAVI_INFO_MIN_INTERVAL_MS) return
        lastNaviInfoPostMs = now
        AmapNaviGuidance.postNaviInfo(
            GuidanceNaviInfo(
                currentRoad = info.currentRoadName,
                nextRoad = info.nextRoadName,
                iconType = info.iconType,
                pathRetainDistanceM = info.pathRetainDistance,
                pathRetainTimeS = info.pathRetainTime,
                stepRetainDistanceM = info.curStepRetainDistance,
                stepRetainTimeS = info.curStepRetainTime,
                currentSpeedKmh = info.currentSpeed,
                remainTrafficLights = info.routeRemainLightCount,
            )
        )
    }

    override fun updateCameraInfo(infoArray: Array<out AMapNaviCameraInfo>?) {
        val cameras = infoArray?.map {
            GuidanceCamera(
                type = it.cameraType,
                distanceM = it.cameraDistance,
                limitKmh = it.cameraSpeed,
                longitude = it.x,
                latitude = it.y,
            )
        } ?: emptyList()
        AmapNaviGuidance.postCameras(cameras)
    }

    override fun updateIntervalCameraInfo(
        start: AMapNaviCameraInfo?,
        end: AMapNaviCameraInfo?,
        status: Int
    ) {
        fun AMapNaviCameraInfo.toGuidance() = GuidanceCamera(
            type = cameraType,
            distanceM = cameraDistance,
            limitKmh = cameraSpeed,
            longitude = x,
            latitude = y,
        )
        AmapNaviGuidance.postIntervalCamera(
            GuidanceIntervalCamera(
                start = start?.toGuidance(),
                end = end?.toGuidance(),
                status = status,
            )
        )
    }

    override fun onServiceAreaUpdate(infoArray: Array<out AMapServiceAreaInfo>?) {
        val areas = infoArray?.map {
            GuidanceServiceArea(
                name = it.name,
                type = it.type,
                remainDistanceM = it.remainDist,
                remainTimeS = it.remainTime,
                longitude = it.coordinate?.longitude,
                latitude = it.coordinate?.latitude,
            )
        } ?: emptyList()
        AmapNaviGuidance.postServiceAreas(areas)
    }

    override fun showCross(p0: AMapNaviCross?) {
        AmapNaviGuidance.postCrossVisible(true)
    }

    override fun hideCross() {
        AmapNaviGuidance.postCrossVisible(false)
    }

    override fun showModeCross(p0: AMapModelCross?) {
        AmapNaviGuidance.postCrossVisible(true)
    }

    override fun hideModeCross() {
        AmapNaviGuidance.postCrossVisible(false)
    }

    override fun showLaneInfo(p0: Array<out AMapLaneInfo>?, p1: ByteArray?, p2: ByteArray?) {
        // legacy overload; prefer single-object callback below
    }

    override fun showLaneInfo(laneInfo: AMapLaneInfo?) {
        if (laneInfo == null) {
            AmapNaviGuidance.postLane(null)
            return
        }
        AmapNaviGuidance.postLane(
            GuidanceLane(
                laneCount = laneInfo.laneCount,
                backgroundLane = laneInfo.backgroundLane,
                frontLane = laneInfo.frontLane,
            )
        )
    }

    override fun hideLaneInfo() {
        AmapNaviGuidance.postLane(null)
    }

    override fun onLocationChange(aMapNaviLocation: AMapNaviLocation?) {
        // car location while navigating — reserved for HUD speed/heading overlays
    }

    override fun onGetNavigationText(type: Int, text: String?) {
        text?.let {
            Log.i(TAG, "Navigation text: $text")
            AmapNaviGuidance.postSpeechText(it)
        }
    }

    override fun onGetNavigationText(text: String?) {
        // SDK also invokes the typed overload; avoid double-posting to LiveData / logs.
    }

    override fun onEndEmulatorNavi() {
        Log.i(TAG, "Emulator navigation ended")
        emulatorNavi = false
        isNavigating = false
        naviStartRequested = false
        currentDestination = null
        mapAppMode.currentNavDestination = null
        mapAppMode.finishRouteSelection()
        AmapNaviGuidance.clear()
        resetIdleMap()
    }

    override fun onArriveDestination() {
        Log.i(TAG, "Arrived at destination")
        isNavigating = false
        naviStartRequested = false
        emulatorNavi = false
        currentDestination = null
        mapAppMode.currentNavDestination = null
        mapAppMode.finishRouteSelection()
        AmapNaviGuidance.clear()
        resetIdleMap()
    }

    override fun onCalculateRouteSuccess(result: AMapCalcRouteResult?) {
        Log.i(TAG, "Route calculation success with result: $result")
        publishCalculatedRoutes(result?.routeid ?: intArrayOf(), allowEmpty = false)
    }

    override fun onCalculateRouteFailure(result: AMapCalcRouteResult?) {
        Log.w(TAG, "Route calculation failed with result: $result")
        publishCalculatedRoutes(intArrayOf(), allowEmpty = true)
    }

    /** @param force publish right away, without waiting for the second route callback */
    private fun publishCalculatedRoutes(ids: IntArray, allowEmpty: Boolean, force: Boolean = false) {
        if (routesPublished) {
            return
        }
        val paths = try {
            mAMapNavi?.naviPaths ?: emptyMap()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read navi paths", e)
            emptyMap()
        }
        val routeIds = ids.takeIf { it.isNotEmpty() } ?: paths.keys.map { it.toInt() }.toIntArray()
        val choices = ArrayList<MapRouteChoice>()
        for (id in routeIds) {
            val path = paths[id] ?: continue
            choices.add(MapRouteChoice(
                    id,
                    path.labels ?: "",
                    path.allTime,
                    path.allLength,
                    path.tollCost
            ))
        }
        if (choices.isEmpty() && !force) {
            if (!awaitingSecondRouteCallback) {
                awaitingSecondRouteCallback = true
                Log.w(TAG, "Route callback before navi paths were ready, waiting for the other callback")
                handler.removeCallbacks(secondRouteTimeoutRunnable)
                handler.postDelayed(secondRouteTimeoutRunnable, 2500)
                return
            }
            if (!allowEmpty) {
                Log.w(TAG, "Both route success callbacks were empty")
            }
        }
        handler.removeCallbacks(secondRouteTimeoutRunnable)
        handler.removeCallbacks(routeTimeoutRunnable)
        awaitingSecondRouteCallback = false
        routesPublished = true
        val delivery = mapAppMode.completePendingRoutes(choices)
        val autoStart = autoStartAfterCalc || delivery == RouteDelivery.NOT_REQUESTED
        autoStartAfterCalc = false
        if (delivery == RouteDelivery.CANCELLED) {
            Log.i(TAG, "Route selection was cancelled, not starting navigation")
        } else if (autoStart && choices.isNotEmpty()) {
            try {
                mAMapNavi?.selectRouteId(choices[0].routeId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to select default route", e)
            }
            startActiveNavi()
        }
    }

    private fun startActiveNavi() {
        if (naviStartRequested) {
            return
        }
        naviStartRequested = true
        disableAmapPhoneGps()
        val origin = locationProvider.currentLocation ?: SimulatedCarLocation.create()
        if (locationProvider.isSimulated || SimulatedCarLocation.matches(origin)) {
            emulatorNavi = true
            try {
                mAMapNavi?.setIsUseExtraGPSData(false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to release extra GPS for emulator", e)
            }
            try {
                mAMapNavi?.setEmulatorNaviSpeed(MapNaviBehavior.emulatorSpeed(appSettings))
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set emulator navi speed", e)
            }
            Log.i(TAG, "Starting emulator navigation from ${origin.latitude},${origin.longitude}")
            mAMapNavi?.startNavi(NaviType.EMULATOR)
        } else {
            emulatorNavi = false
            feedExtraGps(origin)
            Log.i(TAG, "Starting GPS navigation with CDS extra GPS")
            mAMapNavi?.startNavi(NaviType.GPS)
        }
    }

    override fun notifyParallelRoad(p0: Int) {
        // legacy int callback; richer status comes from ParallelRoadListener
    }

    override fun OnUpdateTrafficFacility(p0: Array<out AMapNaviTrafficFacilityInfo>?) {
        // also delivered via AimlessModeListener while cruising
    }

    override fun OnUpdateTrafficFacility(p0: AMapNaviTrafficFacilityInfo?) {
    }

    override fun updateAimlessModeStatistics(stat: AimLessModeStat?) {
        AmapNaviGuidance.postAimless(
            GuidanceAimless(
                statisticsSummary = stat?.let {
                    "dist=${it.aimlessModeDistance}m time=${it.aimlessModeTime}s"
                }
            )
        )
    }

    override fun updateAimlessModeCongestionInfo(info: AimLessModeCongestionInfo?) {
        AmapNaviGuidance.postAimless(
            GuidanceAimless(
                congestionDistanceM = info?.length,
                statisticsSummary = info?.roadName,
            )
        )
    }

    override fun onPlayRing(p0: Int) {
    }

    override fun onNaviRouteNotify(notifyData: AMapNaviRouteNotifyData?) {
        if (notifyData == null) {
            AmapNaviGuidance.postRouteNotify(null)
            return
        }
        AmapNaviGuidance.postRouteNotify(
            GuidanceRouteNotify(
                notifyType = notifyData.notifyType,
                success = notifyData.isSuccess,
                distanceM = notifyData.distance,
                roadName = notifyData.roadName,
                reason = notifyData.reason,
                subTitle = notifyData.subTitle,
                longitude = notifyData.longitude,
                latitude = notifyData.latitude,
            )
        )
    }

    override fun onGpsSignalWeak(p0: Boolean) {
    }

    // ParallelRoadListener (驾车平行路)
    override fun notifyParallelRoad(roadStatus: AMapNaviParallelRoadStatus?) {
        if (roadStatus == null) {
            AmapNaviGuidance.postParallelRoad(null)
            return
        }
        AmapNaviGuidance.postParallelRoad(
            GuidanceParallelRoad(
                status = roadStatus.status,
                parallelRoadFlag = roadStatus.getmParallelRoadStatusFlag(),
                elevatedRoadFlag = roadStatus.getmElevatedRoadStatusFlag(),
            )
        )
    }

    // AimlessModeListener (智能巡航)
    override fun onUpdateTrafficFacility(infos: Array<out AMapNaviTrafficFacilityInfo>?) {
        AmapNaviGuidance.postAimless(
            GuidanceAimless(facilityCount = infos?.size ?: 0)
        )
    }

    override fun onUpdateAimlessModeElecCameraInfo(cameraInfo: Array<out AMapNaviTrafficFacilityInfo>?) {
        AmapNaviGuidance.postAimless(
            GuidanceAimless(elecCameraCount = cameraInfo?.size ?: 0)
        )
    }

    // AMapNaviViewListener 回调方法
    override fun onNaviSetting() {
        Log.i(TAG, "Navigation setting from view")
    }

    override fun onNaviCancel() {
        Log.i(TAG, "Navigation cancelled from view")
        stopNavigation()
    }

    override fun onNaviBackClick(): Boolean {
        Log.i(TAG, "Navigation back clicked")
        return false
    }

    override fun onNaviMapMode(type: Int) {
        Log.i(TAG, "Navigation map mode from view: $type")
    }

    override fun onNaviTurnClick() {
        Log.i(TAG, "Navigation turn clicked from view")
    }

    override fun onNextRoadClick() {
        Log.i(TAG, "Next road clicked from view")
    }

    override fun onScanViewButtonClick() {
        Log.i(TAG, "Scan view button clicked from view")
    }

    override fun onLockMap(isLock: Boolean) {
        Log.i(TAG, "Map lock from view: $isLock")
    }

    override fun onNaviViewLoaded() {
        Log.i(TAG, "Navigation view loaded from view")
    }

    override fun onMapTypeChanged(mapType: Int) {
        Log.i(TAG, "Map type changed from view: $mapType")
    }

    override fun onNaviViewShowMode(showMode: Int) {
        Log.i(TAG, "Navigation view show mode from view: $showMode")
    }

    // AMapNaviViewListener additions in Amap 11.x (no-ops for headless car Presentation)
    override fun onStopSpeaking() {}
    override fun onViewTypeChanged(viewType: AmapPageType?) {}
    override fun onAMapNaviViewExit() {}
    override fun onStrategyChanged(strategy: Int) {}
    override fun onBroadcastModeChanged(mode: Int) {}
    override fun onDayAndNightModeChanged(mode: Int) {}
    override fun onScaleAutoChanged(enable: Boolean) {}
    override fun onListenToVoiceDuringCallChanged(isListenToVoiceDuringCall: Boolean) {}
    override fun onControlMusicVolumeModeChanged(controlMusicVolumeMode: Int) {}
    override fun onEagleChanged(isEagle: Boolean) {}
    override fun onNaviRouteHighlightChange(highLightPathId: Long, triggerType: Int) {}
}
