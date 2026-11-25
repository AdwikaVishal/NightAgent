package com.example.nightagent.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.nightagent.journey.JourneyViewModel
import com.example.nightagent.journey.RouteProvider
import com.example.nightagent.journey.model.Journey
import com.example.nightagent.journey.model.JourneyStatus
import com.example.nightagent.ui.theme.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun JourneyScreen(onOpenSafeWalk: () -> Unit = {}, vm: JourneyViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by vm.uiState.collectAsState()
    val history by vm.history.collectAsState()
    val journey = state.journey
    val activeJourney = journey?.takeIf { it.statusEnum() == JourneyStatus.ACTIVE }

    var destinationName by remember { mutableStateOf("") }
    var destLat by remember { mutableStateOf("") }
    var destLng by remember { mutableStateOf("") }
    var etaMinutes by remember { mutableStateOf("30") }
    var checkInMinutes by remember { mutableStateOf("5") }
    var checkInTimeoutSec by remember { mutableStateOf("120") }
    var searchQuery by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var formError by remember { mutableStateOf<String?>(null) }

    var showBgDisclosure by remember { mutableStateOf(false) }
    var showBgSettingsHint by remember { mutableStateOf(false) }
    var bgDisclosureShown by remember { mutableStateOf(false) }
    val neededPerms = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    val bgLocationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) { vm.refreshHistory() }

    fun hasLocationPerm(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackgroundLocationPerm(): Boolean = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) true else
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Spacer(Modifier.height(24.dp))
            Text("Safe Journey", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            Text("Plan your journey, track live location, and respond to safety check-ins.", fontSize = 13.sp, color = TextSecondary)
        }

        if (activeJourney != null) {
            item { ActiveJourneyCard(activeJourney, state.pendingCheckIn != null, state.distanceToDestinationMeters, state.distanceFromRouteMeters, onOpenSafeWalk, onSafe = { vm.acknowledgeCheckIn() }, onHelp = { vm.requestHelp() }, onArrived = { vm.completeJourney() }, onCancel = { vm.cancelJourney() }) }
        } else {
            item {
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Start a journey", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        OutlinedTextField(value = searchQuery, onValueChange = { searchQuery = it }, label = { Text("Search destination (OSM)") }, modifier = Modifier.fillMaxWidth(), singleLine = true, trailingIcon = {
                            IconButton(onClick = {
                                if (searchQuery.isBlank()) return@IconButton
                                searching = true; formError = null
                                scope.launch {
                                    val res = RouteProvider.geocode(searchQuery)
                                    searching = false
                                    if (res != null) { destLat = res.first.toString(); destLng = res.second.toString(); if (destinationName.isBlank()) destinationName = searchQuery }
                                    else formError = "Destination not found. Enter coordinates manually."
                                }
                            }) { if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Icon(Icons.Default.Search, null) }
                        })
                        OutlinedTextField(value = destinationName, onValueChange = { destinationName = it }, label = { Text("Destination name") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = destLat, onValueChange = { destLat = it }, label = { Text("Dest lat") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = destLng, onValueChange = { destLng = it }, label = { Text("Dest lng") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = etaMinutes, onValueChange = { etaMinutes = it.filter(Char::isDigit) }, label = { Text("ETA (min)") }, modifier = Modifier.weight(1f), singleLine = true)
                            OutlinedTextField(value = checkInMinutes, onValueChange = { checkInMinutes = it.filter(Char::isDigit) }, label = { Text("Check-in (min)") }, modifier = Modifier.weight(1f), singleLine = true)
                        }
                        OutlinedTextField(value = checkInTimeoutSec, onValueChange = { checkInTimeoutSec = it.filter(Char::isDigit) }, label = { Text("Check-in response timeout (sec)") }, modifier = Modifier.fillMaxWidth(), singleLine = true)

                        val err = formError ?: state.error
                        if (!err.isNullOrBlank()) Text(err, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)

                        Button(onClick = {
                            formError = null
                            val missing = neededPerms.any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
                            if (missing) { permLauncher.launch(neededPerms); formError = "Grant location/notification permissions and try again."; return@Button }
                            if (!hasLocationPerm()) { formError = "Location permission is required."; return@Button }
                            if (!hasBackgroundLocationPerm() && !bgDisclosureShown) { showBgDisclosure = true; bgDisclosureShown = true; return@Button }
                            val lat = destLat.toDoubleOrNull(); val lng = destLng.toDoubleOrNull()
                            val etaMin = etaMinutes.toIntOrNull(); val ciMin = checkInMinutes.toIntOrNull(); val ciSec = checkInTimeoutSec.toIntOrNull()
                            if (destinationName.isBlank()) { formError = "Destination name is required."; return@Button }
                            if (lat == null || lng == null) { formError = "Valid destination coordinates are required."; return@Button }
                            if (etaMin == null || etaMin <= 0) { formError = "ETA minutes must be positive."; return@Button }
                            if (ciMin == null || ciMin <= 0) { formError = "Check-in interval must be positive."; return@Button }
                            if (ciSec == null || ciSec < 30) { formError = "Check-in timeout must be at least 30 seconds."; return@Button }
                            val expectedArrival = System.currentTimeMillis() + etaMin * 60_000L
                            scope.launch {
                                // Fetch route; if fails, engine still works with empty route (deviation disabled)
                                @OptIn(ExperimentalCoroutinesApi::class)
                                val current = com.example.nightagent.sos.LocationProvider.let { lp ->
                                    kotlinx.coroutines.suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
                                        lp.getLocation(context) { loc ->
                                            if (cont.isActive) cont.resume(if (loc != null) loc.latitude to loc.longitude else null) {}
                                        }
                                    }
                                }
                                val route = if (current != null) RouteProvider.fetchWalkingRoute(current.first, current.second, lat, lng) else emptyList()
                                vm.startJourney(destinationName.trim(), lat, lng, expectedArrival, ciMin, ciSec, route)
                            }
                        }, modifier = Modifier.fillMaxWidth(), enabled = !state.isLoading) {
                            if (state.isLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                            else Text("Start Safe Journey")
                        }
                    }
                }
            }
        }

        item { Spacer(Modifier.height(8.dp)); Text("Journey History", fontSize = 18.sp, fontWeight = FontWeight.Bold) }
        if (history.isEmpty()) {
            item { Text("No journeys yet.", color = TextSecondary, fontSize = 13.sp) }
        } else {
            items(history, key = { it.journeyId }) { j -> HistoryCard(j) }
        }
        item { Spacer(Modifier.height(100.dp)) }
    }

    if (showBgDisclosure) {
        AlertDialog(
            onDismissRequest = { showBgDisclosure = false },
            title = { Text("Background location access") },
            text = {
                Text("NightAgent uses background location during an active Safe Journey so your trusted safety features can keep tracking, check-ins, and emergency alerts working even if the app is closed. Location is used only for journey safety features. You can choose not to grant it, but tracking may stop when the app is in the background.")
            },
            confirmButton = {
                TextButton(onClick = {
                    showBgDisclosure = false
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        showBgSettingsHint = true
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { showBgDisclosure = false }) { Text("Not now") }
            }
        )
    }

    if (showBgSettingsHint) {
        AlertDialog(
            onDismissRequest = { showBgSettingsHint = false },
            title = { Text("Allow background location") },
            text = { Text("On Android 11+, background location must be granted from system settings. Choose \"Allow all the time\" for NightAgent location permission so Safe Journey tracking can continue in the background.") },
            confirmButton = {
                TextButton(onClick = {
                    showBgSettingsHint = false
                    val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = android.net.Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showBgSettingsHint = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun ActiveJourneyCard(j: Journey, pendingCheckIn: Boolean, distDest: Float?, distRoute: Float?, onOpenSafeWalk: () -> Unit, onSafe: () -> Unit, onHelp: () -> Unit, onArrived: () -> Unit, onCancel: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Lavender.copy(alpha = 0.18f))) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Journey in progress", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text("To: ${j.destinationName}", color = TextSecondary)
            Text("ETA: ${fmtTime(j.expectedArrivalTime)}", fontSize = 13.sp)
            distDest?.let { Text("Distance to destination: ${"%.0f".format(it)} m", fontSize = 13.sp) }
            distRoute?.let { Text("Distance from route: ${"%.0f".format(it)} m", fontSize = 13.sp) }
            if (pendingCheckIn) {
                Text("Safety check-in pending", color = Color(0xFFFF9800), fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onSafe, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)) { Text("I'm Safe") }
                    Button(onClick = onHelp, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = SOSRed)) { Text("I Need Help") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onOpenSafeWalk, modifier = Modifier.weight(1f)) { Icon(Icons.Default.Map, null); Spacer(Modifier.width(4.dp)); Text("Live map") }
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
            }
            Button(onClick = onArrived, modifier = Modifier.fillMaxWidth()) { Text("I've arrived") }
        }
    }
}

@Composable
private fun HistoryCard(j: Journey) {
    val statusColor = when (j.statusEnum()) {
        JourneyStatus.ACTIVE -> InfoBlue
        JourneyStatus.COMPLETED -> SuccessGreen
        JourneyStatus.EMERGENCY -> SOSRed
        JourneyStatus.CANCELLED -> Color.Gray
        else -> TextSecondary
    }
    Card(shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(j.destinationName.ifBlank { "Journey" }, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(j.status, color = statusColor, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
            Text("Started: ${fmtTime(j.startedAt)}", fontSize = 12.sp, color = TextSecondary)
            Text("ETA: ${fmtTime(j.expectedArrivalTime)}", fontSize = 12.sp, color = TextSecondary)
        }
    }
}

private fun fmtTime(ts: Long): String = if (ts <= 0) "-" else SimpleDateFormat("dd MMM, hh:mm a", Locale.getDefault()).format(Date(ts))
