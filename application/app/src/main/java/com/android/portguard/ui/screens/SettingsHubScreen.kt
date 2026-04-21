package com.android.portguard.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private enum class SettingsRoute {
    HOME,
    STAGE1_DASHBOARD,
    STAGE2_TRAFFIC,
    STAGE3_ADVANCED,
    STAGE4_EXCEPTIONS,
    STAGE5_SYSTEM,
}

@Composable
fun SettingsHubScreen() {
    var route by rememberSaveable { mutableStateOf(SettingsRoute.HOME) }

    BackHandler(enabled = true) {
        if (route != SettingsRoute.HOME) {
            route = SettingsRoute.HOME
        }
    }

    when (route) {
        SettingsRoute.HOME -> SettingsHomeScreen(
            onOpenStage1 = { route = SettingsRoute.STAGE1_DASHBOARD },
            onOpenStage2 = { route = SettingsRoute.STAGE2_TRAFFIC },
            onOpenStage3 = { route = SettingsRoute.STAGE3_ADVANCED },
            onOpenStage4 = { route = SettingsRoute.STAGE4_EXCEPTIONS },
            onOpenStage5 = { route = SettingsRoute.STAGE5_SYSTEM },
        )
        SettingsRoute.STAGE1_DASHBOARD -> DashboardScreen(
            onBackToSetup = { route = SettingsRoute.HOME },
            onOpenExceptions = { route = SettingsRoute.STAGE4_EXCEPTIONS },
            onOpenAdvancedSettings = { route = SettingsRoute.STAGE3_ADVANCED },
        )
        SettingsRoute.STAGE2_TRAFFIC -> TrafficSettingsScreen(onBack = { route = SettingsRoute.HOME })
        SettingsRoute.STAGE3_ADVANCED -> AdvancedSettingsScreen(onBack = { route = SettingsRoute.HOME })
        SettingsRoute.STAGE4_EXCEPTIONS -> ExceptionsScreen(onBack = { route = SettingsRoute.HOME })
        SettingsRoute.STAGE5_SYSTEM -> SystemSettingsScreen(onBack = { route = SettingsRoute.HOME })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsHomeScreen(
    onOpenStage1: () -> Unit,
    onOpenStage2: () -> Unit,
    onOpenStage3: () -> Unit,
    onOpenStage4: () -> Unit,
    onOpenStage5: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Настройки PortGuard")
                        Text(
                            text = "Все настройки разбиты на этапы, чтобы не перегружать интерфейс.",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                StageCard(
                    title = "Этап 1 · Базовая защита",
                    description = "Главные переключатели защиты, обучение, реакция на угрозы и общий режим работы.",
                    icon = Icons.Rounded.Security,
                    onClick = onOpenStage1,
                )
            }
            item {
                StageCard(
                    title = "Этап 2 · Протоколы и охват",
                    description = "Выбор TCP/UDP и IPv4/IPv6, правило только для пользовательских приложений и self-test.",
                    icon = Icons.Rounded.Apps,
                    onClick = onOpenStage2,
                )
            }
            item {
                StageCard(
                    title = "Этап 3 · Детектор и таймеры",
                    description = "Чувствительность скан-детектора, окна анализа, интервалы опроса и детализация процессов.",
                    icon = Icons.Rounded.Speed,
                    onClick = onOpenStage3,
                )
            }
            item {
                StageCard(
                    title = "Этап 4 · Доверие и исключения",
                    description = "Доверенные пакеты и UID, пакеты-исключения и приложения, которые не нужно считать сканерами.",
                    icon = Icons.Rounded.AutoFixHigh,
                    onClick = onOpenStage4,
                )
            }
            item {
                StageCard(
                    title = "Этап 5 · Системные параметры",
                    description = "Имя chain, state_dir, лимиты правил, package_uid_sources и игнорируемые владельцы портов.",
                    icon = Icons.Rounded.Memory,
                    onClick = onOpenStage5,
                )
            }
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Что уже покрыто",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            text = "В этих этапах собраны все реальные настройки из Rust-части PortGuard: базовые параметры, протоколы, детектор, списки исключений и системные параметры модуля.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StageCard(
    title: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(text = description, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.size(4.dp))
            Icon(
                imageVector = Icons.Rounded.AdminPanelSettings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
