package com.dougkeen.bart.ui

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dougkeen.bart.BartRunnerApplication
import com.dougkeen.bart.R
import com.dougkeen.bart.activities.DeparturesViewModel
import com.dougkeen.bart.activities.RoutesUiState
import com.dougkeen.bart.model.Departure
import com.dougkeen.bart.model.Line
import com.dougkeen.bart.model.Station
import com.dougkeen.bart.model.StationPair
import com.dougkeen.bart.model.TimeSource
import com.dougkeen.bart.model.TripLeg
import com.dougkeen.bart.presentation.DepartureTextFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val Blue = Color(0xFF0B63CE)
private val BlueDark = Color(0xFF064B9B)
private val BlueLight = Color(0xFFE8F1FF)
private val Teal = Color(0xFF006B6B)
private val Warning = Color(0xFFB3261E)

@Composable
fun BartRunnerTheme(content: @Composable () -> Unit) {
    val light = androidx.compose.material3.lightColorScheme(
        primary = Blue,
        onPrimary = Color.White,
        primaryContainer = BlueLight,
        onPrimaryContainer = BlueDark,
        secondary = Teal,
        surface = Color(0xFFF8FAFD),
        surfaceContainer = Color.White,
        background = Color(0xFFF8FAFD),
        error = Warning,
    )
    val dark = androidx.compose.material3.darkColorScheme(
        primary = Color(0xFFA9C7FF),
        onPrimary = Color(0xFF003062),
        primaryContainer = Color(0xFF174A83),
        onPrimaryContainer = Color(0xFFD7E3FF),
        secondary = Color(0xFF7DD9D4),
        surface = Color(0xFF101419),
        background = Color(0xFF101419),
    )
    androidx.compose.material3.MaterialTheme(
        colorScheme = if (androidx.compose.foundation.isSystemInDarkTheme()) dark else light,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: RoutesUiState,
    followedTrip: Departure?,
    timeSource: TimeSource,
    onRouteSelected: (StationPair) -> Unit,
    onAddFavorite: (StationPair) -> Unit,
    onRemoveFavorite: (StationPair) -> Unit,
    onViewTrip: (Departure) -> Unit,
    onViewMap: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    var pickerAddsFavorite by remember { mutableStateOf(false) }
    var showDelete by remember { mutableStateOf<StationPair?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val tick = rememberSecondTick()
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("BART Runner", fontWeight = FontWeight.Bold)
                         Text("Plan a smoother ride", style = MaterialTheme.typography.labelMedium)
                    }
                },
                actions = {
                    IconButton(onClick = onViewMap) {
                        Icon(Icons.Filled.Map, contentDescription = "System map")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Button(onClick = { pickerAddsFavorite = false; showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.DirectionsSubway, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Plan a trip")
                }
            }
            if (followedTrip != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onViewTrip(followedTrip) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Train, "Trip in progress", tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(25.dp))
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text("Trip in progress", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "${followedTrip.origin?.getName().orEmpty()} → ${(followedTrip.passengerDestination ?: followedTrip.trainDestination)?.getName().orEmpty()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
            if (state.alertKind != RoutesUiState.AlertKind.HIDDEN) {
                item { ServiceAlert(state, context) }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("Saved trips", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickerAddsFavorite = true; showPicker = true }) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add")
                    }
                }
            }
            if (state.isLoading) {
                item {
                    Box(Modifier.fillMaxWidth().padding(28.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else if (state.favorites.isEmpty()) {
                item { EmptyFavorites(onAdd = { pickerAddsFavorite = true; showPicker = true }) }
            } else {
                items(state.favorites, key = { it.toString() }) { route ->
                    FavoriteRouteCard(
                        route = route,
                        departure = state.firstDepartures[route],
                        timeSource = timeSource,
                        tick = tick,
                        onClick = { onRouteSelected(route) },
                        onDelete = { showDelete = route },
                    )
                }
            }
        }
    }

    if (showPicker) {
        RoutePickerDialog(
            title = if (pickerAddsFavorite) "Save a trip" else "Plan a trip",
            showReturn = pickerAddsFavorite,
            onDismiss = { showPicker = false },
            onConfirm = { route, addReturn ->
                showPicker = false
                if (pickerAddsFavorite) {
                    onAddFavorite(route)
                    if (addReturn && route.destination != null) onAddFavorite(StationPair(route.destination, route.origin))
                }
                onRouteSelected(route)
            },
        )
    }
    showDelete?.let { route ->
        AlertDialog(
            onDismissRequest = { showDelete = null },
            title = { Text("Remove saved trip?") },
            text = { Text("${route.origin?.getName()} → ${route.destination?.getName()}") },
            confirmButton = {
                TextButton(onClick = {
                    onRemoveFavorite(route)
                    showDelete = null
                    scope.launch { snackbar.showSnackbar("Trip removed") }
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { showDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun ServiceAlert(state: RoutesUiState, context: Context) {
    val isGood = state.alertKind == RoutesUiState.AlertKind.NO_DELAYS
    val messages = state.alerts?.getAlerts()?.joinToString("\n\n") { it.description.orEmpty() }.orEmpty()
    var expanded by remember { mutableStateOf(false) }
    val containerColor = if (isGood) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer
    val contentColor = if (isGood) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer
    val accentColor = if (isGood) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Card(
        modifier = Modifier.clickable(enabled = !isGood) { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isGood) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = accentColor)
            Spacer(Modifier.width(10.dp))
            Text(
                text = if (isGood) context.getString(R.string.no_delays_reported) else messages.ifBlank { "Service advisory" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded || isGood) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!isGood) {
                Icon(
                    if (expanded) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowForward,
                    "Expand service alert",
                    tint = accentColor,
                    modifier = Modifier.padding(start = 8.dp).size(18.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyFavorites(onAdd: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Filled.Schedule, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(38.dp))
            Text("Your favorite trips will appear here", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Text("Save the routes you ride most often for a quick departure check.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            TextButton(onClick = onAdd, modifier = Modifier.padding(top = 8.dp)) { Text("Save a route") }
        }
    }
}

@Composable
private fun FavoriteRouteCard(route: StationPair, departure: Departure?, timeSource: TimeSource, tick: Long, onClick: () -> Unit, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(route.origin?.getName().orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(24.dp).padding(vertical = 4.dp)) { HorizontalDivider(color = MaterialTheme.colorScheme.primary, thickness = 2.dp) }
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Text(route.destination?.getName() ?: "Any destination", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 5.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Remove saved trip", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            if (departure != null) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineBadge(departure.line)
                    Text(departure.getTrainDestinationName().orEmpty(), modifier = Modifier.padding(start = 8.dp).weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(DepartureTextFormatter.countdown(androidx.compose.ui.platform.LocalContext.current, departure, tick), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        Text(DepartureTextFormatter.estimatedDepartureTime(androidx.compose.ui.platform.LocalContext.current, departure), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                Text("Loading upcoming trains…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePickerDialog(title: String, showReturn: Boolean, onDismiss: () -> Unit, onConfirm: (StationPair, Boolean) -> Unit) {
    val stations = remember { Station.getStationList() }
    var origin by remember { mutableStateOf(stations.firstOrNull()) }
    var destination by remember { mutableStateOf<Station?>(null) }
    var addReturn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StationMenu("From", origin, stations, { origin = it }, allowAny = false)
                IconButton(enabled = destination != null, onClick = { val old = origin; origin = destination; destination = old }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Filled.SwapVert, "Swap stations") }
                StationMenu("To", destination, stations, { destination = it }, allowAny = true)
                if (showReturn) {
                    CheckboxRow("Also save the return trip", addReturn) { addReturn = it }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (origin == null) error = "Choose an origin station."
                else if (destination == origin) error = "Origin and destination must be different."
                else onConfirm(StationPair(origin, destination), addReturn)
            }) { Text("Continue") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StationMenu(label: String, selected: Station?, stations: List<Station>, onSelected: (Station?) -> Unit, allowAny: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(selected?.getName() ?: "Any destination", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowAny) DropdownMenuItem(text = { Text("Any destination") }, onClick = { onSelected(null); expanded = false })
            stations.forEach { station -> DropdownMenuItem(text = { Text(station.getName()) }, onClick = { onSelected(station); expanded = false }) }
        }
    }
}

@Composable
private fun CheckboxRow(text: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }) {
        androidx.compose.material3.Checkbox(checked, onCheckedChange)
        Text(text)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeparturesScreen(
    route: StationPair,
    state: DeparturesViewModel.State,
    timeSource: TimeSource,
    onBack: () -> Unit,
    onOpenTrip: (Departure) -> Unit,
    onFollowTrip: (Departure) -> Unit,
    onMap: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tick = rememberSecondTick()
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Column { Text("Departures", fontWeight = FontWeight.Bold); Text(routeTitle(route), style = MaterialTheme.typography.labelMedium) } },
                actions = { IconButton(onClick = onMap) { Icon(Icons.Filled.Map, "System map") } },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        when (state.status) {
            DeparturesViewModel.Status.LOADING -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            DeparturesViewModel.Status.ERROR -> EmptyState("Could not connect to BART services.", "Try again when you have a connection.", modifier = Modifier.padding(padding))
            DeparturesViewModel.Status.EMPTY -> EmptyState("No departures found", "This route may require a temporary or non-standard transfer.", modifier = Modifier.padding(padding))
            DeparturesViewModel.Status.CONTENT -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Text("Upcoming trains", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold) }
                items(state.departures, key = { it.identity }) { departure ->
                    DepartureCard(
                        departure = departure,
                        context = context,
                        timeSource = timeSource,
                        tick = tick,
                        passengerDestination = route.destination,
                        onClick = { onOpenTrip(departure) },
                        onFollow = { onFollowTrip(departure) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DepartureCard(departure: Departure, context: Context, timeSource: TimeSource, tick: Long, passengerDestination: Station?, onClick: () -> Unit, onFollow: () -> Unit) {
    val primary = if (departure.isCanceled()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick), shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(departure.line)
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(departure.getTrainDestinationName().orEmpty(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(DepartureTextFormatter.trainLengthAndPlatform(context, departure).ifBlank { "BART train" }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(DepartureTextFormatter.countdown(context, departure, tick), color = primary, fontWeight = FontWeight.Bold)
                    Text(DepartureTextFormatter.estimatedDepartureTime(context, departure), style = MaterialTheme.typography.labelMedium)
                }
            }
            if (departure.hasTransfers()) {
                AssistChip(onClick = onClick, label = { Text("${departure.tripLegs.size - 1} connection${if (departure.tripLegs.size == 2) "" else "s"}") }, leadingIcon = { Icon(Icons.Filled.SwapVert, null, Modifier.size(16.dp)) }, modifier = Modifier.padding(top = 12.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClick, modifier = Modifier.weight(1f)) { Text("View trip") }
                Button(onClick = onFollow, modifier = Modifier.weight(1f), enabled = !departure.isCanceled()) { Icon(Icons.Filled.Train, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Board") }
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, message: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Icon(Icons.Filled.Refresh, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(42.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
        Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripScreen(
    departure: Departure?,
    route: StationPair?,
    isFollowingInitially: Boolean,
    alarmVisible: Boolean,
    timeSource: TimeSource,
    alarmPending: Boolean,
    alarmLeadTimeMinutes: Int,
    onBack: () -> Unit,
    onFollow: (Departure) -> Unit,
    onSetAlarm: (Int) -> Unit,
    onCancelAlarm: () -> Unit,
    onClear: () -> Unit,
    onShare: (Departure) -> Unit,
    onSilenceAlarm: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tick = rememberSecondTick()
    var following by remember(isFollowingInitially) { mutableStateOf(isFollowingInitially) }
    var showAlarm by remember { mutableStateOf(false) }
    var showClear by remember { mutableStateOf(false) }
    val current = departure
    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = { Text(if (following) "Trip in progress" else "Trip details", fontWeight = FontWeight.Bold) },
                actions = {
                    if (current != null) IconButton(onClick = { onShare(current) }) { Icon(Icons.Filled.Share, "Share arrival") }
                    if (following && current != null) IconButton(onClick = { showClear = true }) { Icon(Icons.Filled.Close, "Stop following") }
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
                item {
                    TripHero(current, route ?: pair, timeSource, tick)
                }
                if (!following) {
                    item { Button(onClick = { following = true; onFollow(current) }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Filled.Train, null); Spacer(Modifier.width(8.dp)); Text("Follow this trip") } }
                } else {
                    item {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (alarmPending) {
                                FilledTonalButton(onClick = onCancelAlarm, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Alarm, null); Spacer(Modifier.width(6.dp)); Text("Alarm ${alarmLeadTimeMinutes}m") }
                            } else if (current.getMeanSecondsLeft(tick) > 60) {
                                OutlinedButton(onClick = { showAlarm = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Filled.Alarm, null); Spacer(Modifier.width(6.dp)); Text("Set alarm") }
                            }
                            OutlinedButton(onClick = { showClear = true }, modifier = Modifier.weight(1f)) { Text("End trip") }
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
        AlertDialog(onDismissRequest = { showClear = false }, title = { Text("Stop following this trip?") }, text = { Text("You can start following another train at any time.") }, confirmButton = { TextButton(onClick = { showClear = false; onClear() }) { Text("Stop following") } }, dismissButton = { TextButton(onClick = { showClear = false }) { Text("Cancel") } })
    }
    if (alarmVisible) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Your train is leaving soon") },
            text = { Text("Your departure alarm is sounding.") },
            confirmButton = { TextButton(onClick = onSilenceAlarm) { Text("Silence alarm") } },
        )
    }
}

@Composable
private fun TripHero(departure: Departure, pair: StationPair?, timeSource: TimeSource, tick: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val origin = pair?.origin?.getName() ?: departure.origin?.getName().orEmpty()
    val destination = pair?.destination?.getName() ?: departure.trainDestination?.getName().orEmpty()
    val status = tripStatus(context, departure, timeSource, tick)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text("$origin → $destination", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 6.dp))
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Metric(Icons.Filled.AccessTime, "Departs", DepartureTextFormatter.estimatedDepartureTime(context, departure).ifBlank { "—" }, Modifier.weight(1f))
                Metric(Icons.AutoMirrored.Filled.ArrowForward, "Arrives", DepartureTextFormatter.estimatedArrivalTime(context, departure).ifBlank { "—" }, Modifier.weight(1f))
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
private fun TripTimeline(departure: Departure, tick: Long) {
    val legs = departure.tripLegs
    if (legs.isEmpty()) {
        OutlinedCard { Text("Detailed stop times are not available for this trip yet.", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        return
    }
    val now = tick
    val currentIndex = legs.indexOfFirst { leg -> !legHasPassed(leg, now) }.let { if (it < 0) legs.lastIndex else it }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Trip timeline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        legs.forEachIndexed { index, leg ->
            TimelineLeg(leg, index == currentIndex, now)
            if (index < legs.lastIndex) ConnectionRow(leg, legs[index + 1], now)
        }
    }
}

@Composable
private fun TimelineLeg(leg: TripLeg, current: Boolean, now: Long) {
    val lineColor = lineColor(leg.line)
    Card(colors = CardDefaults.cardColors(containerColor = if (current) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.surfaceContainer), border = if (current) androidx.compose.foundation.BorderStroke(1.dp, lineColor) else null) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                LineBadge(leg.line)
                Column(Modifier.padding(start = 8.dp)) {
                    if (current) {
                        Text("Current train", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                    Text(
                        leg.trainDestination?.getName() ?: leg.destination?.getName() ?: "Train",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Text("${leg.origin?.getName().orEmpty()} → ${leg.destination?.getName().orEmpty()}", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            if (leg.stops.isEmpty()) {
                Text("Departs ${formatTime(leg.departureTime)} · arrives ${formatTime(leg.arrivalTime)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            } else {
                Column(Modifier.padding(top = 10.dp)) {
                    leg.stops.forEachIndexed { index, stop ->
                        val displayTime = if (stop.station == leg.origin) stop.departureTime else stop.arrivalTime
                        StationTimelineRow(
                            name = stop.station?.getName().orEmpty(),
                            displayTime = displayTime,
                            now = now,
                            color = lineColor,
                            isFirst = index == 0,
                            isLast = index == leg.stops.lastIndex,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StationTimelineRow(name: String, displayTime: Long, now: Long, color: Color, isFirst: Boolean, isLast: Boolean) {
    val reached = displayTime > 0 && displayTime <= now
    Row(Modifier.fillMaxWidth().height(42.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Text("${formatTime(displayTime)} · ${etaText(displayTime, now)}", style = MaterialTheme.typography.bodySmall, color = if (reached) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ConnectionRow(arriving: TripLeg, next: TripLeg, now: Long) {
    val arrival = arriving.stops.lastOrNull()?.arrivalTime ?: arriving.arrivalTime
    val departure = next.stops.firstOrNull()?.departureTime ?: next.departureTime
    val margin = departure - arrival
    val warning = margin < 0 || (arriving.minimumTransferSecondsAfter > 0 && margin < arriving.minimumTransferSecondsAfter * 1000L)
    Row(Modifier.fillMaxWidth().padding(start = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(34.dp).background(if (warning) Warning else MaterialTheme.colorScheme.outline))
        Column(Modifier.padding(start = 12.dp)) {
            Text("Transfer at ${arriving.destination?.getName().orEmpty()}", fontWeight = FontWeight.SemiBold, color = if (warning) Warning else MaterialTheme.colorScheme.primary)
            Text(if (departure <= 0) "Next departure unavailable" else "Arrives ${formatTime(arrival)} · ${if (margin < 0) "Connection missed" else "${margin / 60000} min connection margin"}", style = MaterialTheme.typography.bodySmall, color = if (warning) Warning else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LineBadge(line: Line?) {
    val color = lineColor(line)
    val contentColor = if (line == Line.YELLOW || line == Line.ORANGE || line == Line.YELLOW_DMU || line == Line.YELLOW_LATE_NIGHT || line == Line.PURPLE) Color(0xFF1B1B1B) else Color.White
    Box(
        modifier = Modifier.size(30.dp).clip(RoundedCornerShape(7.dp)).background(color),
        contentAlignment = Alignment.Center,
    ) {
        Text(line?.getDisplayName()?.take(1) ?: "B", color = contentColor, fontSize = 22.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AlarmPickerDialog(departure: Departure, timeSource: TimeSource, onDismiss: () -> Unit, onConfirm: (Int) -> Unit) {
    val max = (departure.getMeanSecondsLeft(timeSource) / 60).coerceAtLeast(1)
    var value by remember { mutableStateOf(5.coerceAtMost(max)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Set departure alarm") }, text = {
        Column {
            Text("Notify me before the train leaves", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("$value minutes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Slider(value = value.toFloat(), onValueChange = { value = it.toInt().coerceIn(1, max) }, valueRange = 1f..max.toFloat(), steps = (max - 2).coerceAtLeast(0))
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text("Set alarm") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemMapScreen(onBack: () -> Unit) {
    Scaffold(topBar = { TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }, title = { Text("BART system map", fontWeight = FontWeight.Bold) }) }, contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        androidx.compose.foundation.Image(androidx.compose.ui.res.painterResource(R.drawable.map), "BART system map", Modifier.fillMaxSize().padding(padding), contentScale = androidx.compose.ui.layout.ContentScale.Fit)
    }
}

private fun routeTitle(route: StationPair): String = if (route.destination == null) "Arrivals at ${route.origin?.getName()}" else "${route.origin?.getName()} → ${route.destination?.getName()}"

private fun lineColor(line: Line?): Color = when (line) {
    Line.RED -> Color(0xFFFF0000)
    Line.ORANGE -> Color(0xFFFF9933)
    Line.YELLOW, Line.YELLOW_DMU, Line.YELLOW_LATE_NIGHT -> Color(0xFFFFFF33)
    Line.BLUE -> Color(0xFF0099CC)
    Line.GREEN -> Color(0xFF339933)
    Line.PURPLE -> Color(0xFFD5CFA3)
    else -> Color(0xFF546E7A)
}

private fun tripStatus(context: Context, departure: Departure, timeSource: TimeSource, now: Long): String {
    if (departure.isCanceled()) return context.getString(R.string.trip_canceled)
    if (!departure.hasDeparted(now)) return context.getString(R.string.trip_leaves_in, DepartureTextFormatter.countdown(context, departure, now))
    departure.tripLegs.forEachIndexed { index, leg ->
        if (index < departure.tripLegs.lastIndex) {
            val arrival = leg.stops.lastOrNull()?.arrivalTime ?: leg.arrivalTime
            val nextLeg = departure.tripLegs[index + 1]
            val nextDeparture = nextLeg.stops.firstOrNull()?.departureTime ?: nextLeg.departureTime
            if (arrival in 1..now && nextDeparture > now) {
                return context.getString(
                    R.string.trip_transfer_now,
                    nextLeg.line?.getDisplayName() ?: "train",
                ) + " · departs ${etaText(nextDeparture, now)}"
            }
        }
    }
    val nextStop = departure.tripLegs.asSequence().flatMap { it.stops.asSequence() }.firstOrNull { it.arrivalTime > now }
    if (nextStop != null) return context.getString(R.string.trip_next_stop, nextStop.station?.getName(), etaText(nextStop.arrivalTime, now))
    if (departure.getEstimatedArrivalTime() in 1..now) return context.getString(R.string.trip_arrived)
    return context.getString(R.string.trip_current_train)
}

private fun legHasPassed(leg: TripLeg, now: Long): Boolean = if (leg.stops.isNotEmpty()) leg.stops.all { it.arrivalTime > 0 && it.arrivalTime <= now } else leg.arrivalTime > 0 && leg.arrivalTime <= now

private fun etaText(time: Long, now: Long): String {
    if (time <= 0) return "ETA unavailable"
    val seconds = (time - now) / 1000
    if (seconds <= 0) return "Passed"
    return String.format(Locale.ROOT, "in %d:%02d", seconds / 60, seconds % 60)
}

private fun formatTime(time: Long): String = if (time <= 0) "—" else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

@Composable
private fun rememberSecondTick(): Long {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { tick = System.currentTimeMillis(); delay(1000) } }
    return tick
}
