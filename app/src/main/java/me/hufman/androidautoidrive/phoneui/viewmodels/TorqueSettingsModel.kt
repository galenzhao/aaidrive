package me.hufman.androidautoidrive.phoneui.viewmodels

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.asLiveData
import androidx.lifecycle.map
import kotlinx.coroutines.flow.combine
import me.hufman.androidautoidrive.AppSettings
import me.hufman.androidautoidrive.BooleanLiveSetting
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.obd.ObdConnectionState
import me.hufman.androidautoidrive.obd.ObdListMode
import me.hufman.androidautoidrive.obd.ObdPidInfo
import me.hufman.androidautoidrive.obd.TorqueObdController

data class TorquePidRow(
	val info: ObdPidInfo,
	val selected: Boolean,
)

class TorqueSettingsModel(private val appContext: Context) : ViewModel() {
	class Factory(val appContext: Context) : ViewModelProvider.Factory {
		@Suppress("UNCHECKED_CAST")
		override fun <T : ViewModel> create(modelClass: Class<T>): T {
			TorqueObdController.init(appContext)
			return TorqueSettingsModel(appContext) as T
		}
	}

	val enabled = BooleanLiveSetting(appContext, AppSettings.KEYS.TORQUE_OBD_ENABLED)

	val connectionState = TorqueObdController.connectionState.asLiveData()
	val availablePids = TorqueObdController.availablePids.asLiveData()
	val selectedIds = TorqueObdController.selectedIds.asLiveData()

	val statusText: LiveData<String> = connectionState.map { state ->
		val base = when (state) {
			ObdConnectionState.DISABLED -> appContext.getString(R.string.lbl_torque_obd_status_disabled)
			ObdConnectionState.DISCONNECTED -> {
				if (!TorqueObdController.torqueInstalled()) {
					appContext.getString(R.string.lbl_torque_obd_status_missing)
				} else {
					appContext.getString(R.string.lbl_torque_obd_status_disconnected)
				}
			}
			ObdConnectionState.BINDING -> appContext.getString(R.string.lbl_torque_obd_status_binding)
			ObdConnectionState.BOUND -> appContext.getString(R.string.lbl_torque_obd_status_bound)
			ObdConnectionState.ECU_CONNECTED -> appContext.getString(R.string.lbl_torque_obd_status_ecu)
			ObdConnectionState.ERROR -> {
				val err = TorqueObdController.errorMessage()
				if (err.isNullOrBlank()) {
					appContext.getString(R.string.lbl_torque_obd_status_error)
				} else {
					appContext.getString(R.string.lbl_torque_obd_status_error) + ": $err"
				}
			}
		}
		base
	}

	val selectedCountText: LiveData<String> = selectedIds.map { ids ->
		appContext.getString(R.string.lbl_torque_obd_selected_count, ids.size)
	}

	val pidRows: LiveData<List<TorquePidRow>> = combine(
		TorqueObdController.availablePids,
		TorqueObdController.selectedIds
	) { available, selected ->
		val selectedSet = selected.toSet()
		// Show selected-but-missing first, then catalog
		val missingSelected = selected.filter { id -> available.none { it.id == id } }
			.map { TorquePidRow(ObdPidInfo(it, it, it, ""), true) }
		val catalog = available.map { TorquePidRow(it, it.id in selectedSet) }
		missingSelected + catalog
	}.asLiveData()

	private val _listMode = MutableLiveData(TorqueObdController.listMode())
	val listMode: LiveData<ObdListMode> = _listMode

	private val _refreshing = MutableLiveData(false)
	val refreshing: LiveData<Boolean> = _refreshing

	fun setListMode(mode: ObdListMode) {
		_listMode.value = mode
		TorqueObdController.setListMode(mode)
	}

	fun refresh() {
		_refreshing.value = true
		TorqueObdController.refreshAvailablePids {
			_refreshing.postValue(false)
		}
	}

	fun setPidSelected(id: String, selected: Boolean) {
		TorqueObdController.toggleSelected(id, selected)
	}
}
