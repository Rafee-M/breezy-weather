/**
 * This file is part of Breezy Weather.
 *
 * Breezy Weather is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, version 3 of the License.
 *
 * Breezy Weather is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Breezy Weather. If not, see <https://www.gnu.org/licenses/>.
 */

package org.breezyweather.sources.metie

import android.graphics.Color
import breezyweather.domain.location.model.Location
import breezyweather.domain.weather.model.Alert
import breezyweather.domain.weather.model.AlertSeverity
import breezyweather.domain.weather.model.Daily
import breezyweather.domain.weather.model.Precipitation
import breezyweather.domain.weather.model.Temperature
import breezyweather.domain.weather.model.WeatherCode
import breezyweather.domain.weather.model.Wind
import breezyweather.domain.weather.wrappers.HourlyWrapper
import breezyweather.domain.weather.wrappers.SecondaryWeatherWrapper
import breezyweather.domain.weather.wrappers.WeatherWrapper
import org.breezyweather.common.exceptions.InvalidOrIncompleteDataException
import org.breezyweather.common.extensions.toDateNoHour
import org.breezyweather.sources.metie.json.MetIeHourly
import org.breezyweather.sources.metie.json.MetIeLocationResult
import org.breezyweather.sources.metie.json.MetIeWarning
import org.breezyweather.sources.metie.json.MetIeWarningResult
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

fun convert(
    location: Location,
    result: MetIeLocationResult
): Location {
    return location.copy(
        timeZone = "Europe/Dublin",
        country = "Ireland",
        countryCode = "IE",
        province = result.county,
        city = result.city ?: ""
    )
}
fun convert(
    hourlyResult: List<MetIeHourly>?,
    warningsResult: MetIeWarningResult?,
    location: Location
): WeatherWrapper {
    // If the API doesn’t return data, consider data as garbage and keep cached data
    if (hourlyResult.isNullOrEmpty()) {
        throw InvalidOrIncompleteDataException()
    }

    return WeatherWrapper(
        dailyForecast = getDailyForecast(location, hourlyResult),
        hourlyForecast = getHourlyForecast(hourlyResult),
        alertList = getAlertList(location, warningsResult?.warnings?.national)
    )
}

private fun getDailyForecast(
    location: Location,
    hourlyResult: List<MetIeHourly>
): List<Daily> {
    val dailyList: MutableList<Daily> = ArrayList()
    val hourlyListByDay = hourlyResult.groupBy { it.date }
    for (i in 0 until hourlyListByDay.entries.size - 1) {
        val dayDate = hourlyListByDay.keys.toTypedArray()[i].toDateNoHour(location.javaTimeZone)
        if (dayDate != null) {
            dailyList.add(
                Daily(
                    date = dayDate
                )
            )
        }
    }
    return dailyList
}

/**
 * Returns hourly forecast
 */
private fun getHourlyForecast(
    hourlyResult: List<MetIeHourly>
): List<HourlyWrapper> {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ENGLISH)
    formatter.timeZone = TimeZone.getTimeZone("Europe/Dublin")

    return hourlyResult.map { result ->
        HourlyWrapper(
            date = formatter.parse("${result.date} ${result.time}")!!,
            weatherCode = getWeatherCode(result.weatherNumber),
            weatherText = result.weatherDescription,
            temperature = Temperature(
                temperature = result.temperature?.toDouble()
            ),
            precipitation = Precipitation(
                total = result.rainfall?.toDoubleOrNull()
            ),
            wind = Wind(
                degree = result.windDirection?.toDoubleOrNull(),
                speed = result.windSpeed?.div(3.6)
            ),
            relativeHumidity = result.humidity?.toDoubleOrNull(),
            pressure = result.pressure?.toDoubleOrNull(),
        )
    }
}

fun getAlertList(location: Location, warnings: List<MetIeWarning>?): List<Alert>? {
    if (warnings == null) return null
    if (warnings.isEmpty()) return emptyList()

    val region = if (MetIeService.regionsMapping.containsKey(location.province)) {
        location.province
    } else location.parameters.getOrElse("metie") { null }?.getOrElse("region") { null }
    val eiRegion = region?.let { MetIeService.regionsMapping.getOrElse(region) { null } }

    return warnings
        .filter { it.regions.contains("EI0") /* National */ || it.regions.contains(eiRegion) }
        .map { alert ->
            Alert(
                alertId = alert.id,
                startDate = alert.onset,
                endDate = alert.expiry,
                headline = alert.headline,
                description = alert.description,
                severity = when (alert.severity?.lowercase()) {
                    "extreme" -> AlertSeverity.EXTREME
                    "severe" -> AlertSeverity.SEVERE
                    "moderate" -> AlertSeverity.MODERATE
                    "minor" -> AlertSeverity.MINOR
                    else -> AlertSeverity.UNKNOWN
                },
                color = when (alert.level?.lowercase()) {
                    "red" -> Color.rgb(224, 0, 0)
                    "orange" -> Color.rgb(255, 140, 0)
                    "yellow" -> Color.rgb(255, 255, 0)
                    else -> null
                }
            )
        }
}

fun convertSecondary(
    warningsResult: MetIeWarningResult?,
    location: Location
): SecondaryWeatherWrapper {

    return SecondaryWeatherWrapper(
        alertList = getAlertList(location, warningsResult?.warnings?.national)
    )
}

private fun getWeatherCode(icon: String?): WeatherCode? {
    if (icon == null) return null
    return with (icon) {
        when {
            startsWith("01") || startsWith("02") -> WeatherCode.CLEAR
            startsWith("03") -> WeatherCode.PARTLY_CLOUDY
            startsWith("04") -> WeatherCode.CLOUDY
            startsWith("05") || startsWith("09") || startsWith("10") ||
                startsWith("40") || startsWith("41") ||
                startsWith("46") -> WeatherCode.RAIN
            startsWith("06") || startsWith("11") || startsWith("14") ||
                startsWith("2")  || startsWith("30") || startsWith("31") ||
                startsWith("32") || startsWith("33") ||
                startsWith("34") -> WeatherCode.THUNDERSTORM
            startsWith("07") || startsWith("12") || startsWith("42") ||
                startsWith("43") || startsWith("47") ||
                startsWith("48") -> WeatherCode.SLEET
            startsWith("08") || startsWith("13") || startsWith("44") ||
                startsWith("45") || startsWith("49") ||
                startsWith("50") -> WeatherCode.SNOW
            startsWith("15") -> WeatherCode.FOG
            startsWith("51") || startsWith("52") -> WeatherCode.HAIL
            else -> null
        }
    }
}
