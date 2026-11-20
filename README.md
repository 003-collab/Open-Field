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

* **Jobs** New job is a screen: name, description, coordinate system and units.
* **Coordinate systems > Select from library** works like the controller picker: choose a System and Zone (US State Plane on NAD83(2011) with 126 zones, or any UTM zone), then a geoid model, **Grid or Ground** coordinates (ground scale computed from the project height and location, or overridden) and the distance units. Library geoids (GEOID18 etc.) are imported as NGS/PROJ `.gtx` files; only the area around the project is kept. You can still add your own projections as JSON.
* **Map** is a tile of its own, and Measure topo, Continuous topo and Stake out show a map beside their form: drag the divider, or use the header buttons for Map / Split / Form. Pan, pinch, double-tap for extents, tap a point for details.
* **Equipment** choose or add a receiver profile (Bluetooth, TCP, simulator; NMEA or JavaScript driver). **Real-time connection** sets up NTRIP.
* **Survey styles** hold the quality limits (solution type, HDOP/PDOP, precision, satellites, correction age) and epochs. Epochs failing the limits are never stored.
* **Measure topo / codes / Observed control / Continuous topo** collect points, reducing antenna height to the ground mark.
* **Repeat observations:** measuring a name that already exists compares the new position with the stored one (dN, dE, dZ) and asks: **Store another** (both kept, the new one becomes the position), **Average** (the position becomes the mean of the GNSS observations), **Store as check** (kept for comparison, position unchanged) or **Overwrite** (earlier observations stay as deleted records). Review shows every observation of a point, and the .jxl export writes each one as its own PointRecord (Check and Averaged are marked). Text exports (CSV, DXF, GeoJSON, LandXML) and the map use one position per name.
* **Vectors:** every RTK observation stores the base-to-rover vector (base ID, base ECEF, dX/dY/dZ, covariance) with the point, averaged when you average. The base position is read from RTCM 1005/1006 in the NTRIP stream, or reported by a JavaScript driver (see `docs/DEVICE_PROFILES.md`). Export them from Import / Export as *Vectors (CSV)*, ready for a least-squares adjustment, which is not built yet. In the .jxl each vector is written inside its PointRecord (`ECEFDeltas`, `RTK_Base`, `QualityControl2` covariance); this follows element names seen in Trimble files and has not been checked in Trimble software.
* **Stake out** points and lines with arrow guidance, cut/fill, station and offset.
* **Cogo**, **Calibration** (site localisation with residuals), **Review job**, **Import / Export** (CSV with your own column order, GeoJSON, DXF, LandXML).
* **Appearance & layout** themes with editable colours, text and touch size, tile order and favourites, status bar and position fields, left-handed mode, and a terminology editor to rename anything.

## Where things live

Everything you can edit is a plain file in the app's private storage: jobs (`.ofjob`), receiver profiles, survey styles,
coordinate systems, geoid grids, JavaScript drivers, NTRIP connections, feature codes and `config.json` (appearance).
Settings can be exported and imported from **Appearance & layout**.

## Known limits

* Geoid grids are imported as NGS/PROJ `.gtx` (from the library picker) or the simple ASCII format. Trimble `.ggf` files are not read.
* The library covers US State Plane (statewide and regional zones on TM or Lambert 2SP) and UTM. Zones that use oblique Mercator or Albers (Alaska 1, Michigan Oblique, county systems) are not included yet.
* No displacement (plate-motion) model such as HTDP is applied; WGS 84 and NAD83(2011) are treated as the same frame.
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
