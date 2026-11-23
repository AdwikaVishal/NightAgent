package com.nightagent.evidence.capture

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nightagent.evidence.storage.EvidenceFailureDiagnostic
import com.nightagent.evidence.storage.toDisplayText

/**
 * In-app developer diagnostic shown when encrypted publication fails.
 *
 * It renders only sanitized identifiers (operation, stage, exception class,
 * numeric error code, and whether publication / metadata registration were
 * reached). It never displays private paths, key material, plaintext or raw
 * exception messages, so it is safe to show on-device without ADB.
 */
@Composable
fun EncryptionDiagnosticCard(
    diagnostic: EvidenceFailureDiagnostic,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Developer diagnostic (no private data)",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = diagnostic.toDisplayText(),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
