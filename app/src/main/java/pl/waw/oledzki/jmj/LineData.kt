// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Grzegorz Olędzki

package pl.waw.oledzki.jmj

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.maplibre.android.geometry.LatLng
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.util.zip.GZIPInputStream

// Per-line JSON published by preprocess/build.py, served gzip-encoded from S3.
private const val DATA_BASE_URL = "https://jak-mam-jechac-data.s3.eu-central-1.amazonaws.com"

/** One line's schedule data — shapes and stops deduped, referenced by id from the trips. */
data class LineData(
    val feedVersion: String,
    val calendar: Map<String, List<String>>,             // service_id -> ISO dates it runs
    val shapes: Map<String, List<LatLng>>,               // shape_id -> path
    val stops: Map<String, Stop>,                        // stop_id -> location
    val services: Map<String, Map<String, List<Trip>>>,  // service_id -> brigade -> day chain
) {
    /** The service_id that runs on [date] (holiday/boundary-correct: it's the feed's own calendar). */
    fun resolveServiceId(date: LocalDate): String? {
        val iso = date.toString()
        return calendar.entries.firstOrNull { iso in it.value }?.key
    }
}

data class Stop(val name: String, val lat: Double, val lon: Double)

/**
 * One line as shown in the picker: its number, display name, vehicle type, and the
 * brigades running on each day-type. Comes from the small brigades.json index, so the
 * picker never has to download a full (multi-MB) line file just to list brigades.
 */
data class LineInfo(
    val line: String,
    val name: String,
    val type: Int,                                  // GTFS route_type (always 3/bus here)
    val brigadesByDay: Map<String, List<String>>,   // ServiceDay.code -> brigades
) {
    fun brigades(day: ServiceDay): List<String> = brigadesByDay[day.code].orEmpty()
}

/** The picker index: the feed version (for attribution) plus the lines it lists. */
data class BrigadeIndex(val feedVersion: String, val lines: List<LineInfo>)

/** "HH:MM" → minutes since midnight, tolerant of hours >= 24 (after-midnight trips). */
fun hhmmToMinutes(hhmm: String): Int {
    val (h, m) = hhmm.split(":")
    return h.toInt() * 60 + m.toInt()
}

/** One scheduled stop along a trip. A bus's arrival==departure, so both may be equal. */
data class StopTime(val stopId: String, val arr: String?, val dep: String?)

/** One trip in a brigade's day. [exc] = 1 marks a non-revenue depot pull-out/in. */
data class Trip(
    val shape: String?,
    val head: String,
    val varCode: String,
    val exc: Int,
    val stops: List<StopTime>,
) {
    val stopIds: List<String> get() = stops.map { it.stopId }
    /** "HH:MM" leaving the first stop / reaching the last, or null if unscheduled. */
    val departure: String? get() = stops.firstOrNull()?.let { it.dep ?: it.arr }
    val arrival: String? get() = stops.lastOrNull()?.let { it.arr ?: it.dep }
}

/** Fetches and parses a line, caching to disk so a later dead zone still renders. */
suspend fun fetchLine(context: Context, line: String): LineData = withContext(Dispatchers.IO) {
    val cache = context.cacheDir.resolve("$line.json")
    val bytes = try {
        download("$DATA_BASE_URL/lines/$line.json").also { cache.writeBytes(it) }
    } catch (e: Exception) {
        if (cache.exists()) cache.readBytes() else throw e
    }
    parseLine(bytes.decodeToString())
}

// The brigade index rarely changes (schedules turn over ~every 10 days), so reuse the
// cached copy for a day rather than re-downloading it on every visit to the picker.
private const val BRIGADES_TTL_MS = 24L * 60 * 60 * 1000

/**
 * Fetches the line/brigade picker index, served from the on-disk cache while it's fresh.
 * [force] skips the freshness check to re-download now (the pull-to-refresh gesture).
 */
suspend fun fetchBrigades(context: Context, force: Boolean = false): BrigadeIndex = withContext(Dispatchers.IO) {
    val cache = context.cacheDir.resolve("brigades.json")
    val fresh = !force && cache.exists() && System.currentTimeMillis() - cache.lastModified() < BRIGADES_TTL_MS
    val bytes = if (fresh) {
        cache.readBytes()
    } else try {
        download("$DATA_BASE_URL/brigades.json").also { cache.writeBytes(it) }
    } catch (e: Exception) {
        if (cache.exists()) cache.readBytes() else throw e
    }
    parseBrigades(bytes.decodeToString())
}

private fun parseBrigades(json: String): BrigadeIndex {
    val root = JSONObject(json)
    val lines = root.getJSONObject("lines")
    val infos = lines.keys().asSequence().map { line ->
        val o = lines.getJSONObject(line)
        val byDay = o.getJSONObject("brigades")
        LineInfo(
            line = line,
            name = o.optString("name"),
            type = o.optInt("type"),
            brigadesByDay = byDay.keys().asSequence().associateWith { code ->
                byDay.getJSONArray(code).let { a -> List(a.length()) { a.getString(it) } }
            },
        )
    }.toList()
    return BrigadeIndex(root.optString("feedVersion"), infos)
}

private fun download(url: String): ByteArray {
    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 15_000
        // We ask for gzip ourselves, which disables HttpURLConnection's transparent
        // decode, so we decode the (always gzip-stored) body deterministically.
        setRequestProperty("Accept-Encoding", "gzip")
    }
    try {
        val raw = conn.inputStream
        val stream = if (conn.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(raw) else raw
        return stream.use { it.readBytes() }
    } finally {
        conn.disconnect()
    }
}

private fun parseLine(json: String): LineData {
    val o = JSONObject(json)

    val cal = o.getJSONObject("calendar")
    val calendar = cal.keys().asSequence().associateWith { sid ->
        cal.getJSONArray(sid).let { a -> List(a.length()) { a.getString(it) } }
    }

    val sh = o.getJSONObject("shapes")
    val shapes = sh.keys().asSequence().associateWith { id ->
        sh.getJSONArray(id).let { pts ->
            List(pts.length()) { i ->
                val c = pts.getJSONArray(i)            // [lon, lat]
                LatLng(c.getDouble(1), c.getDouble(0))
            }
        }
    }

    val st = o.getJSONObject("stops")
    val stops = st.keys().asSequence().associateWith { id ->
        val s = st.getJSONObject(id)
        Stop(s.getString("n"), s.getDouble("lat"), s.getDouble("lon"))
    }

    val sv = o.getJSONObject("services")
    val services = sv.keys().asSequence().associateWith { sid ->
        val brigades = sv.getJSONObject(sid)
        brigades.keys().asSequence().associateWith { brig ->
            brigades.getJSONArray(brig).let { trips ->
                List(trips.length()) { i -> parseTrip(trips.getJSONObject(i)) }
            }
        }
    }

    return LineData(o.optString("feedVersion"), calendar, shapes, stops, services)
}

private fun parseTrip(t: JSONObject): Trip {
    val s = t.getJSONArray("stops")
    return Trip(
        shape = t.optString("shape").ifEmpty { null },
        head = t.optString("head"),
        varCode = t.optString("var"),
        exc = t.optInt("exc"),
        stops = List(s.length()) { parseStopTime(s.getJSONObject(it)) },
    )
}

private fun parseStopTime(s: JSONObject): StopTime {
    // Compact form: "t" when arrival==departure (buses), else separate "a"/"d".
    val t = s.optString("t").ifEmpty { null }
    return StopTime(
        stopId = s.getString("s"),
        arr = t ?: s.optString("a").ifEmpty { null },
        dep = t ?: s.optString("d").ifEmpty { null },
    )
}

/** A trip's path as a GeoJSON LineString Feature — feeds both the map layer and [RouteFraming]. */
fun LineData.tripLineGeoJson(trip: Trip): String {
    val coords = shapes[trip.shape].orEmpty().joinToString(",") { "[${it.longitude},${it.latitude}]" }
    return """{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]}}"""
}

/**
 * A trip's stops as a GeoJSON Point FeatureCollection, in travel order. The final stop
 * carries `role:final` so the map can paint the terminus a distinct colour.
 */
fun LineData.tripStopsGeoJson(trip: Trip): String {
    val pts = trip.stopIds.mapNotNull { stops[it] }
    val feats = pts.mapIndexed { i, s ->
        val props = if (i == pts.lastIndex) ""","properties":{"role":"final"}""" else ""
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[${s.lon},${s.lat}]}$props}"""
    }.joinToString(",")
    return """{"type":"FeatureCollection","features":[$feats]}"""
}

/**
 * Index of the first stop in the terminus cluster: the trailing run of stops sharing the
 * final stop's name (e.g. both `Dw. Centralny` posts). The map switches to whole-tail
 * framing on approach to this stop. Returns the last index when the terminus is a single
 * stop. Computed over the same name-bearing stops [tripStopsGeoJson] keeps, so the index
 * lines up with [RouteFraming]'s stop list.
 */
fun LineData.finalClusterStart(trip: Trip): Int {
    val names = trip.stopIds.mapNotNull { stops[it]?.name }
    if (names.isEmpty()) return 0
    val last = names.last()
    var i = names.lastIndex
    while (i > 0 && names[i - 1] == last) i--
    return i
}
