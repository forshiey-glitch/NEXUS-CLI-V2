package com.nexus.cli

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class WeatherRepository(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()
) {

    suspend fun fetch(
        city: String = DEFAULT_CITY,
        lat: Double = DEFAULT_LAT,
        lon: Double = DEFAULT_LON
    ): Result<WeatherInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://api.open-meteo.com/v1/forecast" +
                    "?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=4"

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NEXUS-CLI/2.0")
                .build()

            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val body = resp.body?.string() ?: error("Respons kosong")
                parse(city, body)
            }
        }
    }

    private fun parse(city: String, body: String): WeatherInfo {
        val root = JsonParser.parseString(body).asJsonObject
        val current = root.getAsJsonObject("current")
        val daily = root.getAsJsonObject("daily")

        val times = daily.getAsJsonArray("time")
        val codes = daily.getAsJsonArray("weather_code")
        val maxes = daily.getAsJsonArray("temperature_2m_max")
        val mins = daily.getAsJsonArray("temperature_2m_min")

        val days = (0 until times.size()).map { i ->
            val label = if (i == 0) {
                "Hari ini"
            } else {
                LocalDate.parse(times[i].asString)
                    .dayOfWeek
                    .getDisplayName(TextStyle.SHORT, Locale.forLanguageTag("id-ID"))
            }
            DayForecast(
                label = label,
                condition = describe(codes[i].asInt),
                maxC = maxes[i].asDouble.roundToInt(),
                minC = mins[i].asDouble.roundToInt()
            )
        }

        return WeatherInfo(
            city = city,
            tempC = current.get("temperature_2m").asDouble.roundToInt(),
            condition = describe(current.get("weather_code").asInt),
            windKmh = current.get("wind_speed_10m").asDouble.roundToInt(),
            daily = days
        )
    }

    companion object {
        const val DEFAULT_CITY = "Jakarta"
        const val DEFAULT_LAT = -6.2088
        const val DEFAULT_LON = 106.8456

        fun describe(code: Int): String = when (code) {
            0 -> "Cerah"
            1 -> "Cerah Berawan"
            2 -> "Berawan Sebagian"
            3 -> "Mendung"
            45, 48 -> "Kabut"
            51, 53, 55, 56, 57 -> "Gerimis"
            61, 63, 65, 66, 67 -> "Hujan"
            71, 73, 75, 77 -> "Salju"
            80, 81, 82 -> "Hujan Lokal"
            85, 86 -> "Salju Lokal"
            95, 96, 99 -> "Badai Petir"
            else -> "Tidak diketahui"
        }
    }
}