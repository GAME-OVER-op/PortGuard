package com.android.portguard.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.portguard.ui.viewmodel.SystemSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SystemSettingsScreen(
    onBack: () -> Unit,
    vm: SystemSettingsViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Этап 5 · Системные параметры")
                        Text(
                            text = "Редкие, но важные параметры ядра конфигурации PortGuard.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = vm::refresh) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null)
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
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
                    Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Осторожно с изменениями", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Этот этап влияет на внутреннюю организацию правил, лимиты и источники package→UID. Менять параметры стоит осознанно, особенно chain_name и state_dir.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(state.statusText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item {
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = state.canEdit && state.dirty && !state.saving && !state.loading,
                    onClick = vm::save,
                ) {
                    if (state.saving) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.size(8.dp))
                    } else {
                        Icon(Icons.Rounded.Save, contentDescription = null)
                        Spacer(modifier = Modifier.size(8.dp))
                    }
                    Text("Сохранить этап 5")
                }
            }
            item { TextFieldCard("Имя chain", "Базовое имя цепочек PortGuard в iptables/ip6tables.", state.chainName, state.canEdit && !state.saving, vm::setChainName) }
            item { TextFieldCard("state_dir", "Каталог, где модуль хранит runtime_state, capabilities и self-test.", state.stateDir, state.canEdit && !state.saving, vm::setStateDir) }
            item { TextFieldCard("max_rules", "Максимальное число желаемых правил до защитного ограничения.", state.maxRules, state.canEdit && !state.saving, vm::setMaxRules) }
            item {
                ToggleCard(
                    title = "Автообнаружение пакетов",
                    description = "Разрешает использовать file-based package_uid_sources до fallback через cmd/pm list packages.",
                    checked = state.autoDiscoverPackages,
                    enabled = state.canEdit && !state.saving,
                    onCheckedChange = vm::setAutoDiscoverPackages,
                )
            }
            item { MultilineCard("package_uid_sources", "Список путей, из которых читать package→UID mapping. По одному пути на строку.", state.packageUidSourcesText, state.canEdit && !state.saving, vm::setPackageUidSourcesText) }
            item { TextFieldCard("summary_port_limit", "Сколько портов показывать в summary и логах до сокращения списка.", state.summaryPortLimit, state.canEdit && !state.saving, vm::setSummaryPortLimit) }
            item { MultilineCard("ignored_owner_packages", "Пакеты-владельцы портов, которые PortGuard не должен включать в protected listeners. По одному пакету на строку.", state.ignoredOwnerPackagesText, state.canEdit && !state.saving, vm::setIgnoredOwnerPackagesText) }
            state.infoMessage?.let { item { MessageCard(it, positive = true) } }
            state.errorMessage?.let { item { MessageCard(it, positive = false) } }
        }
    }
}

@Composable
private fun TextFieldCard(
    title: String,
    description: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                singleLine = true,
                label = { Text(title) },
                leadingIcon = { Icon(Icons.Rounded.Build, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun MultilineCard(
    title: String,
    description: String,
    value: String,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                modifier = Modifier.fillMaxWidth(),
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                minLines = 4,
                label = { Text(title) },
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
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}
