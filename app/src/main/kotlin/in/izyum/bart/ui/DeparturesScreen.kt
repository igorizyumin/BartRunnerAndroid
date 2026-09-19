package `in`.izyum.bart.ui

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


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeparturesScreen(
    route: StationPair,
    state: DeparturesViewModel.State,
    alerts: Alert.AlertList? = null,
    isOffline: Boolean = false,
    timeSource: TimeSource,
    fare: String? = null,
    onBack: () -> Unit,
    onOpenTrip: (Departure) -> Unit,
    onMap: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tick = rememberSecondTick(timeSource)
    val originName = route.origin.getName()
    val destinationName = route.destination?.getName()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                title = {
                    val titleStyle = MaterialTheme.typography.titleMedium
                    if (destinationName == null) {
                        Text(
                            originName,
                            style = titleStyle,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    } else {
                        val textMeasurer = rememberTextMeasurer()
                        val density = LocalDensity.current
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val requiredWidth = with(density) {
                                textMeasurer.measure(AnnotatedString(originName), style = titleStyle).size.width
                                    + textMeasurer.measure(AnnotatedString(destinationName), style = titleStyle).size.width
                                    + 22.dp.toPx()
                            }
                            if (requiredWidth <= with(density) { maxWidth.toPx() }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(originName, style = titleStyle, fontWeight = FontWeight.Bold, maxLines = 1)
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward,
                                        contentDescription = null,
                                        modifier = Modifier.padding(horizontal = 2.dp).size(18.dp),
                                    )
                                    Text(destinationName, style = titleStyle, maxLines = 1)
                                }
                            } else {
                                Column(Modifier.fillMaxWidth()) {
                                    Text(
                                        originName,
                                        style = titleStyle,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            Icons.AutoMirrored.Filled.ArrowForward,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                        )
                                        Text(
                                            destinationName,
                                            style = titleStyle,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.padding(start = 4.dp).weight(1f),
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                actions = { IconButton(onClick = onMap) { Icon(Icons.Filled.Map, stringResource(R.string.system_map)) } },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        when (state.status) {
            DeparturesViewModel.Status.CONTENT -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (shouldShowServiceStatus(alerts, isOffline)) {
                    item { ServiceStatusBanner(alerts, isOffline) }
                }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.upcoming_trains), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        if (route.destination != null && fare != null) {
                            FareValue(fare)
                        }
                    }
                }
                items(state.departures, key = { it.identity }) { departure ->
                    DepartureCard(
                        departure = departure,
                        context = context,
                        timeSource = timeSource,
                        tick = tick,
                        onClick = { onOpenTrip(departure) },
                    )
                }
            }
            else -> Column(Modifier.fillMaxSize().padding(padding)) {
                if (shouldShowServiceStatus(alerts, isOffline)) {
                    ServiceStatusBanner(alerts, isOffline)
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    when (state.status) {
                        DeparturesViewModel.Status.LOADING -> Box(
                            Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                        DeparturesViewModel.Status.ERROR -> EmptyState(
                            stringResource(R.string.could_not_connect),
                            stringResource(R.string.try_again_connection),
                            icon = Icons.Filled.Warning,
                            modifier = Modifier.fillMaxSize(),
                        )
                        DeparturesViewModel.Status.EMPTY -> EmptyState(
                            stringResource(R.string.no_departures_found),
                            stringResource(R.string.no_departures_message),
                            icon = Icons.Filled.Schedule,
                            modifier = Modifier.fillMaxSize(),
                        )
                        DeparturesViewModel.Status.CONTENT -> Unit
                    }
                }
            }
        }
    }
}

@Composable
private fun DepartureCard(departure: Departure, context: Context, timeSource: TimeSource, tick: Long, onClick: () -> Unit) {
    val primary = if (departure.isCanceled()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val scheduleDetails = DepartureTextFormatter.departureSchedulePresentation(context, departure)
    val arrivalTime = if (departure.isCanceled()) {
        ""
    } else {
        DepartureTextFormatter.estimatedArrivalTime(context, departure)
    }
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(departure.line)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(
                        departure.getTrainDestinationName().orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        DepartureTextFormatter.trainLengthAndPlatform(context, departure).ifBlank { stringResource(R.string.bart_train) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(DepartureTextFormatter.countdown(context, departure, tick), color = primary, fontWeight = FontWeight.Bold)
                    if (scheduleDetails.isNotBlank()) {
                        ScheduleDetailsRow(
                            details = scheduleDetails,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
            }
            if (departure.hasTransfers() || arrivalTime.isNotBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (departure.hasTransfers()) {
                        Row(
                            modifier = Modifier.weight(1f).padding(start = 22.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.SwapVert, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                            Text(
                                pluralStringResource(R.plurals.transfer_count, departure.tripLegs.size - 1, departure.tripLegs.size - 1),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    } else {
                        Spacer(Modifier.weight(1f))
                    }
                    if (arrivalTime.isNotBlank()) {
                        Text(
                            stringResource(R.string.arrives_at_time, arrivalTime),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyState(
    title: String,
    message: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text(
            message,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

