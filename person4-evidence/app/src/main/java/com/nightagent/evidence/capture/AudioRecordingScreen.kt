package com.nightagent.evidence.capture

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nightagent.evidence.integration.AuthorizationResult
import com.nightagent.evidence.integration.EvidenceAuthorization
import com.nightagent.evidence.model.EvidenceOperation
import com.nightagent.evidence.permission.AppPermission
import com.nightagent.evidence.permission.PermissionRequester
import com.nightagent.evidence.permission.PermissionStateProvider
import com.nightagent.evidence.permission.PermissionStatus
import com.nightagent.evidence.storage.EncryptedAudioStore
import com.nightagent.evidence.storage.EncryptedEvidenceFailure
import com.nightagent.evidence.storage.EncryptedEvidenceOutcome
import com.nightagent.evidence.storage.EvidenceFailureDiagnostic
import com.nightagent.evidence.storage.LocalAudioStore
import com.nightagent.evidence.storage.diagnosticOrNull
import com.nightagent.evidence.storage.MediaValidation
import com.nightagent.evidence.storage.encryptedEvidenceOutcomeMessage
import com.nightagent.evidence.storage.validateMediaFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.Locale

/**
 * Foreground-only microphone recording with explicit consent and permission
 * checks through the existing [EvidenceAuthorization] layer.
 *
 * Phase 4A-2B encrypts a finalized, validated M4A before reporting success.
 * The plaintext source is not deleted until encryption and verification pass,
 * and lifecycle cleanup never removes a source that is awaiting encryption.
 */
@Composable
fun AudioRecordingScreen(
    authorization: EvidenceAuthorization,
    permissionStateProvider: PermissionStateProvider,
    permissionRequester: PermissionRequester,
    audioStore: LocalAudioStore,
    encryptedAudioStore: EncryptedAudioStore,
    onBack: () -> Unit,
    onOpenConsent: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var authorizationResult by remember { mutableStateOf<AuthorizationResult?>(null) }
    var microphonePermission by remember {
        mutableStateOf(permissionStateProvider.stateOf(AppPermission.RECORD_AUDIO).status)
    }
    var isRecording by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var publishedFileName by remember { mutableStateOf<String?>(null) }
    var currentFile by remember { mutableStateOf<File?>(null) }
    var pendingSourceFile by remember { mutableStateOf<File?>(null) }
    var isEncrypting by remember { mutableStateOf(false) }
    var elapsedMillis by remember { mutableStateOf(0L) }
    var recorderError by remember { mutableStateOf<String?>(null) }
    var failureDiagnostic by remember { mutableStateOf<EvidenceFailureDiagnostic?>(null) }

    val recorder = remember(context) {
        AudioRecorderController(context).also { controller ->
            controller.onError = { message -> recorderError = message }
        }
    }

    suspend fun refreshAuthorization() {
        authorizationResult = authorization.authorize(EvidenceOperation.CAPTURE_AUDIO)
    }

    fun refreshPermissionAndAuthorization() {
        microphonePermission = permissionStateProvider.stateOf(AppPermission.RECORD_AUDIO).status
        scope.launch { refreshAuthorization() }
    }

    /** Ends any active recording, releases resources and discards a partial file. */
    fun cancelRecording() {
        val file = currentFile
        recorder.release()
        isRecording = false
        currentFile = null
        // A finalized source awaiting encryption must never be deleted here.
        if (file != null && file != pendingSourceFile) audioStore.deleteIncomplete(file)
    }

    /** Starts encryption only after MediaRecorder has finalized a valid file. */
    fun startEncryption(file: File) {
        pendingSourceFile = file
        isEncrypting = true
        feedback = "Encrypting and verifying recording..."
        failureDiagnostic = null
        scope.launch {
            try {
                val outcome = try {
                    encryptedAudioStore.encryptAndPublish(file)
                } catch (e: Exception) {
                    EncryptedEvidenceOutcome.Failure(EncryptedEvidenceFailure.ENCRYPTION_FAILED)
                }
                failureDiagnostic = outcome.diagnosticOrNull()
                when (outcome) {
                    is EncryptedEvidenceOutcome.Success -> {
                        publishedFileName = outcome.encryptedName
                        feedback = audioOutcomeMessage(outcome)
                    }

                    is EncryptedEvidenceOutcome.MetadataPending -> {
                        publishedFileName = outcome.encryptedName
                        feedback = audioOutcomeMessage(outcome)
                    }

                    is EncryptedEvidenceOutcome.Failure -> {
                        publishedFileName = null
                        feedback = audioOutcomeMessage(outcome)
                    }
                }
            } finally {
                pendingSourceFile = null
                isEncrypting = false
            }
        }
    }

    fun stopRecording() {
        if (!recorder.isRecording || isEncrypting) return

        val file = currentFile
        val outcome = recorder.stop()
        isRecording = false
        currentFile = null

        if (!outcome.stoppedCleanly) {
            feedback = "Recording failed. Please try again."
            if (file != null) audioStore.deleteIncomplete(file)
            return
        }

        if (file == null) {
            feedback = "Recording stopped, but no file was available to verify."
            return
        }

        when (validateMediaFile(file)) {
            is MediaValidation.Valid -> startEncryption(file)

            else -> {
                audioStore.deleteIncomplete(file)
                publishedFileName = null
                feedback = "Recording was empty or incomplete and was discarded."
            }
        }
    }

    fun startRecording() {
        scope.launch {
            if (isEncrypting) return@launch
            feedback = null
            publishedFileName = null
            failureDiagnostic = null

            // Fresh check immediately before the microphone is activated.
            val result = authorization.authorize(EvidenceOperation.CAPTURE_AUDIO)
            authorizationResult = result
            if (result is AuthorizationResult.Denied) {
                feedback = "Recording blocked: ${authorizationMessage(result.reason)}"
                return@launch
            }

            if (recorder.isRecording || isRecording || isEncrypting) {
                feedback = "A recording or encryption is already in progress."
                return@launch
            }

            val file = try {
                audioStore.prepareOutput()
            } catch (e: IOException) {
                feedback = "Could not prepare audio storage. Please try again."
                return@launch
            }

            currentFile = file
            try {
                recorder.start(file)
                isRecording = true
            } catch (e: SecurityException) {
                audioStore.deleteIncomplete(file)
                currentFile = null
                feedback = "Microphone permission was revoked. Recording did not start."
                refreshPermissionAndAuthorization()
            } catch (e: IllegalStateException) {
                audioStore.deleteIncomplete(file)
                currentFile = null
                feedback = "Recording could not start. Please try again."
            } catch (e: Exception) {
                audioStore.deleteIncomplete(file)
                currentFile = null
                feedback = "Recording could not start. Please try again."
            }
        }
    }

    LaunchedEffect(Unit) { refreshAuthorization() }

    // MediaRecorder errors arrive on an internal thread; handle on composition.
    LaunchedEffect(recorderError) {
        recorderError?.let { message ->
            recorderError = null
            cancelRecording()
            feedback = "Recording stopped after a microphone error: $message"
        }
    }

    // Elapsed-time indicator while the microphone is active.
    LaunchedEffect(isRecording) {
        if (!isRecording) {
            elapsedMillis = 0L
            return@LaunchedEffect
        }
        val startedAt = SystemClock.elapsedRealtime()
        while (isActive) {
            elapsedMillis = SystemClock.elapsedRealtime() - startedAt
            delay(250L)
        }
    }

    // Recheck permission on resume; stop and release on pause/stop/disposal.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> refreshPermissionAndAuthorization()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    if (recorder.isRecording) cancelRecording()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            val file = currentFile
            recorder.release()
            // Do not touch a finalized source while encryption owns it.
            if (file != null && file != pendingSourceFile && !isEncrypting) {
                audioStore.deleteIncomplete(file)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Audio evidence (encrypted)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Finalized recordings are encrypted and verified before success is reported. " +
                "Plaintext staging is removed only after verification succeeds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = if (isRecording) {
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            } else {
                CardDefaults.cardColors()
            }
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = if (isRecording) "● Recording active" else "Microphone is off",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Microphone permission: " +
                        microphonePermission.name.lowercase().replace('_', ' '),
                    style = MaterialTheme.typography.bodySmall
                )
                if (isRecording) {
                    Spacer(Modifier.height(4.dp))
                    Text(text = "Duration: ${formatDuration(elapsedMillis)}")
                }
            }
        }

        val denial = authorizationResult as? AuthorizationResult.Denied
        when {
            authorizationResult == null -> {
                Text("Checking authorization...", style = MaterialTheme.typography.bodyMedium)
            }

            denial != null -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "Recording unavailable: ${authorizationMessage(denial.reason)}",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (denial.reason) {
                                AuthorizationResult.Reason.PERMISSION_MISSING,
                                AuthorizationResult.Reason.PERMISSION_PERMANENTLY_DENIED -> {
                                    Button(
                                        onClick = {
                                            permissionRequester.request(
                                                setOf(AppPermission.RECORD_AUDIO)
                                            ) { refreshPermissionAndAuthorization() }
                                        }
                                    ) { Text("Grant microphone permission") }

                                    if (denial.reason ==
                                        AuthorizationResult.Reason.PERMISSION_PERMANENTLY_DENIED
                                    ) {
                                        OutlinedButton(
                                            onClick = { permissionRequester.openAppSettings() }
                                        ) { Text("Open settings") }
                                    }
                                }

                                AuthorizationResult.Reason.CONSENT_NOT_GRANTED,
                                AuthorizationResult.Reason.CONSENT_REVOKED,
                                AuthorizationResult.Reason.CONSENT_OUTDATED -> {
                                    OutlinedButton(onClick = onOpenConsent) {
                                        Text("Manage consent")
                                    }
                                }
                            }
                        }
                    }
                }
            }

            else -> {
                Text(
                    "Authorized: AUDIO consent is current and RECORD_AUDIO permission is granted.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        feedback?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    message.startsWith("Encrypted recording saved") ->
                        MaterialTheme.colorScheme.primary
                    message == "Encrypting and verifying recording..." ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }

        publishedFileName?.let { name ->
            Text(
                text = "Published encrypted file: $name",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        failureDiagnostic?.let { diagnostic ->
            EncryptionDiagnosticCard(diagnostic)
        }

        Button(
            onClick = { startRecording() },
            modifier = Modifier.fillMaxWidth(),
            enabled = authorizationResult is AuthorizationResult.Authorized &&
                !isRecording && !isEncrypting
        ) {
            Text("Start Recording")
        }

        Button(
            onClick = { stopRecording() },
            modifier = Modifier.fillMaxWidth(),
            enabled = isRecording
        ) {
            Text("Stop Recording")
        }

        OutlinedButton(
            onClick = {
                cancelRecording()
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isEncrypting
        ) {
            Text("Back")
        }
    }
}

private fun formatDuration(elapsedMillis: Long): String {
    val totalSeconds = elapsedMillis / 1000
    return String.format(Locale.US, "%02d:%02d", totalSeconds / 60, totalSeconds % 60)
}

private fun authorizationMessage(reason: AuthorizationResult.Reason): String = when (reason) {
    AuthorizationResult.Reason.CONSENT_NOT_GRANTED ->
        "AUDIO consent has not been granted. Grant it in consent settings first."
    AuthorizationResult.Reason.CONSENT_REVOKED ->
        "AUDIO consent was revoked. Grant it again in consent settings."
    AuthorizationResult.Reason.CONSENT_OUTDATED ->
        "AUDIO consent is outdated. Renew it in consent settings."
    AuthorizationResult.Reason.PERMISSION_MISSING ->
        "Android RECORD_AUDIO permission is not granted."
    AuthorizationResult.Reason.PERMISSION_PERMANENTLY_DENIED ->
        "Android RECORD_AUDIO permission was permanently denied. Enable it in app settings."
}

private fun audioOutcomeMessage(outcome: EncryptedEvidenceOutcome): String =
    encryptedEvidenceOutcomeMessage("recording", "recording", outcome)
