package com.example.nightagent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nightagent.journey.JourneyViewModel
import com.example.nightagent.journey.model.JourneyStatus
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

@Composable
fun SafeWalkScreen(vm: JourneyViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by vm.uiState.collectAsState()
    val journey = state.journey
    val active = journey?.statusEnum() == JourneyStatus.ACTIVE

    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    val actualPath = remember { mutableStateListOf<GeoPoint>() }

    val neededPerms = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    LaunchedEffect(Unit) {
        val missing = neededPerms.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing) permLauncher.launch(neededPerms)
    }

    LaunchedEffect(state.lastLocation?.timestamp) {
        state.lastLocation?.let { actualPath.add(GeoPoint(it.latitude, it.longitude)) }
    }
    LaunchedEffect(journey?.journeyId) { actualPath.clear(); state.lastLocation?.let { actualPath.add(GeoPoint(it.latitude, it.longitude)) } }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapViewRef?.onResume()
                Lifecycle.Event.ON_PAUSE -> mapViewRef?.onPause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs); mapViewRef?.onDetach() }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (!active) {
            Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.AutoMirrored.Filled.DirectionsWalk, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
                Spacer(Modifier.height(12.dp))
                Text("No active Safe Journey", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Start a journey from the Journey screen to see live tracking here.", color = Color.Gray)
            }
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    Configuration.getInstance().load(ctx, ctx.getSharedPreferences("osmdroid", android.content.Context.MODE_PRIVATE))
                    Configuration.getInstance().userAgentValue = ctx.packageName
                    MapView(ctx).apply {
                        setTileSource(TileSourceFactory.MAPNIK)
                        setMultiTouchControls(true)
                        controller.setZoom(17.0)
                        onResume()
                        mapViewRef = this
                    }
                },
                update = { map ->
                    map.overlays.clear()
                    val routePts = journey.routePoints.map { GeoPoint(it.latitude, it.longitude) }
                    if (routePts.size >= 2) {
                        val planned = Polyline().apply {
                            setPoints(routePts)
                            outlinePaint.color = android.graphics.Color.BLUE
                            outlinePaint.strokeWidth = 10f
                        }
                        map.overlays.add(planned)
                    }
                    if (actualPath.size >= 2) {
                        val actual = Polyline().apply {
                            setPoints(actualPath.toList())
                            outlinePaint.color = android.graphics.Color.RED
                            outlinePaint.strokeWidth = 8f
                        }
                        map.overlays.add(actual)
                    }
                    state.lastLocation?.let { loc ->
                        val p = GeoPoint(loc.latitude, loc.longitude)
                        map.controller.setCenter(p)
                        val m = Marker(map).apply { position = p; title = "You"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }
                        map.overlays.add(m)
                    }
                    val dest = GeoPoint(journey.destinationLatitude, journey.destinationLongitude)
                    val dm = Marker(map).apply { position = dest; title = "Destination"; setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM) }
                    map.overlays.add(dm)
                    map.invalidate()
                },
                onRelease = { it.onPause(); it.onDetach() }
            )

            // Top status card
            Card(
                modifier = Modifier.align(Alignment.TopCenter).padding(16.dp).fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Active journey", fontWeight = FontWeight.Bold)
                    Text("To: ${journey.destinationName}", fontSize = 13.sp, color = Color.Gray)
                    state.distanceToDestinationMeters?.let { Text("Distance to destination: ${"%.0f".format(it)} m", fontSize = 13.sp) }
                    state.distanceFromRouteMeters?.let { Text("Distance from route: ${"%.0f".format(it)} m", fontSize = 13.sp) }
                    state.pendingCheckIn?.let { Text("Check-in pending", color = Color(0xFFFF9800), fontWeight = FontWeight.SemiBold) }
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
                }
            }

            // Bottom actions
            Row(
                Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.pendingCheckIn != null) {
                    Button(onClick = { vm.acknowledgeCheckIn() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))) { Text("I'm Safe") }
                    Button(onClick = { vm.requestHelp() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Help") }
                } else {
                    OutlinedButton(onClick = { vm.cancelJourney() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
                    Button(onClick = { vm.completeJourney() }, modifier = Modifier.weight(1f)) { Text("Arrived") }
                }
            }
        }
    }
}
