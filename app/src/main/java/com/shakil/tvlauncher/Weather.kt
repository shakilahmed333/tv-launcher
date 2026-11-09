package com.shakil.tvlauncher

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Current weather plus three upcoming 3-hourly temperatures. */
class Weather(
    val temp: Int,
    val code: Int,
    val isDay: Boolean,
    val upcoming: List<Pair<String, Int>>, // ("1 AM", 27)
    val fetchedAt: Long,
) {
    val label: String
        get() = when (code) {
            0 -> if (isDay) "Sunny" else "Clear"
            1 -> "Mainly clear"
            2 -> "Partly cloudy"
            3 -> "Cloudy"
            45, 48 -> "Foggy"
            in 51..57 -> "Drizzle"
            in 61..67 -> "Rain"
            in 71..77, 85, 86 -> "Snow"
            in 80..82 -> "Showers"
            in 95..99 -> "Thunderstorm"
            else -> "—"
        }

    val iconRes: Int
        get() = when (code) {
            0, 1 -> if (isDay) R.drawable.ic_sun else R.drawable.ic_moon
            2 -> if (isDay) R.drawable.ic_partly else R.drawable.ic_cloud
            3, 45, 48 -> R.drawable.ic_cloud
            in 51..67, in 80..82 -> R.drawable.ic_rain
            in 95..99 -> R.drawable.ic_storm
            else -> R.drawable.ic_cloud
        }
}

/**
 * Weather for Dhaka from Open-Meteo (free, no account or key).
 * The last answer is cached so the card fills instantly after a restart.
 */
object WeatherRepo {
    private const val URL_DHAKA =
        "https://api.open-meteo.com/v1/forecast?latitude=23.8103&longitude=90.4125" +
            "&current=temperature_2m,weather_code,is_day" +
            "&hourly=temperature_2m&forecast_days=2&timezone=Asia%2FDhaka"

    const val REFRESH_MS = 30 * 60 * 1000L

    /** Last known weather in memory, so a new home screen can show it before any disk read. */
    @Volatile
    var cachedInMemory: Weather? = null
        private set

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun cached(ctx: Context): Weather? {
        val p = prefs(ctx)
        val json = p.getString("json", null) ?: return null
        return parse(json, p.getLong("at", 0L))?.also { cachedInMemory = it }
    }

    /** Network call: run off the main thread. Returns null if offline or on error. */
    fun fetch(ctx: Context): Weather? {
        return try {
            val conn = URL(URL_DHAKA).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            try {
                if (conn.responseCode != 200) return null
                val json = conn.inputStream.bufferedReader().use { it.readText() }
                val now = System.currentTimeMillis()
                val w = parse(json, now) ?: return null
                prefs(ctx).edit().putString("json", json).putLong("at", now).apply()
                cachedInMemory = w
                w
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parse(json: String, at: Long): Weather? {
        return try {
            val root = JSONObject(json)
            val cur = root.getJSONObject("current")
            val curTime = cur.getString("time") // e.g. 2026-11-09T22:15

            val hourly = root.getJSONObject("hourly")
            val times = hourly.getJSONArray("time")
            val temps = hourly.getJSONArray("temperature_2m")
            var start = -1
            val hourPrefix = curTime.substring(0, 13)
            for (i in 0 until times.length()) {
                if (times.getString(i).startsWith(hourPrefix)) {
                    start = i
                    break
                }
            }
            val upcoming = ArrayList<Pair<String, Int>>()
            if (start >= 0) {
                for (step in 1..3) {
                    val i = start + step * 3
                    if (i >= times.length()) break
                    val hour = times.getString(i).substring(11, 13).toInt()
                    upcoming.add(hourLabel(hour) to Math.round(temps.getDouble(i)).toInt())
                }
            }

            Weather(
                temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
                code = cur.getInt("weather_code"),
                isDay = cur.optInt("is_day", 1) == 1,
                upcoming = upcoming,
                fetchedAt = at,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun hourLabel(h: Int): String {
        val h12 = if (h % 12 == 0) 12 else h % 12
        return "$h12 ${if (h < 12) "AM" else "PM"}"
    }
}
