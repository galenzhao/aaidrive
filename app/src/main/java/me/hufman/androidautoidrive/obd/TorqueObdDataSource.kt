package me.hufman.androidautoidrive.obd

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.RemoteException
import android.util.Log
import org.prowl.torque.remote.ITorqueService
import java.util.concurrent.atomic.AtomicReference

/**
 * [ObdDataSource] backed by Torque Pro's [ITorqueService] AIDL.
 */
class TorqueObdDataSource(
	private val appContext: Context,
	private val onStateChanged: (() -> Unit)? = null,
) : ObdDataSource {
	companion object {
		const val TAG = "TorqueObd"
		const val TORQUE_PACKAGE = "org.prowl.torque"
		const val TORQUE_SERVICE = "org.prowl.torque.remote.TorqueService"
	}

	private val mainHandler = Handler(Looper.getMainLooper())
	private val serviceRef = AtomicReference<ITorqueService?>(null)
	@Volatile private var bindRequested = false
	@Volatile private var lastError: String? = null

	override var connectionState: ObdConnectionState = ObdConnectionState.DISCONNECTED
		private set(value) {
			field = value
			onStateChanged?.invoke()
		}

	val errorMessage: String? get() = lastError

	fun isTorqueInstalled(): Boolean {
		return try {
			appContext.packageManager.getPackageInfo(TORQUE_PACKAGE, 0)
			true
		} catch (_: PackageManager.NameNotFoundException) {
			false
		}
	}

	private val connection = object : ServiceConnection {
		override fun onServiceConnected(name: ComponentName, service: IBinder) {
			val torque = ITorqueService.Stub.asInterface(service)
			serviceRef.set(torque)
			lastError = null
			connectionState = if (safeIsConnected(torque)) {
				ObdConnectionState.ECU_CONNECTED
			} else {
				ObdConnectionState.BOUND
			}
			Log.i(TAG, "Connected to Torque service $name (ecu=${connectionState == ObdConnectionState.ECU_CONNECTED})")
		}

		override fun onServiceDisconnected(name: ComponentName) {
			serviceRef.set(null)
			connectionState = ObdConnectionState.DISCONNECTED
			Log.i(TAG, "Disconnected from Torque service $name")
		}
	}

	override fun connect() {
		if (serviceRef.get() != null) {
			refreshEcuState()
			return
		}
		if (!isTorqueInstalled()) {
			lastError = "Torque not installed"
			connectionState = ObdConnectionState.ERROR
			return
		}
		if (bindRequested) {
			connectionState = ObdConnectionState.BINDING
			return
		}
		connectionState = ObdConnectionState.BINDING
		val intent = Intent().setClassName(TORQUE_PACKAGE, TORQUE_SERVICE)
		val ok = try {
			bindRequested = true
			appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
		} catch (e: SecurityException) {
			bindRequested = false
			lastError = e.message
			connectionState = ObdConnectionState.ERROR
			Log.w(TAG, "Not allowed to bind Torque", e)
			false
		}
		if (!ok) {
			bindRequested = false
			lastError = "Unable to bind Torque service"
			connectionState = ObdConnectionState.ERROR
			Log.w(TAG, "bindService returned false for Torque")
		}
	}

	override fun disconnect() {
		if (bindRequested) {
			bindRequested = false
			try {
				appContext.unbindService(connection)
			} catch (_: IllegalArgumentException) {
				// never bound
			}
		}
		serviceRef.set(null)
		connectionState = ObdConnectionState.DISCONNECTED
	}

	override fun isEcuConnected(): Boolean = refreshEcuState()

	private fun refreshEcuState(): Boolean {
		val service = serviceRef.get() ?: return false
		val connected = safeIsConnected(service)
		connectionState = if (connected) ObdConnectionState.ECU_CONNECTED else ObdConnectionState.BOUND
		return connected
	}

	private fun safeIsConnected(service: ITorqueService): Boolean {
		return try {
			service.isConnectedToECU
		} catch (e: RemoteException) {
			Log.w(TAG, "isConnectedToECU failed", e)
			false
		}
	}

	private fun serviceOrNull(): ITorqueService? = serviceRef.get()

	override fun listPids(mode: ObdListMode): List<String> {
		val service = serviceOrNull() ?: return emptyList()
		return try {
			val raw = when (mode) {
				ObdListMode.ACTIVE -> service.listActivePIDs()
				ObdListMode.ECU_SUPPORTED -> service.listECUSupportedPIDs()
				ObdListMode.ALL -> service.listAllPIDs()
			} ?: emptyArray()
			raw.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
		} catch (e: RemoteException) {
			Log.w(TAG, "listPids($mode) failed", e)
			lastError = e.message
			emptyList()
		} catch (e: RuntimeException) {
			// Binder can throw on transaction failures
			Log.w(TAG, "listPids($mode) runtime failure", e)
			lastError = e.message
			emptyList()
		}
	}

	override fun getPidInfo(ids: List<String>): List<ObdPidInfo> {
		if (ids.isEmpty()) return emptyList()
		val service = serviceOrNull() ?: return ids.map { ObdPidInfo(it, it, it, "") }
		return try {
			val csvRows = service.getPIDInformation(ids.toTypedArray()) ?: emptyArray()
			ids.mapIndexed { index, id ->
				parsePidInfo(id, csvRows.getOrNull(index))
			}
		} catch (e: Exception) {
			Log.w(TAG, "getPidInfo failed", e)
			ids.map { ObdPidInfo(it, it, it, "") }
		}
	}

	override fun pollValues(ids: List<String>): Map<String, Double> {
		if (ids.isEmpty()) return emptyMap()
		val service = serviceOrNull() ?: return emptyMap()
		return try {
			refreshEcuState()
			val values = service.getPIDValuesAsDouble(ids.toTypedArray()) ?: return emptyMap()
			ids.mapIndexedNotNull { index, id ->
				val v = values.getOrNull(index) ?: return@mapIndexedNotNull null
				id to v
			}.toMap()
		} catch (e: Exception) {
			Log.w(TAG, "pollValues failed", e)
			emptyMap()
		}
	}

	private fun parsePidInfo(id: String, csv: String?): ObdPidInfo {
		if (csv.isNullOrBlank()) {
			return ObdPidInfo(id, id, id, "")
		}
		// Format: longName,shortName,unit,maxValue,minValue,scale
		val parts = csv.split(',')
		val longName = parts.getOrNull(0)?.trim().orEmpty().ifBlank { id }
		val shortName = parts.getOrNull(1)?.trim().orEmpty().ifBlank { longName }
		val unit = parts.getOrNull(2)?.trim().orEmpty()
		val max = parts.getOrNull(3)?.toFloatOrNull() ?: 0f
		val min = parts.getOrNull(4)?.toFloatOrNull() ?: 0f
		val scale = parts.getOrNull(5)?.toFloatOrNull() ?: 1f
		return ObdPidInfo(id, longName, shortName, unit, max, min, scale)
	}

	/** Run [block] on the main thread if needed for bindService from background. */
	fun connectOnMainThread() {
		if (Looper.myLooper() == Looper.getMainLooper()) {
			connect()
		} else {
			mainHandler.post { connect() }
		}
	}
}
