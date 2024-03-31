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

package org.breezyweather.main.adapters.main.holder

import android.graphics.Typeface
import android.text.Layout
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import breezyweather.domain.location.model.Location
import breezyweather.domain.weather.model.Minutely
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.chart.CartesianChartHost
import com.patrykandpatrick.vico.compose.chart.decoration.rememberHorizontalLine
import com.patrykandpatrick.vico.compose.chart.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.chart.rememberCartesianChart
import com.patrykandpatrick.vico.compose.chart.scroll.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.component.fixed
import com.patrykandpatrick.vico.compose.component.marker.rememberMarkerComponent
import com.patrykandpatrick.vico.compose.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.component.rememberShapeComponent
import com.patrykandpatrick.vico.compose.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.component.shape.markerCorneredShape
import com.patrykandpatrick.vico.compose.dimensions.dimensionsOf
import com.patrykandpatrick.vico.core.axis.AxisPosition
import com.patrykandpatrick.vico.core.axis.formatter.AxisValueFormatter
import com.patrykandpatrick.vico.core.chart.layer.ColumnCartesianLayer
import com.patrykandpatrick.vico.core.chart.values.AxisValueOverrider
import com.patrykandpatrick.vico.core.component.shape.Shapes
import com.patrykandpatrick.vico.core.component.shape.cornered.Corner
import com.patrykandpatrick.vico.core.component.text.TextComponent
import com.patrykandpatrick.vico.core.component.text.VerticalPosition
import com.patrykandpatrick.vico.core.marker.DefaultMarkerLabelFormatter
import com.patrykandpatrick.vico.core.model.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.model.columnSeries
import org.breezyweather.R
import org.breezyweather.common.basic.GeoActivity
import org.breezyweather.common.extensions.getFormattedTime
import org.breezyweather.common.extensions.is12Hour
import org.breezyweather.common.extensions.isDarkMode
import org.breezyweather.common.extensions.toDate
import org.breezyweather.common.ui.widgets.precipitationBar.PrecipitationBar
import org.breezyweather.domain.weather.model.getMinutelyDescription
import org.breezyweather.domain.weather.model.getMinutelyTitle
import org.breezyweather.main.utils.MainThemeColorProvider
import org.breezyweather.settings.SettingsManager
import org.breezyweather.theme.ThemeManager
import org.breezyweather.theme.compose.BreezyWeatherTheme
import org.breezyweather.theme.resource.providers.ResourceProvider
import org.breezyweather.theme.weatherView.WeatherViewController
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * TODO:
 * - Improve marker: make always showing, put correct label, initialize the marker on "current time"
 * - Define better values for light/medium/heavy precipitation
 * - Remove bottom start/middle/end time below chart
 * - Check what's the best way to make thick bars (currently we just put a very high random value)
 */
class PrecipitationNowcastViewHolder(
    parent: ViewGroup
) : AbstractMainCardViewHolder(
    LayoutInflater
        .from(parent.context)
        .inflate(R.layout.container_main_precipitation_nowcast_card, parent, false)
) {
    private val minutelyTitle: TextView = itemView.findViewById(R.id.container_main_minutely_card_title)
    private val minutelySubtitle: TextView = itemView.findViewById(R.id.container_main_minutely_card_subtitle)
    private val precipitationBar: PrecipitationBar = itemView.findViewById(R.id.container_main_minutely_card_minutelyBar)
    private val minutelyStartText: TextView = itemView.findViewById(R.id.container_main_minutely_card_minutelyStartText)
    private val minutelyCenterText: TextView = itemView.findViewById(R.id.container_main_minutely_card_minutelyCenterText)
    private val minutelyEndText: TextView = itemView.findViewById(R.id.container_main_minutely_card_minutelyEndText)
    private val minutelyStartLine: View = itemView.findViewById(R.id.container_main_minutely_card_minutelyStartLine)
    private val minutelyEndLine: View = itemView.findViewById(R.id.container_main_minutely_card_minutelyEndLine)
    private val minutelyChartComposeView: ComposeView = itemView.findViewById(R.id.container_main_minutely_chart_composeView)

    override fun onBindView(
        activity: GeoActivity,
        location: Location,
        provider: ResourceProvider,
        listAnimationEnabled: Boolean,
        itemAnimationEnabled: Boolean,
        firstCard: Boolean
    ) {
        super.onBindView(
            activity,
            location,
            provider,
            listAnimationEnabled,
            itemAnimationEnabled,
            firstCard
        )

        val weather = location.weather ?: return
        val colors = ThemeManager
            .getInstance(context)
            .weatherThemeDelegate
            .getThemeColors(
                context,
                WeatherViewController.getWeatherKind(location),
                WeatherViewController.isDaylight(location)
            )

        minutelyTitle.setTextColor(colors[0])
        minutelyTitle.text = weather.getMinutelyTitle(context)
        minutelySubtitle.text = weather.getMinutelyDescription(context, location)

        val minutelyList = weather.minutelyForecast
        minutelyChartComposeView.setContent {
            BreezyWeatherTheme(
                lightTheme = MainThemeColorProvider.isLightTheme(context, location)
            ) {
                ContentView(location)
            }
        }
        minutelyChartComposeView.contentDescription =
            activity.getString(
                R.string.precipitation_between_time,
                minutelyList.first().date.getFormattedTime(location, context, context.is12Hour),
                minutelyList.last().date.getFormattedTime(location, context, context.is12Hour)
            )

        val firstTime = minutelyList.first().date
        val lastTime = Date(minutelyList.last().date.time + 5 * 60 * 1000)
        minutelyStartText.text = firstTime.getFormattedTime(location, context, context.is12Hour)
        minutelyCenterText.text = Date(firstTime.time + (lastTime.time - firstTime.time) / 2).getFormattedTime(location, context, context.is12Hour)
        minutelyEndText.text = lastTime.getFormattedTime(location, context, context.is12Hour)
        minutelyStartText.setTextColor(MainThemeColorProvider.getColor(location, R.attr.colorBodyText))
        minutelyCenterText.setTextColor(MainThemeColorProvider.getColor(location, R.attr.colorBodyText))
        minutelyEndText.setTextColor(MainThemeColorProvider.getColor(location, R.attr.colorBodyText))

        minutelyStartLine.setBackgroundColor(MainThemeColorProvider.getColor(location, com.google.android.material.R.attr.colorOutline))
        minutelyEndLine.setBackgroundColor(MainThemeColorProvider.getColor(location, com.google.android.material.R.attr.colorOutline))

        // TODO: Everything below is deprecated, keeping for tests:
        precipitationBar.precipitationIntensities = minutelyList.map {
            it.precipitationIntensity ?: 0.0
        }.toTypedArray()
        precipitationBar.indicatorGenerator = object : PrecipitationBar.IndicatorGenerator {
            override fun getIndicatorContent(precipitation: Double) =
                SettingsManager
                    .getInstance(activity)
                    .precipitationIntensityUnit
                    .getValueText(activity, precipitation)
        }

        precipitationBar.precipitationColor = ThemeManager
            .getInstance(context)
            .weatherThemeDelegate
            .getThemeColors(
                context,
                WeatherViewController.getWeatherKind(location),
                WeatherViewController.isDaylight(location)
            )[0]
        precipitationBar.subLineColor = MainThemeColorProvider.getColor(location, com.google.android.material.R.attr.colorOutline)
        precipitationBar.highlightColor = MainThemeColorProvider.getColor(location, androidx.appcompat.R.attr.colorPrimary)
        precipitationBar.textColor = MainThemeColorProvider.getColor(location, com.google.android.material.R.attr.colorOnPrimary)
        precipitationBar.setShadowColors(colors[0], colors[1], MainThemeColorProvider.isLightTheme(itemView.context, location))
    }

    @Composable
    private fun ContentView(
        location: Location
    ) {
        val minutely = location.weather!!.minutelyForecastBy5Minutes

        val modelProducer = remember { CartesianChartModelProducer.build() }

        val thresholdLineColor = if (context.isDarkMode) {
            R.color.colorTextGrey
        } else R.color.colorTextGrey2nd

        val axisValueOverrider = AxisValueOverrider.fixed(
            maxY = max(
                Minutely.PRECIPITATION_HEAVY,
                minutely.maxOfOrNull { it.precipitationIntensity ?: 0.0 } ?: 0.0
            ).toFloat()
        )
        val bottomAxisValueFormatter =
            AxisValueFormatter<AxisPosition.Horizontal.Bottom> { x, _, _ ->
                x.roundToLong().times(60).times(1000).times(5).toDate().getFormattedTime(location, context, context.is12Hour)
            }

        LaunchedEffect(location) {
            modelProducer.tryRunTransaction {
                columnSeries {
                    series(
                        x = minutely.map {
                            it.date.time.div(60).div(1000).div(5)
                        },
                        y = minutely.map {
                            it.precipitationIntensity ?: 0
                        }
                    )
                }
            }
        }

        CartesianChartHost(
            rememberCartesianChart(
                rememberColumnCartesianLayer(
                    ColumnCartesianLayer.ColumnProvider.series(
                        rememberLineComponent(
                            color = Color(
                                ThemeManager
                                    .getInstance(context)
                                    .weatherThemeDelegate
                                    .getThemeColors(
                                        context,
                                        WeatherViewController.getWeatherKind(location),
                                        WeatherViewController.isDaylight(location)
                                    )[0]
                            ),
                            thickness = 500.dp
                        )
                    ),
                    axisValueOverrider = axisValueOverrider
                ),
                bottomAxis = rememberBottomAxis(
                    guideline = null,
                    valueFormatter = bottomAxisValueFormatter
                ),
                decorations = listOf(
                    rememberHorizontalLine(
                        y = { Minutely.PRECIPITATION_LIGHT.toFloat() },
                        verticalLabelPosition = VerticalPosition.Bottom,
                        line = rememberLineComponent(
                            color = colorResource(thresholdLineColor)
                        ),
                        labelComponent = rememberTextComponent(
                            color = colorResource(thresholdLineColor)
                        ),
                        label = { "Light" } // TODO: Translation
                    ),
                    rememberHorizontalLine(
                        y = { Minutely.PRECIPITATION_MEDIUM.toFloat() },
                        verticalLabelPosition = VerticalPosition.Bottom,
                        line = rememberLineComponent(
                            color = colorResource(thresholdLineColor)
                        ),
                        labelComponent = rememberTextComponent(
                            color = colorResource(thresholdLineColor)
                        ),
                        label = { "Medium" } // TODO: Translation
                    ),
                    rememberHorizontalLine(
                        y = { Minutely.PRECIPITATION_HEAVY.toFloat() },
                        verticalLabelPosition = VerticalPosition.Bottom,
                        line = rememberLineComponent(
                            color = colorResource(thresholdLineColor)
                        ),
                        labelComponent = rememberTextComponent(
                            color = colorResource(thresholdLineColor)
                        ),
                        label = { "Heavy" } // TODO: Translation
                    )
                )
            ),
            modelProducer,
            marker = rememberMarkerComponent(
                label = rememberTextComponent(
                    color = MaterialTheme.colorScheme.onSurface,
                    background = rememberShapeComponent(
                        Shapes.markerCorneredShape(Corner.FullyRounded), MaterialTheme.colorScheme.surface
                    ).setShadow(
                        radius = LABEL_BACKGROUND_SHADOW_RADIUS_DP,
                        dy = LABEL_BACKGROUND_SHADOW_DY_DP,
                        applyElevationOverlay = true,
                    ),
                    padding = dimensionsOf(8.dp, 4.dp),
                    typeface = Typeface.MONOSPACE,
                    textAlignment = Layout.Alignment.ALIGN_CENTER,
                    minWidth = TextComponent.MinWidth.fixed(40.dp),
                ),
                labelFormatter = remember { DefaultMarkerLabelFormatter() }
            ),
            scrollState = rememberVicoScrollState(scrollEnabled = false)
        )
    }
}

private const val LABEL_BACKGROUND_SHADOW_RADIUS_DP = 4f
private const val LABEL_BACKGROUND_SHADOW_DY_DP = 2f
