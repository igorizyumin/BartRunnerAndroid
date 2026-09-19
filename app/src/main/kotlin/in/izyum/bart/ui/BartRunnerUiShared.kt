package `in`.izyum.bart.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Elevator
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.core.content.edit
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font as DownloadableFont
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import android.widget.ImageView
import android.view.View
import android.webkit.WebView
import android.net.Uri
import java.util.Calendar
import `in`.izyum.bart.R
import `in`.izyum.bart.activities.DeparturesViewModel
import `in`.izyum.bart.activities.RoutesUiState
import `in`.izyum.bart.model.Alert
import `in`.izyum.bart.model.Departure
import `in`.izyum.bart.model.Itinerary
import `in`.izyum.bart.model.Line
import `in`.izyum.bart.model.PredictionSource
import `in`.izyum.bart.model.Station
import `in`.izyum.bart.model.StationPair
import `in`.izyum.bart.model.TimeSource
import `in`.izyum.bart.model.TripLeg
import `in`.izyum.bart.model.TripStop
import `in`.izyum.bart.networktasks.RiderCategory
import `in`.izyum.bart.presentation.DepartureTextFormatter
import `in`.izyum.bart.presentation.DurationTextFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

private val Blue = Color(0xFF0B63CE)
private val BlueDark = Color(0xFF064B9B)
private val BlueLight = Color(0xFFE8F1FF)
private val Teal = Color(0xFF006B6B)
private val SuccessGreen = Color(0xFF2E7D32)
private val SuccessGreenContainer = Color(0xFFDDF5E3)
internal val Warning = Color(0xFFB3261E)
private val OfflineYellowContainer = Color(0xFFFFF3CD)
private val OfflineOnYellowContainer = Color(0xFF5A4300)
private val DarkOfflineYellowContainer = Color(0xFF4D3F00)
private val DarkOfflineOnYellowContainer = Color(0xFFFFE082)
internal val LightConnectingTrainTile = Color(0xFFE6E6E6)
private val LightYellowLine = Color(0xFFE6C400)

private val RobotoGoogleFont = GoogleFont("Roboto")
@SuppressLint("PrivateResource")
private val RobotoFontProvider = GoogleFont.Provider(
    providerAuthority = "com.google.android.gms.fonts",
    providerPackage = "com.google.android.gms",
    certificates = androidx.compose.ui.text.googlefonts.R.array.com_google_android_gms_fonts_certs,
)
private val RobotoFontFamily = FontFamily(
    DownloadableFont(
        googleFont = RobotoGoogleFont,
        fontProvider = RobotoFontProvider,
        weight = FontWeight.Normal,
    ),
    DownloadableFont(
        googleFont = RobotoGoogleFont,
        fontProvider = RobotoFontProvider,
        weight = FontWeight.Medium,
    ),
    DownloadableFont(
        googleFont = RobotoGoogleFont,
        fontProvider = RobotoFontProvider,
        weight = FontWeight.Bold,
    ),
)

private fun androidx.compose.material3.Typography.withFontFamily(fontFamily: FontFamily) = copy(
    displayLarge = displayLarge.copy(fontFamily = fontFamily),
    displayMedium = displayMedium.copy(fontFamily = fontFamily),
    displaySmall = displaySmall.copy(fontFamily = fontFamily),
    headlineLarge = headlineLarge.copy(fontFamily = fontFamily),
    headlineMedium = headlineMedium.copy(fontFamily = fontFamily),
    headlineSmall = headlineSmall.copy(fontFamily = fontFamily),
    titleLarge = titleLarge.copy(fontFamily = fontFamily),
    titleMedium = titleMedium.copy(fontFamily = fontFamily),
    titleSmall = titleSmall.copy(fontFamily = fontFamily),
    bodyLarge = bodyLarge.copy(fontFamily = fontFamily),
    bodyMedium = bodyMedium.copy(fontFamily = fontFamily),
    bodySmall = bodySmall.copy(fontFamily = fontFamily),
    labelLarge = labelLarge.copy(fontFamily = fontFamily),
    labelMedium = labelMedium.copy(fontFamily = fontFamily),
    labelSmall = labelSmall.copy(fontFamily = fontFamily),
)

@Composable
fun BartRunnerTheme(content: @Composable () -> Unit) {
    val light = androidx.compose.material3.lightColorScheme(
        primary = Blue,
        onPrimary = Color.White,
        primaryContainer = BlueLight,
        onPrimaryContainer = BlueDark,
        secondary = Teal,
        tertiary = SuccessGreen,
        onTertiary = Color.White,
        tertiaryContainer = SuccessGreenContainer,
        onTertiaryContainer = Color(0xFF0D3B1E),
        surface = Color(0xFFF8FAFD),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF7F7F7),
        surfaceContainer = Color(0xFFF0F0F0),
        surfaceContainerHigh = Color(0xFFEBEBEB),
        surfaceContainerHighest = LightConnectingTrainTile,
        background = Color(0xFFF8FAFD),
        error = Warning,
    )
    val dark = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFFA9C7FF),
        onPrimary = Color(0xFF003062),
        primaryContainer = Color(0xFF174A83),
        onPrimaryContainer = Color(0xFFD7E3FF),
        secondary = Color(0xFF7DD9D4),
        tertiary = Color(0xFF8BD5A0),
        onTertiary = Color(0xFF00391B),
        tertiaryContainer = Color(0xFF20552F),
        onTertiaryContainer = Color(0xFFA6F2B8),
        surface = Color(0xFF101419),
        background = Color(0xFF101419),
    )
    androidx.compose.material3.MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) dark else light,
        typography = androidx.compose.material3.Typography().withFontFamily(RobotoFontFamily),
        content = content,
    )
}

@Composable
internal fun OfflineBanner(modifier: Modifier = Modifier) {
    val isDarkTheme = androidx.compose.foundation.isSystemInDarkTheme()
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = if (isDarkTheme) DarkOfflineYellowContainer else OfflineYellowContainer,
        contentColor = if (isDarkTheme) DarkOfflineOnYellowContainer else OfflineOnYellowContainer,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.offline_in_app_message),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}


@Composable
internal fun lineColor(line: Line?): Color = when (line) {
    Line.RED -> Color(0xFFFF0000)
    Line.ORANGE -> Color(0xFFFF9933)
    Line.YELLOW ->
        if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFFFFF33)
        else LightYellowLine
    Line.BLUE -> Color(0xFF0099CC)
    Line.GREEN -> Color(0xFF339933)
    Line.PURPLE -> Color(0xFFD5CFA3)
    else -> Color(0xFF546E7A)
}

internal data class TripStatusPresentation(val title: String, val subtitle: String? = null)

internal fun tripStatus(context: Context, departure: Itinerary, timeSource: TimeSource, now: Long): TripStatusPresentation {
    if (departure.canceled) return TripStatusPresentation(context.getString(R.string.trip_canceled))

    val initialArrival = departure.getInitialArrivalTime(pessimistic = true)
    val initialDeparture = departure.getInitialDepartureTime(pessimistic = true)
    val initialStation = departure.origin.getName()
    if (now < initialArrival - STATION_ARRIVAL_NOTICE_MILLIS) {
        return TripStatusPresentation(
            context.getString(R.string.trip_arrives_in, countdownText(initialArrival, now)),
        )
    }
    if (now < initialArrival) {
        return TripStatusPresentation(context.getString(R.string.trip_arriving_at, initialStation))
    }
    if (now < initialDeparture) {
        val initialDwell = initialDeparture - initialArrival
        if (initialDwell > LONG_INITIAL_DWELL_MILLIS
            && now < initialDeparture - STATION_DEPARTURE_NOTICE_MILLIS
        ) {
            return TripStatusPresentation(
                context.getString(
                    R.string.trip_departs_in,
                    countdownText(initialDeparture, now),
                ),
            )
        }
        return TripStatusPresentation(context.getString(R.string.trip_leaving_station, initialStation))
    }

    departure.legs.forEachIndexed { index, leg ->
        val nextLeg = departure.legs.getOrNull(index + 1)
        val transferStation = nextLeg?.origin ?: leg.destination
        val stops = if (leg.stops.isNotEmpty()) leg.stops.drop(1) else emptyList()
        stops.forEach { stop ->
            val arrival = stop.arrivalTime
            if (arrival <= 0L) return@forEach
            if (now < arrival) {
                if (now >= arrival - STATION_ARRIVAL_NOTICE_MILLIS) {
                    return TripStatusPresentation(
                        context.getString(R.string.trip_arriving_at, stop.station?.getName().orEmpty()),
                        nextLeg?.takeIf { stop.station == transferStation }?.let {
                            context.getString(
                                R.string.trip_transfer_to,
                                it.line?.getDisplayName() ?: context.getString(R.string.train_label),
                            )
                        },
                    )
                }
                return TripStatusPresentation(
                    context.getString(
                        R.string.trip_next_stop,
                        stop.station?.getName().orEmpty(),
                        etaText(context, arrival, now),
                    ),
                )
            }
            if (nextLeg == null && stop.station == leg.destination) {
                return@forEach
            }
            if (stop.station == transferStation && nextLeg != null) {
                val nextDeparture = nextLeg.departureTime
                if (nextDeparture > now) {
                    return transferNowStatus(context, nextLeg, transferStation, now)
                }
            } else if (stop.departureTime > now) {
                return TripStatusPresentation(context.getString(R.string.trip_leaving_station, stop.station?.getName().orEmpty()))
            }
        }

        if (leg.stops.isEmpty() && leg.arrivalTime > 0L) {
            val arrival = leg.arrivalTime
            if (now < arrival) {
                if (now >= arrival - STATION_ARRIVAL_NOTICE_MILLIS) {
                    return TripStatusPresentation(
                        context.getString(R.string.trip_arriving_at, leg.destination?.getName().orEmpty()),
                        nextLeg?.let {
                            context.getString(
                                R.string.trip_transfer_to,
                                it.line?.getDisplayName() ?: context.getString(R.string.train_label),
                            )
                        },
                    )
                }
                return TripStatusPresentation(
                    context.getString(
                        R.string.trip_next_stop,
                        leg.destination?.getName().orEmpty(),
                        etaText(context, arrival, now),
                    ),
                )
            }
            if (nextLeg != null && nextLeg.departureTime > now) {
                return transferNowStatus(context, nextLeg, leg.destination, now)
            }
        }
    }
    if (departure.getEstimatedArrivalTime() in 1..now) return TripStatusPresentation(context.getString(R.string.trip_arrived))
    return TripStatusPresentation(context.getString(R.string.trip_current_train))
}

private fun transferNowStatus(
    context: Context,
    connectingLeg: TripLeg,
    transferStation: Station?,
    now: Long,
): TripStatusPresentation {
    val line = connectingLeg.line?.getDisplayName() ?: context.getString(R.string.train_label)
    val platform = connectingLeg.platform?.takeIf { it.isNotBlank() }
        ?: "—"
    val arrival = connectingLeg.stops
        .firstOrNull { it.station == transferStation }
        ?.arrivalTime
        ?.takeIf { it > 0L }
        ?: connectingLeg.departureTime.takeIf { it > 0L }
    val subtitle = if (arrival != null && arrival > now) {
        context.getString(
            R.string.trip_connecting_train_arriving_in,
            countdownText(arrival, now),
        )
    } else {
        context.getString(R.string.trip_connecting_train_arrived)
    }
    return TripStatusPresentation(
        context.getString(R.string.trip_transfer_now_title, line, platform),
        subtitle,
    )
}

private const val STATION_ARRIVAL_NOTICE_MILLIS = 45_000L
private const val STATION_DEPARTURE_NOTICE_MILLIS = 45_000L
private const val LONG_INITIAL_DWELL_MILLIS = 60_000L

internal fun legHasPassed(leg: TripLeg, now: Long): Boolean = if (leg.stops.isNotEmpty()) leg.stops.all { it.arrivalTime > 0 && it.arrivalTime <= now } else leg.arrivalTime > 0 && leg.arrivalTime <= now

internal fun etaText(context: Context, time: Long, now: Long): String {
    if (time <= 0) return context.getString(R.string.eta_unavailable)
    val seconds = (time - now) / 1000
    if (seconds <= 0) return context.getString(R.string.passed)
    return context.getString(R.string.eta_in, DurationTextFormatter.clock(seconds))
}

internal fun countdownText(time: Long, now: Long): String {
    val seconds = ((time - now) / 1000L).coerceAtLeast(0L)
    return DurationTextFormatter.clock(seconds)
}

internal fun formatTime(time: Long): String = if (time <= 0) "—" else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

@Composable
internal fun rememberSecondTick(timeSource: TimeSource): Long {
    var tick by remember(timeSource) { mutableLongStateOf(timeSource.nowMillis()) }
    LaunchedEffect(timeSource) {
        while (isActive) {
            tick = timeSource.nowMillis()
            delay(1000)
        }
    }
    return tick
}
