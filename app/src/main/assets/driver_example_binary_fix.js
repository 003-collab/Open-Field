// Example: a made-up binary protocol emitting a fixed-size position record, to show the "fix" event.
// Integers are decoded with plain arithmetic so nothing beyond core JavaScript is needed.
//
//   byte 0      0xAA  sync
//   byte 1      0x55  sync
//   bytes 2-5   latitude   int32 little-endian, units of 1e-7 degrees
//   bytes 6-9   longitude  int32 little-endian, units of 1e-7 degrees
//   bytes 10-13 height     int32 little-endian, millimetres above the ellipsoid
//   byte 14     status     0 none, 1 single, 2 float, 3 fixed
//   byte 15     satellites used
//   byte 16     checksum   sum of bytes 0..15, low byte

var pending = [];
var FRAME = 17;

function int32le(a, i) {
  var v = a[i] + a[i + 1] * 256 + a[i + 2] * 65536 + a[i + 3] * 16777216;
  return v >= 2147483648 ? v - 4294967296 : v;
}

function onData(bytes) {
  var events = [];
  for (var i = 0; i < bytes.length; i++) pending.push(bytes[i]);
  while (pending.length >= FRAME) {
    if (pending[0] !== 0xAA || pending[1] !== 0x55) { pending.shift(); continue; }   // resynchronise
    var frame = pending.slice(0, FRAME);
    pending = pending.slice(FRAME);
    var sum = 0;
    for (var k = 0; k < FRAME - 1; k++) sum = (sum + frame[k]) & 0xFF;
    if (sum !== frame[FRAME - 1]) { events.push({ type: "line", text: "bad checksum" }); continue; }
    var names = ["NONE", "AUTONOMOUS", "RTK_FLOAT", "RTK_FIXED"];
    events.push({
      type: "fix",
      lat: int32le(frame, 2) * 1e-7,
      lon: int32le(frame, 6) * 1e-7,
      altMsl: int32le(frame, 10) / 1000.0,   // pass the ellipsoidal height here ...
      geoidSep: 0,                            // ... and leave the geoid separation at 0
      solution: names[frame[14]] || "NONE",
      sats: frame[15]
    });
  }
  return events;
}
