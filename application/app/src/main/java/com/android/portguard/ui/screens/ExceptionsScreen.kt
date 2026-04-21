package com.android.portguard.ui.screens

import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ManageAccounts
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.portguard.R
import com.android.portguard.data.InstalledAppEntry
import com.android.portguard.ui.state.ExceptionsTab
import com.android.portguard.ui.state.ExceptionsUiState
import com.android.portguard.ui.viewmodel.ExceptionsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExceptionsScreen(
    onBack: () -> Unit,
    vm: ExceptionsViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(text = stringResource(id = R.string.exceptions_title))
                        Text(
                            text = stringResource(id = R.string.exceptions_subtitle),
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
            item {
                ExceptionsHeroCard(state = state)
            }

            item {
                ExceptionsTabRow(
                    selectedTab = state.selectedTab,
                    onSelect = vm::selectTab,
                )
            }

            if (state.selectedTab == ExceptionsTab.TRUSTED_UIDS) {
                item {
                    TrustedUidEditor(
                        state = state,
                        onUidDraftChange = vm::updateUidDraft,
                        onAddUid = vm::addUid,
                    )
                }
                item {
                    TrustedUidList(
                        values = state.trustedUids,
                        onRemove = vm::removeUid,
                    )
                }
            } else {
                item {
                    SearchCard(
                        query = state.searchQuery,
                        onQueryChange = vm::updateSearchQuery,
                    )
                }
                item {
                    SelectedPackagesCard(state = state)
                }
                item {
                    HorizontalDivider()
                }
                items(
                    items = state.filteredApps,
                    key = { it.packageName },
                ) { app ->
                    AppSelectionRow(
                        app = app,
                        checked = state.selectedPackageList.contains(app.packageName),
                        enabled = !state.loading && !state.saving,
                        onCheckedChange = { vm.togglePackage(app.packageName, it) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExceptionsHeroCard(state: ExceptionsUiState) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(imageVector = Icons.Rounded.Shield, contentDescription = null)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.exceptions_status_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(id = R.string.exceptions_status_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                AnimatedVisibility(visible = state.loading || state.saving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AssistChip(
                    onClick = {},
                    label = { Text(text = stringResource(id = R.string.exceptions_chip_apps_count, state.installedApps.size)) },
                )
                AssistChip(
                    onClick = {},
                    label = { Text(text = stringResource(id = R.string.exceptions_chip_selected_count, selectedCountForCurrentTab(state))) },
                )
            }
            AnimatedVisibility(visible = state.infoMessage != null) {
                AssistChip(
                    onClick = {},
                    label = { Text(text = state.infoMessage.orEmpty()) },
                )
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
private fun ExceptionsTabRow(
    selectedTab: ExceptionsTab,
    onSelect: (ExceptionsTab) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(id = R.string.exceptions_tabs_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTab == ExceptionsTab.TRUSTED_PACKAGES,
                    onClick = { onSelect(ExceptionsTab.TRUSTED_PACKAGES) },
                    label = { Text(text = stringResource(id = R.string.exceptions_tab_trusted_packages)) },
                )
                FilterChip(
                    selected = selectedTab == ExceptionsTab.KILL_EXCEPTIONS,
                    onClick = { onSelect(ExceptionsTab.KILL_EXCEPTIONS) },
                    label = { Text(text = stringResource(id = R.string.exceptions_tab_kill_exceptions)) },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = selectedTab == ExceptionsTab.SCAN_IGNORE,
                    onClick = { onSelect(ExceptionsTab.SCAN_IGNORE) },
                    label = { Text(text = stringResource(id = R.string.exceptions_tab_scan_ignore)) },
                )
                FilterChip(
                    selected = selectedTab == ExceptionsTab.TRUSTED_UIDS,
                    onClick = { onSelect(ExceptionsTab.TRUSTED_UIDS) },
                    label = { Text(text = stringResource(id = R.string.exceptions_tab_trusted_uids)) },
                )
            }
        }
    }
}

@Composable
private fun SearchCard(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(id = R.string.exceptions_search_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text(text = stringResource(id = R.string.exceptions_search_label)) },
            )
        }
    }
}

@Composable
private fun SelectedPackagesCard(state: ExceptionsUiState) {
    val titleRes = when (state.selectedTab) {
        ExceptionsTab.TRUSTED_PACKAGES -> R.string.exceptions_selected_trusted_packages
        ExceptionsTab.KILL_EXCEPTIONS -> R.string.exceptions_selected_kill_exceptions
        ExceptionsTab.SCAN_IGNORE -> R.string.exceptions_selected_scan_ignore
        ExceptionsTab.TRUSTED_UIDS -> R.string.exceptions_selected_trusted_uids
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(id = titleRes),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (state.selectedPackageList.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.exceptions_selected_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.selectedPackageList.forEach { pkg ->
                        AssistChip(onClick = {}, label = { Text(text = pkg) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AppSelectionRow(
    app: InstalledAppEntry,
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
            AppIcon(packageName = app.packageName)
            Column(modifier = Modifier.weight(1f)) {
                Text(text = app.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = app.packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    Box(modifier = Modifier.size(40.dp), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.size(40.dp),
            factory = { ctx -> ImageView(ctx) },
            update = { imageView ->
                val drawable = runCatching {
                    context.packageManager.getApplicationIcon(packageName)
                }.getOrNull()
                imageView.setImageDrawable(drawable)
            }
        )
    }
}

@Composable
private fun TrustedUidEditor(
    state: ExceptionsUiState,
    onUidDraftChange: (String) -> Unit,
    onAddUid: () -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(id = R.string.exceptions_uid_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = stringResource(id = R.string.exceptions_uid_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = state.uidDraft,
                onValueChange = onUidDraftChange,
                singleLine = true,
                label = { Text(text = stringResource(id = R.string.exceptions_uid_label)) },
            )
            Button(
                onClick = onAddUid,
                enabled = !state.saving && state.uidDraft.isNotBlank(),
            ) {
                Icon(imageVector = Icons.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = stringResource(id = R.string.exceptions_uid_add_button))
            }
        }
    }
}

@Composable
private fun TrustedUidList(
    values: List<String>,
    onRemove: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(id = R.string.exceptions_uid_list_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            if (values.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.exceptions_uid_empty_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    values.forEach { uid ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            AssistChip(
                                onClick = {},
                                leadingIcon = { Icon(imageVector = Icons.Rounded.ManageAccounts, contentDescription = null) },
                                label = { Text(text = uid) },
                            )
                            Button(onClick = { onRemove(uid) }) {
                                Text(text = stringResource(id = R.string.exceptions_uid_remove_button))
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun selectedCountForCurrentTab(state: ExceptionsUiState): Int = when (state.selectedTab) {
    ExceptionsTab.TRUSTED_PACKAGES -> state.trustedPackages.size
    ExceptionsTab.KILL_EXCEPTIONS -> state.killExceptions.size
    ExceptionsTab.SCAN_IGNORE -> state.scanIgnorePackages.size
    ExceptionsTab.TRUSTED_UIDS -> state.trustedUids.size
}
