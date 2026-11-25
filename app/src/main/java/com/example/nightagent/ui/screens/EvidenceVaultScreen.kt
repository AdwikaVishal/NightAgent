package com.example.nightagent.ui.screens

import android.content.Intent
import android.util.Log
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
import com.example.nightagent.ui.theme.*
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A record produced by EvidenceUploadWorker at `evidence/{sosId}`.
 * The vault is read-only; capture and upload remain in the `evidence` package.
 */
data class EvidenceVaultRecord(
    val id: String,
    val sosId: String,
    val date: String,
    val time: String,
    val audioFiles: Int,
    val photoFiles: Int,
    val videoFiles: Int,
    val sizeMb: Double,
    val location: String,
    val sha256Short: String,
    val uploaded: Boolean
)

/**
 * Evidence Vault screen backed by real Firestore evidence records.
 *
 * Only the signed-in owner's records are queried; Firestore rules enforce the
 * same ownership check server-side. "Share" uses a real ACTION_SEND intent.
 */
@Composable
fun EvidenceVaultScreen() {

    val context = LocalContext.current

    var filter by remember { mutableStateOf("All") }
    var selected by remember { mutableStateOf<EvidenceVaultRecord?>(null) }
    var records by remember { mutableStateOf<List<EvidenceVaultRecord>>(emptyList()) }
    var status by remember { mutableStateOf("Loading evidence…") }

    LaunchedEffect(Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) {
            status = "Sign in to view your evidence vault"
            return@LaunchedEffect
        }
        try {
            val snapshot = Firebase.firestore
                .collection("evidence")
                .whereEqualTo("userId", uid)
                .orderBy("timestamp", Query.Direction.DESCENDING)
                .get()
                .await()
            records = snapshot.documents.mapNotNull { doc -> doc.toEvidenceVaultRecord() }
            status = if (records.isEmpty()) "No evidence captured yet" else ""
        } catch (e: Exception) {
            Log.w("EvidenceVault", "Failed to load evidence: ${e.message}")
            status = "Could not load evidence: ${e.message ?: "unknown error"}"
        }
    }

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
            if (status.isNotBlank()) {
                item {
                    Text(
                        status,
                        color = TextSecondary,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                }
            }
            items(filtered.size) { index ->
                EvidenceVaultRecordCard(filtered[index], onClick = { selected = filtered[index] })
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
private fun EvidenceVaultRecordCard(record: EvidenceVaultRecord, onClick: () -> Unit) {
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

private fun com.google.firebase.firestore.DocumentSnapshot.toEvidenceVaultRecord(): EvidenceVaultRecord? {
    val sosId = getString("sosId")?.takeIf { it.isNotBlank() } ?: id
    val timestamp = getLong("timestamp") ?: 0L
    val fileUrls = get("fileUrls") as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val hashes = get("hashes") as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val sizes = get("fileSizes") as? Map<*, *> ?: emptyMap<Any?, Any?>()
    val paths = fileUrls.keys.mapNotNull { it as? String }
    val firstHash = hashes.values.firstOrNull() as? String
    val dateFormat = SimpleDateFormat("dd MMM yyyy", Locale.US)
    val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
    val date = Date(timestamp)
    val lat = getDouble("latitude")
    val lng = getDouble("longitude")
    return EvidenceVaultRecord(
        id = id,
        sosId = sosId,
        date = dateFormat.format(date),
        time = timeFormat.format(date),
        audioFiles = paths.count { it.startsWith("audio/") },
        photoFiles = paths.count { it.startsWith("photos/") },
        videoFiles = paths.count { it.startsWith("video/") },
        sizeMb = sizes.values.filterIsInstance<Number>().sumOf { it.toLong() } / 1_000_000.0,
        location = if (lat != null && lng != null) {
            String.format(Locale.US, "%.4f, %.4f", lat, lng)
        } else {
            "Not recorded"
        },
        sha256Short = firstHash?.let { it.take(4) + "…" + it.takeLast(4) } ?: "unavailable",
        uploaded = true // the record only exists after upload and Firestore write
    )
}
