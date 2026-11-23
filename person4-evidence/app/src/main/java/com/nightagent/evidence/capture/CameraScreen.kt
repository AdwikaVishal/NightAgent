package com.nightagent.evidence.capture

import android.util.Log
import androidx.camera.core.CameraInfoUnavailableException
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.nightagent.evidence.integration.AuthorizationResult
import com.nightagent.evidence.integration.EvidenceAuthorization
import com.nightagent.evidence.model.EvidenceOperation
import com.nightagent.evidence.permission.AppPermission
import com.nightagent.evidence.permission.PermissionRequester
import com.nightagent.evidence.permission.PermissionStateProvider
import com.nightagent.evidence.permission.PermissionStatus
import com.nightagent.evidence.storage.EncryptedEvidenceOutcome
import com.nightagent.evidence.storage.EncryptedPhotoStore
import com.nightagent.evidence.storage.EvidenceFailureDiagnostic
import com.nightagent.evidence.storage.LocalPhotoStore
import com.nightagent.evidence.storage.diagnosticOrNull
import com.nightagent.evidence.storage.encryptedEvidenceOutcomeMessage
import kotlinx.coroutines.launch
import java.io.IOException

private const val TAG = "PhotoCamera"

/**
 * Camera screen: live back-camera preview and single photo capture.
 *
 * All capture decisions go through the existing [EvidenceAuthorization]. The
 * CameraX flow still writes to app-private plaintext staging first; Phase 4A-2A
 * encrypts, verifies and publishes a copy before reporting success. No plaintext
 * path is ever displayed in the UI.
 */
@Composable
fun CameraScreen(
    authorization: EvidenceAuthorization,
    permissionStateProvider: PermissionStateProvider,
    permissionRequester: PermissionRequester,
    photoStore: LocalPhotoStore,
    encryptedPhotoStore: EncryptedPhotoStore,
    onBack: () -> Unit,
    onOpenConsent: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val previewView = remember(context) { PreviewView(context) }
    val imageCaptureRef = remember { mutableStateOf<ImageCapture?>(null) }

    var cameraPermission by remember {
        mutableStateOf(permissionStateProvider.stateOf(AppPermission.CAMERA).status)
    }
    var authorizationResult by remember { mutableStateOf<AuthorizationResult?>(null) }
    var cameraState by remember {
        mutableStateOf(CameraInitializationState.INITIALIZING)
    }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var cameraRetryToken by remember { mutableStateOf(0) }
    var feedback by remember { mutableStateOf<String?>(null) }
    var isProcessing by remember { mutableStateOf(false) }
    var failureDiagnostic by remember { mutableStateOf<EvidenceFailureDiagnostic?>(null) }

    suspend fun refreshAuthorization() {
        authorizationResult = authorization.authorize(EvidenceOperation.CAPTURE_PHOTO)
    }

    fun refreshPermissionAndAuthorization() {
        cameraPermission = permissionStateProvider.stateOf(AppPermission.CAMERA).status
        scope.launch { refreshAuthorization() }
    }

    LaunchedEffect(Unit) { refreshAuthorization() }

    // Permission can change while the user is in Android settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshPermissionAndAuthorization()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // One effect owns the whole CameraX session. The provider is kept in a local
    // variable instead of Composition state so cleanup can never read a newer
    // value and unbind a session that was bound after this effect started.
    DisposableEffect(cameraPermission, lifecycleOwner, cameraRetryToken) {
        var disposed = false
        var boundProvider: ProcessCameraProvider? = null

        val reportInitFailure: (Exception) -> Unit = { thrown ->
            if (!disposed) {
                val cause = thrown.cause ?: thrown
                cameraState = CameraInitializationState.INITIALIZATION_FAILED
                cameraError = when (cause) {
                    is CameraInfoUnavailableException ->
                        "Camera information is unavailable right now. Please try again."
                    is SecurityException -> "Camera permission is no longer granted."
                    is IllegalStateException ->
                        "The camera is unavailable or already in use by another app."
                    else -> "Could not start the camera. Please try again."
                }
                Log.e(TAG, "Camera initialization failed: ${cause::class.java.simpleName}")
                if (cause is SecurityException) refreshPermissionAndAuthorization()
            }
        }

        if (cameraPermission != PermissionStatus.GRANTED) {
            cameraState = CameraInitializationState.UNAVAILABLE
            cameraError = null
            imageCaptureRef.value = null
            Log.d(TAG, "Camera initialization skipped: permission not granted")
        } else {
            cameraState = CameraInitializationState.INITIALIZING
            cameraError = null
            imageCaptureRef.value = null
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

                        // A PreviewView without a measured size cannot supply a
                        // surface. Bind only after layout so Preview receives a
                        // live surface and ImageCapture joins the same session.
                        previewView.doOnLayout {
                            if (disposed) return@doOnLayout
                            try {
                                val preview = Preview.Builder().build().also { useCase ->
                                    useCase.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val capture = ImageCapture.Builder()
                                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                                    .build()

                                provider.unbindAll()
                                provider.bindToLifecycle(
                                    lifecycleOwner,
                                    CameraSelector.DEFAULT_BACK_CAMERA,
                                    preview,
                                    capture
                                )
                                imageCaptureRef.value = capture
                                cameraState = CameraInitializationState.READY
                                cameraError = null
                                Log.d(TAG, "Camera bound: preview and ImageCapture ready")
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
            imageCaptureRef.value = null
            Log.d(TAG, "Camera session disposed and use cases unbound")
        }
    }

    fun capturePhoto() {
        scope.launch {
            if (isProcessing) return@launch
            feedback = null
            failureDiagnostic = null

            // Re-check at the moment of capture: consent may have been revoked
            // or the permission removed since the screen was opened.
            val result = authorization.authorize(EvidenceOperation.CAPTURE_PHOTO)
            authorizationResult = result
            if (result is AuthorizationResult.Denied) {
                feedback = "Capture blocked: ${authorizationMessage(result.reason)}"
                return@launch
            }

            val capture = imageCaptureRef.value
            if (cameraState != CameraInitializationState.READY || capture == null) {
                feedback = "The camera is not ready yet. Please wait and try again."
                return@launch
            }

            val pending = try {
                photoStore.prepareOutput()
            } catch (e: IOException) {
                feedback = "Could not prepare photo storage. Please try again."
                return@launch
            }

            isProcessing = true
            Log.d(TAG, "Photo capture requested")
            capture.takePicture(
                pending.outputOptions,
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val saved = pending.file
                        if (!saved.exists() || saved.length() <= 0L) {
                            isProcessing = false
                            feedback = "Capture finished but the photo file could not be verified."
                            return
                        }

                        scope.launch {
                            feedback = "Encrypting and verifying photo..."
                            failureDiagnostic = null
                            try {
                                val outcome = encryptedPhotoStore.encryptAndPublish(saved)
                                failureDiagnostic = outcome.diagnosticOrNull()
                                feedback = photoOutcomeMessage(outcome)
                            } finally {
                                isProcessing = false
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        isProcessing = false
                        Log.e(
                            TAG,
                            "Photo capture failed: code=${exception.imageCaptureError}"
                        )
                        cameraState = CameraInitializationState.INITIALIZATION_FAILED
                        cameraError =
                            "Photo capture failed. The camera session may have ended; " +
                                "reopen the camera to retry."
                        feedback = "Photo capture failed. Please try again."
                    }
                }
            )
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Photo evidence (encrypted)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Captured photos are encrypted and verified before success is reported. " +
                "Plaintext staging is removed after verification when possible.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(340.dp)
                .background(Color.Black)
        ) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

            when {
                cameraPermission != PermissionStatus.GRANTED -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Card(modifier = Modifier.padding(24.dp)) {
                            Text(
                                "Camera permission is required for the preview.",
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
                            "Capture unavailable: ${authorizationMessage(denial.reason)}",
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (denial.reason) {
                                AuthorizationResult.Reason.PERMISSION_MISSING,
                                AuthorizationResult.Reason.PERMISSION_PERMANENTLY_DENIED -> {
                                    Button(
                                        onClick = {
                                            permissionRequester.request(setOf(AppPermission.CAMERA)) {
                                                refreshPermissionAndAuthorization()
                                            }
                                        }
                                    ) { Text("Grant camera permission") }

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
                    "Authorized: PHOTO consent is current and CAMERA permission is granted.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        feedback?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = when {
                    message.startsWith("Encrypted photo saved") ->
                        MaterialTheme.colorScheme.primary
                    message == "Encrypting and verifying photo..." ->
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                }
            )
        }

        failureDiagnostic?.let { diagnostic ->
            EncryptionDiagnosticCard(diagnostic)
        }

        Button(
            onClick = { capturePhoto() },
            modifier = Modifier.fillMaxWidth(),
            enabled = authorizationResult is AuthorizationResult.Authorized &&
                cameraState == CameraInitializationState.READY && !isProcessing
        ) {
            Text("Capture photo")
        }

        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}

private fun authorizationMessage(reason: AuthorizationResult.Reason): String = when (reason) {
    AuthorizationResult.Reason.CONSENT_NOT_GRANTED ->
        "PHOTO consent has not been granted. Grant it in consent settings first."
    AuthorizationResult.Reason.CONSENT_REVOKED ->
        "PHOTO consent was revoked. Grant it again in consent settings."
    AuthorizationResult.Reason.CONSENT_OUTDATED ->
        "PHOTO consent is outdated. Renew it in consent settings."
    AuthorizationResult.Reason.PERMISSION_MISSING ->
        "Android CAMERA permission is not granted."
    AuthorizationResult.Reason.PERMISSION_PERMANENTLY_DENIED ->
        "Android CAMERA permission was permanently denied. Enable it in app settings."
}

private fun photoOutcomeMessage(outcome: EncryptedEvidenceOutcome): String =
    encryptedEvidenceOutcomeMessage("photo", "photo", outcome)
