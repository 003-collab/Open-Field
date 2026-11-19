# OpenField

A GNSS data collector for land surveying on Android, with a Trimble Access-style workflow (jobs, survey styles, measure
topo, observed control points, continuous topo, stake out, cogo, calibration) and much more customisation: themes,
home-screen layout, status bar fields, terminology, and support for your own receivers.

## Status (read this first)

| Part | State |
|---|---|
| `core` module (NMEA, NTRIP, projections, calibration, stakeout, import/export, profiles) | Built and tested: 51 checks pass, projections verified against PROJ to 1 mm |
| `app` module (Jetpack Compose UI, Bluetooth, JS drivers) | **Written but never compiled or run.** It was authored without an Android SDK or network access, so expect a few compile errors on first build and some on-device rough edges |
| Real receivers, real NTRIP casters | **Not yet tested.** NTRIP is tested against a mock caster, NMEA against sample sentences and a simulator |
| USB serial | Not included (see `docs/DEVICE_PROFILES.md`, section 4) |

Do not rely on this for production survey work until you have checked it against known control on your own equipment.

## Build

1. Open this folder in **Android Studio** (Ladybug or newer, JDK 17+). Let it sync; it downloads Gradle 8.10.2 and the Android SDK 35.
2. Run the **app** configuration on a device or emulator (Android 8.0 / API 26 or newer).
3. Pick the *Simulator (demo)* receiver profile and tap **Connect** to try everything without hardware.

Core checks without Android (needs only a JDK): `./gradlew :core:runChecks` .

Any compile errors will most likely be in `app/.../ui/` (Compose API details). Icons are mapped in one function, `featureIcon()` in `ScreensA.kt`.

## Quick tour

* **Jobs** create a job with a coordinate system (any UTM zone, or your own projection) and units (metres, US survey feet, international feet).
* **Equipment** choose or add a receiver profile (Bluetooth, TCP, simulator; NMEA or JavaScript driver). **Real-time connection** sets up NTRIP.
* **Survey styles** hold the quality limits (solution type, HDOP/PDOP, precision, satellites, correction age) and epochs. Epochs failing the limits are never stored.
* **Measure topo / codes / Observed control / Continuous topo** collect points, reducing antenna height to the ground mark.
* **Stake out** points and lines with arrow guidance, cut/fill, station and offset.
* **Cogo**, **Calibration** (site localisation with residuals), **Review job**, **Import / Export** (CSV with your own column order, GeoJSON, DXF, LandXML).
* **Appearance & layout** themes with editable colours, text and touch size, tile order and favourites, status bar and position fields, left-handed mode, and a terminology editor to rename anything.

## Where things live

Everything you can edit is a plain file in the app's private storage: jobs (`.ofjob`), receiver profiles, survey styles,
coordinate systems, geoid grids, JavaScript drivers, NTRIP connections, feature codes and `config.json` (appearance).
Settings can be exported and imported from **Appearance & layout**.

## Known limits

* Geoid grids must be imported in the simple ASCII format described in the app. Resample an official geoid to your project area first.
* Heights are orthometric only when a geoid is selected; otherwise they are ellipsoidal (a warning appears when a job's geoid grid is missing).
* Datum transformations are 7-parameter Helmert only (no grid-based NADCON/NTv2). For NAD83 with RTK against CORS, WGS 84 and NAD83 are treated as coincident.
* Transverse Mercator and Lambert two-parallel projections are provided; others can be added in `core/.../geo/Projection.kt`.
* NTRIP passwords are stored unencrypted in app-private storage.
* Jobs are rewritten in full after each stored point; very large jobs (tens of thousands of points) will want a database-backed store.
* Scripts run in an interpreter with no time limit; a faulty driver can stall the receiver thread.

## Layout

```
core/   plain Kotlin: gnss/ ntrip/ geo/ survey/ io/ device/ config/   (+ src/test Checks.kt)
app/    Android: MainActivity, AppViewModel, Storage, RhinoRuntime, BluetoothTransport, ui/ screens
docs/   DEVICE_PROFILES.md
```
