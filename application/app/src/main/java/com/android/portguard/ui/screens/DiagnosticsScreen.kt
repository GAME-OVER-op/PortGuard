package com.android.portguard.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import com.android.portguard.ui.state.DiagnosticsUiState
import com.android.portguard.ui.viewmodel.DiagnosticsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    vm: DiagnosticsViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(id = R.string.diagnostics_title))
                        Text(
                            text = stringResource(id = R.string.diagnostics_subtitle),
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
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { DiagnosticsHeroCard(state) }
            item {
                SectionCard(
                    icon = Icons.Rounded.Description,
                    title = stringResource(id = R.string.diagnostics_about_title),
                    description = stringResource(id = R.string.diagnostics_about_desc),
                    lines = listOf(
                        stringResource(id = R.string.diagnostics_app_version, state.appVersionName, state.appVersionCode),
                        stringResource(id = R.string.diagnostics_android_version, state.androidVersionText),
                        stringResource(id = R.string.diagnostics_module_version, state.moduleVersionName.ifBlank { "—" }, state.moduleVersionCode.ifBlank { "—" }),
                        stringResource(id = R.string.diagnostics_installer_value, state.installerLabel),
                        if (state.assetPresent) stringResource(id = R.string.installer_asset_ready) else stringResource(id = R.string.installer_asset_missing),
                    )
                )
            }
            item {
                SectionCard(
                    icon = Icons.Rounded.Folder,
                    title = stringResource(id = R.string.diagnostics_paths_title),
                    description = stringResource(id = R.string.diagnostics_paths_desc),
                    lines = listOf(state.modulePath, state.settingsPath, state.statePath),
                )
            }
            item {
                FileStateCard(state)
            }
            item {
                RawPreviewCard(
                    title = stringResource(id = R.string.diagnostics_raw_config_title),
                    description = stringResource(id = R.string.diagnostics_raw_config_desc),
                    value = state.configPreview,
                    emptyText = stringResource(id = R.string.diagnostics_raw_config_empty),
                )
            }
            item {
                RawPreviewCard(
                    title = stringResource(id = R.string.diagnostics_raw_status_title),
                    description = stringResource(id = R.string.diagnostics_raw_status_desc),
                    value = state.statusPreview,
                    emptyText = stringResource(id = R.string.diagnostics_raw_status_empty),
                )
            }
            item {
                RawPreviewCard(
                    title = stringResource(id = R.string.diagnostics_raw_summary_title),
                    description = stringResource(id = R.string.diagnostics_raw_summary_desc),
                    value = state.summaryPreview,
                    emptyText = stringResource(id = R.string.diagnostics_raw_summary_empty),
                )
            }
            item {
                RawPreviewCard(
                    title = stringResource(id = R.string.diagnostics_raw_incidents_title),
                    description = stringResource(id = R.string.diagnostics_raw_incidents_desc),
                    value = state.incidentsPreview,
                    emptyText = stringResource(id = R.string.diagnostics_raw_incidents_empty),
                )
            }
        }
    }
}

@Composable
private fun DiagnosticsHeroCard(state: DiagnosticsUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(imageVector = Icons.Rounded.BugReport, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.diagnostics_status_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = state.statusText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = state.loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (state.rootGranted) stringResource(id = R.string.root_status_granted) else stringResource(id = R.string.root_status_denied)) })
                AssistChip(onClick = {}, label = { Text(if (state.moduleInstalled) stringResource(id = R.string.module_status_found) else stringResource(id = R.string.module_status_not_found)) })
                AssistChip(onClick = {}, label = { Text(if (state.settingsFound) stringResource(id = R.string.settings_status_found) else stringResource(id = R.string.settings_status_not_found)) })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(onClick = {}, label = { Text(if (state.configFound) stringResource(id = R.string.diagnostics_config_found) else stringResource(id = R.string.diagnostics_config_missing)) })
                AssistChip(onClick = {}, label = { Text(if (state.summaryFileFound) stringResource(id = R.string.diagnostics_summary_found) else stringResource(id = R.string.diagnostics_summary_missing)) })
            }
            AnimatedVisibility(visible = state.infoMessage != null) {
                AssistChip(onClick = {}, label = { Text(state.infoMessage.orEmpty()) })
            }
            AnimatedVisibility(visible = state.errorMessage != null) {
                AssistChip(onClick = {}, leadingIcon = { Icon(Icons.Rounded.WarningAmber, null) }, label = { Text(state.errorMessage.orEmpty()) })
            }
        }
    }
}

@Composable
private fun FileStateCard(state: DiagnosticsUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = stringResource(id = R.string.diagnostics_files_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = stringResource(id = R.string.diagnostics_files_desc), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FileStateLine(
                title = stringResource(id = R.string.diagnostics_file_config),
                found = state.configFound,
                validLabel = when {
                    !state.configFound -> null
                    state.configJsonValid -> stringResource(id = R.string.diagnostics_json_valid)
                    else -> stringResource(id = R.string.diagnostics_json_invalid)
                }
            )
            FileStateLine(
                title = stringResource(id = R.string.diagnostics_file_status),
                found = state.statusFileFound,
                validLabel = when {
                    !state.statusFileFound -> null
                    state.statusJsonValid -> stringResource(id = R.string.diagnostics_json_valid)
                    else -> stringResource(id = R.string.diagnostics_json_invalid)
                }
            )
            FileStateLine(
                title = stringResource(id = R.string.diagnostics_file_summary),
                found = state.summaryFileFound,
                validLabel = when {
                    !state.summaryFileFound -> null
                    state.summaryJsonValid -> stringResource(id = R.string.diagnostics_json_valid)
                    else -> stringResource(id = R.string.diagnostics_json_invalid)
                }
            )
            FileStateLine(
                title = stringResource(id = R.string.diagnostics_file_incidents),
                found = state.incidentsFileFound,
                validLabel = null,
            )
        }
    }
}

@Composable
private fun FileStateLine(title: String, found: Boolean, validLabel: String?) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        AssistChip(onClick = {}, label = { Text(if (found) stringResource(id = R.string.diagnostics_file_found_short) else stringResource(id = R.string.diagnostics_file_missing_short)) })
        if (validLabel != null) {
            AssistChip(onClick = {}, label = { Text(validLabel) })
        }
    }
}

@Composable
private fun SectionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String, lines: List<String>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(imageVector = icon, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            lines.filter { it.isNotBlank() }.forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun RawPreviewCard(title: String, description: String, value: String, emptyText: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth().height(200.dp),
                value = value.ifBlank { emptyText },
                onValueChange = {},
                readOnly = true,
                label = { Text(text = title) },
            )
        }
    }
}
