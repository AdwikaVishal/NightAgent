package com.example.nightagent.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.nightagent.ui.demo.DemoData
import com.example.nightagent.ui.demo.EvidenceRecord
import com.example.nightagent.ui.theme.*

/**
 * Evidence Vault screen — currently UI-only.
 *
 * Lists past SOS evidence records (audio chunks, photos, video, location,
 * SHA-256 integrity hash) from [DemoData]. The real capture pipeline lives in
 * the `evidence` package (EvidenceCaptureService + EvidenceUploadWorker) and is
 * reachable from the SOS flow / Evidence tab; this vault will read the real
 * `evidence/{sosId}` Firestore records once wired up.
 *
 * "Share" uses a real ACTION_SEND intent with the record summary, so it works
 * without any backend.
 */
@Composable
fun EvidenceVaultScreen() {

    val context = LocalContext.current

    var filter by remember { mutableStateOf("All") }
    var selected by remember { mutableStateOf<EvidenceRecord?>(null) }

    val records = DemoData.evidenceRecords

    val filtered = when (filter) {
        "Audio" -> records.filter { it.audioFiles > 0 }
        "Photo" -> records.filter { it.photoFiles > 0 }
        "Video" -> records.filter { it.videoFiles > 0 }
        else -> records
    }

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
                    "Evidence Vault",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Tamper-evident records captured during SOS events",
                    fontSize = 13.sp,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(16.dp))
                DemoBanner()
                Spacer(modifier = Modifier.height(16.dp))
            }

            // ── Summary cards ─────────────────────────────────────────────
            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    VaultStat(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Folder,
                        label = "Records",
                        value = records.size.toString(),
                        tint = PurpleEnd
                    )
                    VaultStat(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.CloudDone,
                        label = "Uploaded",
                        value = records.count { it.uploaded }.toString(),
                        tint = SuccessGreen
                    )
                    VaultStat(
                        modifier = Modifier.weight(1f),
                        icon = Icons.Default.Storage,
                        label = "Total size",
                        value = "%.0f MB".format(records.sumOf { it.sizeMb }),
                        tint = InfoBlue
                    )
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            // ── Filters ───────────────────────────────────────────────────
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("All", "Audio", "Photo", "Video").forEach { chip ->
                        FilterChip(
                            selected = filter == chip,
                            onClick = { filter = chip },
                            label = { Text(chip, fontSize = 13.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BlushPink.copy(alpha = 0.5f),
                                selectedLabelColor = PurpleEnd
                            )
                        )
                    }
                }
                Spacer(modifier = Modifier.height(14.dp))
            }

            // ── Records ───────────────────────────────────────────────────
            items(filtered.size) { index ->
                EvidenceRecordCard(filtered[index], onClick = { selected = filtered[index] })
                Spacer(modifier = Modifier.height(10.dp))
            }

            if (filtered.isEmpty()) {
                item {
                    Text(
                        "No records for this filter",
                        color = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }

            item { Spacer(modifier = Modifier.height(100.dp)) }
        }
    }

    // ── Detail dialog ─────────────────────────────────────────────────────
    selected?.let { record ->
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text("SOS Evidence · ${record.date}") },
            text = {
                Column {
                    DetailRow("Time", record.time)
                    DetailRow("SOS ID", record.sosId)
                    DetailRow("Audio chunks", record.audioFiles.toString())
                    DetailRow("Photos", record.photoFiles.toString())
                    DetailRow("Videos", record.videoFiles.toString())
                    DetailRow("Size", "%.1f MB".format(record.sizeMb))
                    DetailRow("Location", record.location)
                    DetailRow("SHA-256", record.sha256Short)
                    DetailRow("Upload", if (record.uploaded) "Uploaded to cloud" else "Pending network")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(
                                Intent.EXTRA_TEXT,
                                "NightAgent evidence record ${record.sosId} (${record.date} ${record.time}) — " +
                                    "${record.audioFiles} audio, ${record.photoFiles} photos, ${record.videoFiles} video. " +
                                    "SHA-256 ${record.sha256Short}"
                            )
                        }
                    )
                }) { Text("Share") }
            },
            dismissButton = {
                TextButton(onClick = { selected = null }) { Text("Close") }
            }
        )
    }
}

@Composable
private fun VaultStat(
    modifier: Modifier = Modifier,
    icon: ImageVector,
    label: String,
    value: String,
    tint: Color
) {
    Card(
        modifier = modifier.padding(end = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
            Text(label, fontSize = 11.sp, color = TextSecondary)
        }
    }
}

@Composable
private fun EvidenceRecordCard(record: EvidenceRecord, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(Lavender.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Shield,
                    null, tint = PurpleStart, modifier = Modifier.size(22.dp)
                )
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${record.date} · ${record.time}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = TextPrimary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (!record.uploaded) {
                        Icon(
                            Icons.Default.CloudOff, "Not uploaded",
                            tint = Color(0xFFFF9800), modifier = Modifier.size(15.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    buildString {
                        append("${record.audioFiles} audio")
                        if (record.photoFiles > 0) append(" · ${record.photoFiles} photo")
                        if (record.videoFiles > 0) append(" · ${record.videoFiles} video")
                        append(" · %.1f MB".format(record.sizeMb))
                    },
                    fontSize = 12.sp,
                    color = TextSecondary
                )
                Text(
                    "SHA-256 ${record.sha256Short}",
                    fontSize = 11.sp,
                    color = PurpleEnd
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = TextSecondary)
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        Text(
            label,
            fontSize = 13.sp,
            color = TextSecondary,
            modifier = Modifier.width(110.dp)
        )
        Text(value, fontSize = 13.sp, color = TextPrimary, fontWeight = FontWeight.Medium)
    }
}
