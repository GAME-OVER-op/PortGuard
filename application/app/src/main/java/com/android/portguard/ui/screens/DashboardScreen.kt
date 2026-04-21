package com.android.portguard.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.ListAlt
import androidx.compose.material.icons.rounded.Loop
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.portguard.R
import com.android.portguard.data.ActiveProtectionMode
import com.android.portguard.data.PortGuardIncident
import com.android.portguard.data.ReactionMode
import com.android.portguard.ui.state.DashboardUiState
import com.android.portguard.ui.viewmodel.DashboardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onBackToSetup: () -> Unit,
    onOpenExceptions: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
    vm: DashboardViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(id = R.string.dashboard_title))
                        Text(
                            text = stringResource(id = R.string.dashboard_subtitle),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(imageVector = Icons.Rounded.Refresh, contentDescription = null)
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
            item { DashboardHeroCard(state) }
            item {
                ActionButtons(
                    state = state,
                    onSave = vm::saveChanges,
                    onCreateConfig = vm::createDefaultConfig,
                    onOpenSetup = onBackToSetup,
                    onOpenExceptions = onOpenExceptions,
                    onOpenAdvancedSettings = onOpenAdvancedSettings,
                )
            }
            item {
                RuntimeSummaryCard(state = state)
            }
            item {
                SectionTitle(
                    title = stringResource(id = R.string.dashboard_controls_title),
                    description = stringResource(id = R.string.dashboard_controls_description),
                )
            }
            item {
                BooleanSettingCard(
                    title = stringResource(id = R.string.dashboard_active_protection_title),
                    description = stringResource(id = R.string.dashboard_active_protection_desc),
                    checked = state.activeProtection == ActiveProtectionMode.ON,
                    enabled = state.canOpenEditor && !state.loading && !state.saving,
                    icon = Icons.Rounded.GppGood,
                    onCheckedChange = vm::setActiveProtection,
                )
            }
            item {
                BooleanSettingCard(
                    title = stringResource(id = R.string.dashboard_learning_mode_title),
                    description = stringResource(id = R.string.dashboard_learning_mode_desc),
                    checked = state.learningMode,
                    enabled = state.canOpenEditor && !state.loading && !state.saving,
                    icon = Icons.Rounded.Loop,
                    onCheckedChange = vm::setLearningMode,
                )
            }
            item {
                BooleanSettingCard(
                    title = stringResource(id = R.string.dashboard_loopback_only_title),
                    description = stringResource(id = R.string.dashboard_loopback_only_desc),
                    checked = state.protectLoopbackOnly,
                    enabled = state.canOpenEditor && !state.loading && !state.saving,
                    icon = Icons.Rounded.AdminPanelSettings,
                    onCheckedChange = vm::setProtectLoopbackOnly,
                )
            }
            item {
                ReactionModeCard(
                    selected = state.reactionMode,
                    enabled = state.canOpenEditor && !state.loading && !state.saving,
                    onSelect = vm::setReactionMode,
                )
            }
            item { HorizontalDivider() }
            item {
                SectionTitle(
                    title = stringResource(id = R.string.dashboard_config_preview_title),
                    description = stringResource(id = R.string.dashboard_config_preview_desc),
                )
            }
            item {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp),
                    value = when {
                        state.configPreview.isNotBlank() -> state.configPreview
                        state.configFound -> stringResource(id = R.string.dashboard_config_empty_after_load)
                        else -> stringResource(id = R.string.dashboard_config_missing_hint)
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(text = stringResource(id = R.string.root_screen_preview_label)) },
                )
            }
            if (state.summaryPreview.isNotBlank()) {
                item {
                    SectionTitle(
                        title = stringResource(id = R.string.dashboard_runtime_json_title),
                        description = stringResource(id = R.string.dashboard_runtime_json_desc),
                    )
                }
                item {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        value = state.summaryPreview,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(text = stringResource(id = R.string.dashboard_runtime_json_label)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardHeroCard(state: DashboardUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = if (state.activeProtection == ActiveProtectionMode.ON) Icons.Rounded.Shield else Icons.Rounded.WarningAmber,
                    contentDescription = null,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.dashboard_status_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.statusText.ifBlank { stringResource(id = R.string.dashboard_status_empty) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = state.loading || state.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            StatusChips(state)
            AnimatedVisibility(visible = state.infoMessage != null) {
                AssistChip(onClick = {}, label = { Text(state.infoMessage.orEmpty()) }, leadingIcon = { Icon(Icons.Rounded.Bolt, null) })
            }
            AnimatedVisibility(visible = state.errorMessage != null) {
                AssistChip(onClick = {}, label = { Text(state.errorMessage.orEmpty()) }, leadingIcon = { Icon(Icons.Rounded.WarningAmber, null) })
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusChips(state: DashboardUiState) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistChip(onClick = {}, label = { Text(if (state.rootGranted) stringResource(id = R.string.root_status_granted) else stringResource(id = R.string.root_status_denied)) })
        AssistChip(onClick = {}, label = { Text(if (state.moduleInstalled) stringResource(id = R.string.module_status_found) else stringResource(id = R.string.module_status_not_found)) })
        AssistChip(onClick = {}, label = { Text(if (state.configFound) stringResource(id = R.string.status_part_config_found) else stringResource(id = R.string.status_part_config_missing)) })
        AssistChip(onClick = {}, label = { Text(if (state.activeProtection == ActiveProtectionMode.ON) stringResource(id = R.string.dashboard_protection_on_chip) else stringResource(id = R.string.dashboard_protection_off_chip)) })
        if (state.runtimeStateAvailable) {
            AssistChip(onClick = {}, label = { Text(stringResource(id = R.string.dashboard_state_files_ready)) })
        }
    }
}

@Composable
private fun ActionButtons(
    state: DashboardUiState,
    onSave: () -> Unit,
    onCreateConfig: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenExceptions: () -> Unit,
    onOpenAdvancedSettings: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(modifier = Modifier.fillMaxWidth(), onClick = onSave, enabled = state.canOpenEditor && state.dirty && !state.loading && !state.saving) {
            Icon(Icons.Rounded.Save, null)
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = stringResource(id = R.string.dashboard_save_button))
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = onCreateConfig, enabled = state.canOpenEditor && !state.configFound && !state.loading && !state.saving) {
            Text(text = stringResource(id = R.string.dashboard_create_config_button))
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenExceptions, enabled = !state.loading && !state.saving) {
            Icon(Icons.Rounded.ListAlt, null)
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = stringResource(id = R.string.dashboard_open_exceptions_button))
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenAdvancedSettings, enabled = !state.loading && !state.saving) {
            Icon(Icons.Rounded.Bolt, null)
            Spacer(modifier = Modifier.size(8.dp))
            Text(text = stringResource(id = R.string.dashboard_open_advanced_button))
        }
        Button(modifier = Modifier.fillMaxWidth(), onClick = onOpenSetup, enabled = !state.loading && !state.saving) {
            Text(text = stringResource(id = R.string.dashboard_open_setup_button))
        }
    }
}

@Composable
private fun RuntimeSummaryCard(
    state: DashboardUiState,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = stringResource(id = R.string.dashboard_runtime_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = state.runtimeStatusText.ifBlank { stringResource(id = R.string.dashboard_runtime_missing) },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.blockedPorts.isNotEmpty()) {
                Text(
                    text = stringResource(id = R.string.dashboard_runtime_blocked_ports, state.blockedPorts.joinToString()),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (state.latestIncident != null) {
                LatestIncidentBlock(state.latestIncident)
            }
        }
    }
}

@Composable
private fun LatestIncidentBlock(incident: PortGuardIncident) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text(
            text = stringResource(id = R.string.dashboard_latest_incident_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = incident.message.ifBlank { incident.rawLine.ifBlank { stringResource(id = R.string.dashboard_latest_incident_empty) } },
            style = MaterialTheme.typography.bodyMedium,
        )
        val metaParts = mutableListOf<String>()
        if (incident.timestamp.isNotBlank()) metaParts += incident.timestamp
        if (incident.uid.isNotBlank()) metaParts += stringResource(id = R.string.dashboard_latest_incident_uid, incident.uid)
        if (incident.packages.isNotEmpty()) metaParts += incident.packages.joinToString()
        if (incident.ports.isNotEmpty()) metaParts += stringResource(id = R.string.dashboard_latest_incident_ports, incident.ports.joinToString())
        val meta = metaParts.joinToString(" • ")
        if (meta.isNotBlank()) {
            Text(text = meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SectionTitle(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun BooleanSettingCard(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun ReactionModeCard(
    selected: ReactionMode,
    enabled: Boolean,
    onSelect: (ReactionMode) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(id = R.string.dashboard_reaction_mode_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = stringResource(id = R.string.dashboard_reaction_mode_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilterChip(selected = selected == ReactionMode.OFF, onClick = { onSelect(ReactionMode.OFF) }, enabled = enabled, label = { Text(text = stringResource(id = R.string.dashboard_reaction_off)) })
                FilterChip(selected = selected == ReactionMode.FORCE_STOP, onClick = { onSelect(ReactionMode.FORCE_STOP) }, enabled = enabled, label = { Text(text = stringResource(id = R.string.dashboard_reaction_force_stop)) })
            }
        }
    }
}
