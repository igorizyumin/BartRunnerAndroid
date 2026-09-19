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
fun SystemMapScreen(onBack: () -> Unit, initialMapStyle: SystemMapStyle? = null) {
    var mapStyle by remember { mutableStateOf(initialMapStyle ?: defaultSystemMapStyle()) }
    var showMapMenu by remember { mutableStateOf(false) }
    var mapWebView by remember { mutableStateOf<WebView?>(null) }
    val mapAsset = mapStyle.assetName
    Scaffold(topBar = {
        TopAppBar(
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
            title = { Text(stringResource(R.string.system_map_title), fontWeight = FontWeight.Bold) },
            actions = {
                Box {
                    TextButton(onClick = { showMapMenu = true }) {
                        Text(stringResource(mapStyle.label))
                    }
                    DropdownMenu(
                        expanded = showMapMenu,
                        onDismissRequest = { showMapMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.map_day)) },
                            onClick = {
                                mapStyle = SystemMapStyle.DAY
                                showMapMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.map_evening)) },
                            onClick = {
                                mapStyle = SystemMapStyle.NIGHT
                                showMapMenu = false
                            },
                        )
                    }
                }
            },
        )
    }, contentWindowInsets = WindowInsets.safeDrawing) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            AndroidView(
                modifier = Modifier
                    .fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        settings.apply {
                            builtInZoomControls = true
                            displayZoomControls = false
                            javaScriptEnabled = false
                            loadWithOverviewMode = true
                            useWideViewPort = true
                        }
                        setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        mapWebView = this
                    }
                },
                update = { view ->
                    if (view.tag != mapAsset) {
                        view.tag = mapAsset
                        view.loadUrl("file:///android_asset/${Uri.encode(mapAsset)}")
                    }
                },
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
                IconButton(onClick = { mapWebView?.zoomIn() }) {
                    Icon(Icons.Filled.ZoomIn, stringResource(R.string.zoom_in))
                }
                IconButton(onClick = { mapWebView?.zoomOut() }) {
                    Icon(Icons.Filled.ZoomOut, stringResource(R.string.zoom_out))
                }
                IconButton(onClick = {
                    mapWebView?.apply {
                        setInitialScale(DEFAULT_MAP_SCALE_PERCENT)
                        clearHistory()
                        scrollTo(0, 0)
                    }
                }) {
                    Icon(Icons.Filled.Refresh, stringResource(R.string.reset_map_zoom))
                }
            }
        }
    }
}

private const val DEFAULT_MAP_SCALE_PERCENT = 100

enum class SystemMapStyle(
    val assetName: String,
    val label: Int,
) {
    DAY("BART Open Source Map Daytime Service.svg", R.string.map_day),
    NIGHT("BART Open Source Map Evening Service.svg", R.string.map_evening),
}

internal fun defaultSystemMapStyle(calendar: Calendar = Calendar.getInstance()): SystemMapStyle {
    val hour = calendar.get(Calendar.HOUR_OF_DAY)
    return if (hour in 3 until 21) SystemMapStyle.DAY else SystemMapStyle.NIGHT
}
