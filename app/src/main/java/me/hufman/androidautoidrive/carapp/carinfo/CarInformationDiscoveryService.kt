package me.hufman.androidautoidrive.carapp.carinfo

import android.util.Log
import io.bimmergestalt.idriveconnectkit.android.CarAppAssetResources
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.CarInformationDiscovery
import me.hufman.androidautoidrive.CarInformationUpdater
import me.hufman.androidautoidrive.MainService
import me.hufman.androidautoidrive.MutableAppSettingsReceiver
import me.hufman.androidautoidrive.carapp.CarAppService
import me.hufman.androidautoidrive.cds.CDSConnection
import me.hufman.androidautoidrive.cds.CDSConnectionAsync
import me.hufman.androidautoidrive.obd.TorqueObdController

class CarInformationDiscoveryService: CarAppService() {
	val appSettings by lazy { MutableAppSettingsReceiver(applicationContext) }
	var carappCapabilities: CarInformationDiscovery? = null

	override fun shouldStartApp(): Boolean {
		return true
	}

	override fun onCarStart() {
		Log.i(MainService.TAG, "Starting to discover car capabilities")
		val handler = handler!!

		// receiver to receive capabilities and cds properties
		// wraps the CDSConnection with a Handler async wrapper
		val carInformationUpdater = object: CarInformationUpdater(appSettings) {
			override fun onCdsConnection(connection: CDSConnection?) {
				super.onCdsConnection(connection?.let { CDSConnectionAsync(handler, connection) })
			}
		}

		val certName = if (CarAppAssetResources(applicationContext, "cdsbaseapp").getAppCertificateRaw("") != null) {
			"cdsbaseapp" } else { "smartthings" }
		carappCapabilities = CarInformationDiscovery(iDriveConnectionStatus, securityAccess,
				CarAppAssetResources(applicationContext, certName), carInformationUpdater)
		carappCapabilities?.onCreate()

		AppSettings.loadSettings(applicationContext)
		TorqueObdController.init(applicationContext)
		if (TorqueObdController.isEnabled()) {
			Log.i(MainService.TAG, "Torque OBD enabled; controller will bind and poll selected PIDs")
		}
	}

	override fun onCarStop() {
		TorqueObdController.onCarStop()
		carappCapabilities?.onDestroy()
		carappCapabilities = null
	}
}
