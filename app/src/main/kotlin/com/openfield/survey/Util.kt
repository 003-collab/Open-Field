package com.openfield.survey

import com.openfield.core.geo.LinearUnit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Locale-independent number formatting: survey data must never switch to decimal commas by accident. */
fun fmtNum(v: Double, decimals: Int): String = String.format(Locale.ROOT, "%.${decimals}f", v)

fun fmtNumOrDash(v: Double?, decimals: Int): String = if (v == null) "-" else fmtNum(v, decimals)

fun LinearUnit.abbr(): String = when (this) {
    LinearUnit.METER -> "m"
    LinearUnit.US_SURVEY_FOOT -> "ftUS"
    LinearUnit.INTL_FOOT -> "ft"
}

fun fmtUtc(seconds: Double?): String {
    if (seconds == null) return "-"
    val s = ((seconds % 86400) + 86400) % 86400
    return String.format(Locale.ROOT, "%02d:%02d:%04.1f", (s / 3600).toInt(), ((s % 3600) / 60).toInt(), s % 60)
}

fun fmtDateTime(millis: Long): String {
    if (millis <= 0) return "-"
    val f = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
    f.timeZone = TimeZone.getDefault()
    return f.format(Date(millis))
}

/** Parses a decimal typed by the user, accepting either '.' or ',' as the separator. */
fun parseNum(s: String): Double? = s.trim().replace(',', '.').toDoubleOrNull()
