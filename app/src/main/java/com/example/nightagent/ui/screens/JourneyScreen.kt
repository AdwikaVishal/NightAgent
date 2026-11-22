package com.example.nightagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightagent.ui.demo.DemoData
import com.example.nightagent.ui.demo.JourneyRecord
import com.example.nightagent.ui.theme.*
import kotlinx.coroutines.delay

/**
 * Safe Journey screen with check-in UI — currently UI-only.
 *
 * The "ongoing" journey, its timer/distance and the check-in countdown are all
 * demo simulation (no real SafeWalk session is started here and no real SOS is
 * triggered when the countdown expires — a warning banner is shown instead).
 * The "Open Safe Walk" button routes to the existing, real SafeWalkScreen.
 * Journey history comes from [DemoData].
 *
 * Check-in model: the user must tap "I'm Safe" before the countdown reaches
 * zero; guardians are (in the real product) alerted if they don't.
 */
@Composable
fun JourneyScreen(onOpenSafeWalk: () -> Unit = {}) {

    val history = DemoData.journeys

    // ── Demo live journey state ──────────────────────────────────────────
    var elapsedSec by remember { mutableStateOf(0) }
    var distanceKm by remember { mutableStateOf(0.42) }
    var checkInsDone by remember { mutableStateOf(1) }

    // Check-in countdown: 5 minutes per check-in window
    var checkInRemaining by remember { mutableStateOf(300) }
    var missedCheckIn by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            elapsedSec += 1
            distanceKm += 0.0004   // ~1.4 m/s
            if (!missedCheckIn && checkInRemaining > 0) {
                checkInRemaining -= 1
                if (checkInRemaining == 0) missedCheckIn = true
            }
        }
    }

    fun checkIn() {
        checkInRemaining = 300
        missedCheckIn = false
        checkInsDone += 1
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
    ) {

        // ── Header ───────────────────────────────────────────────────────
        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Safe Journey",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Check in during your walk so guardians stay calm",
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(16.dp))
            DemoBanner()
            Spacer(modifier = Modifier.height(16.dp))
        }

        // ── Ongoing journey card ─────────────────────────────────────────
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Lavender.copy(alpha = 0.18f)
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(SuccessGreen, CircleShape)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Journey in progress",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = TextPrimary
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            "Live",
                            fontSize = 12.sp,
                            color = SuccessGreen,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Route
                    RouteRow("Office, Tech Park", Icons.Default.RadioButtonChecked, PurpleStart)
                    Spacer(modifier = Modifier.height(6.dp))
                    RouteRow("Home, MG Road", Icons.Default.Place, PurpleEnd)

                    Spacer(modifier = Modifier.height(16.dp))

                    // Stats
                    Row {
                        JourneyStat(
                            modifier = Modifier.weight(1f),
                            label = "Elapsed",
                            value = "%d:%02d".format(elapsedSec / 60, elapsedSec % 60)
                        )
                        JourneyStat(
                            modifier = Modifier.weight(1f),
                            label = "Distance",
                            value = "%.2f km".format(distanceKm)
                        )
                        JourneyStat(
                            modifier = Modifier.weight(1f),
                            label = "Check-ins",
                            value = checkInsDone.toString()
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // ── Check-in countdown ring ─────────────────────────
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val progress = checkInRemaining / 300f
                        androidx.compose.foundation.Canvas(
                            modifier = Modifier.size(130.dp)
                        ) {
                            drawArc(
                                color = Lavender.copy(alpha = 0.4f),
                                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 10f, cap = StrokeCap.Round
                                )
                            )
                            drawArc(
                                color = if (missedCheckIn) SOSRed
                                else if (progress < 0.25f) Color(0xFFFF9800)
                                else SuccessGreen,
                                startAngle = -90f,
                                sweepAngle = 360f * progress,
                                useCenter = false,
                                style = androidx.compose.ui.graphics.drawscope.Stroke(
                                    width = 10f, cap = StrokeCap.Round
                                )
                            )
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (missedCheckIn) "Missed!"
                                else "%d:%02d".format(checkInRemaining / 60, checkInRemaining % 60),
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (missedCheckIn) SOSRed else TextPrimary
                            )
                            Text(
                                "until next check-in",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (missedCheckIn) {
                        // Demo-only warning — real SOS is NOT triggered here
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = SOSRed.copy(alpha = 0.12f)
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, null, tint = SOSRed, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    "Check-in missed — guardians would be alerted (demo)",
                                    fontSize = 12.sp,
                                    color = SOSRed,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Check-in button
                    Button(
                        onClick = { checkIn() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                    ) {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(22.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "I'm Safe — Check In",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = onOpenSafeWalk,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(Icons.Default.Map, null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Open live Safe Walk map")
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // ── History ──────────────────────────────────────────────────────
        item {
            Text(
                "Journey History",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        items(history.size) { index ->
            JourneyHistoryCard(history[index])
            Spacer(modifier = Modifier.height(10.dp))
        }

        item { Spacer(modifier = Modifier.height(100.dp)) }
    }
}

@Composable
private fun RouteRow(place: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(18.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(place, fontSize = 14.sp, color = TextPrimary)
    }
}

@Composable
private fun JourneyStat(modifier: Modifier = Modifier, label: String, value: String) {
    Surface(
        modifier = modifier.padding(end = 8.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, fontWeight = FontWeight.Bold, fontSize = 17.sp, color = PurpleEnd)
            Text(label, fontSize = 11.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun JourneyHistoryCard(journey: JourneyRecord) {
    val statusColor = when (journey.status) {
        "Ongoing" -> InfoBlue
        "Completed with alert" -> SOSRed
        else -> SuccessGreen
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(statusColor.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (journey.status == "Ongoing") Icons.AutoMirrored.Filled.DirectionsWalk
                    else Icons.Default.CheckCircle,
                    null, tint = statusColor, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${journey.from} → ${journey.to}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    color = TextPrimary,
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "${journey.date} · ${journey.distanceKm} km · ${journey.durationMin} min · ${journey.checkIns} check-ins",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
            }
            Text(
                journey.status,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = statusColor
            )
        }
    }
}
