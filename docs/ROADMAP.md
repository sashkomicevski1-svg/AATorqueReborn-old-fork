# AATorqueReborn roadmap

Written 2026-11-14, after the 2.10.0 release and two research passes over similar open-source
projects. "Evidence" points at where an idea was seen or asked for. Effort: S (hours), M (a day
or two), L (a week or more).

Two long-term goals:

1. **Bring the Mazda PIDs to Torque from the app** (one tap, no CSV import).
2. **Work without Torque**: talk to the OBD adapter directly.

## Waiting for the first drive logs

These need real values from the car first: which PIDs answer, and in what units.

| Item | What | Evidence | Effort |
|---|---|---|---|
| DPF % and clogging warning | Soot accumulated (22042C) vs generated (22042D). Warn when accumulated is above 5.7 g/L while generated is below 4.2 and no regen runs. Learn the regen trigger level from each start to show "DPF %". | maciekelga/Mazda-6-GJ-GL-DPF (V3.2), Rilard-T/AA-Mazda-Monitor README | S |
| Regen confirmed by the counter | Completed when counter 220432 rises, or the engine stops within 10 min of the end. Interrupted if it has not risen 3 min after the end. Record a regen from the counter alone if no status PID answers. | Rilard-T RegenTracker | S |
| Regen status PID choice | 220380 ("DPF Regeneration Request" in our pack: 0 none, 1 normal, 2 forced) vs 22f48b. Pick the default from the logs. | Rilard-T Pids, maciekelga | S |
| Outlier filter for slow Mode 22 values | Ignore a jump unless 4 readings agree; hold the last good slow value up to 30 s instead of showing 0. | maciekelga, Rilard-T ObdEngine | S |
| Oil dilution and service tracker | Distance and regens since the last oil change; interrupted regens weigh more. Reminder near P252F territory. No Mazda dilution PID is known yet. | dixtone/GiuliaAndStelvioDPFMonitor | M |
| Clear fault codes and freeze frame | Clear (mode 04) only when parked and confirmed; freeze frame (mode 02). First confirm that reading codes works on the car. | AndrOBD, qo-roj/springtide | S |
| Readiness monitors and VIN | Mode 01 01 and Mode 09 (VIN, calibration). | AndrOBD, Universal-OBD-Dashboard | S |
| Exhaust temperatures | 0178 (three EGT sensors), 017A DPF pressure drop, 0123 rail pressure. | Rilard-T diagnostics log | S |

## Goal 1: Mazda PIDs into Torque

- Push the SKYACTIV-D PID pack into Torque with `sendPIDDataV2` (Torque plugin API). Do it only
  when the PIDs are missing; equal equations keep equal ids, so existing gauges stay bound. Effort S–M.
  Evidence: imuhavi/HKMC-EV-Torque-Plugin, JejuSoul's PID set.
- Pick the car profile from Torque's vehicle profile (`getVehicleProfileInformation`). Effort S.

## Goal 2: no Torque

Plan, after Rilard-T/AA-Mazda-Monitor (GPL-3.0, like this app; reuse is allowed with notices):

1. A foreground service in this app that answers the same AIDL interface as Torque
   (listAllPIDs, getPIDInformation, getPIDValuesAsDouble, getPIDUpdateTime, isConnectedToECU).
   The dashboard keeps working unchanged; only the data source changes. A setting chooses
   "Torque" or "Direct adapter".
2. Transports: Bluetooth Classic (SPP; secure, then insecure, then RFCOMM channel 1), BLE
   (FFF0/FFE0/18F0/Nordic UART, MTU 185), Wi-Fi (192.168.0.10:35000 on a non-internet network).
3. Init: ATZ, ATE0, ATL0, ATS0, ATH0, ATAT2, ATSP6, 0100 to 7E0 (fallback ATSP0), baro 0133.
4. Headers: ATSH only when it changes (7E0 engine, 7E1 gearbox, 720 tyres). Optional "fast
   responses" digit. Join multi-frame replies.
5. Polling: per-PID cache (100 ms fast, 3 s slow); slow DPF PIDs in the background.
6. Reconnect after 5 errors; wait 3 s (5 s with no adapter).
7. PID definitions for the 2.2: built into the app, with equations (our pack plus the table
   below), so no Torque CSV import is needed.

Effort L. Risk: one adapter takes one connection, so Torque and the app cannot both use it.

| Header | PID | Item | Formula (verify on the 2.2) |
|---|---|---|---|
| 7E0 | 220432 | Regen count | A*256+B |
| 7E0 | 220434 | km since last regen | ((B<<16)+(C<<8)+D)*100/65536 (maciekelga divides by 640) |
| 7E0 | 22042C | Soot accumulated g/L | (A*256+B)*100/65536 |
| 7E0 | 22042D | Soot generated g/L | (A*256+B)*100/65536 |
| 7E0 | 220380 | Regen status | A |
| 7E1 | 221E1C | Gearbox oil temp | signed16/16 °C (automatic) |
| 720 | 222A05–08 | Tyre pressure | (A*1373/1000)/100 bar (may not answer on EU cars) |

## Display and settings

| Item | Evidence | Effort |
|---|---|---|
| Formulas that use other PIDs (boost = MAP − baro, HP from torque and rpm) | agronick discussions 140, 28 | M |
| Text or icon instead of a number ("REGEN", gear) | agronick discussions 120, 186 | M |
| Second PID on one gauge | agronick discussion 54 | M |
| Element size sliders (50–150 %), hide the top row | Rilard-T settings | S |
| Shrink long values instead of cutting them | YuhiAida 163, PR 200 | S |
| Shift-light strip | agronick discussion 176 | M |
| A cue when a value changes | YuhiAida 182 | S |
| Colour contrast guard in the colour picker | YuhiAida 178 | S |
| Show which dashboard is live in Settings; placeholder for empty slots; dashboard count control | YuhiAida 170, 176, 191 | S each |
| Per-gauge needle colour | agronick 68 | S |
| Persistent chart history (the chart is memory only) | YuhiAida 193 | M |

## Logging and sharing

| Item | Evidence | Effort |
|---|---|---|
| Fuel log and real consumption between fill-ups | aead1988/pruebita, Universal-OBD-Dashboard | M |
| GPS track (GPX) and a "dynamics" page (g-force, incline) | Universal-OBD-Dashboard, agronick discussions 89, 158 | M |
| "Report a problem": open a pre-filled GitHub issue with the log (MAC and VIN removed) | Rilard-T ErrorReport | S |
| Update check against this fork's GitHub Releases | Rilard-T | S |
| Upload logs (Google Drive, MQTT) | ObdGraphs, AndrOBD | M |

## Not planned, or only with care

- A card next to Maps (Coolwalk) as a media session. It takes the music slot, and YuhiAida's try
  was not merged. Effort M–L.
- Auto-start Torque on connect: Android 14+ background-start limits, and the owner's Samsung
  routine already does it.
- Configurable buttons that send commands, or forced regeneration: these write to the ECU.
- Video or anything usable while driving that Android Auto forbids.

## Process

- Calibrate from the first drive logs (see the first table).
- Foreground service for the locked-phone case only if the logs show our side stalls.
- Merge `reborn` into `master` after the workflows are adapted to this fork (signing, releases).
