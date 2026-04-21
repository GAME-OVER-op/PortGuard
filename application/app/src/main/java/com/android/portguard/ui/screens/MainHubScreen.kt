@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.android.portguard.ui.screens

import android.widget.ImageView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.GppGood
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Lan
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SettingsApplications
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import android.graphics.drawable.Drawable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.platform.LocalContext
import androidx.appcompat.widget.AppCompatImageView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.android.portguard.data.FirewallHealth
import com.android.portguard.data.FirewallProtoStatus
import com.android.portguard.data.InstalledAppEntry
import com.android.portguard.data.ReactionMode
import com.android.portguard.ui.state.MainHubTab
import com.android.portguard.ui.state.MainHubUiState
import com.android.portguard.ui.state.filteredAppsForPicker
import com.android.portguard.ui.state.protectionEnabled
import com.android.portguard.ui.viewmodel.MainHubViewModel

@Composable
fun MainHubScreen(vm: MainHubViewModel = viewModel()) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    var portsDialogOpen by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.extraLarge,
                    tonalElevation = 8.dp,
                    shadowElevation = 14.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        HubTabItem(
                            selected = state.selectedTab == MainHubTab.MAIN,
                            label = "Основное",
                            icon = Icons.Rounded.Security,
                            onClick = { vm.selectTab(MainHubTab.MAIN) },
                        )
                        HubTabItem(
                            selected = state.selectedTab == MainHubTab.SETTINGS,
                            label = "Настройки",
                            icon = Icons.Rounded.Settings,
                            onClick = { vm.selectTab(MainHubTab.SETTINGS) },
                        )
                        HubTabItem(
                            selected = state.selectedTab == MainHubTab.ADVANCED,
                            label = "Расширенное",
                            icon = Icons.Rounded.Tune,
                            onClick = { vm.selectTab(MainHubTab.ADVANCED) },
                        )
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (state.selectedTab) {
                MainHubTab.MAIN -> MainTab(state = state, vm = vm, onOpenPorts = { portsDialogOpen = true })
                MainHubTab.SETTINGS -> SettingsTab(state = state, vm = vm)
                MainHubTab.ADVANCED -> AdvancedTab(state = state, vm = vm)
            }

            AnimatedVisibility(
                visible = state.saveMessage != null,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 12.dp),
            ) {
                AssistChip(
                    onClick = {},
                    label = { Text(state.saveMessage.orEmpty()) },
                    leadingIcon = {
                        Icon(imageVector = Icons.Rounded.CheckCircle, contentDescription = null)
                    },
                )
            }
        }
    }

    if (state.pickerOpen) {
        TrustedAppsPickerDialog(
            state = state,
            onDismiss = vm::closePicker,
            onQueryChange = vm::setPickerQuery,
            onToggle = vm::toggleTrustedPackage,
        )
    }

    if (portsDialogOpen) {
        PortsDialog(
            ports = state.blockedPorts,
            onDismiss = { portsDialogOpen = false },
        )
    }
}

@Composable
private fun HubTabItem(
    selected: Boolean,
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    val activeColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    val interactionSource = remember { MutableInteractionSource() }

    Column(
        modifier = Modifier
            .clip(MaterialTheme.shapes.large)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier = Modifier.size(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = activeColor)
        }
        Text(
            text = label,
            color = activeColor,
            style = MaterialTheme.typography.labelMedium,
            textAlign = TextAlign.Center,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
        )
        Box(
            modifier = Modifier
                .height(3.dp)
                .width(22.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
                )
        )
    }
}

private data class ResolvedAppVisual(
    val label: String,
    val packageName: String = "",
    val icon: Drawable? = null,
)

@Composable
private fun rememberResolvedApp(packageName: String?): ResolvedAppVisual {
    val context = LocalContext.current
    return remember(packageName, context) {
        if (packageName.isNullOrBlank()) {
            ResolvedAppVisual(label = "")
        } else {
            val pm = context.packageManager
            runCatching {
                val info = pm.getApplicationInfo(packageName, 0)
                ResolvedAppVisual(
                    label = pm.getApplicationLabel(info).toString().ifBlank { packageName },
                    packageName = packageName,
                    icon = pm.getApplicationIcon(info),
                )
            }.getOrElse {
                ResolvedAppVisual(label = packageName, packageName = packageName)
            }
        }
    }
}

@Composable
private fun AppIconBadge(
    resolved: ResolvedAppVisual,
    fallbackIcon: androidx.compose.ui.graphics.vector.ImageVector,
    size: androidx.compose.ui.unit.Dp = 52.dp,
    iconSize: androidx.compose.ui.unit.Dp = 26.dp,
) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .padding((size * 0.15f).coerceAtLeast(6.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (resolved.icon != null) {
                AndroidView(
                    factory = { ctx ->
                        AppCompatImageView(ctx).apply {
                            scaleType = ImageView.ScaleType.FIT_CENTER
                        }
                    },
                    update = { view ->
                        view.setImageDrawable(resolved.icon)
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = fallbackIcon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}

@Composable
private fun MainTab(state: MainHubUiState, vm: MainHubViewModel, onOpenPorts: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            AppHeroCard(rootManager = state.rootManagerLabel)
        }
        item {
            ProtectionCard(state = state, onToggle = vm::setActiveProtection)
        }
        item {
            ToggleDescriptionCard(
                title = "Режим обучения",
                description = "PortGuard будет обнаруживать попытки анализа, но не будет выполнять жёсткую реакцию. Полезно для спокойной обкатки настроек.",
                checked = state.config.learningMode,
                onCheckedChange = vm::setLearningMode,
                icon = Icons.Rounded.AutoMode,
            )
        }
        item {
            ToggleDescriptionCard(
                title = "Только loopback",
                description = "Защищать только локально доступные порты localhost и wildcard-bind. Остальные адреса в защиту не включать.",
                checked = state.config.protectLoopbackOnly,
                onCheckedChange = vm::setProtectLoopbackOnly,
                icon = Icons.Rounded.Lan,
            )
        }
        item {
            ProtectionInfoCard(
                state = state,
                onOpenPorts = onOpenPorts,
            )
        }
        item {
            AnimatedVisibility(
                visible = state.protectionEnabled,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                FirewallStateCard(state = state)
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

@Composable
private fun SettingsTab(state: MainHubUiState, vm: MainHubViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHero(
                title = "Исключения",
                description = "Доверенные пакеты не будут считаться анализаторами и не получат блокировку. Выбирай их из общего списка приложений.",
                icon = Icons.Rounded.Apps,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("Доверенные пакеты", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = if (state.trustedPackages.isEmpty()) "Пока ничего не выбрано. Можно открыть список приложений и отметить доверенные." else "Выбрано ${state.trustedPackages.size} приложений.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = vm::openPicker) {
                        Text("Выбрать приложения")
                    }
                    if (state.trustedPackages.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            state.trustedPackages.take(6).forEach { pkg ->
                                val resolvedApp = rememberResolvedApp(pkg)
                                TrustedPackageRow(
                                    packageName = pkg,
                                    resolved = resolvedApp,
                                    onRemove = { vm.toggleTrustedPackage(pkg) },
                                )
                            }
                            if (state.trustedPackages.size > 6) {
                                AssistChip(
                                    onClick = vm::openPicker,
                                    label = { Text("Ещё ${state.trustedPackages.size - 6} приложений") },
                                )
                            }
                        }
                    }
                }
            }
        }
        item {
            SectionHero(
                title = "Обнаружения",
                description = "Здесь показываются приложения, которые чаще всего пытались обращаться к защищённым локальным портам. Самые активные — выше всех.",
                icon = Icons.Rounded.BugReport,
            )
        }
        if (state.detections.isEmpty()) {
            item {
                EmptyInfoCard(
                    icon = Icons.Rounded.GppGood,
                    title = "Пока спокойно",
                    description = "Новых зафиксированных попыток анализа пока нет. Когда PortGuard увидит активность, она появится в этом разделе.",
                )
            }
        } else {
            items(state.detections) { detection ->
                val resolvedApp = rememberResolvedApp(detection.packageName)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppIconBadge(
                            resolved = resolvedApp,
                            fallbackIcon = Icons.Rounded.Apps,
                        )
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = resolvedApp.label.ifBlank { detection.title },
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (resolvedApp.packageName.isNotBlank()) {
                                Text(
                                    resolvedApp.packageName,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (detection.latestMessage.isNotBlank()) {
                                Text(
                                    detection.latestMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) {
                            Text(
                                text = detection.attempts.toString(),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}

@Composable
private fun AdvancedTab(state: MainHubUiState, vm: MainHubViewModel) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            SectionHero(
                title = "Реакция на анализатор",
                description = "Выбери, как PortGuard будет реагировать после обнаружения подозрительного сканирования локальных портов.",
                icon = Icons.Rounded.Block,
            )
        }
        item {
            ChoiceCard(
                selected = state.config.reactionMode,
                onSelect = vm::setReactionMode,
            )
        }
        item {
            SectionHero(
                title = "Протоколы и охват",
                description = "Какие стеки и дополнительные механизмы защиты использовать прямо сейчас.",
                icon = Icons.Rounded.Hub,
            )
        }
        item {
            ProtocolsCard(state = state, vm = vm)
        }
        item {
            SectionHero(
                title = "Таймеры и детектор",
                description = "Эти параметры влияют на частоту опроса системы и чувствительность обнаружения анализатора.",
                icon = Icons.Rounded.SettingsApplications,
            )
        }
        item {
            NumberEditCard(
                title = "Интервал основного цикла, мс",
                value = state.config.loopIntervalMs.toString(),
                onDone = vm::setLoopIntervalMs,
            )
        }
        item {
            NumberEditCard(
                title = "Обновление пакетов, сек",
                value = state.config.packageRefreshSecs.toString(),
                onDone = vm::setPackageRefreshSecs,
            )
        }
        item {
            NumberEditCard(
                title = "Чтение счётчиков каждые N циклов",
                value = state.config.counterRefreshLoops.toString(),
                onDone = vm::setCounterRefreshLoops,
            )
        }
        item {
            NumberEditCard(
                title = "Уникальные порты для срабатывания",
                value = state.config.suspiciousUniquePorts.toString(),
                onDone = vm::setSuspiciousUniquePorts,
            )
        }
        item {
            NumberEditCard(
                title = "Попытки для срабатывания",
                value = state.config.suspiciousAttempts.toString(),
                onDone = vm::setSuspiciousAttempts,
            )
        }
        item {
            NumberEditCard(
                title = "Rule hits для срабатывания",
                value = state.config.suspiciousRuleHits.toString(),
                onDone = vm::setSuspiciousRuleHits,
            )
        }
        item {
            ToggleDescriptionCard(
                title = "Определять детали процессов",
                description = "Пытаться дополнительно связывать сокеты с PID и именем процесса. Это полезно для диагностики, но может сильнее нагружать систему.",
                checked = state.config.resolveProcessDetails,
                onCheckedChange = vm::setResolveProcessDetails,
                icon = Icons.Rounded.Memory,
            )
        }
        item { Spacer(modifier = Modifier.height(12.dp)) }
    }
}


@Composable
private fun AppHeroCard(rootManager: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(18.dp).size(40.dp),
                )
            }
            Text(
                text = "PortGuard",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Менеджер root: $rootManager",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun ProtectionCard(state: MainHubUiState, onToggle: (Boolean) -> Unit) {
    val enabled = state.protectionEnabled
    val container = if (enabled) Color(0xFF0F3D24) else Color(0xFF471819)
    val accent = if (enabled) Color(0xFF58D68D) else Color(0xFFFF6B6B)
    Card(colors = CardDefaults.cardColors(containerColor = container)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(color = accent.copy(alpha = 0.16f), shape = MaterialTheme.shapes.large) {
                    Icon(
                        imageVector = if (enabled) Icons.Rounded.GppGood else Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = accent,
                        modifier = Modifier.padding(12.dp).size(30.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (enabled) "Защита включена" else "Защита выключена",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                    Text(
                        text = if (enabled) "PortGuard активно охраняет локальные порты и отслеживает подозрительные обращения к ним." else "При выключенной защите модуль не будет блокировать анализ локальных портов.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.82f),
                    )
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
        }
    }
}

@Composable
private fun ToggleDescriptionCard(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }
}

@Composable
private fun ProtectionInfoCard(state: MainHubUiState, onOpenPorts: () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Информация по защите", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            InfoRow("Количество защищаемых портов", state.portsCount.toString(), true, onOpenPorts)
            InfoRow("Правил после группировки", state.groupedRules.toString())
            InfoRow("Кандидатов приложений", state.candidateApps.toString())
        }
    }
}

@Composable
private fun FirewallStateCard(state: MainHubUiState) {
    val healthText = when (state.firewallHealth) {
        FirewallHealth.WAITING -> "Ожидание"
        FirewallHealth.GOOD -> "Хорошее"
        FirewallHealth.PARTIAL -> "Частично"
        FirewallHealth.ERROR -> "Ошибка"
    }
    val healthColor = when (state.firewallHealth) {
        FirewallHealth.WAITING -> Color(0xFF64B5F6)
        FirewallHealth.GOOD -> Color(0xFF4CAF50)
        FirewallHealth.PARTIAL -> Color(0xFFFFB300)
        FirewallHealth.ERROR -> Color(0xFFFF6B6B)
    }
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(
                    imageVector = when (state.firewallHealth) {
                        FirewallHealth.WAITING -> Icons.Rounded.AutoMode
                        FirewallHealth.GOOD -> Icons.Rounded.CheckCircle
                        FirewallHealth.PARTIAL -> Icons.Rounded.WarningAmber
                        FirewallHealth.ERROR -> Icons.Rounded.ErrorOutline
                    },
                    contentDescription = null,
                    tint = healthColor,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text("Состояние iptables", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(healthText, style = MaterialTheme.typography.bodyMedium, color = healthColor)
                }
            }
            if (state.firewallStatuses.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.firewallStatuses.forEach { proto ->
                        ProtoStatusChip(proto)
                    }
                }
            }
            if (state.selfTestSummary.isNotBlank()) {
                Text("Self-test: ${state.selfTestSummary}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (state.reapplyReason.isNotBlank()) {
                Text("Последняя перестройка: ${state.reapplyReason}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProtoStatusChip(proto: FirewallProtoStatus) {
    val bg = when (proto.status) {
        "ok" -> Color(0x1F4CAF50)
        "idle", "waiting" -> Color(0x1F64B5F6)
        else -> Color(0x1FFF6B6B)
    }
    val fg = when (proto.status) {
        "ok" -> Color(0xFF4CAF50)
        "idle", "waiting" -> Color(0xFF64B5F6)
        else -> Color(0xFFFF6B6B)
    }
    Surface(shape = MaterialTheme.shapes.large, color = bg) {
        Text(
            text = "${proto.proto.uppercase()} · ${if (proto.status == "idle") "waiting" else proto.status}",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            color = fg,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun SectionHero(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape = MaterialTheme.shapes.large) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp).size(28.dp))
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun EmptyInfoCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, description: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun ChoiceCard(selected: ReactionMode, onSelect: (ReactionMode) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ReactionChoiceRow(
                title = "Только блокировать",
                description = "PortGuard ограничится блокировкой обращений к локальным портам и записью факта обнаружения.",
                selected = selected == ReactionMode.OFF,
                onClick = { onSelect(ReactionMode.OFF) },
            )
            HorizontalDivider()
            ReactionChoiceRow(
                title = "Убить приложение",
                description = "После обнаружения анализатора PortGuard сможет принудительно остановить приложение, если это разрешено настройками.",
                selected = selected == ReactionMode.FORCE_STOP,
                onClick = { onSelect(ReactionMode.FORCE_STOP) },
            )
        }
    }
}

@Composable
private fun ReactionChoiceRow(title: String, description: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ProtocolsCard(state: MainHubUiState, vm: MainHubViewModel) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ToggleDescriptionCardInline("TCP IPv4", state.config.tcp4Enabled) { vm.setProtocol("tcp4", it) }
            ToggleDescriptionCardInline("UDP IPv4", state.config.udp4Enabled) { vm.setProtocol("udp4", it) }
            ToggleDescriptionCardInline("TCP IPv6", state.config.tcp6Enabled) { vm.setProtocol("tcp6", it) }
            ToggleDescriptionCardInline("UDP IPv6", state.config.udp6Enabled) { vm.setProtocol("udp6", it) }
            HorizontalDivider()
            ToggleDescriptionCardInline("Только пользовательские приложения", state.config.userAppsOnly) { vm.setUserAppsOnly(it) }
            ToggleDescriptionCardInline("Активный self-test", state.config.activeSelfTestEnabled) { vm.setSelfTestEnabled(it) }
            HorizontalDivider()
            NumberEditCard(
                title = "Проверка смены сети, сек",
                value = state.config.networkRefreshSecs.toString(),
                onDone = vm::setNetworkRefreshSecs,
                compact = true,
            )
        }
    }
}

@Composable
private fun ToggleDescriptionCardInline(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NumberEditCard(title: String, value: String, onDone: (String) -> Unit, compact: Boolean = false) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            OutlinedTextField(
                value = value,
                onValueChange = {},
                modifier = Modifier.fillMaxWidth(),
                readOnly = true,
                singleLine = true,
                trailingIcon = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { onDone((((value.toIntOrNull() ?: 1) - 1).coerceAtLeast(1)).toString()) }) { Text("-") }
                        TextButton(onClick = { onDone(((value.toIntOrNull() ?: 1) + 1).toString()) }) { Text("+") }
                    }
                },
                textStyle = if (compact) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleMedium,
            )
        }
    }
}

@Composable
private fun TrustedAppsPickerDialog(
    state: MainHubUiState,
    onDismiss: () -> Unit,
    onQueryChange: (String) -> Unit,
    onToggle: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Готово") }
        },
        title = { Text("Выбор доверенных приложений") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    modifier = Modifier.fillMaxWidth(),
                    value = state.pickerQuery,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    label = { Text("Поиск") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                )
                if (state.pickerLoading && state.allApps.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        val trustedSet = state.trustedPackages.toSet()
                        val appComparator = compareByDescending<InstalledAppEntry> { it.packageName in trustedSet }
                            .thenBy { it.label.lowercase() }
                            .thenBy { it.packageName }
                        val userApps = state.filteredAppsForPicker
                            .filterNot { it.isSystem }
                            .sortedWith(appComparator)
                        val systemApps = state.filteredAppsForPicker
                            .filter { it.isSystem }
                            .sortedWith(appComparator)
                        if (userApps.isNotEmpty()) {
                            item { PickerSectionLabel("Пользовательские · ${userApps.size}") }
                            items(userApps, key = { it.packageName }) { app ->
                                PickerAppRow(app = app, selected = app.packageName in trustedSet, onToggle = onToggle)
                            }
                        }
                        if (systemApps.isNotEmpty()) {
                            item { PickerSectionLabel("Системные · ${systemApps.size}") }
                            items(systemApps, key = { it.packageName }) { app ->
                                PickerAppRow(app = app, selected = app.packageName in trustedSet, onToggle = onToggle)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun PickerSectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TrustedPackageRow(
    packageName: String,
    resolved: ResolvedAppVisual,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIconBadge(
                resolved = resolved,
                fallbackIcon = Icons.Rounded.Apps,
                size = 42.dp,
                iconSize = 22.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = resolved.label.ifBlank { packageName },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = packageName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onRemove) {
                Text("Убрать")
            }
        }
    }
}

@Composable
private fun PickerAppRow(app: InstalledAppEntry, selected: Boolean, onToggle: (String) -> Unit) {
    val resolvedApp = rememberResolvedApp(app.packageName)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onToggle(app.packageName) },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            else MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AppIconBadge(
                resolved = resolvedApp,
                fallbackIcon = Icons.Rounded.Apps,
                size = 42.dp,
                iconSize = 22.dp,
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    resolvedApp.label.ifBlank { app.label },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(app.packageName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (app.isSystem) "Системное приложение" else "Пользовательское приложение",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (app.isSystem) Color(0xFFFFB300) else Color(0xFF58D68D),
                )
            }
            Switch(checked = selected, onCheckedChange = { onToggle(app.packageName) })
        }
    }
}


@Composable
private fun PortsDialog(ports: List<Int>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
        title = { Text("Защищаемые порты") },
        text = {
            if (ports.isEmpty()) {
                Text(
                    text = "Список портов пока пуст.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(ports) { port ->
                        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text("Порт", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(port.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun InfoRow(title: String, value: String, clickable: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (clickable && onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
    }
}
