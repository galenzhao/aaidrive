package me.hufman.androidautoidrive.phoneui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import me.hufman.androidautoidrive.R
import me.hufman.androidautoidrive.databinding.TorqueSettingsBinding
import me.hufman.androidautoidrive.obd.ObdListMode
import me.hufman.androidautoidrive.obd.TorqueObdController
import me.hufman.androidautoidrive.phoneui.viewmodels.TorquePidRow
import me.hufman.androidautoidrive.phoneui.viewmodels.TorqueSettingsModel
import me.hufman.androidautoidrive.phoneui.viewmodels.viewModels

class TorqueSettingsFragment : Fragment() {
	val viewModel by viewModels<TorqueSettingsModel> { TorqueSettingsModel.Factory(requireContext().applicationContext) }

	private var binding: TorqueSettingsBinding? = null
	private val adapter = TorquePidAdapter { id, selected ->
		viewModel.setPidSelected(id, selected)
	}

	override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
		val binding = TorqueSettingsBinding.inflate(inflater, container, false)
		this.binding = binding
		binding.lifecycleOwner = viewLifecycleOwner
		binding.viewModel = viewModel

		binding.torquePidList.layoutManager = LinearLayoutManager(requireContext())
		binding.torquePidList.adapter = adapter
		binding.torquePidList.isNestedScrollingEnabled = false

		binding.btnTorqueRefresh.setOnClickListener { viewModel.refresh() }

		binding.torqueListMode.setOnCheckedChangeListener { _, checkedId ->
			val mode = when (checkedId) {
				R.id.torqueModeEcu -> ObdListMode.ECU_SUPPORTED
				R.id.torqueModeAll -> ObdListMode.ALL
				else -> ObdListMode.ACTIVE
			}
			viewModel.setListMode(mode)
		}

		viewModel.listMode.observe(viewLifecycleOwner) { mode ->
			val id = when (mode) {
				ObdListMode.ECU_SUPPORTED -> R.id.torqueModeEcu
				ObdListMode.ALL -> R.id.torqueModeAll
				ObdListMode.ACTIVE -> R.id.torqueModeActive
			}
			if (binding.torqueListMode.checkedRadioButtonId != id) {
				binding.torqueListMode.check(id)
			}
		}

		viewModel.pidRows.observe(viewLifecycleOwner) { rows ->
			adapter.submit(rows)
			binding.torqueEmpty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
		}

		viewModel.refreshing.observe(viewLifecycleOwner) { refreshing ->
			binding.btnTorqueRefresh.isEnabled = refreshing != true
		}

		return binding.root
	}

	override fun onResume() {
		super.onResume()
		TorqueObdController.init(requireContext().applicationContext)
		// Soft refresh status / catalog when returning to settings
		if (viewModel.enabled.value == true || TorqueObdController.availablePids.value.isEmpty()) {
			viewModel.refresh()
		}
	}

	override fun onDestroyView() {
		super.onDestroyView()
		binding = null
	}

	private class TorquePidAdapter(
		private val onToggle: (String, Boolean) -> Unit,
	) : RecyclerView.Adapter<TorquePidAdapter.Holder>() {
		private var items: List<TorquePidRow> = emptyList()

		fun submit(rows: List<TorquePidRow>) {
			items = rows
			notifyDataSetChanged()
		}

		override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
			val view = LayoutInflater.from(parent.context).inflate(R.layout.item_torque_pid, parent, false)
			return Holder(view)
		}

		override fun getItemCount(): Int = items.size

		override fun onBindViewHolder(holder: Holder, position: Int) {
			holder.bind(items[position], onToggle)
		}

		class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
			private val checkbox: CheckBox = itemView.findViewById(R.id.pidCheckbox)

			fun bind(row: TorquePidRow, onToggle: (String, Boolean) -> Unit) {
				val info = row.info
				val unit = if (info.unit.isBlank()) "" else " (${info.unit})"
				val label = when {
					info.shortName.isNotBlank() && info.longName.isNotBlank() && info.shortName != info.longName ->
						"${info.shortName}: ${info.longName}$unit"
					info.longName.isNotBlank() -> "${info.longName}$unit"
					else -> info.id
				}
				checkbox.setOnCheckedChangeListener(null)
				checkbox.text = label
				checkbox.isChecked = row.selected
				checkbox.setOnCheckedChangeListener { _, isChecked ->
					onToggle(info.id, isChecked)
				}
			}
		}
	}
}
