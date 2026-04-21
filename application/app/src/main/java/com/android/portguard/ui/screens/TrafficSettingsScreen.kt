package com.android.portguard.ui.screens

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
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.VerifiedUser
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
import com.android.portguard.ui.viewmodel.TrafficSettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrafficSettingsScreen(
    onBack: () -> Unit,
    vm: TrafficSettingsViewModel = viewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Этап 2 · Протоколы и охват")
                        Text(
                            text = "Настройки протоколов, области защиты и активного self-test.",
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
                HeroBlock(
                    title = "Что делает этот этап",
                    text = "Здесь задаётся, какой трафик должен защищаться: TCP/UDP, IPv4/IPv6, только пользовательские приложения или также системные источники. Здесь же настраивается self-test и реакция на смену сети.",
                    status = state.statusText,
                )
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
                    Text("Сохранить этап 2")
                }
            }
            item { SwitchCard("TCP IPv4", "Основной стек защиты для IPv4 TCP.", state.tcp4Enabled, state.canEdit && !state.saving, vm::setTcp4) }
            item { SwitchCard("UDP IPv4", "Включает защиту локальных UDP-портов в IPv4.", state.udp4Enabled, state.canEdit && !state.saving, vm::setUdp4) }
            item { SwitchCard("TCP IPv6", "Добавляет защиту локальных TCP-портов в IPv6.", state.tcp6Enabled, state.canEdit && !state.saving, vm::setTcp6) }
            item { SwitchCard("UDP IPv6", "Добавляет защиту локальных UDP-портов в IPv6.", state.udp6Enabled, state.canEdit && !state.saving, vm::setUdp6) }
            item { HorizontalDivider() }
            item {
                SwitchCard(
                    title = "Только пользовательские приложения",
                    description = "Если включено, источниками блокировки считаются только пользовательские приложения. Если выключено — и пользовательские, и системные пакеты/службы.",
                    checked = state.userAppsOnly,
                    enabled = state.canEdit && !state.saving,
                    onCheckedChange = vm::setUserAppsOnly,
                    icon = Icons.Rounded.VerifiedUser,
                )
            }
            item {
                NumberFieldCard(
                    title = "Проверка смены сети (сек)",
                    description = "Как часто проверять изменение локальных IP-адресов, чтобы автоматически пересобрать правила при смене сети.",
                    value = state.networkRefreshSecs,
                    enabled = state.canEdit && !state.saving,
                    onValueChange = vm::setNetworkRefreshSecs,
                )
            }
            item {
                SwitchCard(
                    title = "Активный self-test",
                    description = "После применения правил PortGuard может сам проверить, ловит ли firewall тестовый локальный трафик.",
                    checked = state.activeSelfTestEnabled,
                    enabled = state.canEdit && !state.saving,
                    onCheckedChange = vm::setActiveSelfTest,
                    icon = Icons.Rounded.Router,
                )
            }
            item {
                NumberFieldCard(
                    title = "Таймаут self-test (мс)",
                    description = "Сколько ждать ответа от тестового трафика после применения правил.",
                    value = state.selfTestTimeoutMs,
                    enabled = state.canEdit && !state.saving,
                    onValueChange = vm::setSelfTestTimeoutMs,
                )
            }
            state.infoMessage?.let { item { MessageCard(it, positive = true) } }
            state.errorMessage?.let { item { MessageCard(it, positive = false) } }
        }
    }
}

@Composable
private fun HeroBlock(title: String, text: String, status: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(status, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SwitchCard(
    title: String,
    description: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector = Icons.Rounded.Router,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun NumberFieldCard(
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
            )
        }
    }
}

@Composable
fun MessageCard(text: String, positive: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = if (positive) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer)) {
        Text(
            text = text,
            modifier = Modifier.padding(14.dp),
            color = if (positive) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}
