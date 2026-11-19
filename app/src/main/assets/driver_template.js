// OpenField receiver driver (JavaScript)
//
// Use this when your receiver does not speak plain NMEA 0183, or needs special start-up commands.
//   io.write(x)  sends x to the receiver. x is a string or an array of byte values (0-255).
//   io.log(text) prints a line in the GNSS status console (prefixed with #).
//
// onOpen()        is called once each time the connection opens (optional).
// onData(bytes)   is called with each chunk received: an array of integers 0-255.
//                 Return an array of events:
//                   { type: "nmea", sentence: "$GNGGA,...*hh" }   -> handled by the standard NMEA decoder
//                   { type: "fix", lat: 40.1, lon: -75.2, altMsl: 100.2, geoidSep: -33.0,
//                     solution: "RTK_FIXED",      // RTK_FIXED | RTK_FLOAT | DGPS | AUTONOMOUS | NONE
//                     sats: 18, hdop: 0.7, pdop: 1.2, vdop: 1.0,
//                     sigmaLat: 0.008, sigmaLon: 0.009, sigmaAlt: 0.015,   // metres, optional
//                     diffAge: 1.0, station: "0001" }                    -> a position you decoded yourself
//                   { type: "line", text: "anything" }                 -> shown in the console only
//
// This example frames text lines and forwards NMEA sentences. Replace it with your own protocol decoder.

var buffer = "";

function onOpen() {
  buffer = "";
  io.log("driver started");
  // Example init command (a string, or [0xB5, 0x62, ...] for binary protocols):
  // io.write("LOG COM1 GPGGA ONTIME 1\r\n");
}

function onData(bytes) {
  var events = [];
  for (var i = 0; i < bytes.length; i++) {
    var c = bytes[i];
    if (c === 10) {                       // end of line
      var line = buffer.replace(/\r$/, "");
      buffer = "";
      if (line.charAt(0) === "$") events.push({ type: "nmea", sentence: line });
    } else if (buffer.length < 300) {
      buffer += String.fromCharCode(c);
    }
  }
  return events;
}
