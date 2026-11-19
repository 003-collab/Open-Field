# Adding your own equipment

OpenField talks to receivers through a **receiver profile**. A profile is a small JSON file; you can create and edit
them in the app (**Equipment → New profile**) with no coding. Receivers with proprietary or binary protocols can use a
short JavaScript driver. Developers can also add compiled drivers in Kotlin.

## 1. A profile for any NMEA receiver

```json
{
  "id": "acme-rtk-1",
  "name": "ACME RTK-1",
  "manufacturer": "ACME",
  "connection": { "type": "BLUETOOTH_SPP", "address": "ACME", "port": 0, "baudRate": 115200 },
  "protocol": {
    "type": "nmea0183",
    "scriptFile": "",
    "initCommands": ["$PUBX,40,GST,0,1,0,0,0,0"],
    "requireChecksum": true,
    "qualityMap": { "5": "RTK_FIXED" },
    "acceptsRtcm": true
  },
  "antenna": { "model": "ACME-A1", "arpToBottomOfMount": 0.036, "arpToNotch": 0.061, "notchRadius": 0.099 },
  "notes": ""
}
```

| Field | Meaning |
|---|---|
| `id` | Letters, digits, `.`, `_`, `-`. A profile with a built-in id replaces the built-in one (Reset restores it). |
| `connection.type` | `BLUETOOTH_SPP`, `TCP`, or `SIMULATOR`. (`USB_SERIAL` is reserved; see section 4.) |
| `connection.address` | Bluetooth: part of the paired device's name, or its MAC address. TCP: host name or IP. |
| `connection.port` | TCP port. |
| `protocol.type` | `nmea0183` or `script`. |
| `protocol.initCommands` | Sent on connect. Lines starting with `$` and without `*` get the checksum and CRLF added. |
| `protocol.requireChecksum` | Set to `false` for simulators or receivers that omit NMEA checksums. |
| `protocol.qualityMap` | Remap GGA quality codes for non-standard receivers. Values: `RTK_FIXED`, `RTK_FLOAT`, `DGPS`, `AUTONOMOUS`, `NONE`. |
| `protocol.acceptsRtcm` | When `true`, corrections from the NTRIP client are written to this receiver's link. |
| `antenna.arpToBottomOfMount` | Vertical distance (m) from the antenna reference point down to the bottom of the mount. |
| `antenna.arpToNotch` | Vertical distance (m) from the ARP down to the measurement notch. |
| `antenna.notchRadius` | Horizontal distance (m) from the antenna axis to the notch, used to reduce slant heights. |

Useful NMEA output: **GGA** (position and quality) is required. **GST** gives real-time precision used by the quality
limits, **GSA** gives DOP, **GSV** feeds the sky plot, **RMC/VTG** give speed and course.

Pair the receiver in Android's Bluetooth settings first. OpenField then matches it by the name or MAC in `address`.

## 2. JavaScript drivers (no compiling)

Set `"protocol": { "type": "script", "scriptFile": "acme.js" }` and open **Edit driver script** in the profile editor.
Two examples ship in the editor's **Load:** buttons.

```js
function onOpen() {                    // optional, runs on every connect
  io.write([0xB5, 0x62, 0x06, 0x01]);  // bytes (array of 0-255) or a string
  io.log("hello");                     // appears in the NMEA console prefixed with #
}

function onData(bytes) {               // bytes: array of ints 0-255
  var events = [];
  // ... decode ...
  events.push({ type: "nmea", sentence: "$GNGGA,...*hh" });       // use the standard NMEA decoder
  events.push({ type: "fix", lat: 40.1, lon: -75.2,               // or give a decoded position directly
               altMsl: 100.2, geoidSep: -33.0, solution: "RTK_FIXED",
               sats: 18, hdop: 0.7, sigmaLat: 0.008, sigmaLon: 0.009, sigmaAlt: 0.015 });
  events.push({ type: "line", text: "status text" });             // console only
  return events;
}
```

* `altMsl + geoidSep` is the ellipsoidal height OpenField uses, so give both as the receiver reports them. If your
  receiver reports an ellipsoidal height, pass it as `altMsl` and set `geoidSep` to 0.
* The script runs in the Rhino interpreter (ES5 plus some ES6). The binary example decodes integers with plain arithmetic, which works everywhere.
* Scripts are stored on the device in the app's `drivers` folder. Reconnect after saving.

## 3. Compiled drivers (Kotlin)

Implement `com.openfield.core.gnss.ReceiverDriver` (see `core/.../gnss/Receiver.kt`):

```kotlin
class MyDriver : ReceiverDriver {
    override val id = "my-driver"
    override fun onOpen(io: DriverIO) { io.write(byteArrayOf(...)) }
    override fun onData(buf: ByteArray, len: Int, sink: DriverSink) {
        sink.snapshot(GnssSnapshot(/* decoded epoch */))
    }
}
```

and return it from `DeviceProfile.createDriver` for a new `protocol` value. Because `core` is plain Kotlin with no Android
dependencies, drivers can be unit-tested on a laptop by feeding captured bytes to `onData`.

## 4. Other links (USB serial, BLE, Wi-Fi bridges)

A link is a `Transport` (`open()`, `input`, `output`, `close()`). `TcpTransport` and `BluetoothTransport` are the two shipped.
To add USB serial, implement `Transport` over a library such as usb-serial-for-android and add a branch for
`ConnectionType.USB_SERIAL` in `AppViewModel.connect()`.
