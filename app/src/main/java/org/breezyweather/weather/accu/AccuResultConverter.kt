package org.breezyweather.weather.accu

import android.content.Context
import org.breezyweather.BreezyWeather
import org.breezyweather.common.basic.models.Location
import org.breezyweather.common.basic.models.options.provider.WeatherSource
import org.breezyweather.common.basic.models.options.unit.PrecipitationUnit
import org.breezyweather.common.basic.models.weather.*
import org.breezyweather.common.utils.DisplayUtils
import org.breezyweather.settings.SettingsManager
import org.breezyweather.weather.accu.json.*
import org.breezyweather.weather.getDailyAirQualityFromHourlyList
import org.breezyweather.weather.getMoonPhaseAngle
import org.breezyweather.weather.getUVLevel
import org.breezyweather.weather.getWindLevel
import org.breezyweather.weather.WeatherService.WeatherResultWrapper
import java.util.*
import java.util.regex.Pattern
import kotlin.math.roundToInt

fun convert(
    location: Location?,
    result: AccuLocationResult,
    zipCode: String?
): Location {
    return if (location != null && !location.province.isNullOrEmpty()
        && location.city.isNotEmpty()
        && !location.district.isNullOrEmpty()
    ) {
        Location(
            cityId = result.Key,
            latitude = result.GeoPosition.Latitude.toFloat(),
            longitude = result.GeoPosition.Longitude.toFloat(),
            timeZone = TimeZone.getTimeZone(result.TimeZone.Name),
            country = result.Country.LocalizedName,
            province = location.province,
            city = location.city,
            district = location.district + if (zipCode != null) " ($zipCode)" else "",
            weatherSource = WeatherSource.ACCU,
            isChina = !result.Country.ID.isNullOrEmpty()
                    && (result.Country.ID.equals("cn", ignoreCase = true)
                    || result.Country.ID.equals("hk", ignoreCase = true)
                    || result.Country.ID.equals("tw", ignoreCase = true))
        )
    } else {
        Location(
            cityId = result.Key,
            latitude = result.GeoPosition.Latitude.toFloat(),
            longitude = result.GeoPosition.Longitude.toFloat(),
            timeZone = TimeZone.getTimeZone(result.TimeZone.Name),
            country = result.Country.LocalizedName,
            province = result.AdministrativeArea?.LocalizedName ?: "",
            city = result.LocalizedName + if (zipCode != null) " ($zipCode)" else "",
            weatherSource = WeatherSource.ACCU,
            isChina = !result.Country.ID.isNullOrEmpty()
                    && (result.Country.ID.equals("cn", ignoreCase = true)
                    || result.Country.ID.equals("hk", ignoreCase = true)
                    || result.Country.ID.equals("tw", ignoreCase = true))
        )
    }
}

fun convert(
    context: Context,
    location: Location,
    currentResult: AccuCurrentResult,
    dailyResult: AccuForecastDailyResult,
    hourlyResultList: List<AccuForecastHourlyResult>,
    minuteResult: AccuMinutelyResult?,
    alertResultList: List<AccuAlertResult>,
    airQualityHourlyResult: AccuAirQualityResult
): WeatherResultWrapper {
    // If the API doesn’t return hourly or daily, consider data as garbage and keep cached data
    if (dailyResult.DailyForecasts == null || dailyResult.DailyForecasts.isEmpty() || hourlyResultList.isEmpty()) {
        return WeatherResultWrapper(null)
    }

    return try {
        val hourlyList = getHourlyList(context, hourlyResultList, airQualityHourlyResult.data)

        val weather = Weather(
            base = Base(
                cityId = location.cityId,
                publishDate = Date(currentResult.EpochTime.times(1000)),
            ),
            current = Current(
                weatherText = currentResult.WeatherText,
                weatherCode = getWeatherCode(currentResult.WeatherIcon),
                temperature = Temperature(
                    temperature = currentResult.Temperature?.Metric?.Value?.roundToInt(),
                    realFeelTemperature = currentResult.RealFeelTemperature?.Metric?.Value?.roundToInt(),
                    realFeelShaderTemperature = currentResult.RealFeelTemperatureShade?.Metric?.Value?.roundToInt(),
                    apparentTemperature = currentResult.ApparentTemperature?.Metric?.Value?.roundToInt(),
                    windChillTemperature = currentResult.WindChillTemperature?.Metric?.Value?.roundToInt(),
                    wetBulbTemperature = currentResult.WetBulbTemperature?.Metric?.Value?.roundToInt()
                ),
                wind = Wind(
                    direction = currentResult.Wind?.Direction?.Localized,
                    degree = WindDegree(currentResult.Wind?.Direction?.Degrees?.toFloat(), false),
                    speed = currentResult.Wind?.Speed?.Metric?.Value?.toFloat(),
                    level = getWindLevel(context, currentResult.Wind?.Speed?.Metric?.Value?.toFloat())
                ),
                uV = UV(
                    index = currentResult.UVIndex,
                    level = getUVLevel(context, currentResult.UVIndex)
                ),
                airQuality = if (airQualityHourlyResult.data?.getOrNull(0) != null) getAirQualityForHour(airQualityHourlyResult.data[0].epochDate, airQualityHourlyResult.data) else null,
                relativeHumidity = currentResult.RelativeHumidity?.toFloat(),
                pressure = currentResult.Pressure?.Metric?.Value?.toFloat(),
                visibility = currentResult.Visibility?.Metric?.Value?.toFloat(),
                dewPoint = currentResult.DewPoint?.Metric?.Value?.roundToInt(),
                cloudCover = currentResult.CloudCover,
                ceiling = (currentResult.Ceiling?.Metric?.Value?.div(1000.0))?.toFloat(),
                dailyForecast = convertUnit(context, dailyResult.Headline?.Text),
                hourlyForecast = convertUnit(context, minuteResult?.Summary?.LongPhrase)
            ),
            yesterday = History(
                date = Date((currentResult.EpochTime - 24 * 60 * 60).times(1000)),
                daytimeTemperature = currentResult.TemperatureSummary?.Past24HourRange?.Maximum?.Metric?.Value?.roundToInt(),
                nighttimeTemperature = currentResult.TemperatureSummary?.Past24HourRange?.Minimum?.Metric?.Value?.roundToInt()
            ),
            dailyForecast = getDailyList(context, dailyResult.DailyForecasts, hourlyList, location.timeZone),
            hourlyForecast = hourlyList,
            minutelyForecast = getMinutelyList(minuteResult),
            alertList = getAlertList(alertResultList)
        )
        WeatherResultWrapper(weather)
    } catch (e: Exception) {
        if (BreezyWeather.instance.debugMode) {
            e.printStackTrace()
        }
        WeatherResultWrapper(null)
    }
}

private fun getDailyList(
    context: Context,
    dailyForecasts: List<AccuForecastDailyForecast>,
    hourlyList: List<Hourly>,
    timeZone: TimeZone
): List<Daily> {
    val dailyList: MutableList<Daily> = ArrayList(dailyForecasts.size)
    val hourlyListByDay = hourlyList.groupBy { DisplayUtils.getFormattedDate(it.date, timeZone, "yyyyMMdd") }
    for (forecasts in dailyForecasts) {
        val theDay = Date(forecasts.EpochDate.times(1000))
        val dailyDateFormatted = DisplayUtils.getFormattedDate(theDay, timeZone, "yyyyMMdd")
        dailyList.add(
            Daily(
                date = theDay,
                day = HalfDay(
                    weatherText = convertUnit(context, forecasts.Day?.LongPhrase),
                    weatherPhase = forecasts.Day?.ShortPhrase,
                    weatherCode = getWeatherCode(forecasts.Day?.Icon),
                    temperature = Temperature(
                        temperature = forecasts.Temperature?.Maximum?.Value?.roundToInt(),
                        realFeelTemperature = forecasts.RealFeelTemperature?.Maximum?.Value?.roundToInt(),
                        realFeelShaderTemperature = forecasts.RealFeelTemperatureShade?.Maximum?.Value?.roundToInt(),
                        degreeDayTemperature = forecasts.DegreeDaySummary?.Heating?.Value?.roundToInt()
                    ),
                    precipitation = Precipitation(
                        total = forecasts.Day?.TotalLiquid?.Value?.toFloat(),
                        rain = forecasts.Day?.Rain?.Value?.toFloat(),
                        snow = forecasts.Day?.Snow?.Value?.toFloat(),
                        ice = forecasts.Day?.Ice?.Value?.toFloat()
                    ),
                    precipitationProbability = PrecipitationProbability(
                        total = forecasts.Day?.PrecipitationProbability?.toFloat(),
                        thunderstorm = forecasts.Day?.ThunderstormProbability?.toFloat(),
                        rain = forecasts.Day?.RainProbability?.toFloat(),
                        snow = forecasts.Day?.SnowProbability?.toFloat(),
                        ice = forecasts.Day?.IceProbability?.toFloat()
                    ),
                    precipitationDuration = PrecipitationDuration(
                        total = forecasts.Day?.HoursOfPrecipitation?.toFloat(),
                        rain = forecasts.Day?.HoursOfRain?.toFloat(),
                        snow = forecasts.Day?.HoursOfSnow?.toFloat(),
                        ice = forecasts.Day?.HoursOfIce?.toFloat()
                    ),
                    wind = Wind(
                        direction = forecasts.Day?.Wind?.Direction?.Localized,
                        degree = WindDegree(forecasts.Day?.Wind?.Direction?.Degrees?.toFloat(), false),
                        speed = forecasts.Day?.Wind?.Speed?.Value?.toFloat(),
                        level = getWindLevel(context, forecasts.Day?.Wind?.Speed?.Value?.toFloat())
                    ),
                    cloudCover = forecasts.Day?.CloudCover
                ),
                night = HalfDay(
                    weatherText = convertUnit(context, forecasts.Night?.LongPhrase),
                    weatherPhase = forecasts.Night?.ShortPhrase,
                    weatherCode = getWeatherCode(forecasts.Night?.Icon),
                    temperature = Temperature(
                        temperature = forecasts.Temperature?.Minimum?.Value?.roundToInt(),
                        realFeelTemperature = forecasts.RealFeelTemperature?.Minimum?.Value?.roundToInt(),
                        realFeelShaderTemperature = forecasts.RealFeelTemperatureShade?.Minimum?.Value?.roundToInt(),
                        degreeDayTemperature = forecasts.DegreeDaySummary?.Cooling?.Value?.roundToInt()
                    ),
                    precipitation = Precipitation(
                        total = forecasts.Night?.TotalLiquid?.Value?.toFloat(),
                        rain = forecasts.Night?.Rain?.Value?.toFloat(),
                        snow = forecasts.Night?.Snow?.Value?.toFloat(),
                        ice = forecasts.Night?.Ice?.Value?.toFloat()
                    ),
                    precipitationProbability = PrecipitationProbability(
                        total = forecasts.Night?.PrecipitationProbability?.toFloat(),
                        thunderstorm = forecasts.Night?.ThunderstormProbability?.toFloat(),
                        rain = forecasts.Night?.RainProbability?.toFloat(),
                        snow = forecasts.Night?.SnowProbability?.toFloat(),
                        ice = forecasts.Night?.IceProbability?.toFloat()
                    ),
                    precipitationDuration = PrecipitationDuration(
                        total = forecasts.Night?.HoursOfPrecipitation?.toFloat(),
                        rain = forecasts.Night?.HoursOfRain?.toFloat(),
                        snow = forecasts.Night?.HoursOfSnow?.toFloat(),
                        ice = forecasts.Night?.HoursOfIce?.toFloat()
                    ),
                    wind = Wind(
                        direction = forecasts.Night?.Wind?.Direction?.Localized,
                        degree = WindDegree(forecasts.Night?.Wind?.Direction?.Degrees?.toFloat(), false),
                        speed = forecasts.Night?.Wind?.Speed?.Value?.toFloat(),
                        level = getWindLevel(context, forecasts.Night?.Wind?.Speed?.Value?.toFloat())
                    ),
                    cloudCover = forecasts.Night?.CloudCover
                ),
                sun = Astro(
                    riseDate = if(forecasts.Sun?.EpochRise != null) Date(forecasts.Sun.EpochRise.times(1000)) else null,
                    setDate = if(forecasts.Sun?.EpochSet != null) Date(forecasts.Sun.EpochSet.times(1000)) else null
                ),
                moon = Astro(
                    riseDate = if(forecasts.Moon?.EpochRise != null) Date(forecasts.Moon.EpochRise.times(1000)) else null,
                    setDate = if(forecasts.Moon?.EpochSet != null) Date(forecasts.Moon.EpochSet.times(1000)) else null
                ),
                moonPhase = MoonPhase(
                    angle = getMoonPhaseAngle(forecasts.Moon?.Phase),
                    description = forecasts.Moon?.Phase
                ),
                airQuality = getDailyAirQualityFromHourlyList(hourlyListByDay.getOrDefault(dailyDateFormatted, null)),
                pollen = getDailyPollen(forecasts.AirAndPollen),
                uV = getDailyUV(forecasts.AirAndPollen),
                hoursOfSun = forecasts.HoursOfSun?.toFloat()
            )
        )
    }
    return dailyList
}

private fun getDailyPollen(list: List<AccuForecastAirAndPollen>?): Pollen? {
    if (list == null) return null

    val grass = list.firstOrNull { it.Name == "Grass" }
    val mold = list.firstOrNull { it.Name == "Mold" }
    val ragweed = list.firstOrNull { it.Name == "Ragweed" }
    val tree = list.firstOrNull { it.Name == "Tree" }
    return Pollen(
        grassIndex = grass?.Value,
        grassLevel = grass?.CategoryValue,
        grassDescription = grass?.Category,
        moldIndex = mold?.Value,
        moldLevel = mold?.CategoryValue,
        moldDescription = mold?.Category,
        ragweedIndex = ragweed?.Value,
        ragweedLevel = ragweed?.CategoryValue,
        ragweedDescription = ragweed?.Category,
        treeIndex = tree?.Value,
        treeLevel = tree?.CategoryValue,
        treeDescription = tree?.Category
    )
}

private fun getDailyUV(list: List<AccuForecastAirAndPollen>?): UV? {
    if (list == null) return null

    val uv = list.firstOrNull { it.Name == "UVIndex" }
    return UV(
        index = uv?.Value,
        level = uv?.Category
    )
}

private fun getHourlyList(
    context: Context,
    resultList: List<AccuForecastHourlyResult>,
    airQualityData: List<AccuAirQualityData>?
): List<Hourly> {
    val hourlyList: MutableList<Hourly> = ArrayList(resultList.size)
    for (result in resultList) {
        hourlyList.add(
            Hourly(
                date = Date(result.EpochDateTime.times(1000)),
                isDaylight = result.IsDaylight,
                weatherText = result.IconPhrase,
                weatherCode = getWeatherCode(result.WeatherIcon),
                temperature = Temperature(
                    temperature = result.Temperature?.Value?.roundToInt(),
                    realFeelTemperature = result.RealFeelTemperature?.Value?.roundToInt(),
                    realFeelShaderTemperature = result.RealFeelTemperatureShade?.Value?.roundToInt(),
                    wetBulbTemperature = result.WetBulbTemperature?.Value?.roundToInt()
                ),
                precipitation = Precipitation(
                    total = result.TotalLiquid?.Value?.toFloat(),
                    rain = result.Rain?.Value?.toFloat(),
                    snow = result.Snow?.Value?.toFloat(),
                    ice = result.Ice?.Value?.toFloat()
                ),
                precipitationProbability = PrecipitationProbability(
                    total = result.PrecipitationProbability?.toFloat(),
                    thunderstorm = result.ThunderstormProbability?.toFloat(),
                    rain = result.RainProbability?.toFloat(),
                    snow = result.SnowProbability?.toFloat(),
                    ice = result.IceProbability?.toFloat()
                ),
                wind = Wind(
                    direction = result.Wind?.Direction?.Localized,
                    degree = WindDegree(result.Wind?.Direction?.Degrees?.toFloat(), false),
                    speed = result.Wind?.Speed?.Value?.toFloat(),
                    level = getWindLevel(context, result.Wind?.Speed?.Value?.toFloat())
                ),
                airQuality = getAirQualityForHour(result.EpochDateTime, airQualityData),
                uV = UV(
                    index = result.UVIndex,
                    level = getUVLevel(context, result.UVIndex),
                    description = result.UVIndexText
                )
            )
        )
    }
    return hourlyList
}

fun getAirQualityForHour(requestedTime: Long, accuAirQualityDataList: List<AccuAirQualityData>?): AirQuality? {
    if (accuAirQualityDataList == null) return null

    var pm25: Float? = null
    var pm10: Float? = null
    var so2: Float? = null
    var no2: Float? = null
    var o3: Float? = null
    var co: Float? = null
    accuAirQualityDataList
        .firstOrNull { it.epochDate == requestedTime }
        ?.pollutants?.forEach {
            p -> when (p.type) {
                "O3" -> o3 = p.concentration.value?.toFloat()
                "NO2" -> no2 = p.concentration.value?.toFloat()
                "PM2_5" -> pm25 = p.concentration.value?.toFloat()
                "PM10" -> pm10 = p.concentration.value?.toFloat()
                "SO2" -> so2 = p.concentration.value?.toFloat()
                "CO" -> co = p.concentration.value?.div(1000)?.toFloat()
            }
        }

    // Return null instead of an object initialized with null values to ease the filtering later when aggregating for daily
    return if (pm25 != null || pm10 != null || so2 != null || no2 != null || o3 != null || co != null) AirQuality(
        pM25 = pm25,
        pM10 = pm10,
        sO2 = so2,
        nO2 = no2,
        o3 = o3,
        cO = co
    ) else null
}

private fun getMinutelyList(minuteResult: AccuMinutelyResult?): List<Minutely> {
    if (minuteResult == null || minuteResult.Intervals.isNullOrEmpty()) return emptyList()
    val minutelyList: MutableList<Minutely> = ArrayList(minuteResult.Intervals.size)
    minuteResult.Intervals.forEach { interval ->
        minutelyList.add(
            Minutely(
                Date(interval.StartEpochDateTime),
                interval.ShortPhrase,
                getWeatherCode(interval.IconCode),
                interval.Minute,
                interval.Dbz.roundToInt(),
                interval.CloudCover
            )
        )
    }
    return minutelyList
}

private fun getAlertList(resultList: List<AccuAlertResult>): List<Alert> {
    val alertList: MutableList<Alert> = ArrayList(resultList.size)
    for (result in resultList) {
        alertList.add(
            Alert(
                result.AlertID.toLong(),
                if (result.Area?.getOrNull(0) != null) Date(result.Area[0].EpochStartTime.times(1000)) else null,
                if (result.Area?.getOrNull(0) != null) Date(result.Area[0].EpochEndTime.times(1000)) else null,
                result.Description?.Localized,
                result.Area?.getOrNull(0)?.Text,
                result.TypeID,
                result.Priority
            )
        )
    }
    return alertList
}

private fun getWeatherCode(icon: Int?): WeatherCode? {
    if (icon == null) return null
    return when (icon) {
        1, 2, 30, 33, 34 -> WeatherCode.CLEAR
        3, 4, 6, 35, 36, 38 -> WeatherCode.PARTLY_CLOUDY
        5, 37 -> WeatherCode.HAZE
        7, 8 -> WeatherCode.CLOUDY
        11 -> WeatherCode.FOG
        12, 13, 14, 18, 39, 40 -> WeatherCode.RAIN
        15, 16, 17, 41, 42 -> WeatherCode.THUNDERSTORM
        19, 20, 21, 22, 23, 24, 31, 43, 44 -> WeatherCode.SNOW
        25 -> WeatherCode.HAIL
        26, 29 -> WeatherCode.SLEET
        32 -> WeatherCode.WIND
        else -> null
    }
}

private fun convertUnit(context: Context, text: String?): String? {
    if (text.isNullOrEmpty()) return text
    val precipitationUnit = SettingsManager.getInstance(context).precipitationUnit
    val newText = convertUnit(context, text, PrecipitationUnit.CM, precipitationUnit)
    return convertUnit(context, newText, PrecipitationUnit.MM, precipitationUnit)
}

// FIXME: issue #441, #463
private fun convertUnit(
    context: Context,
    text: String,
    targetUnit: PrecipitationUnit,
    resultUnit: PrecipitationUnit
): String {
    var newText = text
    return try {
        val numberPattern = "\\d+-\\d+(\\s+)?"
        val matcher = Pattern.compile(numberPattern + targetUnit).matcher(newText)
        val targetList: MutableList<String> = ArrayList()
        val resultList: MutableList<String> = ArrayList()
        while (matcher.find()) {
            val target = newText.substring(matcher.start(), matcher.end())
            targetList.add(target)
            val targetSplitResults = target.replace(" ".toRegex(), "").split(
                targetUnit.getName(context).toRegex()
            ).dropLastWhile { it.isEmpty() }.toTypedArray()
            val numberTexts =
                targetSplitResults[0].split("-".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            for (i in numberTexts.indices) {
                var number = numberTexts[i].toFloat()
                number = targetUnit.getValueInDefaultUnit(number)
                numberTexts[i] = resultUnit.getValueWithoutUnit(number).toString()
            }
            resultList.add(arrayToString(numberTexts) + " " + resultUnit.getName(context))
        }
        for (i in targetList.indices) {
            newText = newText.replace(targetList[i], resultList[i])
        }
        newText
    } catch (ignore: Exception) {
        newText
    }
}

private fun arrayToString(array: Array<String>): String {
    val builder = StringBuilder()
    for (i in array.indices) {
        builder.append(array[i])
        if (i < array.size - 1) {
            builder.append("-")
        }
    }
    return builder.toString()
}
