package me.hufman.androidautoidrive.obd

import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.MutableAppSettingsReceiver
import me.hufman.androidautoidrive.carapp.L
import org.json.JSONArray

/**
 * App-wide Torque OBD bridge: bind, refresh PID catalog, poll selected sensors,
 * and expose car-info category pages.
 */
object TorqueObdController {
	private const val TAG = "TorqueObd"
	private const val POLL_INTERVAL_MS = 750L
	const val OBD_PAGE_SIZE = 8

	@Volatile private var appContext: Context? = null
	@Volatile private var dataSource: TorqueObdDataSource? = null
	private var settings: MutableAppSettingsReceiver? = null
	private var worker: HandlerThread? = null
	private var workerHandler: Handler? = null

	private val _connectionState = MutableStateFlow(ObdConnectionState.DISABLED)
	val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()

	private val _availablePids = MutableStateFlow<List<ObdPidInfo>>(emptyList())
	val availablePids: StateFlow<List<ObdPidInfo>> = _availablePids.asStateFlow()

	private val _selectedIds = MutableStateFlow<List<String>>(emptyList())
	val selectedIds: StateFlow<List<String>> = _selectedIds.asStateFlow()

	/** pidId -> formatted label for the car screen */
	private val _displayValues = MutableStateFlow<Map<String, String>>(emptyMap())
	val displayValues: StateFlow<Map<String, String>> = _displayValues.asStateFlow()

	/** Bumps when selected PIDs / page layout changes so CarInfo rebuilds categories. */
	private val _pagesRevision = MutableStateFlow(0)
	val pagesRevision: StateFlow<Int> = _pagesRevision.asStateFlow()

	private val metaCache = LinkedHashMap<String, ObdPidInfo>()
	private val pollRunnable = object : Runnable {
		override fun run() {
			pollOnce()
			workerHandler?.postDelayed(this, POLL_INTERVAL_MS)
		}
	}

	fun init(context: Context) {
		val app = context.applicationContext
		if (appContext === app && dataSource != null) {
			applySettings()
			return
		}
		appContext = app
		settings = MutableAppSettingsReceiver(app).also { receiver ->
			receiver.callback = { applySettings() }
		}
		dataSource = TorqueObdDataSource(app) {
			_connectionState.value = if (isEnabled()) {
				dataSource?.connectionState ?: ObdConnectionState.DISCONNECTED
			} else {
				ObdConnectionState.DISABLED
			}
		}
		ensureWorker()
		applySettings()
	}

	fun isEnabled(): Boolean =
		settings?.get(AppSettings.KEYS.TORQUE_OBD_ENABLED)?.toBoolean() == true

	fun listMode(): ObdListMode =
		ObdListMode.fromSetting(settings?.get(AppSettings.KEYS.TORQUE_OBD_LIST_MODE) ?: "ACTIVE")

	fun torqueInstalled(): Boolean = dataSource?.isTorqueInstalled() == true

	fun errorMessage(): String? = dataSource?.errorMessage

	private fun ensureWorker() {
		if (worker?.isAlive == true) return
		val thread = HandlerThread("TorqueObd").also { it.start() }
		worker = thread
		workerHandler = Handler(thread.looper)
	}

	private fun applySettings() {
		val enabled = isEnabled()
		_selectedIds.value = readSelectedIds()
		_pagesRevision.value = _pagesRevision.value + 1

		if (!enabled) {
			stopPolling()
			dataSource?.disconnect()
			_connectionState.value = ObdConnectionState.DISABLED
			_displayValues.value = emptyMap()
			return
		}

		ensureWorker()
		workerHandler?.post {
			dataSource?.connect()
			_connectionState.value = dataSource?.connectionState ?: ObdConnectionState.BINDING
			startPollingLocked()
		}
	}

	fun setEnabled(enabled: Boolean) {
		val s = settings ?: return
		s[AppSettings.KEYS.TORQUE_OBD_ENABLED] = enabled.toString()
		// callback triggers applySettings
	}

	fun setListMode(mode: ObdListMode) {
		settings?.set(AppSettings.KEYS.TORQUE_OBD_LIST_MODE, mode.name)
	}

	fun setSelectedIds(ids: List<String>) {
		val s = settings ?: return
		val json = JSONArray().apply { ids.forEach { put(it) } }.toString()
		s[AppSettings.KEYS.TORQUE_OBD_PIDS] = json
	}

	fun toggleSelected(id: String, selected: Boolean) {
		val current = _selectedIds.value.toMutableList()
		if (selected) {
			if (!current.contains(id)) current.add(id)
		} else {
			current.remove(id)
		}
		setSelectedIds(current)
	}

	fun refreshAvailablePids(onDone: ((List<ObdPidInfo>) -> Unit)? = null) {
		ensureWorker()
		workerHandler?.post {
			val source = dataSource
			if (source == null) {
				onDone?.invoke(emptyList())
				return@post
			}
			if (!isEnabled()) {
				// allow catalog refresh while configuring before enable, still bind temporarily
				source.connect()
			} else {
				source.connect()
			}
			val ids = source.listPids(listMode())
			val infos = source.getPidInfo(ids)
			infos.forEach { metaCache[it.id] = it }
			// Keep meta for already-selected ids even if not in current list
			val missingSelected = _selectedIds.value.filter { it !in metaCache }
			if (missingSelected.isNotEmpty()) {
				source.getPidInfo(missingSelected).forEach { metaCache[it.id] = it }
			}
			_availablePids.value = infos
			_connectionState.value = source.connectionState
			onDone?.invoke(infos)
		}
	}

	private fun readSelectedIds(): List<String> {
		val raw = settings?.get(AppSettings.KEYS.TORQUE_OBD_PIDS).orEmpty()
		if (raw.isBlank()) return emptyList()
		return try {
			if (raw.trimStart().startsWith("[")) {
				val arr = JSONArray(raw)
				(0 until arr.length()).mapNotNull { arr.optString(it, null)?.takeIf { s -> s.isNotBlank() } }
			} else {
				raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
			}
		} catch (e: Exception) {
			Log.w(TAG, "Failed to parse TORQUE_OBD_PIDS", e)
			emptyList()
		}
	}

	private fun startPollingLocked() {
		val handler = workerHandler ?: return
		handler.removeCallbacks(pollRunnable)
		handler.post(pollRunnable)
	}

	private fun stopPolling() {
		workerHandler?.removeCallbacks(pollRunnable)
	}

	private fun pollOnce() {
		if (!isEnabled()) return
		val source = dataSource ?: return
		val ids = _selectedIds.value
		if (ids.isEmpty()) {
			_displayValues.value = emptyMap()
			return
		}
		source.connect()
		// ensure meta for selected
		val needMeta = ids.filter { it !in metaCache }
		if (needMeta.isNotEmpty()) {
			source.getPidInfo(needMeta).forEach { metaCache[it.id] = it }
		}
		val values = source.pollValues(ids)
		val formatted = LinkedHashMap<String, String>()
		ids.forEach { id ->
			val info = metaCache[id] ?: ObdPidInfo(id, id, id, "")
			formatted[id] = info.formatValue(values[id])
		}
		_displayValues.value = formatted
		_connectionState.value = source.connectionState
	}

	fun buildCategoryPages(): LinkedHashMap<String, List<kotlinx.coroutines.flow.Flow<String>>> {
		val pages = LinkedHashMap<String, List<kotlinx.coroutines.flow.Flow<String>>>()
		if (!isEnabled()) return pages
		val ids = _selectedIds.value
		if (ids.isEmpty()) return pages
		ids.chunked(OBD_PAGE_SIZE).forEachIndexed { index, chunk ->
			val title = if (index == 0) {
				L.CARINFO_TITLE_OBD
			} else {
				"${L.CARINFO_TITLE_OBD} ${index + 1}"
			}
			val fields = chunk.map { pidId ->
				displayValues.map { map -> map[pidId] ?: (metaCache[pidId]?.let { "${it.shortName} —" } ?: "—") }
			}
			pages[title] = fields
		}
		return pages
	}

	fun onCarStop() {
		// Keep phone settings binding; only stop aggressive polling if desired.
		// Unbind when car disconnects to free Torque when not needed for settings.
		if (!isEnabled()) {
			stopPolling()
			dataSource?.disconnect()
		}
	}

	fun shutdown() {
		stopPolling()
		dataSource?.disconnect()
		worker?.quitSafely()
		worker = null
		workerHandler = null
		settings?.callback = null
	}
}
