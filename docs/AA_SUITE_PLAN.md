# AA Suite plan (v2)

Written 2026-11-14 from two rounds of research (eleven research passes):
- what Android Auto users miss;
- the sideloaded app ecosystem;
- vehicle utilities;
- media, communication and smart home;
- technical feasibility;
- what the AA SDK offers;
- voice and steering-wheel control;
- competition and business;
- data sources by country;
- in-car UX and safety;
- distribution, legal and privacy.

The OBD and DPF items are in [ROADMAP.md](ROADMAP.md).

## 1. Positioning

**The diesel and car-data companion for Android Auto: gauges, DPF and fault codes on the car
screen, with any OBD adapter, and no subscription, ads or account.**

- **The gap is real.**
  - No maintained app draws gauges on Android Auto. Car Scanner and OBD Fusion show text only;
    OBD Fusion's listing says Android Auto "does not support dashboard gauges".
  - OBDeleven and Carista show live data in the car only with their own adapter and a
    subscription (about $60–190 a year).
  - A DPF/regeneration view on the head unit exists only for BMW (one app) and partly in Carista.
    Diesel owners ask for it (for example on VW T6 forums).
  - The Play car app categories (navigation, POI, IoT, weather) cannot draw gauges, so
    sideloading is the opening.
- **Main users:** diesel owners worldwide with an ELM327 or Torque, and tinkerers (Home
  Assistant, XDA).
- **Unique points:** graphical gauges on Android Auto, DPF assistant, any adapter, GPL, one app
  instead of four.
- **De-emphasise**, because Google or free apps already cover it:
  - message read-out;
  - generic info tiles (Android Auto widgets are rolling out);
  - radio, which stays only as the media route for steering-wheel keys (see §5).
- **Torque Pro is stale** (last update Feb 2025). Direct OBD (ROADMAP goal 2) moves up in
  priority.

## 2. Built for any country

Every country-dependent feature takes a country setting, defaulting to the phone's region:
- radio;
- winter-equipment rules;
- fuel prices;
- border waits;
- legal limits such as camera warnings.

North Macedonia is the first test region, not the target market.

## 3. Ground rules (not negotiable)

- No video, games, browsing or mirroring. No removing Android Auto's scroll limits or lockouts.
- No typing in the car. Data entry happens on the phone.
- No speed-camera alerts. They are illegal in North Macedonia, Germany, Switzerland,
  Liechtenstein and Cyprus, and France allows only "danger zones".
- No AI chat screen. No replacing the assistant.
- **When the driving state is unknown, assume the car is moving.** "Parked" means the car
  reports unrestricted **and** the speed is under 1 km/h.
- Show readings, not instructions. DPF advice stays factual: "regeneration interrupted", never
  "you must do X".

## 4. Architecture

- **One APK, at most two Android Auto entries**, the way Fermata does it:
  1. **Gauges**: today's dashboard, no menu, swipe and rotary.
  2. **Car hub** (later): tile grid, see §6.
- **Media module** (`MediaBrowserService`): needed for radio, and **the only route for the
  steering-wheel Next/Prev keys**. The media type passes Android Auto's "Unknown sources" check.
- **Alerts**: notifications, speech, the notice strip, and a toast (`CarToast`) for
  confirmations.
- **No Car App Library.** Android Auto 17.7–18.1 rejects sideloaded template apps ("Package
  DENIED"). The Car App Library (CAL) navigation type would take Maps' slot. No App Actions:
  they need a Play upload, and Gemini ignores them.
- **Shared core:**
  - the data source (Torque, later direct OBD);
  - an alert bus (speech, beeps, strip, audio focus);
  - settings;
  - the diagnostics log;
  - **CarSignals**, a new class: the car's own sensors through the SDK's `CarSensorManager`
    (`Car.createCar`).
- **CarSignals**, from bytecode analysis of the SDK:
  - **Signals:** DRIVING_STATUS (restriction bits), CAR_SPEED, GEAR (PARK=101), PARKING_BRAKE,
    NIGHT, FUEL_LEVEL, ODOMETER, ENVIRONMENT and LOCATION.
  - **Car and head-unit info:** CarInfoManager (make, model, year, head unit), plus
    `hasRotaryController` and `hasTouchscreen`.
  - **Permissions:** speed, fuel and odometer need the `com.google.android.gms.permission.CAR_*`
    permissions, granted on the phone.
  - **Isolation:** the SDK loads its code from the Android Auto app at runtime, so CarSignals runs
    on its own thread with a catch-all handler.
  - **Unknown:** which sensors the Mazda unit actually sends. Mazda says "location, speed and
    other vehicle data". Logging `supportedSensors` in the car comes first.
- **Not used:** CarNavigationStatusManager would take navigation focus from Maps. The SDK's
  SearchController keyboard is blocked while moving.

## 5. Controls without touching the screen

| Input | Action | Works sideloaded |
|---|---|---|
| Rotary: rotate or nudge | Move between tiles or screens (Tab order, no wrap) | Yes, already used |
| Rotary: press / Back | Select / back | Yes |
| Steering Next/Prev (our media session active) | Next or previous station; **double Next = push-to-talk** (short command list); **double Prev = repeat last alert**; long Next = acknowledge (if the Mazda sends hold timing) | Yes, only while our media module is the active source |
| Gemini "play &lt;station&gt;" / pause / next | Media control | Partly: a named-app request is unreliable |
| Notification voice reply | Answer our prompts (yes/no parsed from text) | Partly; Android Auto shows only Reply/Read |
| Tasker / MacroDroid / Home Assistant | Intents out on session start and end, webhooks | Yes, phone side |

**Not possible:**
- "Hey Google, open the app";
- taking over the talk button;
- the car microphone (the phone mic is used instead);
- an always-on wake word;
- Gemini smart-home control while driving (Google blocked it in Sep 2026).

**Risky actions** (garage, gate) always need a second confirmation: a knob press or a "yes"
within 10 s.

## 6. UX spec (Car hub and alerts)

**Standards:**
- NHTSA: mean glance ≤2.0 s, total eyes-off-road ≤12 s, no auto-scrolling text, no reading text
  while moving.
- Google:
  - primary text 32 dp, body ≥24 dp;
  - contrast ≥4.5:1, including the focus highlight;
  - targets ≥76×76 dp with ≥23 dp gaps;
  - no animation while driving;
  - a task takes ≤5 screens.
- Mazda: while moving, the display is knob-only.

**Hub home:**
- 800×480: 3×2 tiles, 232×204 dp, 24 dp margins and gaps. 1280×720: 4×2.
- Each tile: icon 44 dp, label 24 sp, one value at 40 sp, a status line at 24 sp.
- Focus ring: 4 dp #F2F3F5 with a #2A2D33 fill.

**Detail screen:**
- At most 3 fact rows and 3 buttons.
- Depth: hub → detail → confirm.
- Lists: at most 6 rows while moving, with "+N on phone".

**Moving vs parked:**
- History, full lists, clearing codes and browsing are parked-only.
- Locked items stay visible and focusable, and say "Available when parked".

**Alerts:**
- The strip: 56 dp, 24 sp, ≤40 characters.
- Full screen only when all three hold:
  - the driver must act now;
  - the hold time (debounce) has passed;
  - our app is in front.
- Voice: a short beep plus ≤8 words, the same words as the headline. Speak only on start or end;
  the same alert at most once per 10 min.

**Colour fixes:**
- Crimson #E3242B fails 4.5:1 as text (4.25:1). Use #FF4D52 for red text and #B3141B for red
  fills; keep crimson for needles.
- Amber backgrounds take black text.
- No blue text at night.

## 7. Modules by wave

Each wave ships only after the previous one passes a car test.

### Wave 0: field test (now)

- The drive test with logs (see ROADMAP), plus:
  - **CarSignals in log-only mode:** supported sensors, connection type (USB or Wi-Fi), car info;
  - UX fixes: no marquee titles, the notice strip at 24 sp.
- Fault-code Google search from the phone.

### Wave 1: core depth (diesel and diagnostics)

- DPF % and the clogging warning.
- Regen confirmed by the counter.
- Outlier filter.
- Oil dilution and service tracker.
- Clear fault codes when parked, with confirmation; freeze frame; readiness monitors.

All in ROADMAP.

### Wave 2: car companion (offline)

| Module | What |
|---|---|
| Reminders | Service by OBD km (or the SDK ODOMETER), and by date: registration, technical inspection, insurance, vignette expiry entered by hand (no public vignette API, and a plate is personal data). Winter rules from a hand-built per-country table (European Consumer Centres, ADAC/ÖAMTC) |
| Fuel log | Fill-ups and real consumption, entered on the phone |
| Leaving the car | On disconnect: parking spot (SDK LOCATION or phone GPS), phone/child/pet reminder, meter timer |
| Break reminder | Driving time plus time of day |
| Automation | Tasker/MacroDroid broadcasts and Home Assistant webhooks on session start and end |
| Install self-check | Explains install-record problems (KingInstaller-style), shown in Settings |

### Wave 3: Car hub entry and controls

- The hub screen (§6).
- The media module, with radio from Radio Browser: every country, by ISO country code, with a
  User-Agent and click counting as its terms ask.
- The steering-key scheme (§5).
- Home Assistant one-press tiles with confirmation.

### Wave 4: network data (zero-cost sources first)

| Category | First sources | Countries |
|---|---|---|
| Weather and warnings | MET Norway (global, CC BY 4.0, commercial OK); Open-Meteo as backup while ad-free; DWD/Bright Sky (DE); NOAA (US) | Global |
| Fuel prices | Tankerkönig (DE, key, CC BY 4.0, 1 req/min, unbiased display); prix-carburants (FR); MIMIT CSV (IT); Geoportal (ES); E-Control (AT); UK Fuel Finder CSV. Regulated-price cards: MK, RS, SI, HR, ME, BE | About 12 |
| Speed limits | Offline OSM maxspeed (Geofabrik) plus legal default limits per country (motorways ~90–100 % covered; residential streets weak) | Global, quality varies |
| Traffic | No-registration feeds first: NDW (NL), Autobahn API (DE), Digitraffic (FI), Wallonia (BE); then the national access points that need free registration | EU first |
| Border waits | US CBP, Canada CBSA; nakordoni.eu (Balkans; key kept server-side, attribution, 24 h cache, no navigation-type use without an agreement) | Growing |

Avoid TomTom, HERE and Waze: their keys would sit in an open-source APK, and Waze for Cities is
closed to apps.

### Wave 5: the big ones

- Mazda PID pack into Torque.
- Direct OBD without Torque, with **higher priority now that Torque is stale**.

## 8. Before any public release

Personal use today. These are required before sharing the app widely:

1. **Fonts:**
   - 10 of the 11 bundled fonts are proprietary (from upstream): Frutiger, VW, Audi, Skoda,
     SEAT, United Sans, Larabie and others.
   - Remove them, or let users import their own.
   - Keep Barlow (OFL, licence shipped).
2. **Names:**
   - Google allows "Android Auto" in a product name only with approval, so "AA Suite" is risky.
     Pick a neutral name and say "works with Android Auto" in the text.
   - Rename car-brand themes ("Mazda Style" → "Kodo Night", etc.) and keep the old names as
     hidden aliases so stored settings still load.
3. **New package id.** `com.aatorque.stats` is shared with agronick and YuhiAida, and developer
   verification ties a package to one key. Migrate with backup/restore.
4. **Developer verification (global from 2027):**
   - register a full account ($25 + ID; possible from North Macedonia);
   - document the user "advanced flow" (24 h wait).
   - It is unknown whether KingInstaller/Shizuku installs count as exempt ADB installs.
5. **`aauto.aar`:**
   - it is an unofficial SDK with no licence that Google asked its author to take down;
   - stop committing it, fetch it at build time, and document its origin;
   - this blocks F-Droid outright;
   - use GitHub Releases, and possibly IzzyOnDroid (NonFreeComp);
   - skip Play: test tracks used to slip through the Android Auto gate risk an account ban.
6. **GPL notices:**
   - a NOTICE/CREDITS file covering agronick, YuhiAida, jilleb/mqb-pm, martoreto, Rilard-T (if
     its code is reused) and CC BY icons;
   - mark our changes;
   - fix the README badges, which still point to YuhiAida.
7. **Privacy** (the GDPR and North Macedonia's aligned law):
   - a PRIVACY.md;
   - network features opt-in;
   - coarse coordinates (~1 km) to weather services;
   - redact VIN (keep the last 4), MACs, coordinates and Home Assistant tokens in "Copy logs".
8. **Safety:**
   - a first-run disclaimer: not affiliated with Google, Torque or any carmaker; values may be
     wrong; do not interact while moving;
   - the GPL no-warranty notice.
9. **Sustainability options:**
   - donations (GitHub Sponsors, Liberapay);
   - a paid "convenience build" (signed APK with auto-update) while the source stays free, the
     OsmAnd model;
   - an adapter affiliate;
   - a Play build is not realistic for the gauge features.

## 9. Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Google blocks the legacy projection type or our package | Medium | High | Core stays lean; follow Fermata and AABrowser |
| Developer verification blocks GitHub APKs (2027) | High | High | §8.3–8.4 |
| Copyright claim over `aauto.aar` or the fonts | Medium | High | §8.1, §8.5 |
| The car does not send the needed sensors | Medium | Medium | Log first; OBD and phone GPS fallbacks; fail safe |
| An Android Auto update breaks the SDK at runtime | Medium | Medium | CarSignals isolated; app keeps working without it |
| DHU passes, car fails | High | Medium | Every release checked in the car with `dumpsys` and the launcher list |
| A competitor adds gauges, or Carista opens to any adapter | Medium | Medium | DPF depth, any adapter, no subscription |
| Data licences (non-commercial Open-Meteo, Tankerkönig rules) | Low | Medium | MET Norway first; attribution; stay free |
| Driver distraction | Low | High | §3, §6, disclaimers, lockouts |
