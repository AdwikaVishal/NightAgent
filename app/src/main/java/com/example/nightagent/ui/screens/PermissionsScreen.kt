package com.example.nightagent.ui.screens

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.core.content.ContextCompat
import com.example.nightagent.ui.theme.*

/**
 * Permissions screen — fully functional, no backend needed.
 *
 * Reads the real grant state of every permission NightAgent relies on and lets
 * the user request each one individually. This exists because MainActivity
 * requests everything at once on first launch; this screen is the friendly
 * recovery path when something was denied.
 */
@Composable
fun PermissionsScreen() {

    val context = LocalContext.current

    // Force a re-read whenever something might have changed
    var refreshKey by remember { mutableStateOf(0) }

    data class PermSpec(
        val permission: String,
        val title: String,
        val why: String,
        val icon: ImageVector,
        val tint: Color,
        val maxSdk: Int? = null,   // permission irrelevant above this SDK level
        val minSdkLevel: Int? = null   // permission irrelevant below this SDK level
    )

    val specs = listOf(
        PermSpec(
            Manifest.permission.SEND_SMS,
            "SMS", "Sends your SOS message with location to guardians",
            Icons.Default.Sms, SOSRed
        ),
        PermSpec(
            Manifest.permission.ACCESS_FINE_LOCATION,
            "Location", "Live location for SOS, Safe Walk and tracking links",
            Icons.Default.LocationOn, InfoBlue
        ),
        PermSpec(
            Manifest.permission.RECORD_AUDIO,
            "Microphone", "Voice SOS keywords, live guardian audio, evidence recording",
            Icons.Default.Mic, PurpleEnd
        ),
        PermSpec(
            Manifest.permission.CAMERA,
            "Camera", "Photo and video evidence during an emergency",
            Icons.Default.PhotoCamera, Color(0xFFFF9800)
        ),
        PermSpec(
            Manifest.permission.POST_NOTIFICATIONS,
            "Notifications", "SOS alerts and voice message notifications",
            Icons.Default.Notifications, SuccessGreen,
            minSdkLevel = 33   // runtime permission only from Android 13; auto-granted below
        )
    )

    fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp)
    ) {

        item {
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                "Permissions",
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "NightAgent needs these to protect you. Granting more means faster help in an emergency.",
                fontSize = 13.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(20.dp))

            val grantedCount = specs.count {
                val aboveMax = it.maxSdk?.let { m -> Build.VERSION.SDK_INT > m } == true
                val belowMin = it.minSdkLevel?.let { m -> Build.VERSION.SDK_INT < m } == true
                aboveMax || belowMin || isGranted(it.permission)
            }
            val total = specs.size
            key(refreshKey) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$grantedCount of $total granted",
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.width(10.dp))
                LinearProgressIndicator(
                    progress = { grantedCount.toFloat() / total },
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(4.dp)),
                    color = SuccessGreen,
                    trackColor = Lavender.copy(alpha = 0.3f)
                )
            }
            }
            Spacer(modifier = Modifier.height(20.dp))
        }

        items(specs.size) { index ->
            val spec = specs[index]
            val irrelevant = spec.maxSdk?.let { Build.VERSION.SDK_INT > it } == true ||
                spec.minSdkLevel?.let { Build.VERSION.SDK_INT < it } == true
            val granted = irrelevant || isGranted(spec.permission)
            key(refreshKey) {
                PermissionRow(
                    permission = spec.permission,
                    title = spec.title,
                    why = spec.why,
                    icon = spec.icon,
                    tint = spec.tint,
                    granted = granted,
                    irrelevant = irrelevant,
                    onResult = { refreshKey++ }
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Tip: if a permission was permanently denied, use Settings → Apps → NightAgent → Permissions to enable it manually.",
                fontSize = 12.sp,
                color = TextSecondary
            )
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

/**
 * One permission row. The request launcher must live at this level so each row
 * has its own ActivityResult contract; [onResult] refreshes the list state.
 */
@Composable
private fun PermissionRow(
    permission: String,
    title: String,
    why: String,
    icon: ImageVector,
    tint: Color,
    granted: Boolean,
    irrelevant: Boolean,
    onResult: () -> Unit
) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { onResult() }

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
                    .background(tint.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary)
                Text(why, fontSize = 12.sp, color = TextSecondary)
            }
            Spacer(modifier = Modifier.width(10.dp))

            when {
                irrelevant -> Text(
                    "Built-in",
                    fontSize = 12.sp,
                    color = TextSecondary,
                    fontWeight = FontWeight.Medium
                )
                granted -> Icon(
                    Icons.Default.CheckCircle,
                    "Granted", tint = SuccessGreen, modifier = Modifier.size(26.dp)
                )
                else -> Button(
                    onClick = { launcher.launch(permission) },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PurpleEnd)
                ) {
                    Text("Grant", fontSize = 13.sp)
                }
            }
        }
    }
}

