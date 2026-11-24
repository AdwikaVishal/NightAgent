package com.example.nightagent.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.example.nightagent.guardian.domain.repository.GuardianRepository
import com.example.nightagent.guardian.repository.GuardianRepositoryImpl
import com.example.nightagent.guardian.presentation.GuardianViewModel
import com.example.nightagent.guardian.presentation.GuardianUiState
import com.example.nightagent.model.Guardian
import com.example.nightagent.model.Relationship
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GuardianScreen() {
    val context = LocalContext.current
    val appContext = context.applicationContext
    val repository: GuardianRepository = remember { GuardianRepositoryImpl(appContext) }

    val viewModel: GuardianViewModel = viewModel(
        factory = GuardianViewModel.Factory(repository)
    )

    val uiState by viewModel.uiState.collectAsState()
    val guardianList = uiState.guardians
    
    var dialogType by remember { mutableStateOf<String?>(null) }  // "add" | "edit" | "deleteConfirm"
    var selectedGuardian by remember { mutableStateOf<Guardian?>(null) }

    var savedGuardianName by remember { mutableStateOf("") }
    var savedGuardianPhone by remember { mutableStateOf("") }
    var savedGuardianEmail by remember { mutableStateOf("") }
    var savedGuardianRelationship: Relationship = Relationship.Other

    LaunchedEffect(Unit) {
        viewModel.loadGuardians()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("My Guardians", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { dialogType = "add" }) {
                        Icon(Icons.Default.Add, contentDescription = "Add")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { dialogType = "add" },
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add Guardian")
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (guardianList.isEmpty()) {
                EmptyState(
                    icon = Icons.Default.Person,
                    title = "No Guardians Added",
                    subtitle = "Add guardians to receive SOS alerts and share location"
                )
            } else {
                GuardianList(
                    guardians = guardianList,
                    onDelete = { guardian ->
                        selectedGuardian = guardian
                        dialogType = "deleteConfirm"
                    },
                    onSetPrimary = { guardianId ->
                        viewModel.setPrimaryGuardian(guardianId)
                        viewModel.loadGuardians()
                    },
                    onEdit = { guardian ->
                        selectedGuardian = guardian
                        dialogType = "edit"
                    }
                )
            }
        }
    }

    when (dialogType) {
        "add" -> {
            GuardianAddDialog(
                onSave = { guardian ->
                    savedGuardianName = guardian.name
                    savedGuardianPhone = guardian.phoneNumber
                    // Handle nullable email: guardian.email is String?
                    val emailVal = guardian.email ?: ""
                    savedGuardianEmail = if (emailVal.isNotBlank()) emailVal else ""
                    savedGuardianRelationship = guardian.relationship
                    viewModel.addGuardian(guardian)
                },
                onCancel = { dialogType = null }
            )
            LaunchedEffect(key1 = uiState.successMessage, key2 = uiState.errorMessage, key3 = savedGuardianName.isNotBlank()) {
                if (uiState.successMessage == "Guardian added successfully" && savedGuardianName.isNotBlank()) {
                    // Pass email as String? (null if empty/blank)
                    val emailToInvite = if (savedGuardianEmail.isNotBlank()) savedGuardianEmail else null
                    viewModel.inviteGuardian(
                        savedGuardianName,
                        savedGuardianPhone,
                        emailToInvite,
                        savedGuardianRelationship
                    )
                }
                if (uiState.errorMessage != null) {
                    // Error shown via UI state, dialog remains open for user to correct
                }
            }
        }
        "edit" -> {
            selectedGuardian?.let { guardian ->
                GuardianEditDialog(
                    guardian = guardian,
                    onSave = { updatedGuardian ->
                        viewModel.updateGuardian(updatedGuardian)
                        dialogType = null
                        selectedGuardian = null
                        viewModel.loadGuardians()
                    },
                    onCancel = { dialogType = null; selectedGuardian = null }
                )
            }
        }
        "deleteConfirm" -> {
            selectedGuardian?.let { guardian ->
                GuardianDeleteConfirmDialog(
                    guardian = guardian,
                    onConfirm = {
                        viewModel.deleteGuardian(guardian.guardianId)
                        dialogType = null
                        selectedGuardian = null
                        viewModel.loadGuardians()
                    },
                    onCancel = { dialogType = null; selectedGuardian = null }
                )
            }
        }
    }
}

@Composable
fun GuardianList(
    guardians: List<Guardian>,
    onDelete: (Guardian) -> Unit,
    onSetPrimary: (String) -> Unit,
    onEdit: (Guardian) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(guardians, key = { it.guardianId }) { guardian ->
            GuardianCard(
                guardian = guardian,
                onDelete = onDelete,
                onSetPrimary = onSetPrimary,
                onEdit = { onEdit(guardian) }
            )
        }
    }
}

@Composable
fun GuardianCard(
    guardian: Guardian,
    onDelete: (Guardian) -> Unit,
    onSetPrimary: (String) -> Unit,
    onEdit: () -> Unit
) {
    val isPrimary = guardian.isPrimary
    val avatarText = if (guardian.name.isNotEmpty()) guardian.name.substring(0, 1).uppercase() else "?"

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clip(CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = avatarText,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = guardian.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Text(
                        text = guardian.phoneNumber,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                    )

                    if (!guardian.email.isNullOrBlank()) {
                        Text(
                            text = guardian.email,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    }

                    Text(
                        text = "Relationship: ${guardian.relationship.displayName}",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onEdit() }) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = "Edit guardian",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(onClick = { onSetPrimary(guardian.guardianId) }) {
                    Icon(
                        imageVector = if (isPrimary) Icons.Default.Star else Icons.Outlined.Star,
                        contentDescription = if (isPrimary) "Primary Guardian" else "Set as Primary",
                        tint = if (isPrimary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (isPrimary) {
                    Text(
                        "Primary",
                        fontSize = 10.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { onDelete(guardian) },
                    modifier = Modifier.weight(1f),
                    // Use default button theming - no custom colors parameter
                ) {
                    Text(
                        "Delete",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuardianAddDialog(
    onSave: (Guardian) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var relationship by remember { mutableStateOf(Relationship.Other) }
    var isPrimary by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Add Guardian") },
        confirmButton = {
            TextButton(
                onClick = {
                    val guardianEmail = if (email.isBlank()) null else email.trim()
                    val guardian = Guardian(
                        guardianId = "",
                        userId = null,
                        name = name.trim(),
                        phoneNumber = phone.trim(),
                        email = guardianEmail,
                        relationship = relationship,
                        isPrimary = isPrimary,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                    onSave(guardian)
                },
                enabled = name.isNotBlank() && phone.isNotBlank()
            ) {
                Text("Save & Send Invitation")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true
                )

                Text("Relationship:", fontSize = 14.sp, fontWeight = FontWeight.Medium)

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Relationship.allValues.forEach { rel ->
                        FilterChip(
                            selected = relationship == rel,
                            onClick = { relationship = rel },
                            label = { Text(rel.displayName, fontSize = 12.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isPrimary,
                        onCheckedChange = { isPrimary = it }
                    )
                    Text("Set as primary guardian", fontSize = 14.sp)
                }
            }
        }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuardianEditDialog(
    guardian: Guardian,
    onSave: (Guardian) -> Unit,
    onCancel: () -> Unit
) {
    var name by remember { mutableStateOf(guardian.name) }
    var phone by remember { mutableStateOf(guardian.phoneNumber) }
    var email by remember { mutableStateOf(guardian.email ?: "") }
    var isPrimary by remember { mutableStateOf(guardian.isPrimary) }
    var relationship by remember { mutableStateOf(guardian.relationship) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Edit Guardian") },
        confirmButton = {
            TextButton(
                onClick = {
                    val updatedEmail = if (email.isBlank()) null else email.trim()
                    val updated = guardian.copy(
                        name = name.trim(),
                        phoneNumber = phone.trim(),
                        email = updatedEmail,
                        relationship = relationship,
                        isPrimary = isPrimary,
                        updatedAt = System.currentTimeMillis()
                    )
                    onSave(updated)
                },
                enabled = name.isNotBlank() && phone.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Full Name") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("Phone Number") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    singleLine = true
                )

                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    singleLine = true
                )

                Text("Relationship:", fontSize = 14.sp, fontWeight = FontWeight.Medium)

                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Relationship.allValues.forEach { rel ->
                        FilterChip(
                            selected = relationship == rel,
                            onClick = { relationship = rel },
                            label = { Text(rel.displayName, fontSize = 12.sp) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = isPrimary,
                        onCheckedChange = { isPrimary = it }
                    )
                    Text("Set as primary guardian", fontSize = 14.sp)
                }
            }
        }
    )
}

@Composable
fun GuardianDeleteConfirmDialog(
    guardian: Guardian,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Delete Guardian") },
        text = {
            Text("Are you sure you want to remove ${guardian.name} from your guardians?")
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Delete", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    subtitle: String
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = title,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = subtitle,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp)
        )
    }
}