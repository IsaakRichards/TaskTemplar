package dev.isaakrichards.tasktemplar.engine

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Pure location maths: parsing, distances and sunrise / sunset (NOAA approximation). */
object GeoMath {

    /** Parses "lat,lng". */
    fun parse(s: String): Pair<Double, Double>? {
        val parts = s.split(',').map { it.trim() }
        if (parts.size != 2) return null
        val lat = parts[0].toDoubleOrNull() ?: return null
        val lng = parts[1].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    fun format(lat: Double, lng: Double) = "%.6f,%.6f".format(java.util.Locale.US, lat, lng)

    /** Great-circle distance in metres. */
    fun distance(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.first - a.first)
        val dLng = Math.toRadians(b.second - a.second)
        val h = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLng / 2).let { it * it }
        return 2 * r * asin(sqrt(h))
    }

    /** Sunrise and sunset for [date] at a place, in [zone]. Null during polar day / night. */
    fun sunTimes(date: LocalDate, lat: Double, lng: Double, zone: ZoneId): Pair<ZonedDateTime, ZonedDateTime>? {
        val g = 2 * PI / 365 * (date.dayOfYear - 1)
        val eqTime = 229.18 * (0.000075 + 0.001868 * cos(g) - 0.032077 * sin(g) - 0.014615 * cos(2 * g) - 0.040849 * sin(2 * g))
        val decl = 0.006918 - 0.399912 * cos(g) + 0.070257 * sin(g) - 0.006758 * cos(2 * g) +
            0.000907 * sin(2 * g) - 0.002697 * cos(3 * g) + 0.00148 * sin(3 * g)
        val latR = Math.toRadians(lat)
        val cosHa = cos(Math.toRadians(90.833)) / (cos(latR) * cos(decl)) - tan(latR) * tan(decl)
        if (cosHa < -1.0 || cosHa > 1.0) return null
        val ha = Math.toDegrees(acos(cosHa))
        val base = date.atStartOfDay(ZoneOffset.UTC)
        fun at(minutes: Double) = base.plusSeconds((minutes * 60).toLong()).withZoneSameInstant(zone)
        return at(720 - 4 * (lng + ha) - eqTime) to at(720 - 4 * (lng - ha) - eqTime)
    }

    /** Next sunrise/sunset (+ offset minutes) strictly after [now]. */
    fun nextSun(event: String, offsetMin: Long, lat: Double, lng: Double, now: ZonedDateTime): ZonedDateTime? {
        for (d in -1..3) {
            val times = sunTimes(now.toLocalDate().plusDays(d.toLong()), lat, lng, now.zone) ?: continue
            val t = (if (event == "sunset") times.second else times.first).plusMinutes(offsetMin)
            if (t.isAfter(now)) return t
        }
        return null
    }
}
