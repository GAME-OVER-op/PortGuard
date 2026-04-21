package com.android.portguard.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.portguard.R
import com.android.portguard.data.PortGuardIncident
import com.android.portguard.ui.state.IncidentsUiState
import com.android.portguard.ui.viewmodel.IncidentsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IncidentsScreen(
    onBack: () -> Unit,
    vm: IncidentsViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var showClearDialog by remember { mutableStateOf(false) }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    vm.clearIncidents()
                }) {
                    Text(text = stringResource(id = R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(text = stringResource(id = R.string.common_cancel))
                }
            },
            title = { Text(text = stringResource(id = R.string.incidents_clear_dialog_title)) },
            text = { Text(text = stringResource(id = R.string.incidents_clear_dialog_message)) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(id = R.string.incidents_title))
                        Text(
                            text = stringResource(id = R.string.incidents_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(imageVector = Icons.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null)
                    }
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(imageVector = Icons.Rounded.CleaningServices, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                IncidentsHeroCard(state = state)
            }

            if (state.incidents.isEmpty()) {
                item {
                    EmptyStateCard(state = state)
                }
            } else {
                items(state.incidents) { incident ->
                    IncidentCard(incident = incident)
                }
            }
        }
    }
}

@Composable
private fun IncidentsHeroCard(state: IncidentsUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(imageVector = Icons.Rounded.WarningAmber, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.incidents_status_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = state.loading || state.clearing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(if (state.rootGranted) stringResource(id = R.string.root_status_granted) else stringResource(id = R.string.root_status_denied)) },
                )
                AssistChip(
                    onClick = {},
                    label = { Text(if (state.incidentsFileFound) stringResource(id = R.string.incidents_file_found) else stringResource(id = R.string.incidents_file_missing)) },
                )
            }

            AnimatedVisibility(visible = state.infoMessage != null) {
                AssistChip(onClick = {}, label = { Text(state.infoMessage.orEmpty()) })
            }
            AnimatedVisibility(visible = state.errorMessage != null) {
                AssistChip(onClick = {}, label = { Text(state.errorMessage.orEmpty()) })
            }
        }
    }
}

@Composable
private fun EmptyStateCard(state: IncidentsUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(id = R.string.incidents_empty_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = when {
                    !state.rootGranted -> stringResource(id = R.string.incidents_root_required)
                    !state.incidentsFileFound -> stringResource(id = R.string.incidents_empty_missing_file)
                    else -> stringResource(id = R.string.incidents_empty_desc)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IncidentCard(incident: PortGuardIncident) {
    val level = incident.level.trim().uppercase()
    val (containerColor, accentColor) = when {
        level.contains("WARN") || level.contains("SCAN") -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        level.contains("ERR") || level.contains("FAIL") -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.surfaceContainer to MaterialTheme.colorScheme.primary
    }
    Card(colors = CardDefaults.cardColors(containerColor = containerColor)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = incident.message.ifBlank { incident.rawLine.ifBlank { stringResource(id = R.string.incidents_item_no_message) } },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(text = level.ifBlank { stringResource(id = R.string.incidents_level_info) }) },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = accentColor,
                        )
                    }
                )
                if (incident.action.isNotBlank()) {
                    AssistChip(
                        onClick = {},
                        label = { Text(text = incident.action) },
                    )
                }
            }
            if (incident.timestamp.isNotBlank()) {
                Text(
                    text = stringResource(id = R.string.incidents_item_time, incident.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (incident.level.isNotBlank()) {
                Text(
                    text = stringResource(id = R.string.incidents_item_level, incident.level),
                    style = MaterialTheme.typography.bodySmall,
                    color = accentColor,
                )
            }
            if (incident.uid.isNotBlank()) {
                Text(
                    text = stringResource(id = R.string.incidents_item_uid, incident.uid),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (incident.packages.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.incidents_item_packages, incident.packages.joinToString()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (incident.ports.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.incidents_item_ports, incident.ports.joinToString()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (incident.rawLine.isNotBlank()) {
                HorizontalDivider()
                Text(
                    text = incident.rawLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
