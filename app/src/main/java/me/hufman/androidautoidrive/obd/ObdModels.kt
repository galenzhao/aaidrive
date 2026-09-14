package me.hufman.androidautoidrive.obd

/**
 * Metadata for a single OBD sensor exposed by an [ObdDataSource].
 * [id] is the opaque string used for polling (Torque PID id, etc).
 */
data class ObdPidInfo(
	val id: String,
	val longName: String,
	val shortName: String,
	val unit: String,
	val max: Float = 0f,
	val min: Float = 0f,
	val scale: Float = 1f,
) {
	fun formatValue(value: Double?): String {
		val label = shortName.ifBlank { longName }.ifBlank { id }
		if (value == null || value.isNaN() || value.isInfinite()) {
			return "$label —"
		}
		val formatted = when {
			value >= 100 || value <= -100 -> "%.0f".format(value)
			value >= 10 || value <= -10 -> "%.1f".format(value)
			else -> "%.2f".format(value)
		}
		return if (unit.isBlank()) "$label $formatted" else "$label $formatted $unit"
	}
}

enum class ObdListMode {
	ACTIVE,
	ECU_SUPPORTED,
	ALL;

	companion object {
		fun fromSetting(value: String): ObdListMode =
			entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ACTIVE
	}
}

enum class ObdConnectionState {
	DISABLED,
	DISCONNECTED,
	BINDING,
	BOUND,
	ECU_CONNECTED,
	ERROR,
}

interface ObdDataSource {
	val connectionState: ObdConnectionState

	/** Bind / start the underlying provider. Idempotent. */
	fun connect()

	/** Unbind / stop. */
	fun disconnect()

	fun isEcuConnected(): Boolean

	fun listPids(mode: ObdListMode): List<String>

	fun getPidInfo(ids: List<String>): List<ObdPidInfo>

	fun pollValues(ids: List<String>): Map<String, Double>
}
