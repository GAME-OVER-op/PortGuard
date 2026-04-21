package com.android.portguard.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.portguard.R
import com.android.portguard.ui.state.RootAccessState
import com.android.portguard.ui.viewmodel.RootModuleViewModel

@Composable
fun ModuleSetupScreen(
    onBack: () -> Unit,
    onOpenDashboard: () -> Unit,
    modifier: Modifier = Modifier,
    vm: RootModuleViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val contentAlpha by animateFloatAsState(targetValue = if (state.checking || state.installing || state.rebooting) 0.78f else 1f, label = "contentAlpha")

    LaunchedEffect(Unit) {
        vm.refresh()
    }

    Surface(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .alpha(contentAlpha),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(id = R.string.root_screen_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(id = R.string.root_screen_description),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                StatusCard(
                    title = stringResource(id = R.string.root_status_title),
                    icon = when (state.rootState) {
                        RootAccessState.GRANTED -> Icons.Rounded.VerifiedUser
                        RootAccessState.DENIED -> Icons.Rounded.ErrorOutline
                        RootAccessState.CHECKING -> Icons.Rounded.Refresh
                        RootAccessState.UNKNOWN -> Icons.Rounded.Key
                    },
                    ok = state.rootState == RootAccessState.GRANTED,
                    value = when (state.rootState) {
                        RootAccessState.UNKNOWN -> stringResource(id = R.string.root_status_unknown)
                        RootAccessState.CHECKING -> stringResource(id = R.string.root_status_checking)
                        RootAccessState.GRANTED -> stringResource(id = R.string.root_status_granted)
                        RootAccessState.DENIED -> stringResource(id = R.string.root_status_denied)
                    },
                )
            }

            item {
                StatusCard(
                    title = stringResource(id = R.string.module_status_title),
                    icon = Icons.Rounded.SettingsApplications,
                    ok = state.moduleInstalled,
                    value = if (state.moduleInstalled) {
                        stringResource(id = R.string.module_status_found)
                    } else {
                        stringResource(id = R.string.module_status_not_found)
                    },
                    extra = state.modulePath,
                )
            }

            item {
                StatusCard(
                    title = stringResource(id = R.string.settings_status_title),
                    icon = Icons.Rounded.Folder,
                    ok = state.settingsDirectoryFound,
                    value = if (state.settingsDirectoryFound) {
                        stringResource(id = R.string.settings_status_found)
                    } else {
                        stringResource(id = R.string.settings_status_not_found)
                    },
                    extra = state.settingsPath,
                )
            }

            item {
                StatusCard(
                    title = stringResource(id = R.string.installer_status_title),
                    icon = Icons.Rounded.Archive,
                    ok = state.assetPresent,
                    value = stringResource(id = R.string.installer_status_value, state.installerLabel.ifBlank { stringResource(id = R.string.installer_label_unknown) }),
                    extra = if (state.assetPresent) {
                        stringResource(id = R.string.installer_asset_ready)
                    } else {
                        stringResource(id = R.string.installer_asset_missing)
                    },
                )
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            text = stringResource(id = R.string.root_screen_summary_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = state.statusText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        AnimatedVisibility(visible = state.stagedUpdateFound) {
                            Text(
                                text = stringResource(id = R.string.installer_reboot_needed),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.tertiary,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        AnimatedVisibility(visible = state.checking || state.installing || state.rebooting) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Text(
                                    text = when {
                                        state.checking -> stringResource(id = R.string.root_screen_checking_details)
                                        state.installing -> stringResource(id = R.string.installer_progress_installing)
                                        else -> stringResource(id = R.string.installer_progress_rebooting)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = vm::refresh,
                        enabled = !state.checking && !state.installing && !state.rebooting,
                    ) {
                        Text(text = stringResource(id = R.string.root_screen_check_button))
                    }

                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = vm::installModule,
                        enabled = state.rootState == RootAccessState.GRANTED && state.assetPresent && !state.installing && !state.rebooting,
                    ) {
                        Text(text = stringResource(id = R.string.installer_install_button))
                    }

                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = vm::exportModuleZip,
                        enabled = state.rootState == RootAccessState.GRANTED && state.assetPresent && !state.installing && !state.rebooting,
                    ) {
                        Text(text = stringResource(id = R.string.installer_export_button))
                    }

                    FilledTonalButton(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = vm::rebootDevice,
                        enabled = state.rootState == RootAccessState.GRANTED && state.stagedUpdateFound && !state.installing && !state.rebooting,
                    ) {
                        Icon(imageVector = Icons.Rounded.RestartAlt, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(text = stringResource(id = R.string.installer_reboot_button))
                    }

                    Button(
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onOpenDashboard,
                        enabled = state.rootState == RootAccessState.GRANTED && state.settingsDirectoryFound && !state.checking && !state.installing && !state.rebooting,
                    ) {
                        Text(text = stringResource(id = R.string.dashboard_open_button))
                    }
                }
            }

            item {
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = onBack,
                ) {
                    Text(text = stringResource(id = R.string.common_back))
                }
            }

            item {
                HorizontalDivider()
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(id = R.string.installer_log_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(id = R.string.installer_log_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    value = when {
                        state.installLog.isNotBlank() -> state.installLog
                        state.installError != null -> state.installError.orEmpty()
                        else -> stringResource(id = R.string.installer_log_empty)
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(text = stringResource(id = R.string.installer_log_label)) },
                )
            }

            item {
                HorizontalDivider()
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(id = R.string.root_screen_preview_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(id = R.string.root_screen_preview_description),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item {
                OutlinedTextField(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp),
                    value = if (state.configPreview.isBlank()) {
                        stringResource(id = R.string.root_screen_preview_empty)
                    } else {
                        state.configPreview
                    },
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(text = stringResource(id = R.string.root_screen_preview_label)) },
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    ok: Boolean,
    value: String,
    extra: String? = null,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.errorContainer,
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text = value, style = MaterialTheme.typography.bodyMedium)
                if (!extra.isNullOrBlank()) {
                    Text(
                        text = extra,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Icon(
                imageVector = if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline,
                contentDescription = null,
            )
        }
    }
}
