package com.nightagent.evidence.vault

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nightagent.evidence.devtest.Phase5DeletionTestController
import com.nightagent.evidence.metadata.EvidenceMetadata
import com.nightagent.evidence.metadata.EvidenceMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Local-only list of registered encrypted evidence. No plaintext paths are shown. */
@Composable
fun EvidenceVaultScreen(
    repository: EvidenceMetadataRepository,
    deletionTestController: Phase5DeletionTestController,
    onOpenDetails: (EvidenceMetadata) -> Unit,
    onBack: () -> Unit
) {
    var filter by remember { mutableStateOf(EvidenceFilter.ALL) }
    var uiState by remember { mutableStateOf<EvidenceVaultUiState>(EvidenceVaultUiState.Loading) }
    var reloadToken by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    var testRecordId by remember { mutableStateOf<String?>(null) }
    var testStatus by remember { mutableStateOf<String?>(null) }
    var isTestBusy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        testRecordId = deletionTestController.currentTestEvidenceId()
    }

    LaunchedEffect(filter, reloadToken) {
        uiState = EvidenceVaultUiState.Loading
        val mediaType = filter.mediaType()
        val flow = if (mediaType == null) {
            repository.observeAll()
        } else {
            repository.observeByMediaType(mediaType)
        }
        flow.collect { result -> uiState = result.toVaultUiState() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Evidence Vault",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            OutlinedButton(onClick = onBack) { Text("Back") }
        }

        Spacer(Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EvidenceFilter.entries.forEach { option ->
                FilterChip(
                    selected = option == filter,
                    onClick = { filter = option },
                    label = { Text(option.label()) }
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "DEVELOPMENT TEST - deletion harness",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Creates one synthetic .ng1 record in a dedicated directory. " +
                        "Only that record can be deleted here; captured evidence is never selectable.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (isTestBusy) return@Button
                        isTestBusy = true
                        scope.launch {
                            try {
                                val outcome = deletionTestController.create()
                                testStatus = outcome.statusText
                                testRecordId = outcome.evidenceId
                                reloadToken++
                            } catch (e: CancellationException) {
                                testStatus = "Creation cancelled; no test record is active"
                            } catch (e: Exception) {
                                testStatus = "Creation failed (${e.javaClass.simpleName})"
                            } finally {
                                isTestBusy = false
                            }
                        }
                    },
                    enabled = !isTestBusy && testRecordId == null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (isTestBusy) "Working..." else "Create temporary test record")
                }

                if (testRecordId != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Temporary test record: $testRecordId",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            if (isTestBusy) return@Button
                            isTestBusy = true
                            scope.launch {
                                try {
                                    val outcome = deletionTestController.delete()
                                    testStatus = outcome.statusText
                                    testRecordId = deletionTestController.currentTestEvidenceId()
                                    reloadToken++
                                } catch (e: CancellationException) {
                                    testStatus = "Deletion cancelled; test record may still exist"
                                } catch (e: Exception) {
                                    testStatus = "Deletion failed (${e.javaClass.simpleName})"
                                } finally {
                                    isTestBusy = false
                                }
                            }
                        },
                        enabled = !isTestBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Delete temporary test record")
                    }
                }

                testStatus?.let { status ->
                    Spacer(Modifier.height(10.dp))
                    Text(text = status, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        when (val state = uiState) {
            is EvidenceVaultUiState.Loading -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Loading evidence records...")
            }

            is EvidenceVaultUiState.Error -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = state.message,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { reloadToken++ }) { Text("Retry") }
            }

            is EvidenceVaultUiState.Ready -> {
                if (state.records.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No encrypted evidence is registered yet.",
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Captured evidence appears here after encryption and verification succeed.",
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.records, key = { it.evidenceId }) { record ->
                            EvidenceVaultRecordCard(record, onOpenDetails)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EvidenceVaultRecordCard(
    record: EvidenceMetadata,
    onOpenDetails: (EvidenceMetadata) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenDetails(record) }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "${record.mediaLabel()}  ${shortenEvidenceId(record.evidenceId)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Text("Created: ${formatEvidenceTimestamp(record.createdAtMillis)}")
            Text("Encrypted size: ${formatEvidenceSize(record.containerSizeBytes)}")
            Text("Storage: ${registrationStatusLabel(record.registrationStatus)}")
            Text(
                text = "Tap to inspect and verify",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
            )
        }
    }
}
