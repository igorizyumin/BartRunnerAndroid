package com.dougkeen.bart.ui

import android.app.Activity
import android.content.Context
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.material.icons.filled.DirectionsSubway
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Map
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
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
import com.dougkeen.bart.model.TripStop
import com.dougkeen.bart.presentation.DepartureTextFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
private val LightConnectingTrainTile = Color(0xFFE6E6E6)
private val LightYellowLine = Color(0xFFE6C400)

@Composable
fun BartRunnerTheme(content: @Composable () -> Unit) {
    val light = androidx.compose.material3.lightColorScheme(
        primary = Blue,
        onPrimary = Color.White,
        primaryContainer = BlueLight,
        onPrimaryContainer = BlueDark,
        secondary = Teal,
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
    onMoveFavorite: (Int, Int) -> Unit,
    onInsertFavorite: (StationPair, Int) -> Unit,
    onViewTrip: (Departure) -> Unit,
    onViewMap: () -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    var pickerAddsFavorite by remember { mutableStateOf(false) }
    var editingRoute by remember { mutableStateOf<StationPair?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var draggedRoute by remember { mutableStateOf<StationPair?>(null) }
    var draggedOffset by remember { mutableFloatStateOf(0f) }
    val favoriteListState = rememberLazyListState()
    val currentFavorites by rememberUpdatedState(state.favorites)
    val currentMoveFavorite by rememberUpdatedState(onMoveFavorite)
    val tick = rememberSecondTick(timeSource)
    val context = androidx.compose.ui.platform.LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = onViewMap) {
                        Icon(Icons.Filled.Map, contentDescription = stringResource(R.string.system_map))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            state = favoriteListState,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 32.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (followedTrip != null) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onViewTrip(followedTrip) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Train, stringResource(R.string.trip_in_progress), tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(25.dp))
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(stringResource(R.string.trip_in_progress), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    stringResource(R.string.route_arrow, followedTrip.origin?.getName().orEmpty(), (followedTrip.passengerDestination ?: followedTrip.trainDestination)?.getName().orEmpty()),
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
                    Text(stringResource(R.string.saved_trips), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    TextButton(onClick = { pickerAddsFavorite = true; showPicker = true }) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.add))
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
                if (isEditing) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.drag_to_reorder),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                isEditing = false
                                draggedRoute = null
                            }) {
                                Icon(Icons.Filled.Done, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(stringResource(R.string.done))
                            }
                        }
                    }
                }
                itemsIndexed(state.favorites, key = { _, route -> route.toString() }) { _, route ->
                    val routeKey = route.toString()
                    FavoriteRouteCard(
                        route = route,
                        departure = state.firstDepartures[route],
                        fare = state.fares[route],
                        timeSource = timeSource,
                        tick = tick,
                        isEditing = isEditing,
                        modifier = Modifier
                            .fillMaxWidth()
                            .zIndex(if (draggedRoute == route) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (draggedRoute == route) draggedOffset else 0f
                            }
                            .then(
                                if (isEditing) Modifier.pointerInput(Unit) {
                                    detectDragGestures(
                                    onDragStart = {
                                        draggedRoute = route
                                        draggedOffset = 0f
                                    },
                                    onDragCancel = {
                                        draggedRoute = null
                                        draggedOffset = 0f
                                    },
                                    onDragEnd = {
                                        draggedRoute = null
                                        draggedOffset = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        if (draggedRoute != route) return@detectDragGestures
                                        draggedOffset += dragAmount.y
                                        val source = favoriteListState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { it.key == routeKey }
                                            ?: return@detectDragGestures
                                        val draggedCenter = source.offset + source.size / 2 + draggedOffset
                                        val target = favoriteListState.layoutInfo.visibleItemsInfo
                                            .firstOrNull { item ->
                                                item.key != routeKey &&
                                                    draggedCenter >= item.offset &&
                                                    draggedCenter <= item.offset + item.size
                                            }
                                            ?: return@detectDragGestures
                                        val from = currentFavorites.indexOf(route)
                                        val to = currentFavorites.indexOfFirst { it.toString() == target.key }
                                        if (from >= 0 && to >= 0 && from != to) {
                                            currentMoveFavorite(from, to)
                                            draggedOffset -= target.offset - source.offset
                                        }
                                    },
                                    )
                                } else {
                                    Modifier
                                }
                            )
                            .combinedClickable(
                                onClick = {
                                    if (isEditing) {
                                        editingRoute = route
                                        showPicker = true
                                        pickerAddsFavorite = false
                                    } else {
                                        onRouteSelected(route)
                                    }
                                },
                                onLongClick = { isEditing = true },
                            ),
                        onRemove = { onRemoveFavorite(route) },
                    )
                }
            }
            item {
                Button(
                    onClick = { pickerAddsFavorite = false; editingRoute = null; showPicker = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.DirectionsSubway, null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.plan_trip))
                }
            }
        }
    }

    if (showPicker) {
        RoutePickerDialog(
            title = stringResource(if (pickerAddsFavorite) R.string.save_trip else R.string.plan_trip),
            showReturn = pickerAddsFavorite,
            initialRoute = editingRoute,
            onDismiss = { showPicker = false },
            onConfirm = { route, addReturn ->
                showPicker = false
                val previousRoute = editingRoute
                if (previousRoute != null) {
                    val previousIndex = state.favorites.indexOf(previousRoute)
                    onRemoveFavorite(previousRoute)
                    onInsertFavorite(route, previousIndex)
                    editingRoute = null
                } else if (pickerAddsFavorite) {
                    onAddFavorite(route)
                    if (addReturn && route.destination != null) onAddFavorite(StationPair(route.destination, route.origin))
                    onRouteSelected(route)
                } else {
                    onRouteSelected(route)
                }
            },
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
                text = if (isGood) context.getString(R.string.no_delays_reported) else messages.ifBlank { context.getString(R.string.service_advisory) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded || isGood) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!isGood) {
                Icon(
                    if (expanded) Icons.Filled.Close else Icons.AutoMirrored.Filled.ArrowForward,
                    stringResource(R.string.expand_service_alert),
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
            Text(stringResource(R.string.favorite_trips_empty_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
            Text(stringResource(R.string.favorite_trips_empty_message), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
            TextButton(onClick = onAdd, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.save_a_route)) }
        }
    }
}

@Composable
private fun FavoriteRouteCard(
    route: StationPair,
    departure: Departure?,
    fare: String?,
    timeSource: TimeSource,
    tick: Long,
    isEditing: Boolean,
    modifier: Modifier = Modifier,
    onRemove: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scheduleDetails = departure?.let { DepartureTextFormatter.departureSchedulePresentation(context, it) }
    val routeTitle = route.destination?.let { destination ->
        stringResource(R.string.route_arrow, route.origin?.getName().orEmpty(), destination.getName())
    } ?: route.origin?.getName().orEmpty()
    Card(modifier = modifier, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    routeTitle,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (isEditing) {
                    TextButton(onClick = onRemove) { Text(stringResource(R.string.remove)) }
                }
            }
            if (departure != null) {
                Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    LineBadge(departure.line)
                    Column(Modifier.padding(start = 8.dp).weight(1f)) {
                        if (departure.trainDestination != null && departure.trainDestination != route.destination) {
                            TrainDestinationLabel(
                                destination = departure.getTrainDestinationName().orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(DepartureTextFormatter.countdown(context, departure, tick), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                Text(stringResource(R.string.loading_upcoming_trains), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            }
            if (scheduleDetails?.isNotBlank() == true || fare != null) {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    fare?.let { CompactFareValue(it, modifier = Modifier.padding(start = 38.dp)) }
                    Spacer(Modifier.weight(1f))
                    if (scheduleDetails?.isNotBlank() == true) {
                        ScheduleDetailsRow(
                            details = scheduleDetails,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePickerDialog(
    title: String,
    showReturn: Boolean,
    initialRoute: StationPair? = null,
    onDismiss: () -> Unit,
    onConfirm: (StationPair, Boolean) -> Unit,
) {
    val stations = remember { Station.getStationList() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val preferences = remember(context) {
        (context as? Activity)?.getPreferences(Context.MODE_PRIVATE)
            ?: context.getSharedPreferences("route_picker_preferences", Context.MODE_PRIVATE)
    }
    val lastOriginPosition = preferences.getInt(LAST_SELECTED_ORIGIN, 0)
    var origin by remember(initialRoute) {
        mutableStateOf(initialRoute?.origin ?: stations.getOrNull(lastOriginPosition) ?: stations.firstOrNull())
    }
    var destination by remember(initialRoute) {
        mutableStateOf(initialRoute?.destination)
    }
    var addReturn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val chooseOriginError = stringResource(R.string.choose_origin_station)
    val sameStationError = stringResource(R.string.origin_destination_must_differ)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StationMenu(stringResource(R.string.from), origin, stations, { origin = it }, allowAny = false)
                IconButton(enabled = destination != null, onClick = { val old = origin; origin = destination; destination = old }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Filled.SwapVert, stringResource(R.string.swap_stations)) }
                StationMenu(stringResource(R.string.to), destination, stations, { destination = it }, allowAny = true)
                if (showReturn) {
                    CheckboxRow(stringResource(R.string.also_save_return_trip), addReturn) { addReturn = it }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (origin == null) error = chooseOriginError
                else if (destination == origin) error = sameStationError
                else {
                    preferences.edit {
                        putInt(LAST_SELECTED_ORIGIN, stations.indexOf(origin))
                    }
                    onConfirm(StationPair(origin, destination), addReturn)
                }
            }) { Text(stringResource(R.string.continue_label)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

private const val LAST_SELECTED_ORIGIN = "lastSelectedOrigin"

@Composable
private fun StationMenu(label: String, selected: Station?, stations: List<Station>, onSelected: (Station?) -> Unit, allowAny: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Text(selected?.getName() ?: stringResource(R.string.any_destination), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (allowAny) DropdownMenuItem(text = { Text(stringResource(R.string.any_destination)) }, onClick = { onSelected(null); expanded = false })
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
    fare: String? = null,
    onBack: () -> Unit,
    onOpenTrip: (Departure) -> Unit,
    onMap: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val tick = rememberSecondTick(timeSource)
    val originName = route.origin?.getName().orEmpty()
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
            DeparturesViewModel.Status.LOADING -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            DeparturesViewModel.Status.ERROR -> EmptyState(stringResource(R.string.could_not_connect), stringResource(R.string.try_again_connection), modifier = Modifier.padding(padding))
            DeparturesViewModel.Status.EMPTY -> EmptyState(stringResource(R.string.no_departures_found), stringResource(R.string.no_departures_message), modifier = Modifier.padding(padding))
            DeparturesViewModel.Status.CONTENT -> LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp, 12.dp, 16.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
    fare: String? = null,
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
                            } else if (current.getMeanSecondsLeft(tick) > 60) {
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
private fun TripHero(departure: Departure, pair: StationPair?, fare: String?, timeSource: TimeSource, tick: Long) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val origin = pair?.origin?.getName() ?: departure.origin?.getName().orEmpty()
    val destination = pair?.destination?.getName() ?: departure.trainDestination?.getName().orEmpty()
    val status = tripStatus(context, departure, timeSource, tick)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), shape = RoundedCornerShape(24.dp)) {
        Column(Modifier.padding(20.dp)) {
            Text(status, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
private fun FareValue(value: String, modifier: Modifier = Modifier) {
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
private fun CompactFareValue(value: String, modifier: Modifier = Modifier) {
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
private fun ScheduleDetailsRow(
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
                Text(actualLabel, style = style, color = color, maxLines = 1, modifier = Modifier.padding(start = 3.dp))
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
private fun TrainDestinationLabel(
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
private fun TripTimeline(departure: Departure, tick: Long) {
    val legs = departure.tripLegs
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
            ScheduleDetailsRow(
                details = DepartureTextFormatter.legSchedulePresentation(context, leg),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                showActualTime = false,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (leg.stops.isEmpty()) {
                val departure = if (leg.departureTime > 0L) formatTime(leg.departureTime)
                else unavailableTime
                val arrival = if (leg.arrivalTime > 0L) formatTime(leg.arrivalTime)
                else unavailableTime
                Text(stringResource(R.string.departure_arrival_times, departure, arrival), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
            } else {
                Column(Modifier.padding(top = 10.dp)) {
                    leg.stops.forEachIndexed { index, stop ->
                        StationTimelineRow(
                            stop = stop,
                            departure = stop.station == leg.origin,
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
private fun StationTimelineRow(stop: TripStop, departure: Boolean, now: Long, color: Color, isFirst: Boolean, isLast: Boolean) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val unavailableEta = stringResource(R.string.eta_unavailable)
    val name = stop.station?.getName().orEmpty()
    val displayTime = if (departure) stop.departureTime else stop.arrivalTime
    val scheduleDetails = DepartureTextFormatter.stopSchedulePresentation(context, stop, departure)
    val reached = displayTime > 0 && displayTime <= now
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
                if (displayTime > 0L) etaText(context, displayTime, now)
                else unavailableEta,
                style = MaterialTheme.typography.bodySmall,
                color = if (reached) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurfaceVariant,
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
        Box(Modifier.width(2.dp).height(34.dp).background(if (warning) Warning else MaterialTheme.colorScheme.outline))
        Column(Modifier.padding(start = 12.dp)) {
            Text(stringResource(R.string.transfer_at, arriving.destination?.getName().orEmpty()), fontWeight = FontWeight.SemiBold, color = if (warning) Warning else MaterialTheme.colorScheme.primary)
            Text(
                connectionText,
                style = MaterialTheme.typography.bodySmall,
                color = if (warning) Warning else MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    var value by remember { mutableIntStateOf(5.coerceAtMost(max)) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(stringResource(R.string.set_departure_alarm)) }, text = {
        Column {
            Text(stringResource(R.string.notify_before_train_leaves), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(pluralStringResource(R.plurals.alarm_minutes_value, value, value), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            Slider(value = value.toFloat(), onValueChange = { value = it.toInt().coerceIn(1, max) }, valueRange = 1f..max.toFloat(), steps = (max - 2).coerceAtLeast(0))
        }
    }, confirmButton = { TextButton(onClick = { onConfirm(value) }) { Text(stringResource(R.string.set_alarm)) } }, dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemMapScreen(onBack: () -> Unit) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 4f)
        val panMultiplier = scale.coerceAtLeast(1f)
        offset = if (scale > 1f) {
            offset + Offset(panChange.x * panMultiplier, panChange.y * panMultiplier)
        } else {
            Offset.Zero
        }
    }
    fun changeScale(multiplier: Float) {
        scale = (scale * multiplier).coerceIn(1f, 4f)
        if (scale == 1f) offset = Offset.Zero
    }
    Scaffold(topBar = { TopAppBar(navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } }, title = { Text(stringResource(R.string.system_map_title), fontWeight = FontWeight.Bold) }) }, contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.map),
                stringResource(R.string.system_map_title),
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    }
                    .transformable(transformState),
                contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        RoundedCornerShape(16.dp),
                    ),
            ) {
                IconButton(onClick = { changeScale(1.5f) }) {
                    Icon(Icons.Filled.ZoomIn, stringResource(R.string.zoom_in))
                }
                IconButton(onClick = { changeScale(1f / 1.5f) }) {
                    Icon(Icons.Filled.ZoomOut, stringResource(R.string.zoom_out))
                }
                IconButton(onClick = { scale = 1f; offset = Offset.Zero }) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.reset_map_zoom))
                }
            }
        }
    }
}

@Composable
private fun lineColor(line: Line?): Color = when (line) {
    Line.RED -> Color(0xFFFF0000)
    Line.ORANGE -> Color(0xFFFF9933)
    Line.YELLOW, Line.YELLOW_DMU, Line.YELLOW_LATE_NIGHT ->
        if (androidx.compose.foundation.isSystemInDarkTheme()) Color(0xFFFFFF33)
        else LightYellowLine
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
                    nextLeg.line?.getDisplayName() ?: context.getString(R.string.train_label),
                ).let { status -> context.getString(R.string.trip_transfer_status, status, etaText(context, nextDeparture, now)) }
            }
        }
    }
    val nextStop = departure.tripLegs.asSequence().flatMap { it.stops.asSequence() }.firstOrNull { it.arrivalTime > now }
    if (nextStop != null) return context.getString(R.string.trip_next_stop, nextStop.station?.getName(), etaText(context, nextStop.arrivalTime, now))
    if (departure.getEstimatedArrivalTime() in 1..now) return context.getString(R.string.trip_arrived)
    return context.getString(R.string.trip_current_train)
}

private fun legHasPassed(leg: TripLeg, now: Long): Boolean = if (leg.stops.isNotEmpty()) leg.stops.all { it.arrivalTime > 0 && it.arrivalTime <= now } else leg.arrivalTime > 0 && leg.arrivalTime <= now

private fun etaText(context: Context, time: Long, now: Long): String {
    if (time <= 0) return context.getString(R.string.eta_unavailable)
    val seconds = (time - now) / 1000
    if (seconds <= 0) return context.getString(R.string.passed)
    return context.getString(R.string.eta_in, seconds / 60, seconds % 60)
}

private fun formatTime(time: Long): String = if (time <= 0) "—" else DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))

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
