# Torque OBD (BMW F23 B48 ZF8)

Community Mode 22 PIDs converted for **Torque Pro** extended PID import.

- Car: BMW F23 (approx 2019), engine **B48**, transmission **ZF 8HP / 8AT**
- Source DIDs: [Shooooooooo/bmw_pid_data](https://github.com/Shooooooooo/bmw_pid_data) `b48_pid_data.csv`
- Verify every sensor on your car before trusting values

## Import into Torque Pro

1. Copy `bmw_f23_b48_zf8_torque.csv` to the phone: `/.torque/extendedpids/`
2. Torque Pro → Settings → Manage extra PIDs → Add predefined set
3. Add **[T1]** sensors to the Torque dashboard first (keeps the bus light)
4. In AAIdrive → Settings → Torque OBD: enable, Refresh, select PIDs for car screens

## Notes

- Header defaults to `7DF`. If NO DATA, try blank header or BMW DME init.
- ATF: prefer `[T1] Trans temp via DME` (`224650`). EGS `22DA12` / header `618` often blocked on cheap ELM adapters.
- Gauge oil pressure ≈ absolute oil pressure − ambient (`AmbP`). Gauge boost ≈ `Boost` − `AmbP`.
