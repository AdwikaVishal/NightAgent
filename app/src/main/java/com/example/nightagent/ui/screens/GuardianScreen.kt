package com.example.nightagent.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightagent.ui.demo.DemoData
import com.example.nightagent.ui.demo.Guardian
import com.example.nightagent.ui.theme.*

/**
 * Guardian management screen — currently UI-only.
 *
 * Guardian roster and alert inbox come from [DemoData]; there is no guardian
 * backend yet. "Mark primary" and "remove" only mutate local state. The invite
 * button uses a real ACTION_SEND share sheet, so it already works end-to-end.
 */
@Composable
fun GuardianScreen() {

    val context = LocalContext.current

    // Local mutable copies of the demo roster
    var guardians by remember { mutableStateOf(DemoData.guardians) }
    val alerts = DemoData.guardianAlerts

    var showInvite by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp)
        ) {

            // ── Header ────────────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(24.dp))
                Text(
                    "Guardians",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "People who can see your live location and listen during SOS",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))

                DemoBanner()
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Invite card ───────────────────────────────────────────────
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Lavender.copy(alpha = 0.15f)
                    ),
                    onClick = { showInvite = true }
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(PurpleEnd.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.PersonAdd, null,
                                tint = PurpleEnd, modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Invite a Guardian",
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                "Share your tracking link with someone you trust",
                                fontSize = 12.sp,
                                color = TextSecondary
                            )
                        }
                        Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── Roster ────────────────────────────────────────────────────
            item {
                Text(
                    "Your Guardians (${guardians.size})",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            items(guardians) { guardian ->
                GuardianCard(
                    guardian = guardian,
                    onMakePrimary = {
                        guardians = guardians.map {
                            it.copy(isPrimary = it.id == guardian.id)
                        }
                    },
                    onRemove = {
                        guardians = guardians.filter { it.id != guardian.id }
                    },
                    onCall = {
                        context.startActivity(
                            Intent(Intent.ACTION_DIAL, Uri.parse("tel:${guardian.phone}"))
                        )
                    }
                )
                Spacer(modifier = Modifier.height(10.dp))
            }

            // ── Alert inbox ───────────────────────────────────────────────
            item {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    "Guardian Activity",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(12.dp))
            }

            items(alerts.size) { index ->
                GuardianAlertRow(alerts[index])
                Spacer(modifier = Modifier.height(8.dp))
            }

            item { Spacer(modifier = Modifier.height(100.dp)) }
        }
    }

    // ── Invite dialog ─────────────────────────────────────────────────────
    if (showInvite) {
        val inviteLink = "https://nightagent.app/track/demo-user-123"
        AlertDialog(
            onDismissRequest = { showInvite = false },
            title = { Text("Invite a Guardian") },
            text = {
                Column {
                    Text("Share this tracking link with someone you trust. They can follow your live location during a journey or SOS.")
                    Spacer(modifier = Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Lavender.copy(alpha = 0.2f)
                    ) {
                        Text(
                            inviteLink,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            fontSize = 13.sp,
                            color = PurpleEnd
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "I'm adding you as my guardian on NightAgent. Track me here if I need help:\n$inviteLink"
                            )
                        }
                    )
                    showInvite = false
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { showInvite = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun GuardianCard(
    guardian: Guardian,
    onMakePrimary: () -> Unit,
    onRemove: () -> Unit,
    onCall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (guardian.isPrimary) BlushPink.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(
                        if (guardian.isPrimary) BlushPink.copy(alpha = 0.4f)
                        else Lavender.copy(alpha = 0.25f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    guardian.name.take(1).uppercase(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = if (guardian.isPrimary) PurpleEnd else PurpleStart
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        guardian.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp,
                        color = TextPrimary
                    )
                    if (guardian.isPrimary) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            Icons.Default.Star, "Primary guardian",
                            tint = Color(0xFFFFD700), modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    "${guardian.relation} · ${guardian.phone}",
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(
                                if (guardian.isAvailable) SuccessGreen else Color.Gray,
                                CircleShape
                            )
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        if (guardian.isAvailable) "Available · ${guardian.lastSeen}"
                        else guardian.lastSeen,
                        fontSize = 12.sp,
                        color = if (guardian.isAvailable) SuccessGreen else TextSecondary
                    )
                }
            }

            IconButton(onClick = onCall, modifier = Modifier.size(40.dp)) {
                Icon(
                    Icons.Default.Call, "Call",
                    tint = SuccessGreen, modifier = Modifier.size(22.dp)
                )
            }

            // Overflow menu: primary / remove
            var menuOpen by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.MoreVert, "Options", tint = TextSecondary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Mark as primary") },
                        onClick = { onMakePrimary(); menuOpen = false },
                        leadingIcon = { Icon(Icons.Default.Star, null, tint = Color(0xFFFFD700)) }
                    )
                    DropdownMenuItem(
                        text = { Text("Remove guardian", color = SOSRed) },
                        onClick = { onRemove(); menuOpen = false },
                        leadingIcon = { Icon(Icons.Default.Delete, null, tint = SOSRed) }
                    )
                }
            }
        }
    }
}

@Composable
private fun GuardianAlertRow(alert: com.example.nightagent.ui.demo.GuardianAlert) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (alert.isUnread) Icons.Default.NotificationsActive else Icons.Default.CheckCircle,
                null,
                tint = if (alert.isUnread) PurpleEnd else SuccessGreen,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${alert.guardianName} · ${alert.time}",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    color = TextPrimary
                )
                Text(alert.message, fontSize = 12.sp, color = TextSecondary)
            }
            if (alert.isUnread) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(PurpleEnd, CircleShape)
                )
            }
        }
    }
}

/** Small chip shown on screens powered by demo data. */
@Composable
fun DemoBanner() {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = InfoBlue.copy(alpha = 0.12f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info, null,
                tint = InfoBlue, modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                "Demo data — backend not connected yet",
                fontSize = 12.sp,
                color = InfoBlue,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
