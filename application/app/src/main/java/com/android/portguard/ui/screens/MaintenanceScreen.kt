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
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.android.portguard.data.PortGuardPreset
import com.android.portguard.ui.state.MaintenanceUiState
import com.android.portguard.ui.viewmodel.MaintenanceViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MaintenanceScreen(
    onBack: () -> Unit,
    vm: MaintenanceViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var pendingAction by remember { mutableStateOf<MaintenanceConfirmAction?>(null) }

    pendingAction?.let { action ->
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            confirmButton = {
                TextButton(onClick = {
                    when (action) {
                        MaintenanceConfirmAction.IMPORT_BACKUP -> vm.importBackup()
                        MaintenanceConfirmAction.RESET_RECOMMENDED -> vm.resetRecommended()
                        MaintenanceConfirmAction.REINSTALL_MODULE -> vm.reinstallModule()
                        MaintenanceConfirmAction.REBOOT_DEVICE -> vm.rebootDevice()
                    }
                    pendingAction = null
                }) {
                    Text(text = stringResource(id = R.string.common_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingAction = null }) {
                    Text(text = stringResource(id = R.string.common_cancel))
                }
            },
            title = { Text(text = action.title()) },
            text = { Text(text = action.message()) },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(id = R.string.maintenance_title))
                        Text(
                            text = stringResource(id = R.string.maintenance_subtitle),
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
            item { MaintenanceHeroCard(state) }
            item {
                SectionCard(
                    icon = Icons.Rounded.Security,
                    title = stringResource(id = R.string.maintenance_presets_title),
                    description = stringResource(id = R.string.maintenance_presets_desc),
                )
            }
            item {
                PresetButton(
                    title = stringResource(id = R.string.maintenance_preset_balanced_title),
                    description = stringResource(id = R.string.maintenance_preset_balanced_desc),
                    enabled = !state.working,
                    onClick = { vm.applyPreset(PortGuardPreset.BALANCED) },
                )
            }
            item {
                PresetButton(
                    title = stringResource(id = R.string.maintenance_preset_strict_title),
                    description = stringResource(id = R.string.maintenance_preset_strict_desc),
                    enabled = !state.working,
                    onClick = { vm.applyPreset(PortGuardPreset.STRICT) },
                )
            }
            item {
                PresetButton(
                    title = stringResource(id = R.string.maintenance_preset_aggressive_title),
                    description = stringResource(id = R.string.maintenance_preset_aggressive_desc),
                    enabled = !state.working,
                    onClick = { vm.applyPreset(PortGuardPreset.AGGRESSIVE) },
                )
            }
            item { HorizontalDivider() }
            item {
                SectionCard(
                    icon = Icons.Rounded.Backup,
                    title = stringResource(id = R.string.maintenance_backup_title),
                    description = stringResource(id = R.string.maintenance_backup_desc),
                )
            }
            item {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !state.working, onClick = vm::exportBackup) {
                    Icon(Icons.Rounded.Save, null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(id = R.string.maintenance_export_backup_button))
                }
            }
            item {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !state.working, onClick = { pendingAction = MaintenanceConfirmAction.IMPORT_BACKUP }) {
                    Icon(Icons.Rounded.Restore, null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(id = R.string.maintenance_import_backup_button))
                }
            }
            item {
                Button(modifier = Modifier.fillMaxWidth(), enabled = !state.working, onClick = { pendingAction = MaintenanceConfirmAction.RESET_RECOMMENDED }) {
                    Icon(Icons.Rounded.WarningAmber, null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(id = R.string.maintenance_reset_button))
                }
            }
            item { HorizontalDivider() }
            item {
                SectionCard(
                    icon = Icons.Rounded.Build,
                    title = stringResource(id = R.string.maintenance_module_actions_title),
                    description = stringResource(id = R.string.maintenance_module_actions_desc),
                )
            }
            item {
                Button(modifier = Modifier.fillMaxWidth(), enabled = !state.working, onClick = { pendingAction = MaintenanceConfirmAction.REINSTALL_MODULE }) {
                    Icon(Icons.Rounded.Bolt, null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(id = R.string.maintenance_reinstall_button))
                }
            }
            item {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !state.working, onClick = vm::exportModuleZip) {
                    Icon(Icons.Rounded.Save, null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(id = R.string.maintenance_export_module_button))
                }
            }
            item {
                OutlinedButton(modifier = Modifier.fillMaxWidth(), enabled = !state.working, onClick = { pendingAction = MaintenanceConfirmAction.REBOOT_DEVICE }) {
                    Icon(Icons.Rounded.WarningAmber, null)
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(text = stringResource(id = R.string.maintenance_reboot_button))
                }
            }
            item {
                if (state.actionLog.isNotBlank()) {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(text = stringResource(id = R.string.maintenance_log_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(text = state.actionLog, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceHeroCard(state: MaintenanceUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(imageVector = Icons.Rounded.Build, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = stringResource(id = R.string.maintenance_status_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(text = state.statusText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                AnimatedVisibility(visible = state.loading || state.working) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (state.rootGranted) stringResource(id = R.string.root_status_granted) else stringResource(id = R.string.root_status_denied)) })
                AssistChip(onClick = {}, label = { Text(if (state.moduleInstalled) stringResource(id = R.string.module_status_found) else stringResource(id = R.string.module_status_not_found)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(text = state.installerLabel) })
                AssistChip(
                    onClick = {},
                    label = { Text(text = if (state.assetPresent) stringResource(id = R.string.maintenance_asset_present) else stringResource(id = R.string.maintenance_asset_missing)) },
                )
            }

            AnimatedVisibility(visible = state.infoMessage != null) {
                AssistChip(onClick = {}, label = { Text(state.infoMessage.orEmpty()) })
            }
            AnimatedVisibility(visible = state.errorMessage != null) {
                AssistChip(onClick = {}, leadingIcon = { Icon(Icons.Rounded.WarningAmber, null) }, label = { Text(state.errorMessage.orEmpty()) })
            }
            AnimatedVisibility(visible = state.backupPath.isNotBlank()) {
                Text(
                    text = stringResource(id = R.string.maintenance_backup_path, state.backupPath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
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
private fun PresetButton(title: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(modifier = Modifier.fillMaxWidth(), enabled = enabled, onClick = onClick) {
                Text(text = stringResource(id = R.string.maintenance_apply_preset_button))
            }
        }
    }
}


private enum class MaintenanceConfirmAction {
    IMPORT_BACKUP,
    RESET_RECOMMENDED,
    REINSTALL_MODULE,
    REBOOT_DEVICE,
}

@Composable
private fun MaintenanceConfirmAction.title(): String = when (this) {
    MaintenanceConfirmAction.IMPORT_BACKUP -> stringResource(id = R.string.maintenance_import_dialog_title)
    MaintenanceConfirmAction.RESET_RECOMMENDED -> stringResource(id = R.string.maintenance_reset_dialog_title)
    MaintenanceConfirmAction.REINSTALL_MODULE -> stringResource(id = R.string.maintenance_reinstall_dialog_title)
    MaintenanceConfirmAction.REBOOT_DEVICE -> stringResource(id = R.string.maintenance_reboot_dialog_title)
}

@Composable
private fun MaintenanceConfirmAction.message(): String = when (this) {
    MaintenanceConfirmAction.IMPORT_BACKUP -> stringResource(id = R.string.maintenance_import_dialog_message)
    MaintenanceConfirmAction.RESET_RECOMMENDED -> stringResource(id = R.string.maintenance_reset_dialog_message)
    MaintenanceConfirmAction.REINSTALL_MODULE -> stringResource(id = R.string.maintenance_reinstall_dialog_message)
    MaintenanceConfirmAction.REBOOT_DEVICE -> stringResource(id = R.string.maintenance_reboot_dialog_message)
}
