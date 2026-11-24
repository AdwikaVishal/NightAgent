package com.nightagent.evidence.consent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nightagent.evidence.model.ConsentKey
import com.nightagent.evidence.model.ConsentRecord
import com.nightagent.evidence.model.EvidenceType
import com.nightagent.evidence.permission.AppPermission
import com.nightagent.evidence.permission.PermissionState
import com.nightagent.evidence.permission.PermissionStateProvider
import com.nightagent.evidence.permission.PermissionStatus
import com.nightagent.evidence.permission.PermissionRequester
import kotlinx.coroutines.launch

@Composable
fun ConsentScreen(
    repository: ConsentRepository,
    permissionStateProvider: PermissionStateProvider,
    permissionRequester: PermissionRequester,
    onBack: () -> Unit
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val consents by repository.observeAll().collectAsState(initial = emptyMap())

    val permissionStates = remember { mutableStateMapOf<AppPermission, PermissionState>() }

    var permissionExplanationFor by remember { mutableStateOf<EvidenceType?>(null) }

    fun refreshPermissions() {
        AppPermission.entries.forEach { permission ->
            permissionStates[permission] = permissionStateProvider.stateOf(permission)
        }
    }

    LaunchedEffect(Unit) { refreshPermissions() }

    // Recheck when returning from Android application settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val pendingType = permissionExplanationFor
    if (pendingType != null) {
        AlertDialog(
            onDismissRequest = { permissionExplanationFor = null },
            title = { Text("Allow ${pendingType.name.lowercase()} capture?") },
            text = { Text(permissionExplanation(pendingType)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        permissionExplanationFor = null
                        scope.launch {
                            repository.grant(ConsentKey.capture(pendingType))
                            refreshPermissions()
                            permissionRequester.request(requiredPermissions(pendingType)) {
                                refreshPermissions()
                            }
                        }
                    }
                ) { Text("Continue") }
            },
            dismissButton = {
                TextButton(onClick = { permissionExplanationFor = null }) { Text("Cancel") }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Evidence Consent",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Please read before enabling", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "Nothing is recorded by this screen. Consent only allows the " +
                        "module to capture evidence if a future capture feature is started.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Photo, audio and video are separate choices. Cloud upload is " +
                        "a separate choice too - allowing capture never allows upload.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "You can revoke any consent here at any time. Android permissions " +
                        "are also required and are requested only after you enable a " +
                        "capability below.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Consent and permissions do not guarantee recording in every " +
                        "Android background situation; the system may restrict camera " +
                        "or microphone access.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ConsentToggleRow(
            title = "Emergency photo capture",
            description = "Allows the app to take a photograph when an evidence capture flow is started during an emergency.",
            record = consents[ConsentKey.capture(EvidenceType.PHOTO)],
            onCheckedChange = { enabled ->
                onCaptureToggle(
                    enabled = enabled,
                    type = EvidenceType.PHOTO,
                    key = ConsentKey.capture(EvidenceType.PHOTO),
                    repository = repository,
                    scope = scope,
                    requestPermissionExplanation = { permissionExplanationFor = it }
                )
            }
        )

        PermissionStatusSection(
            title = "Camera permission (photo)",
            permissions = requiredPermissions(EvidenceType.PHOTO),
            consentValid = isCaptureValid(consents[ConsentKey.capture(EvidenceType.PHOTO)]),
            states = permissionStates,
            onRequest = {
                permissionRequester.request(requiredPermissions(EvidenceType.PHOTO)) { refreshPermissions() }
            },
            onOpenSettings = { permissionRequester.openAppSettings() }
        )

        ConsentToggleRow(
            title = "Emergency audio recording",
            description = "Allows the app to record sound when an evidence capture flow is started during an emergency.",
            record = consents[ConsentKey.capture(EvidenceType.AUDIO)],
            onCheckedChange = { enabled ->
                onCaptureToggle(
                    enabled = enabled,
                    type = EvidenceType.AUDIO,
                    key = ConsentKey.capture(EvidenceType.AUDIO),
                    repository = repository,
                    scope = scope,
                    requestPermissionExplanation = { permissionExplanationFor = it }
                )
            }
        )

        PermissionStatusSection(
            title = "Microphone permission (audio)",
            permissions = requiredPermissions(EvidenceType.AUDIO),
            consentValid = isCaptureValid(consents[ConsentKey.capture(EvidenceType.AUDIO)]),
            states = permissionStates,
            onRequest = {
                permissionRequester.request(requiredPermissions(EvidenceType.AUDIO)) { refreshPermissions() }
            },
            onOpenSettings = { permissionRequester.openAppSettings() }
        )

        ConsentToggleRow(
            title = "Emergency video recording",
            description = "Allows the app to record video with sound when an evidence capture flow is started during an emergency.",
            record = consents[ConsentKey.capture(EvidenceType.VIDEO)],
            onCheckedChange = { enabled ->
                onCaptureToggle(
                    enabled = enabled,
                    type = EvidenceType.VIDEO,
                    key = ConsentKey.capture(EvidenceType.VIDEO),
                    repository = repository,
                    scope = scope,
                    requestPermissionExplanation = { permissionExplanationFor = it }
                )
            }
        )

        PermissionStatusSection(
            title = "Camera + microphone permissions (video)",
            permissions = requiredPermissions(EvidenceType.VIDEO),
            consentValid = isCaptureValid(consents[ConsentKey.capture(EvidenceType.VIDEO)]),
            states = permissionStates,
            onRequest = {
                permissionRequester.request(requiredPermissions(EvidenceType.VIDEO)) { refreshPermissions() }
            },
            onOpenSettings = { permissionRequester.openAppSettings() }
        )

        ConsentToggleRow(
            title = "Cloud upload (separate)",
            description = "Separate choice. Allows captured evidence to be uploaded to cloud storage. This phase stores only your decision - no evidence or uploading exists yet.",
            record = consents[ConsentKey.CLOUD_UPLOAD],
            onCheckedChange = { enabled ->
                scope.launch {
                    if (enabled) repository.grant(ConsentKey.CLOUD_UPLOAD)
                    else repository.revoke(ConsentKey.CLOUD_UPLOAD)
                }
            }
        )

        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
    }
}

private fun onCaptureToggle(
    enabled: Boolean,
    type: EvidenceType,
    key: ConsentKey,
    repository: ConsentRepository,
    scope: kotlinx.coroutines.CoroutineScope,
    requestPermissionExplanation: (EvidenceType) -> Unit
) {
    if (enabled) {
        // Explanation is shown before any Android permission is requested.
        requestPermissionExplanation(type)
    } else {
        scope.launch { repository.revoke(key) }
    }
}

private fun requiredPermissions(type: EvidenceType): Set<AppPermission> = when (type) {
    EvidenceType.PHOTO -> setOf(AppPermission.CAMERA)
    EvidenceType.AUDIO -> setOf(AppPermission.RECORD_AUDIO)
    EvidenceType.VIDEO -> setOf(AppPermission.CAMERA, AppPermission.RECORD_AUDIO)
}

private fun permissionExplanation(type: EvidenceType): String = when (type) {
    EvidenceType.PHOTO ->
        "Photo capture needs the camera permission. The app will ask Android for camera " +
            "access only after you confirm. Consent alone does not enable capture."

    EvidenceType.AUDIO ->
        "Audio recording needs the microphone permission. The app will ask Android for " +
            "microphone access only after you confirm. Consent alone does not enable recording."

    EvidenceType.VIDEO ->
        "Video recording needs both the camera and microphone permissions. The app will " +
            "ask Android for both only after you confirm. Consent alone does not enable recording."
}

private fun isCaptureValid(record: ConsentRecord?): Boolean =
    record?.isGranted == true && record.version == ConsentTerms.CURRENT_VERSION

@Composable
private fun ConsentToggleRow(
    title: String,
    description: String,
    record: ConsentRecord?,
    onCheckedChange: (Boolean) -> Unit
) {
    val status = consentStatus(record)
    val checked = isCaptureValid(record)
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text(description, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = status.text,
                    style = MaterialTheme.typography.labelMedium,
                    color = status.color()
                )
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun PermissionStatusSection(
    title: String,
    permissions: Set<AppPermission>,
    consentValid: Boolean,
    states: Map<AppPermission, PermissionState>,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val permanentlyDenied = permissions.any {
        states[it]?.status == PermissionStatus.PERMANENTLY_DENIED
    }
    val allGranted = permissions.all { states[it]?.isGranted == true }

    Column(modifier = Modifier.padding(horizontal = 8.dp)) {
        Text(
            text = "$title: ${
                permissions.joinToString(", ") { permission ->
                    "${permission.name.lowercase()} ${
                        states[permission]?.status?.name?.lowercase() ?: "unknown"
                    }"
                }
            }",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (consentValid && !allGranted) {
            Spacer(Modifier.height(6.dp))
            if (permanentlyDenied) {
                Text(
                    "Android permission was permanently denied. Open app settings to change it.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(onClick = onOpenSettings) { Text("Open app settings") }
            } else {
                OutlinedButton(onClick = onRequest) { Text("Grant permission") }
            }
        }
    }
}

private data class ConsentStatus(val text: String, val isProblem: Boolean)

@Composable
private fun ConsentStatus.color() =
    if (isProblem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary

private fun consentStatus(record: ConsentRecord?): ConsentStatus = when {
    record == null || (!record.isGranted && record.revokedAt == null) ->
        ConsentStatus("Not granted", isProblem = false)

    !record.isGranted && record.revokedAt != null ->
        ConsentStatus("Revoked - tap the switch to grant again", isProblem = true)

    record.version != ConsentTerms.CURRENT_VERSION ->
        ConsentStatus("Outdated consent - renewal required", isProblem = true)

    else -> ConsentStatus("Granted", isProblem = false)
}
