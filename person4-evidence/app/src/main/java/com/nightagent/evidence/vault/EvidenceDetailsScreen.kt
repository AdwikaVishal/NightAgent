package com.nightagent.evidence.vault

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nightagent.evidence.devtest.Phase5AccessTestController
import com.nightagent.evidence.integrity.EvidenceIntegrityVerifier
import com.nightagent.evidence.integrity.IntegrityStatus
import com.nightagent.evidence.integrity.IntegrityVerificationResult
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Read-only details and on-demand local integrity verification. */
@Composable
fun EvidenceDetailsScreen(
    initialRecord: EvidenceMetadata,
    repository: EvidenceMetadataRepository,
    verifier: EvidenceIntegrityVerifier,
    accessTestController: Phase5AccessTestController,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val controller = remember(initialRecord.evidenceId) {
        EvidenceDetailsController(repository, verifier)
    }

    var record by remember(initialRecord.evidenceId) { mutableStateOf(initialRecord) }
    var verification by remember(initialRecord.evidenceId) {
        mutableStateOf<IntegrityVerificationResult?>(null)
    }
    var isVerifying by remember(initialRecord.evidenceId) { mutableStateOf(false) }
    var actionError by remember(initialRecord.evidenceId) { mutableStateOf<String?>(null) }
    var digestCopied by remember(initialRecord.evidenceId) { mutableStateOf(false) }
    var accessStatus by remember(initialRecord.evidenceId) { mutableStateOf<String?>(null) }
    var accessMediaType by remember(initialRecord.evidenceId) { mutableStateOf<String?>(null) }
    var plaintextPresent by remember(initialRecord.evidenceId) { mutableStateOf(false) }
    var sessionActive by remember(initialRecord.evidenceId) {
        mutableStateOf(accessTestController.hasActiveSession())
    }
    var isOpening by remember(initialRecord.evidenceId) { mutableStateOf(false) }

    // Leaving this screen closes any temporary plaintext held for this session.
    DisposableEffect(initialRecord.evidenceId) {
        onDispose {
            accessTestController.close()
        }
    }

    LaunchedEffect(initialRecord.evidenceId) {
        controller.loadLatest(initialRecord.evidenceId)?.let { record = it }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Evidence Details",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        Spacer(Modifier.height(16.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                DetailRow("Evidence ID", record.evidenceId)
                DetailRow("Media type", record.mediaLabel())
                DetailRow("Original extension", record.originalExtension)
                DetailRow("Created", formatEvidenceTimestamp(record.createdAtMillis))
                DetailRow("Encrypted size", formatEvidenceSize(record.containerSizeBytes))
                DetailRow("Storage status", registrationStatusLabel(record.registrationStatus))
                DetailRow("Metadata schema", record.schemaVersion.toString())

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "SHA-256 (local fingerprint)",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = record.sha256,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        clipboard.setText(AnnotatedString(record.sha256))
                        digestCopied = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (digestCopied) "Digest copied" else "Copy SHA-256")
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Button(
            onClick = {
                if (isVerifying) return@Button
                isVerifying = true
                actionError = null
                scope.launch {
                    try {
                        when (val attempt = controller.verifyOnce(record.evidenceId)) {
                            is VerificationAttempt.Completed -> {
                                verification = attempt.result
                                (attempt.metadataAfter ?: attempt.metadataBefore)?.let {
                                    record = it
                                }
                            }

                            VerificationAttempt.Duplicate -> {
                                actionError = "A verification request is already running."
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        actionError = e.message ?: "Integrity verification could not be started."
                    } finally {
                        isVerifying = false
                    }
                }
            },
            enabled = !isVerifying,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isVerifying) "Verifying..." else "Verify integrity")
        }

        if (isVerifying) {
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text("Checking file, digest and NGE1 authentication", textAlign = TextAlign.Center)
            }
        }

        actionError?.let { message ->
            Spacer(Modifier.height(12.dp))
            Text(text = message, color = MaterialTheme.colorScheme.error)
        }

        verification?.let { result ->
            Spacer(Modifier.height(16.dp))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Integrity outcome",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(text = "Status: ${result.status.name}")
                    Text(text = integrityStatusDescription(result.status))
                    result.detail?.let { detail ->
                        Spacer(Modifier.height(6.dp))
                        Text(text = detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DEVELOPMENT TEST - decrypted access",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Available only after integrity verification reports VERIFIED for this record.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        if (isOpening) return@Button
                        isOpening = true
                        scope.launch {
                            try {
                                val outcome = accessTestController.open(record.evidenceId)
                                accessStatus = outcome.statusText
                                accessMediaType = outcome.mediaType
                                plaintextPresent = outcome.plaintextExists
                                sessionActive = accessTestController.hasActiveSession()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                accessStatus = "Access could not be started " +
                                    "(${e.javaClass.simpleName})"
                            } finally {
                                isOpening = false
                            }
                        }
                    },
                    enabled = verification?.status == IntegrityStatus.VERIFIED &&
                        verification?.evidenceId == record.evidenceId &&
                        !sessionActive && !isOpening,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isOpening) "Opening..." else "Open decrypted evidence")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        val outcome = accessTestController.close()
                        accessStatus = outcome.statusText
                        plaintextPresent = if (outcome.hadSession) !outcome.cleanedUp else false
                        sessionActive = accessTestController.hasActiveSession()
                        if (plaintextPresent.not()) accessMediaType = null
                    },
                    enabled = sessionActive,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close access session")
                }
                accessStatus?.let { status ->
                    Spacer(Modifier.height(10.dp))
                    Text(text = status, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(6.dp))
                DetailRow("Decrypted media type", accessMediaType ?: "none")
                DetailRow(
                    "Temporary plaintext exists",
                    if (plaintextPresent) "yes" else "no"
                )
                Text(
                    text = "No plaintext path or contents are displayed or logged.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            text = "A local hash and metadata record are consistency checks only. They do not " +
                "prove provenance or an independently trusted chain of custody.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
    }
}
