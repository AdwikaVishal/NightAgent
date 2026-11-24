package com.nightagent.evidence.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun EvidenceReadyScreen(
    onManageConsent: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenAudio: () -> Unit,
    onOpenVideo: () -> Unit,
    onOpenVault: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "NightAgent - Person 4 Evidence Module",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground
        )
        Text(
            text = "The independent module is ready.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 12.dp)
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onOpenCamera, modifier = Modifier.fillMaxWidth()) {
            Text("Open camera (photo)")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenAudio, modifier = Modifier.fillMaxWidth()) {
            Text("Open audio recording")
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = onOpenVideo, modifier = Modifier.fillMaxWidth()) {
            Text("Open video recording")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenVault, modifier = Modifier.fillMaxWidth()) {
            Text("Open Evidence Vault")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onManageConsent, modifier = Modifier.fillMaxWidth()) {
            Text("Manage evidence consent")
        }
    }
}
