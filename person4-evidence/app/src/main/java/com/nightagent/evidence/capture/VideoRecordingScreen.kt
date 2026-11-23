package com.nightagent.evidence.capture

import android.os.SystemClock
import android.util.Log
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nightagent.evidence.integration.AuthorizationResult
import com.nightagent.evidence.integration.CombinedEvidenceAuthorization
import com.nightagent.evidence.model.EvidenceOperation
import com.nightagent.evidence.permission.AppPermission
import com.nightagent.evidence.permission.PermissionRequester
import com.nightagent.evidence.permission.PermissionStateProvider
import com.nightagent.evidence.permission.PermissionStatus
import com.nightagent.evidence.storage.EncryptedEvidenceFailure
import com.nightagent.evidence.storage.EncryptedEvidenceOutcome
import com.nightagent.evidence.storage.EncryptedVideoStore
import com.nightagent.evidence.storage.EvidenceFailureDiagnostic
import com.nightagent.evidence.storage.LocalVideoStore
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

private const val TAG = "VideoCamera"

/**
 * Foreground-only video recording with microphone audio.
 *
 * Both CAPTURE_VIDEO and CAPTURE_AUDIO consent are required through the shared
 * [CombinedEvidenceAuthorization]. Both Android permissions are also required.
 * Video is recorded with CameraX VideoCapture, never with MediaRecorder. The
 * MP4 is encrypted and verified only after CameraX reports ERROR_NONE at
 * Finalize and the file passes validation; plaintext is removed only then.
 */
@Composable
fun VideoRecordingScreen(
    videoAuthorization: CombinedEvidenceAuthorization,
    permissionStateProvider: PermissionStateProvider,
    permissionRequester: PermissionRequester,
    videoStore: LocalVideoStore,
    encryptedVideoStore: EncryptedVideoStore,
    onBack: () -> Unit,
    onOpenConsent: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val previewView = remember(context) { PreviewView(context) }
    val recordingRef = remember { mutableStateOf<Recording?>(null) }
    val videoCaptureRef = remember { mutableStateOf<VideoCapture<Recorder>?>(null) }

    var cameraPermission by remember {
        mutableStateOf(permissionStateProvider.stateOf(AppPermission.CAMERA).status)
    }
    var microphonePermission by remember {
        mutableStateOf(permissionStateProvider.stateOf(AppPermission.RECORD_AUDIO).status)
    }
    var authorizationResult by remember { mutableStateOf<AuthorizationResult?>(null) }
    var cameraState by remember {
        mutableStateOf(CameraInitializationState.INITIALIZING)
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraRetryToken by remember { mutableStateOf(0) }
    var isRecording by remember { mutableStateOf(false) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var publishedFileName by remember { mutableStateOf<String?>(null) }
    var currentFile by remember { mutableStateOf<File?>(null) }
    var pendingSourceFile by remember { mutableStateOf<File?>(null) }
    var isEncrypting by remember { mutableStateOf(false) }
    var elapsedMillis by remember { mutableStateOf(0L) }
    var failureDiagnostic by remember { mutableStateOf<EvidenceFailureDiagnostic?>(null) }

    suspend fun refreshAuthorization() {
        authorizationResult = videoAuthorization.authorizeVideoWithAudio()
    }

    fun refreshPermissionAndAuthorization() {
        cameraPermission = permissionStateProvider.stateOf(AppPermission.CAMERA).status
        microphonePermission = permissionStateProvider.stateOf(AppPermission.RECORD_AUDIO).status
        scope.launch { refreshAuthorization() }
    }

    fun missingPermissions(): Set<AppPermission> = buildSet {
        if (permissionStateProvider.stateOf(AppPermission.CAMERA).status !=
            PermissionStatus.GRANTED
        ) {
            add(AppPermission.CAMERA)
        }
        if (permissionStateProvider.stateOf(AppPermission.RECORD_AUDIO).status !=
            PermissionStatus.GRANTED
        ) {
            add(AppPermission.RECORD_AUDIO)
        }
    }

    /**
     * Cancels an active recording and discards the partial file. This path is
     * deliberately independent of consent/authorization so stopping is always
     * possible, even if permissions are revoked mid-recording.
     */
    fun discardActiveRecording() {
        val recording = recordingRef.value
        val file = currentFile
        recordingRef.value = null
        currentFile = null
        isRecording = false

        if (recording != null) {
            runCatching { recording.stop() }
            runCatching { recording.close() }
        }
        // A finalized source awaiting encryption must never be deleted here.
        if (file != null && file != pendingSourceFile) videoStore.deleteIncomplete(file)
    }

    /**
     * Clears screen-owned camera references. The CameraX effect owns the
     * provider and performs the matching unbind in its own disposal, so this
     * function must not unbind the process-wide provider directly.
     */
    fun releaseCameraResources() {
        discardActiveRecording()
        videoCaptureRef.value = null
    }

    /** Starts encryption only after a successful, validated Finalize event. */
    fun startEncryption(file: File) {
        if (isEncrypting || pendingSourceFile != null) return
        pendingSourceFile = file
        isEncrypting = true
        feedback = "Encrypting and verifying video..."
        failureDiagnostic = null
        scope.launch {
            try {
                val outcome = try {
                    encryptedVideoStore.encryptAndPublish(file)
                } catch (e: Exception) {
                    EncryptedEvidenceOutcome.Failure(
                        EncryptedEvidenceFailure.ENCRYPTION_FAILED
                    )
                }
                failureDiagnostic = outcome.diagnosticOrNull()
                when (outcome) {
                    is EncryptedEvidenceOutcome.Success -> {
                        publishedFileName = outcome.encryptedName
                        feedback = videoOutcomeMessage(outcome)
                    }

                    is EncryptedEvidenceOutcome.MetadataPending -> {
                        publishedFileName = outcome.encryptedName
                        feedback = videoOutcomeMessage(outcome)
                    }

                    is EncryptedEvidenceOutcome.Failure -> {
                        publishedFileName = null
                        feedback = videoOutcomeMessage(outcome)
                    }
                }
            } finally {
                pendingSourceFile = null
                isEncrypting = false
            }
        }
    }

    /** Handles the sole point at which CameraX says the video is finalized. */
    fun handleFinalize(event: VideoRecordEvent.Finalize) {
        val eventFile = (event.outputOptions as? FileOutputOptions)?.file
        val error = event.error
        if (error != VideoRecordEvent.Finalize.ERROR_NONE) {
            Log.e(TAG, "Video recording finalized with error code=$error")
        } else {
            Log.d(TAG, "Video recording finalized successfully")
        }

        // A duplicate/stale Finalize must not discard a source already owned by
        // the encryption pipeline.
        if (isEncrypting && eventFile != null && eventFile == pendingSourceFile) return

        val decision = evaluateVideoFinalization(
            error = error,
            eventFile = eventFile,
            currentFile = currentFile,
            hasActiveRecording = recordingRef.value != null
        )

        runCatching { recordingRef.value?.close() }
        recordingRef.value = null
        currentFile = null
        isRecording = false

        when (decision.action) {
            VideoFinalizationAction.ENCRYPT -> {
                val file = decision.fileToEncrypt ?: return
                startEncryption(file)
            }

            VideoFinalizationAction.DISCARD -> {
                decision.fileToDiscard?.let(videoStore::deleteIncomplete)
                publishedFileName = null
                when (decision.reason) {
                    VideoFinalizationReason.FAILED ->
                        feedback = "Video recording failed: ${videoErrorMessage(error)}"
                    VideoFinalizationReason.INVALID ->
                        feedback = "Video was empty or incomplete and was discarded."
                    VideoFinalizationReason.CANCELLED, VideoFinalizationReason.READY -> Unit
                }
            }

            VideoFinalizationAction.IGNORE -> Unit
        }
    }

    fun stopRecording() {
        val recording = recordingRef.value ?: return
        runCatching { recording.stop() }.onFailure {
            discardActiveRecording()
            feedback = "Could not stop recording. Please try again."
        }
    }

    fun startRecording() {
        scope.launch {
            if (isEncrypting) return@launch
            feedback = null
            publishedFileName = null
            failureDiagnostic = null
            Log.d(TAG, "Video recording start requested")

            // Fresh combined check immediately before the camera is activated.
            val result = videoAuthorization.authorizeVideoWithAudio()
            authorizationResult = result
            if (result is AuthorizationResult.Denied) {
                feedback = "Recording blocked: ${authorizationMessage(result)}"
                return@launch
            }

            if (recordingRef.value != null || isRecording || isEncrypting) {
                feedback = "A recording or encryption is already in progress."
                return@launch
            }

            val videoCapture = videoCaptureRef.value
            if (cameraState != CameraInitializationState.READY || videoCapture == null) {
                feedback = "The camera is not ready yet. Please wait and try again."
                return@launch
            }

            if (missingPermissions().isNotEmpty()) {
                refreshPermissionAndAuthorization()
                feedback = "CAMERA and RECORD_AUDIO permissions are required. " +
                    "Recording did not start."
                return@launch
            }

            val file = try {
                videoStore.prepareOutput()
            } catch (e: IOException) {
                feedback = "Could not prepare video storage. Please try again."
                return@launch
            }

            currentFile = file
            try {
                val outputOptions = FileOutputOptions.Builder(file).build()
                val pendingRecording = videoCapture.output
                    .prepareRecording(context, outputOptions)
                    .withAudioEnabled()

                pendingRecording
                    .start(ContextCompat.getMainExecutor(context)) { event ->
                        when (event) {
                            is VideoRecordEvent.Finalize -> handleFinalize(event)
                            else -> Unit
                        }
                    }
                    .also { recording ->
                        recordingRef.value = recording
                        isRecording = true
                        Log.d(TAG, "Video recording started")
                    }
            } catch (e: SecurityException) {
                Log.w(TAG, "Video recording start denied: ${e::class.java.simpleName}")
                videoStore.deleteIncomplete(file)
                currentFile = null
                feedback = "Camera or microphone permission was revoked. " +
                    "Recording did not start."
                refreshPermissionAndAuthorization()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Video recording start failed: ${e::class.java.simpleName}")
                videoStore.deleteIncomplete(file)
                currentFile = null
                feedback = "Video recording could not start. Please try again."
                refreshPermissionAndAuthorization()
            } catch (e: Exception) {
                Log.e(TAG, "Video recording start failed: ${e::class.java.simpleName}")
                videoStore.deleteIncomplete(file)
                currentFile = null
                feedback = "Video recording could not start. Please try again."
            }
        }
    }

    LaunchedEffect(Unit) { refreshAuthorization() }

    // One effect owns the whole CameraX session. The provider stays local so
    // disposal cannot read a newer Composition value and unbind a session that
    // was created after this effect began.
    DisposableEffect(
        cameraPermission,
        microphonePermission,
        lifecycleOwner,
        cameraRetryToken
    ) {
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null

        val reportInitFailure: (Exception) -> Unit = { thrown ->
            if (!disposed) {
                val cause = thrown.cause ?: thrown
                cameraState = CameraInitializationState.INITIALIZATION_FAILED
                cameraError = when (cause) {
                    is CameraInfoUnavailableException ->
                        "Camera information is unavailable right now. Please try again."
                    is SecurityException ->
                        "Camera or microphone permission is no longer granted."
                    is IllegalStateException ->
                        "The camera or microphone is unavailable or already in use."
                    else -> "Could not start the camera. Please try again."
                }
                Log.e(TAG, "Camera initialization failed: ${cause::class.java.simpleName}")
                if (cause is SecurityException) refreshPermissionAndAuthorization()
            }
        }

        val permissionsGranted = cameraPermission == PermissionStatus.GRANTED &&
            microphonePermission == PermissionStatus.GRANTED
        if (!permissionsGranted) {
            releaseCameraResources()
            cameraState = CameraInitializationState.UNAVAILABLE
            cameraError = null
            Log.d(TAG, "Camera initialization skipped: CAMERA/RECORD_AUDIO not granted")
        } else {
            cameraState = CameraInitializationState.INITIALIZING
            cameraError = null
            videoCaptureRef.value = null
            Log.d(TAG, "Camera initialization started: retry=$cameraRetryToken")

            val future = ProcessCameraProvider.getInstance(context)
            future.addListener(
                {
                    if (disposed) return@addListener
                    try {
                        val provider = future.get()
                        if (disposed) return@addListener
                        boundProvider = provider

                        if (!provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                            cameraState = CameraInitializationState.UNAVAILABLE
                            cameraError = "The back camera is not available on this device."
                            Log.w(TAG, "Back camera is unavailable")
                            return@addListener
                        }

                        // Preview and VideoCapture must be bound together only
                        // after PreviewView has a measured size and surface.
                        previewView.doOnLayout {
                            if (disposed) return@doOnLayout
                            try {
                                val preview = Preview.Builder().build().also { useCase ->
                                    useCase.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val recorder = Recorder.Builder()
                                    .setQualitySelector(QualitySelector.from(Quality.HIGHEST))
                                    .build()
                                val videoCapture = VideoCapture.withOutput(recorder)

                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    videoCapture
                                )
                                videoCaptureRef.value = videoCapture
                                cameraState = CameraInitializationState.READY
                                cameraError = null
                                Log.d(TAG, "Camera bound: preview and VideoCapture ready")
                            } catch (e: Exception) {
                                reportInitFailure(e)
                            }
                        }
                    } catch (e: Exception) {
                        reportInitFailure(e)
                    }
                },
                ContextCompat.getMainExecutor(context)
            )
        }

        onDispose {
            disposed = true
            boundProvider?.unbindAll()
            videoCaptureRef.value = null
            Log.d(TAG, "Camera session disposed and use cases unbound")
        }
    }

    // Elapsed-time indicator while the recording is active.
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

    // Recheck permissions on resume; always cancel on pause/stop/disposal.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> refreshPermissionAndAuthorization()
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> discardActiveRecording()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            releaseCameraResources()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Video evidence (encrypted)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Finalized videos are encrypted and verified before success is reported. " +
                "Plaintext staging is removed only after verification succeeds.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(Color.Black)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            when {
                cameraPermission != PermissionStatus.GRANTED ||
                    microphonePermission != PermissionStatus.GRANTED -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Card(modifier = Modifier.padding(24.dp)) {
                            Text(
                                "CAMERA and RECORD_AUDIO permissions are required " +
                                    "for video with audio.",
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }

                cameraState == CameraInitializationState.INITIALIZING -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(Modifier.height(8.dp))
                            Text("Initializing camera...", color = Color.White)
                        }
                    }
                }

                cameraState == CameraInitializationState.INITIALIZATION_FAILED ||
                    cameraState == CameraInitializationState.UNAVAILABLE -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Card(
                            modifier = Modifier.padding(24.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    cameraError ?: "Camera initialization failed.",
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Spacer(Modifier.height(8.dp))
                                OutlinedButton(
                                    onClick = { cameraRetryToken++ }
                                ) { Text("Retry camera") }
                            }
                        }
                    }
                }
            }
        }

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
                    text = if (isRecording) "● Recording active" else "Camera and microphone are idle",
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "CAMERA: ${cameraPermission.name.lowercase().replace('_', ' ')}  |  " +
                        "RECORD_AUDIO: ${microphonePermission.name.lowercase().replace('_', ' ')}",
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
                            "Recording unavailable: ${authorizationMessage(denial)}",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (denial.reason) {
                                AuthorizationResult.Reason.PERMISSION_MISSING,
                                AuthorizationResult.Reason.PERMISSION_PERMANENTLY_DENIED -> {
                                    Button(
                                        onClick = {
                                            val missing = missingPermissions()
                                            permissionRequester.request(missing) {
                                                refreshPermissionAndAuthorization()
                                            }
                                        }
                                    ) { Text("Grant permissions") }

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
                    "Authorized: VIDEO and AUDIO consents are current, and " +
                        "CAMERA and RECORD_AUDIO permissions are granted.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        feedback?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    message.startsWith("Encrypted video saved") ->
                        MaterialTheme.colorScheme.primary
                    message == "Encrypting and verifying video..." ->
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
                cameraState == CameraInitializationState.READY && !isRecording && !isEncrypting
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
                discardActiveRecording()
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

private fun authorizationMessage(denial: AuthorizationResult.Denied): String {
    val consentLabel = when (denial.operation) {
        EvidenceOperation.CAPTURE_AUDIO -> "AUDIO"
        EvidenceOperation.CAPTURE_VIDEO -> "VIDEO"
        else -> denial.operation.name
    }
    return when (denial.reason) {
        AuthorizationResult.Reason.CONSENT_NOT_GRANTED ->
            "$consentLabel consent has not been granted. Grant it in consent settings first."
        AuthorizationResult.Reason.CONSENT_REVOKED ->
            "$consentLabel consent was revoked. Grant it again in consent settings."
        AuthorizationResult.Reason.CONSENT_OUTDATED ->
            "$consentLabel consent is outdated. Renew it in consent settings."
        AuthorizationResult.Reason.PERMISSION_MISSING ->
            "Android CAMERA and RECORD_AUDIO permissions are both required."
        AuthorizationResult.Reason.PERMISSION_PERMANENTLY_DENIED ->
            "CAMERA or RECORD_AUDIO permission was permanently denied. " +
                "Enable them in app settings."
    }
}

private fun videoErrorMessage(error: Int): String = when (error) {
    VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE -> "device storage is full"
    VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED -> "the file size limit was reached"
    VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED -> "the duration limit was reached"
    VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA -> "no valid video data was produced"
    VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ->
        "the camera or microphone became unavailable"
    VideoRecordEvent.Finalize.ERROR_ENCODING_FAILED -> "video encoding failed"
    VideoRecordEvent.Finalize.ERROR_RECORDER_ERROR -> "the recorder reported an error"
    VideoRecordEvent.Finalize.ERROR_INVALID_OUTPUT_OPTIONS -> "the output file could not be used"
    VideoRecordEvent.Finalize.ERROR_RECORDING_GARBAGE_COLLECTED ->
        "the recording was released before it finalized"
    VideoRecordEvent.Finalize.ERROR_UNKNOWN -> "an unknown recorder error occurred"
    VideoRecordEvent.Finalize.ERROR_NONE -> "the recorder reported an unspecified failure"
    else -> "unrecognized error code $error"
}

private fun videoOutcomeMessage(outcome: EncryptedEvidenceOutcome): String =
    encryptedEvidenceOutcomeMessage("video", "recording", outcome)
