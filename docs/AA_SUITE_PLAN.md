# AA Suite plan

Written 2026-11-14 from five research passes:
- what Android Auto users miss;
- the sideloaded app ecosystem;
- vehicle utilities;
- media, communication and smart home;
- technical feasibility.

It builds on [ROADMAP.md](ROADMAP.md), which keeps the OBD and DPF items.

## Why this app can be a suite

- **The gap is ours.** No maintained app shows live vehicle data on Android Auto while driving.
  aa-torque upstream, obd2aa and mqb-pm are dead or stalled, and OBD Fusion says Android Auto
  "does not support dashboard gauges". Gauges, DPF, fault codes and trips are the core. Everything
  else is built around them.
- **Users ask for what we already do well.** "More layout customisation" won Android Authority's
  2026 wishlist poll (29 %). Car data, speed without a route, and glanceable info are on every
  wishlist.
- **The foundation is there.** We already have:
  - a car screen;
  - one car session;
  - spoken alerts on the navigation audio channel;
  - a notice strip;
  - phone notifications;
  - profiles and settings.

## Built for any country

The app is not tied to one region. Anything that depends on where the driver is takes a country
setting, which defaults to the phone's region:
- radio stations;
- winter-equipment season;
- fuel price sources;
- border crossings;
- legal limits such as camera warnings.

North Macedonia and the Balkans are examples and the first test region, not the target market.
Every data source below is listed with the countries it covers.

## Ground rules (not negotiable)

- Nothing that shows video, games, web pages or mirroring while driving. Nothing that removes
  Android Auto's scroll limits or speed lockouts.
- Typing and data entry happen on the phone when parked, never on the car screen while moving.
- No speed-camera alerts. They are illegal in North Macedonia, Germany, Switzerland, Liechtenstein
  and Cyprus, and only "danger zones" are allowed in France.
- No open AI chat on the car screen. No replacing the system assistant.
- When the driving state is unknown, assume the car is moving.

## Architecture

- **One APK, at most two launcher entries** (legacy projection services, like Fermata Auto does):
  1. **Gauges** (today's dashboard, no menu: swipe and rotary);
  2. later, **Car hub**: big rotary tiles for DPF, codes, trip, reminders, home buttons and info
     tiles.

  Each entry is a separate icon the user enables in Customize launcher.
- **Media modules** (radio, later podcasts) as a `MediaBrowserService`. The media type passes
  Android Auto's "Unknown sources" for sideloaded apps.
- **Alerts and reminders** as notifications and spoken messages.
- **Not the Car App Library.** Android Auto 17.7–18.1 rejects sideloaded template apps
  ("Package DENIED"), and a CAL navigation app would take the navigation slot from Maps or Waze.
  The legacy projection type still passes with our install record.
- **Shared core:**
  - one data source (Torque now, direct OBD later; see ROADMAP goal 2);
  - one alert bus (speech, beeps, notice strip, audio focus);
  - one settings store and one diagnostics log;
  - a driving-state service.

  The driving state comes from the car through the SDK's `CarSensorManager` (DRIVING_STATUS,
  parking brake, gear), as AABrowser does. Whether the Mazda reports it through the dongle must
  be logged first.
- **800x480 rotary UX:**
  - at most about 6 targets per screen, with a fixed focus order and a visible focus ring;
  - text 24 dp or larger;
  - long press only as a shortcut, never required.

  Test in the DHU with the Mazda config, then in the car. The DHU skips the install checks, so it
  cannot prove that a build will show up in the car.

## Modules by wave

Effort: S (hours), M (a day or two), L (a week or more). Each wave ships only after the previous
one has passed a car test.

### Wave 0: field test (now)

- Calibrate from the first drive logs: the DPF status PID, units, getPIDUpdateTime, the
  fault-code reply format, locked-phone behaviour and the audio route.
- Search a fault code on Google: from Drive log, and from a phone notification when a new code
  appears. Effort S.

### Wave 1: car companion (offline, all S)

| Module | What | Notes |
|---|---|---|
| Reminders | Oil and service by OBD km; registration, technical inspection, insurance and vignette by date; winter equipment season from a per-country table (for example North Macedonia 15 Nov–15 Mar; the dates differ by country), editable | Spoken once at start-up; entry on the phone |
| Fuel log | Fill-ups with real consumption and cost per km, pre-filled from OBD distance | Entry on the phone when parked |
| Leaving the car | On disconnect: save the parking spot, remind about phone, child or pet, optional parking-meter timer | Maps on Android Auto saves parking only manually or while navigating |
| Break reminder | Driving time plus time of day, a spoken suggestion | Complements Mazda's attention alert |
| Automation triggers | Car session start and end fire Tasker/MacroDroid broadcasts and a Home Assistant webhook | More reliable than Bluetooth or UI-mode detection |
| "While you drove" digest | Notifications that arrived during the drive, read out or listed at the end | Phone side |

### Wave 2: glance and voice

| Module | What | Data | Effort |
|---|---|---|---|
| Info tiles (Car hub) | Phone battery and temperature, next calendar event, weather, Home Assistant sensors | On-device; Home Assistant REST API | M |
| Weather card | Now plus a short forecast, spoken on connect | Open-Meteo: free for non-commercial use only, CC BY 4.0, 10k calls/day | S |
| Message read-out | Viber and others: Viber left Android Auto in Oct 2023 and has not come back | NotificationListener, then TTS on the navigation channel | M |
| Home buttons | Garage, gate, heating: one big button plus a spoken confirmation; "open garage?" when arriving | Home Assistant REST/webhooks | S–M |
| News briefing | Headlines from chosen RSS feeds, read out | Publisher RSS (headlines only) | S |
| Voice note | One tap, record, saved as a phone note or reminder | Phone speech-to-text | S–M |

### Wave 3: media

| Module | What | Data | Effort |
|---|---|---|---|
| Internet radio | Stations by country (any country, chosen in settings; the phone's region by default), plus genre/tag, favourites, search on the phone, easy switching while driving | Radio Browser API (free, no key, worldwide, filters by country code) | S–M |
| Podcasts / audiobooks | Speed control, resume position | Podcast Index API (key) | M–L, crowded field |

### Wave 4: network data

| Module | What | Data | Effort |
|---|---|---|---|
| Fuel prices | Per country, from whatever source exists: station prices where open data exists, a regulated national price where prices are set (for example North Macedonia), nothing where there is no source | Tankerkönig (DE, key and attribution), E-Control (AT), open data for FR/IT/HR, national regulators; add countries one by one | M |
| Border waits | Where a data source exists, spoken | nakordoni.eu API for Balkan crossings (free tier with attribution); more regions as sources appear | M |
| Route weather and road conditions | Alerts along the route | Open-Meteo; DATEX II national access points | M |

### Wave 5: the big ones (see ROADMAP)

- Push the Mazda PID pack into Torque.
- Direct OBD without Torque: an in-app service answering Torque's AIDL interface.
- Offline speed limits from OpenStreetMap: optional, and coverage is thin.

## Not doing

- Video, browser or mirroring.
- Speed-camera alerts.
- Dashcam (background camera limits on Android 11+, unclear laws, low value).
- An AI chat screen.
- Scroll-limit or lockout removal.
- Sending voice clips into other messaging apps.
- EV charging (not relevant to this car).

## Risks and what we do about them

| Risk | Mitigation |
|---|---|
| Google blocks the legacy projection type, or puts our package on its server block list | Keep the core gauges free of risky features; consider a separate package for experiments; follow Fermata and AABrowser releases |
| Android developer verification: unregistered apps blocked on certified phones worldwide from 2027 | Register as a developer before then, even for sideloading |
| An Android Auto update resets developer settings or the install record | Install only with KingInstaller/UnknownAuto; ship one universal APK (split APKs never pass); add an install self-check screen |
| DHU passes, car fails | Verify every release in the car with `dumpsys` and Customize launcher |
| Data licences (Open-Meteo is non-commercial; Tankerkönig requires attribution) | Show attribution; stay non-commercial or pay |
| The car does not report the driving state | Fail safe: treat the car as moving and lock parked-only actions |
| Too many features for the car screen | No menus in Gauges; the hub only has tiles; settings stay on the phone |
