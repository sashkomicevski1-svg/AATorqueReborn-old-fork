# Changelog

## AATorqueReborn 2.6.0

Fork of YuhiAida/aa-torque 2.5.0 (`d740bed`). Same package id (`com.aatorque.stats`), signed
with the AATorqueReborn key, so it replaces any other AA Torque build only after an uninstall.

### Torque connection
- Torque Pro is never asked to quit by a theme change or a closing dashboard. Quitting it is a
  developer option, "Quit Torque when Android Auto ends", off by default.
- When Torque is killed or updated, the app rebinds by itself: after 1 s, then 2, 4, 8, 16 and
  every 30 s. The car screen shows "Torque Pro stopped. Reconnecting…" meanwhile.
- One binding for the whole Android Auto session, held by the car screen. It is always
  released, even when Torque never answered.
- Polling follows the dashboard being visible (STARTED) instead of focused (RESUMED), and its
  worker thread is shut down with the dashboard.

### Values
- Default refresh 150 ms (was 300 ms). Developer options: 100, 150, 250, 300 or 500 ms.
- A PID Torque has never read shows "-". A real 0 that Torque did read is shown as 0.
- Alarms work on 0 (for example DPF regeneration `EQ 0`) as soon as Torque confirms a read.
- A value Torque stopped refreshing is drawn at 40% opacity. The threshold is three of the
  PID's observed update periods, and at least 2 s.
- The gauge centre shows the latest reading: not animated, and not clamped to the dial range.
- The chart plots values relative to the gauge minimum.

### Diagnostics
- Every 5 s the log records ECU state, Torque read speed, configured speed, "full access"
  permission, logged items, and age and period per PID. It also records screen off, screen
  on, unlock and the car screen lifecycle. Use Settings → menu → Copy logs.
- The copied log keeps INFO and above, so per-tick debug lines do not push this out.

### Leaks and threading
- The rotary and key event receiver, the media session listener, and an `observeForever`
  observer are now released with their view.
- Number formatters are per gauge instead of shared mutable statics.
- TypedArrays are recycled. Gauge state restores from the keys it is saved under.

### Build
- Release is signed with the AATorqueReborn key, which is kept outside the repository.
- The update check is off: it pointed at another fork's releases.
- Removed the protected `INSTALL_PACKAGES` and `DELETE_PACKAGES` permissions.
- protoc 3.25.1, matching the protobuf runtime. The submodule uses HTTPS.

### Theme
- New "Mazda Style" theme, made for 800×480 head units. All artwork is original and drawn by
  `tools/make_mazda_theme.py`: no logo, wordmark or brand font. Deep black face, satin bezel,
  thin ticks with a crimson red zone, and a slim crimson needle that keeps clear of the centre
  value.
