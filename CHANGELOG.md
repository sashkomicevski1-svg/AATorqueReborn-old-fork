# Changelog

## AATorqueReborn 2.8.0 — car assistant

Settings → **Car assistant** groups the new features. They run only on the car screen, from
the moment Android Auto opens it to the moment it closes (one "session").

- **DPF assistant.**
  - It finds the DPF PIDs by their Torque names (SKYACTIV-D PID pack: regeneration status,
    soot load, trigger level and %, count, distance since the last one, total distance). Any of
    them can be picked by hand.
  - During a regeneration, a strip at the bottom of the car screen shows
    "DPF regeneration · 6:12 · 4.3 km · 63%".
  - It says when a regeneration starts, finishes, or is paused by the engine.
  - It can show a chosen dashboard screen during a regeneration and go back afterwards.
  - If the engine is switched off during a regeneration, the next start warns that it was
    interrupted.
  - Drive log keeps the history: duration, distance, interval, soot before and after, and
    outcome, plus the average distance between regenerations (last 5 against earlier).
  - Its PIDs are read every 2 s, not every tick, so gauges keep their speed.
- **Spoken alarms and screen switching.** An alarm rule can say a text of its own when it starts
  or ends (instead of the beep), and can switch to its gauge's screen while it lasts.
- **Fault codes.**
  - Stored and pending engine codes are read 20 s after connecting, then every 5 minutes.
    The car screen shows them with a short description, and new ones are spoken.
  - Read only: codes are never cleared.
  - Needs "Allow plugins full access" in Torque.
- **Trip summary.** When Android Auto ends, a phone notification shows distance, time, average
  and top speed, top coolant temperature, DPF regenerations and fault codes. Trips are kept in
  Drive log.
- The car dashboard keeps reading while another Android Auto app (Maps) is in front, so
  alarms with sounds, the DPF assistant and the trip go on. The phone preview in Settings never
  sounds or speaks.
- **PID picker:** custom Mode 22 PIDs are always offered. Before, they were hidden while an
  adapter was connected.
- Backups stay compatible: the new alarm fields read as empty in older backups.

## AATorqueReborn 2.7.0

- Alarm sounds. An alarm rule can now "Beep when it starts" and/or "Beep when it ends" (in the
  rule's dialog under Alarms). Start: three short beeps. End: a falling two-tone.
  - Made for DPF regeneration: rule `= 1` with both boxes ticked beeps when a regeneration
    starts and again when it ends, so you know when it is safe to switch the engine off.
  - A start beep also plays if the condition is already true at start-up; an end beep never
    plays at start-up.
  - The same sound for the same rule plays at most once per 10 s.
  - PIDs with a sound alarm are read on every screen, not only on the one they are on.
  - The sound uses navigation-prompt audio, so Android Auto should play it on the car speakers
    and duck the music. Developer options → "Test alarm sound" checks that in the car.
- Backups stay compatible: the two new fields read as off in older backups.

## AATorqueReborn 2.6.1

- "Mazda Style" car background (800×400, original, drawn by `tools/make_mazda_theme.py`).
  Pick it under Settings → Default background (the app does not switch backgrounds with the
  theme).
- New font "Barlow Semi Condensed" (SIL Open Font License 1.1, from github.com/jpt/barlow).
  The licence ships in the APK at `app/src/main/assets/licenses/Barlow-OFL.txt`.

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
- The needle glides: each reading is animated linearly over about one of the PID's own update
  periods (measured from Torque, 100-800 ms), so it keeps moving between readings instead of
  jumping and stopping under hard acceleration. It trails the reading by about one period.
- The gauge centre shows the latest reading: not animated, and not clamped to the dial range.
  It shows "-" until Torque has read the PID.
- After a Torque restart, a PID it has not read again is shown greyed out, not as a fake 0.
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
