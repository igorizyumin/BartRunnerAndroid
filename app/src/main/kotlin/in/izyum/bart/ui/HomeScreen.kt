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
fun HomeScreen(
    state: RoutesUiState,
    isOffline: Boolean = false,
    followedTrip: Itinerary?,
    timeSource: TimeSource,
    onRouteSelected: (StationPair) -> Unit,
    onAddFavorite: (StationPair) -> Unit,
    onRemoveFavorite: (StationPair) -> Unit,
    onMoveFavorite: (Int, Int) -> Unit,
    onInsertFavorite: (StationPair, Int) -> Unit,
    onViewTrip: (Itinerary) -> Unit,
    onViewMap: () -> Unit,
    onViewElevators: () -> Unit = {},
    onViewAbout: () -> Unit = {},
    fareDiscountId: String? = null,
    fareDiscountOptions: List<RiderCategory> = emptyList(),
    onFareDiscountChanged: (String?) -> Unit = {},
    alarmVibrationEnabled: Boolean = true,
    onAlarmVibrationChanged: (Boolean) -> Unit = {},
) {
    var showPicker by remember { mutableStateOf(false) }
    var pickerAddsFavorite by remember { mutableStateOf(false) }
    var editingRoute by remember { mutableStateOf<StationPair?>(null) }
    var isEditing by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showElevatorDialog by remember { mutableStateOf(false) }
    var draggedRoute by remember { mutableStateOf<StationPair?>(null) }
    var draggedOffset by remember { mutableFloatStateOf(0f) }
    val favoriteListState = rememberLazyListState()
    val currentFavorites by rememberUpdatedState(state.favorites)
    val currentMoveFavorite by rememberUpdatedState(onMoveFavorite)
    val tick = rememberSecondTick(timeSource)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(stringResource(R.string.app_name), fontWeight = FontWeight.Bold)
                },
                actions = {
                    IconButton(onClick = {
                        pickerAddsFavorite = false
                        editingRoute = null
                        showPicker = true
                    }) {
                        Icon(
                            Icons.Filled.DirectionsSubway,
                            contentDescription = stringResource(R.string.plan_trip),
                        )
                    }
                    IconButton(onClick = {
                        showElevatorDialog = true
                        onViewElevators()
                    }) {
                        Icon(
                            Icons.Filled.Elevator,
                            contentDescription = stringResource(R.string.elevator_status),
                        )
                    }
                    IconButton(onClick = onViewMap) {
                        Icon(Icons.Filled.Map, contentDescription = stringResource(R.string.system_map))
                    }
                    Box {
                        IconButton(onClick = { showOverflowMenu = true }) {
                            Icon(
                                Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.more_options),
                            )
                        }
                        DropdownMenu(
                            expanded = showOverflowMenu,
                            onDismissRequest = { showOverflowMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.settings)) },
                                onClick = {
                                    showOverflowMenu = false
                                    showSettingsDialog = true
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.about)) },
                                onClick = {
                                    showOverflowMenu = false
                                    onViewAbout()
                                },
                            )
                        }
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
            if (shouldShowServiceStatus(state.alerts, isOffline)) {
                item { ServiceStatusBanner(state.alerts, isOffline) }
            }
            if (followedTrip != null) {
                item {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    val status = tripStatus(context, followedTrip, timeSource, tick)
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onViewTrip(followedTrip) },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                                Icon(Icons.Filled.Train, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(25.dp))
                            }
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(status.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                status.subtitle?.let {
                                    Text(
                                        it,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                Text(
                                    stringResource(R.string.route_arrow, followedTrip.origin.getName(), followedTrip.destination.getName()),
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
                        isLoading = route !in state.loadedRoutes,
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
                } else {
                    onRouteSelected(route)
                }
            },
        )
    }

    if (showSettingsDialog) {
        SettingsDialog(
            fareDiscountId = fareDiscountId,
            fareDiscountOptions = fareDiscountOptions,
            onFareDiscountChanged = onFareDiscountChanged,
            alarmVibrationEnabled = alarmVibrationEnabled,
            onAlarmVibrationChanged = onAlarmVibrationChanged,
            onDismiss = { showSettingsDialog = false },
        )
    }

    if (showElevatorDialog) {
        ElevatorStatusDialog(
            isLoading = state.elevatorIsLoading,
            description = state.elevatorDescription,
            error = state.elevatorError,
            onDismiss = { showElevatorDialog = false },
        )
    }
}

@Composable
private fun ElevatorStatusDialog(
    isLoading: Boolean,
    description: String?,
    error: Exception?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.elevator_status)) },
        text = {
            when {
                isLoading || description == null && error == null -> {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
                error != null -> {
                    Text(
                        text = stringResource(R.string.elevator_status_unavailable),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    Text(
                        text = description.orEmpty(),
                        modifier = Modifier
                            .heightIn(max = 320.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

@Composable
private fun SettingsDialog(
    fareDiscountId: String?,
    fareDiscountOptions: List<RiderCategory>,
    onFareDiscountChanged: (String?) -> Unit,
    alarmVibrationEnabled: Boolean,
    onAlarmVibrationChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var showFareDiscountMenu by remember { mutableStateOf(false) }
    val selectedDescription = fareDiscountOptions
        .firstOrNull { it.id == fareDiscountId }
        ?.description
        ?: stringResource(R.string.none)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.fare_discount),
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(
                        onClick = { showFareDiscountMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selectedDescription, modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null)
                    }
                    DropdownMenu(
                        expanded = showFareDiscountMenu,
                        onDismissRequest = { showFareDiscountMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.none)) },
                            onClick = {
                                onFareDiscountChanged(null)
                                showFareDiscountMenu = false
                            },
                        )
                        fareDiscountOptions.forEach { category ->
                            DropdownMenuItem(
                                text = { Text(category.description) },
                                onClick = {
                                    onFareDiscountChanged(category.id)
                                    showFareDiscountMenu = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.alarm_vibration),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(R.string.alarm_vibration_description),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Checkbox(
                        checked = alarmVibrationEnabled,
                        onCheckedChange = onAlarmVibrationChanged,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.done))
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    versionName: String,
    gitBuildHash: String,
    onBack: () -> Unit,
    onOpenGithub: () -> Unit,
    onOpenApacheLicense: () -> Unit = {},
    onOpenLicenses: () -> Unit,
    onFeedback: () -> Unit = {},
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.about)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back),
                        )
                    }
                },
            )
        },
        contentWindowInsets = WindowInsets.safeDrawing,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AndroidView(
                    factory = { context ->
                        ImageView(context).apply {
                            setImageDrawable(
                                context.packageManager.getApplicationIcon(context.packageName),
                            )
                            contentDescription = context.getString(R.string.app_name)
                        }
                    },
                    modifier = Modifier.size(56.dp),
                )
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(stringResource(R.string.about_description))
            Spacer(modifier = Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.app_version, versionName))
                Text(stringResource(R.string.git_build_hash, gitBuildHash))
            }
            Spacer(modifier = Modifier.height(4.dp))
            val apacheLicenseText = buildAnnotatedString {
                append(stringResource(R.string.about_license_prefix))
                append(" ")
                withLink(
                    LinkAnnotation.Clickable(
                        tag = "apache-license",
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = MaterialTheme.colorScheme.primary,
                                textDecoration = TextDecoration.Underline,
                            )
                        ),
                        linkInteractionListener = object : LinkInteractionListener {
                            override fun onClick(link: LinkAnnotation) {
                                onOpenApacheLicense()
                            }
                        },
                    )
                ) {
                    append(stringResource(R.string.apache_license_name))
                }
                append(" ")
                append(stringResource(R.string.about_license_suffix))
            }
            Text(
                text = apacheLicenseText,
                style = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                )
            )
            TextButton(onClick = onOpenGithub) {
                Text(stringResource(R.string.github_url))
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.developer_copyright_igor))
                Text(stringResource(R.string.developer_copyright_doug))
                Text(
                    text = stringResource(R.string.open_source_licenses),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        textDecoration = TextDecoration.Underline,
                    ),
                    modifier = Modifier.clickable(onClick = onOpenLicenses),
                )
            }
            Button(
                onClick = onFeedback,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
            ) {
                Text(stringResource(R.string.feedback))
            }
        }
    }
}

internal fun shouldShowServiceStatus(alerts: Alert.AlertList?, isOffline: Boolean): Boolean =
    isOffline || alerts?.hasAlerts() == true || alerts?.areNoDelaysReported() == true

@Composable
internal fun ServiceStatusBanner(
    alerts: Alert.AlertList?,
    isOffline: Boolean,
    modifier: Modifier = Modifier,
) {
    if (isOffline) {
        OfflineBanner(modifier)
    } else if (alerts != null) {
        ServiceAlert(alerts, modifier)
    }
}

@Composable
private fun ServiceAlert(alerts: Alert.AlertList, modifier: Modifier = Modifier) {
    val isNoAlerts = alerts.areNoDelaysReported() && alerts.getAlerts().isEmpty()
    val messages = alerts.getAlerts().joinToString("\n\n") { it.description.orEmpty() }
    var expanded by remember { mutableStateOf(false) }
    val containerColor = if (isNoAlerts) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer
    val contentColor = if (isNoAlerts) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer
    val accentColor = if (isNoAlerts) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error
    Card(
        modifier = modifier.fillMaxWidth().clickable(enabled = !isNoAlerts) { expanded = !expanded },
        colors = CardDefaults.cardColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (isNoAlerts) Icons.Filled.CheckCircle else Icons.Filled.Warning, null, tint = accentColor)
            Spacer(Modifier.width(10.dp))
            Text(
                text = when {
                    isNoAlerts -> stringResource(R.string.no_delays_reported)
                    else -> messages.ifBlank { stringResource(R.string.service_advisory) }
                },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded || isNoAlerts) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!isNoAlerts) {
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
    isLoading: Boolean,
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
        stringResource(R.string.route_arrow, route.origin.getName(), destination.getName())
    } ?: route.origin.getName()
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
            } else if (isLoading) {
                Text(stringResource(R.string.loading_upcoming_trains), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
            } else {
                Text(stringResource(R.string.no_departures_found), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp))
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
        mutableStateOf(initialRoute?.origin ?: stations.getOrNull(lastOriginPosition) ?: stations.first())
    }
    var destination by remember(initialRoute) {
        mutableStateOf(initialRoute?.destination)
    }
    var addReturn by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sameStationError = stringResource(R.string.origin_destination_must_differ)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                StationMenu(stringResource(R.string.from), origin, stations, { it?.let { selected -> origin = selected } }, allowAny = false)
                IconButton(enabled = destination != null, onClick = { destination?.let { oldDestination -> destination = origin; origin = oldDestination } }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Icon(Icons.Filled.SwapVert, stringResource(R.string.swap_stations)) }
                StationMenu(stringResource(R.string.to), destination, stations, { destination = it }, allowAny = true)
                if (showReturn) {
                    CheckboxRow(stringResource(R.string.also_save_return_trip), addReturn) { addReturn = it }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (destination == origin) error = sameStationError
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
