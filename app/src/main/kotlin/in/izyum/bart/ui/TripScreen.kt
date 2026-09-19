package `in`.izyum.bart.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripScreen(
    departure: Itinerary?,
    route: StationPair?,
    alerts: Alert.AlertList? = null,
    isOffline: Boolean = false,
    fare: String? = null,
    isFollowingInitially: Boolean,
    alarmVisible: Boolean,
    timeSource: TimeSource,
    alarmPending: Boolean,
    alarmLeadTimeMinutes: Int,
    onBack: () -> Unit,
    onFollow: (Itinerary) -> Unit,
    onSetAlarm: (Int) -> Unit,
    onCancelAlarm: () -> Unit,
    onClear: () -> Unit,
    onShare: (Itinerary) -> Unit,
    onSilenceAlarm: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tick = rememberSecondTick(timeSource)
    var following by remember(isFollowingInitially) { mutableStateOf(isFollowingInitially) }
    var showAlarm by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    val current = departure
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = { Text(stringResource(if (following) R.string.trip_in_progress else R.string.trip_details), fontWeight = FontWeight.Bold) },
                actions = {
                    if (current != null) IconButton(onClick = { onShare(current) }) { Icon(Icons.Filled.Share, stringResource(R.string.share_arrival)) }
                    if (following && current != null) IconButton(onClick = { showClear = true }) { Icon(Icons.Filled.Close, stringResource(R.string.stop_following)) }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        if (current == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            val pair = current.getStationPair()
            LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                if (shouldShowServiceStatus(alerts, isOffline)) {
                    item { ServiceStatusBanner(alerts, isOffline) }
                }
                item {
                    TripHero(current, route ?: pair, fare, timeSource, tick)
                }
                if (!following) {
                    item { Button(onClick = { following = true; onFollow(current) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Train, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.follow_this_trip)) } }
                } else {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (alarmPending) {
                                FilledTonalButton(onClick = onCancelAlarm, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Alarm, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.alarm_minutes, alarmLeadTimeMinutes)) }
                            } else if (current.getInitialDepartureTime(pessimistic = true) - tick > 60_000L) {
                                OutlinedButton(onClick = { showAlarm = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Alarm, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.set_alarm)) }
                            }
                            OutlinedButton(onClick = { showClear = true }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.end_trip)) }
                        }
                    }
                }
                item { TripTimeline(current, tick) }
            }
        }
    }
    if (showAlarm && current != null) {
        AlarmPickerDialog(current, timeSource, onDismiss = { showAlarm = false }) { value -> showAlarm = false; onSetAlarm(value) }
    }
    if (showClear) {
        AlertDialog(onDismissRequest = { showClear = false }, title = { Text(stringResource(R.string.stop_following_title)) }, text = { Text(stringResource(R.string.stop_following_message)) }, confirmButton = { TextButton(onClick = { showClear = false; onClear() }) { Text(stringResource(R.string.stop_following)) } }, dismissButton = { TextButton(onClick = { showClear = false }) { Text(stringResource(R.string.cancel)) } })
    }
    if (alarmVisible) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.your_train_leaving_soon)) },
            confirmButton = { TextButton(onClick = onSilenceAlarm) { Text(stringResource(R.string.silence_alarm)) } },
        )
    }
}

@Composable
private fun TripHero(departure: Itinerary, pair: StationPair?, fare: String?, timeSource: TimeSource, tick: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val origin = pair?.origin?.getName() ?: departure.origin.getName()
    val destination = pair?.destination?.getName() ?: departure.destination.getName()
    val status = tripStatus(context, departure, timeSource, tick)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(status.title, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            status.subtitle?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(stringResource(R.string.route_arrow, origin, destination), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric(Icons.Filled.AccessTime, stringResource(R.string.departs), DepartureTextFormatter.estimatedDepartureTime(context, departure).ifBlank { "—" }, Modifier.weight(1f))
                Metric(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.arrives), DepartureTextFormatter.estimatedArrivalTime(context, departure).ifBlank { "—" }, Modifier.weight(1f))
                if (pair?.destination != null) {
                    FareMetric(fare ?: "—", Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun Metric(icon: ImageVector, label: String, value: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Column(Modifier.padding(start = 7.dp)) { Text(label, style = MaterialTheme.typography.labelSmall); Text(value, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
internal fun FareValue(value: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            painter = painterResource(R.drawable.ic_credit_card),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Text(value, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 7.dp))
    }
}

@Composable
internal fun CompactFareValue(value: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            painter = painterResource(R.drawable.ic_credit_card),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
internal fun ScheduleDetailsRow(
    details: DepartureTextFormatter.ScheduleDetails,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    showActualTime: Boolean = true,
) {
    if (!details.isNotBlank()) return
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        var hasContent = false
        details.scheduledTime?.let { scheduledTime ->
            Icon(
                painter = painterResource(R.drawable.ic_scheduled_time),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp),
            )
            Text(scheduledTime, style = style, color = color, maxLines = 1, modifier = Modifier.padding(start = 3.dp))
            hasContent = true
        }
        val hasActualContent = details.showActualIcon
            || details.showScheduleOnlyIcon
            || (showActualTime && details.actualTime != null)
            || details.actualLabel != null
            || details.predictionLabel != null
        if (hasActualContent) {
            if (hasContent) {
                Text("·", style = style, color = color, modifier = Modifier.padding(horizontal = 4.dp))
            }
            if (details.showActualIcon) {
                Icon(
                    painter = painterResource(R.drawable.ic_actual_time),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            if (details.showScheduleOnlyIcon) {
                Icon(
                    painter = painterResource(R.drawable.ic_schedule_only),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(14.dp),
                )
            }
            details.actualTime?.takeIf { showActualTime }?.let { actualTime ->
                Text(actualTime, style = style, color = color, maxLines = 1)
            }
            details.actualLabel?.let { actualLabel ->
                Text(
                    actualLabel,
                    style = style,
                    color = if (details.isPositiveDelay) MaterialTheme.colorScheme.error else color,
                    maxLines = 1,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
            details.predictionLabel?.let { predictionLabel ->
                Text(predictionLabel, style = style, color = color, maxLines = 1, modifier = Modifier.padding(start = 3.dp))
            }
        }
    }
}

@Composable
private fun FareMetric(value: String, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Icon(
            painter = painterResource(R.drawable.ic_credit_card),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(Modifier.padding(start = 7.dp)) {
            Text(stringResource(R.string.fare), style = MaterialTheme.typography.labelSmall)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun TrainDestinationLabel(
    destination: String,
    style: androidx.compose.ui.text.TextStyle,
    color: Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_train_destination),
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp),
        )
        Text(
            destination,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun TripTimeline(departure: Itinerary, tick: Long) {
    val legs = departure.legs
    if (legs.isEmpty()) {
        OutlinedCard { Text(stringResource(R.string.detailed_stop_times_unavailable), modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val now = tick
    val currentIndex = legs.indexOfFirst { leg -> !legHasPassed(leg, now) }.let { if (it < 0) legs.lastIndex else it }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.trip_timeline), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        legs.forEachIndexed { index, leg ->
            TimelineLeg(leg, index == currentIndex, now)
            if (index < legs.lastIndex) ConnectionRow(leg, legs[index + 1], now)
        }
    }
}

@Composable
private fun TimelineLeg(leg: TripLeg, current: Boolean, now: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val unavailableTime = stringResource(R.string.station_time_unavailable)
    val lineColor = lineColor(leg.line)
    val tileColor = when {
        current -> MaterialTheme.colorScheme.surfaceContainerHighest
        androidx.compose.foundation.isSystemInDarkTheme() -> MaterialTheme.colorScheme.surfaceContainer
        else -> LightConnectingTrainTile
    }
    Card(colors = CardDefaults.cardColors(containerColor = tileColor), border = if (current) androidx.compose.foundation.BorderStroke(1.dp, lineColor) else null) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(leg.line)
                Column(Modifier.padding(start = 8.dp)) {
                    if (current) {
                        Text(stringResource(R.string.trip_current_train), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        leg.trainDestination?.getName() ?: leg.destination?.getName() ?: stringResource(R.string.train_label),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text(stringResource(R.string.route_arrow, leg.origin?.getName().orEmpty(), leg.destination?.getName().orEmpty()), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            if (!departureHasPassed(leg.departureTime, leg.scheduledDepartureTime, now)) {
                ScheduleDetailsRow(
                    details = DepartureTextFormatter.legSchedulePresentation(context, leg),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    showActualTime = false,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (leg.stops.isEmpty()) {
                val departure = if (leg.departureTime > 0L) formatTime(leg.departureTime)
                else unavailableTime
                val arrival = if (leg.arrivalTime > 0L) formatTime(leg.arrivalTime)
                else unavailableTime
                Text(stringResource(R.string.departure_arrival_times, departure, arrival), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            } else {
                val partialUpdate = legHasPartialUpdate(leg)
                var previousReached = true
                Column(Modifier.padding(top = 10.dp)) {
                    leg.stops.forEachIndexed { index, stop ->
                        val displayTime = stop.departureTime
                        val reachedByTime = displayTime > 0L && displayTime <= now
                        val reached = reachedByTime && (!partialUpdate || previousReached)
                        StationTimelineRow(
                            stop = stop,
                            now = now,
                            color = lineColor,
                            isFirst = index == 0,
                            isLast = index == leg.stops.lastIndex,
                            reached = reached,
                            noData = partialUpdate && reachedByTime && !reached,
                        )
                        previousReached = reached
                    }
                }
            }
        }
    }
}

@Composable
private fun StationTimelineRow(
    stop: TripStop,
    now: Long,
    color: Color,
    isFirst: Boolean,
    isLast: Boolean,
    reached: Boolean,
    noData: Boolean,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val unavailableEta = stringResource(R.string.eta_unavailable)
    val noDataText = stringResource(R.string.timeline_no_data)
    val name = stop.station?.getName().orEmpty()
    val displayTime = stop.departureTime
    Row(Modifier.fillMaxWidth().height(54.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
            if (!isFirst) {
                Box(Modifier.width(2.dp).height(21.dp).align(Alignment.TopCenter).background(color.copy(alpha = 0.35f)))
            }
            if (!isLast) {
                Box(Modifier.width(2.dp).height(21.dp).align(Alignment.BottomCenter).background(color.copy(alpha = 0.35f)))
            }
            if (reached) {
                Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(color))
            } else {
                Box(Modifier.size(12.dp).clip(androidx.compose.foundation.shape.CircleShape).background(MaterialTheme.colorScheme.surface).then(Modifier.border(2.dp, color, androidx.compose.foundation.shape.CircleShape)))
            }
        }
        Text(name, Modifier.weight(1f).padding(start = 10.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Column(horizontalAlignment = Alignment.End) {
            Text(
                if (noData) noDataText
                else if (displayTime > 0L) etaText(context, displayTime, now)
                else unavailableEta,
                style = MaterialTheme.typography.bodySmall,
                color = if (reached) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!departureHasPassed(stop.departureTime, stop.scheduledDepartureTime, now)) {
                val scheduleDetails = DepartureTextFormatter.stopSchedulePresentation(
                    context,
                    stop,
                    departure = true,
                )
                if (scheduleDetails.isNotBlank()) {
                    ScheduleDetailsRow(
                        details = scheduleDetails,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        showActualTime = false,
                    )
                }
            }
        }
    }
}

private fun departureHasPassed(
    effectiveTime: Long,
    scheduledTime: Long,
    now: Long,
): Boolean = when {
    effectiveTime > 0L -> effectiveTime <= now
    scheduledTime > 0L -> scheduledTime <= now
    else -> false
}

private fun legHasPartialUpdate(leg: TripLeg): Boolean {
    val hasRealtime = leg.stops.any {
        it.arrivalSource == PredictionSource.REALTIME
            || it.departureSource == PredictionSource.REALTIME
    }
    val hasNonRealtime = leg.stops.any {
        it.arrivalSource != PredictionSource.REALTIME
            || it.departureSource != PredictionSource.REALTIME
    }
    return hasRealtime && hasNonRealtime
}

@Composable
private fun ConnectionRow(arriving: TripLeg, next: TripLeg, now: Long) {
    val arrival = arriving.stops.lastOrNull()?.arrivalTime ?: arriving.arrivalTime
    val departure = next.stops.firstOrNull()?.departureTime ?: next.departureTime
    val margin = departure - arrival
    val warning = margin < 0 || (arriving.minimumTransferSecondsAfter > 0 && margin < arriving.minimumTransferSecondsAfter * 1000L)
    val unavailableDeparture = stringResource(R.string.next_departure_unavailable)
    val connectionText = if (departure <= 0) {
        unavailableDeparture
    } else {
        val arrivalText = stringResource(R.string.arrives_at_time, formatTime(arrival))
        val marginText = if (margin < 0) {
            stringResource(R.string.connection_missed)
        } else {
            stringResource(R.string.connection_margin, margin / 60000)
        }
        stringResource(R.string.station_eta, arrivalText, marginText)
    }
    Row(Modifier.fillMaxWidth().padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(48.dp).background(if (warning) Warning else MaterialTheme.colorScheme.outline))
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(R.string.transfer_at, arriving.destination?.getName().orEmpty()), fontWeight = FontWeight.SemiBold, color = if (warning) Warning else MaterialTheme.colorScheme.primary)
            next.platform?.takeIf { it.isNotBlank() }?.let { platform ->
                Text(
                    stringResource(
                        R.string.board_train_at_platform,
                        next.line?.getDisplayName() ?: stringResource(R.string.train_label),
                        platform,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (warning) Warning else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text(
                connectionText,
                style = MaterialTheme.typography.bodySmall,
                color = if (warning) Warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun LineBadge(line: Line?) {
    val color = lineColor(line)
    val contentColor = if (line == Line.YELLOW || line == Line.ORANGE || line == Line.PURPLE) Color(0xFF1B1B1B) else Color.White
    Box(
        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(line?.getDisplayName()?.take(1) ?: "B", color = contentColor, fontSize = 22.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AlarmPickerDialog(departure: Itinerary, timeSource: TimeSource, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val max = (((departure.getInitialDepartureTime(pessimistic = true) - timeSource.nowMillis()) / 60_000L).toInt())
        .coerceAtLeast(1)
    var value by remember { mutableIntStateOf(5.coerceAtMost(max)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.set_departure_alarm)) }, text = {
        Column {
            Text(stringResource(R.string.notify_before_train_arrives), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pluralStringResource(R.plurals.alarm_minutes_value, value, value), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Slider(value = value.toFloat(), onValueChange = { value = it.toInt().coerceIn(1, max) }, valueRange = 1f..max.toFloat(), steps = (max - 2).coerceAtLeast(0))
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(stringResource(R.string.set_alarm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}
