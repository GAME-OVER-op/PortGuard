package com.android.portguard.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.portguard.R
import com.android.portguard.ui.state.AdvancedSettingsUiState
import com.android.portguard.ui.viewmodel.AdvancedNumberField
import com.android.portguard.ui.viewmodel.AdvancedSettingsViewModel
import androidx.compose.ui.res.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    onBack: () -> Unit,
    vm: AdvancedSettingsViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(id = R.string.advanced_settings_title))
                        Text(
                            text = stringResource(id = R.string.advanced_settings_subtitle),
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
            item { HeroCard(state) }
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canEdit && state.dirty && !state.loading && !state.saving,
                    onClick = vm::saveChanges,
                ) {
                    Icon(imageVector = Icons.Rounded.Save, contentDescription = null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(id = R.string.advanced_settings_save_button))
                }
            }
            item {
                SectionTitle(
                    title = stringResource(id = R.string.advanced_detector_section_title),
                    description = stringResource(id = R.string.advanced_detector_section_desc),
                    icon = Icons.Rounded.Tune,
                )
            }
            item {
                NumericCard(
                    title = stringResource(id = R.string.advanced_unique_ports_title),
                    description = stringResource(id = R.string.advanced_unique_ports_desc),
                    value = state.suspiciousUniquePorts,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onValueChange = { vm.updateNumber(AdvancedNumberField.SUSPICIOUS_UNIQUE_PORTS, it) },
                )
            }
            item {
                NumericCard(
                    title = stringResource(id = R.string.advanced_attempts_title),
                    description = stringResource(id = R.string.advanced_attempts_desc),
                    value = state.suspiciousAttempts,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onValueChange = { vm.updateNumber(AdvancedNumberField.SUSPICIOUS_ATTEMPTS, it) },
                )
            }
            item {
                NumericCard(
                    title = stringResource(id = R.string.advanced_rule_hits_title),
                    description = stringResource(id = R.string.advanced_rule_hits_desc),
                    value = state.suspiciousRuleHits,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onValueChange = { vm.updateNumber(AdvancedNumberField.SUSPICIOUS_RULE_HITS, it) },
                )
            }
            item {
                NumericCard(
                    title = stringResource(id = R.string.advanced_scan_window_title),
                    description = stringResource(id = R.string.advanced_scan_window_desc),
                    value = state.scanWindowSecs,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onValueChange = { vm.updateNumber(AdvancedNumberField.SCAN_WINDOW_SECS, it) },
                )
            }
            item {
                NumericCard(
                    title = stringResource(id = R.string.advanced_warn_cooldown_title),
                    description = stringResource(id = R.string.advanced_warn_cooldown_desc),
                    value = state.warnCooldownSecs,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onValueChange = { vm.updateNumber(AdvancedNumberField.WARN_COOLDOWN_SECS, it) },
                )
            }
            item {
                NumericCard(
                    title = stringResource(id = R.string.advanced_reaction_cooldown_title),
                    description = stringResource(id = R.string.advanced_reaction_cooldown_desc),
                    value = state.reactionCooldownSecs,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onValueChange = { vm.updateNumber(AdvancedNumberField.REACTION_COOLDOWN_SECS, it) },
                )
            }
            item { HorizontalDivider() }
            item {
                SectionTitle(
                    title = stringResource(id = R.string.advanced_performance_section_title),
                    description = stringResource(id = R.string.advanced_performance_section_desc),
                    icon = Icons.Rounded.Speed,
                )
            }
            item {
                NumericCard(
                    title = stringResource(id = R.string.advanced_loop_interval_title),
                    description = stringResource(id = R.string.advanced_loop_interval_desc),
                    value = state.loopIntervalMs,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onValueChange = { vm.updateNumber(AdvancedNumberField.LOOP_INTERVAL_MS, it) },
                )
            }
            item {
                NumericCard(
                    title = stringResource(id = R.string.advanced_reload_check_title),
                    description = stringResource(id = R.string.advanced_reload_check_desc),
                    value = state.reloadCheckSecs,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onValueChange = { vm.updateNumber(AdvancedNumberField.RELOAD_CHECK_SECS, it) },
                )
            }
            item {
                NumericCard(
                    title = stringResource(id = R.string.advanced_package_refresh_title),
                    description = stringResource(id = R.string.advanced_package_refresh_desc),
                    value = state.packageRefreshSecs,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onValueChange = { vm.updateNumber(AdvancedNumberField.PACKAGE_REFRESH_SECS, it) },
                )
            }
            item {
                NumericCard(
                    title = stringResource(id = R.string.advanced_counter_refresh_title),
                    description = stringResource(id = R.string.advanced_counter_refresh_desc),
                    value = state.counterRefreshLoops,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onValueChange = { vm.updateNumber(AdvancedNumberField.COUNTER_REFRESH_LOOPS, it) },
                )
            }
            item {
                ToggleCard(
                    title = stringResource(id = R.string.advanced_resolve_process_title),
                    description = stringResource(id = R.string.advanced_resolve_process_desc),
                    checked = state.resolveProcessDetails,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onCheckedChange = vm::setResolveProcessDetails,
                )
            }
            item {
                ToggleCard(
                    title = stringResource(id = R.string.dashboard_loopback_only_title),
                    description = stringResource(id = R.string.advanced_loopback_only_desc),
                    checked = state.protectLoopbackOnly,
                    enabled = state.canEdit && !state.loading && !state.saving,
                    onCheckedChange = vm::setProtectLoopbackOnly,
                )
            }
        }
    }
}

@Composable
private fun HeroCard(state: AdvancedSettingsUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(imageVector = Icons.Rounded.Tune, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.advanced_settings_status_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.statusText.ifBlank { stringResource(id = R.string.advanced_settings_status_waiting) },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = state.loading || state.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            AnimatedVisibility(visible = state.infoMessage != null) {
                AssistChip(onClick = {}, label = { Text(text = state.infoMessage.orEmpty()) })
            }
            AnimatedVisibility(visible = state.errorMessage != null) {
                AssistChip(
                    onClick = {},
                    leadingIcon = { Icon(imageVector = Icons.Rounded.WarningAmber, contentDescription = null) },
                    label = { Text(text = state.errorMessage.orEmpty()) },
                )
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun NumericCard(
    title: String,
    description: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
            )
        }
    }
}

@Composable
private fun ToggleCard(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}
