package com.android.portguard.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.portguard.data.ModuleInstallerType
import com.android.portguard.ui.state.InstallerFlowUiState

@Composable
fun InstallModuleScreen(
    state: InstallerFlowUiState,
    onInstall: () -> Unit,
    onReboot: () -> Unit,
) {
    val installEnabled = state.rootGranted && state.assetPresent && !state.installing && !state.rebooting && state.installerType != ModuleInstallerType.MANUAL
    val animatedProgress by animateFloatAsState(
        targetValue = (state.installProgress.coerceIn(0, 100) / 100f),
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "install_progress",
    )
    var showLogs by rememberSaveable { mutableStateOf(false) }
    val hasLogs = state.installLog.isNotBlank() || state.installError != null
    LaunchedEffect(hasLogs) {
        if (!hasLogs) showLogs = false
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 560.dp)
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = if (state.installSuccess || state.stagedUpdateFound) Icons.Rounded.CheckCircle else Icons.Rounded.InstallMobile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(82.dp),
            )
            Text(
                text = when {
                    state.installSuccess || state.stagedUpdateFound -> "Установка завершена"
                    state.updateAvailable -> "Для продолжения нужно обновить модуль"
                    else -> "Для установки нужно произвести установку"
                },
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = when {
                    state.installSuccess || state.stagedUpdateFound -> "Модуль установлен. Для применения изменений требуется перезагрузка устройства."
                    state.updateAvailable -> "В системе обнаружена более старая версия PortGuard. Чтобы использовать актуальную сборку, модуль нужно обновить."
                    else -> "Приложение подготовило встроенный архив модуля и определило доступный способ установки."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    InfoValue(title = "Метод установки", value = state.installerType.label)
                    if (state.updateAvailable) {
                        InfoValue(
                            title = "Обновление",
                            value = "${state.installedModuleInfo?.version.orEmpty()}  →  ${state.bundledModuleInfo?.version.orEmpty()}",
                        )
                    } else if (state.bundledModuleInfo?.version?.isNullOrBlank() == false) {
                        InfoValue(title = "Версия модуля", value = state.bundledModuleInfo?.version.orEmpty())
                    }
                    if (!state.assetPresent) {
                        Text(
                            text = "Встроенный архив module.zip не найден в приложении.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (state.installerType == ModuleInstallerType.MANUAL) {
                        Text(
                            text = "Автоматический установщик не найден. Нужен Magisk, KernelSU или APatch.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                    if (state.installError != null && !state.installing) {
                        Text(
                            text = state.installError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = state.installing || state.installSuccess || state.installError != null,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically(),
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            if (state.installing) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                )
                            }
                            Text(
                                text = state.installPhase.ifBlank {
                                    when {
                                        state.installSuccess -> "Установка завершена"
                                        state.installError != null -> "Установка завершилась с ошибкой"
                                        else -> "Подготовка установки"
                                    }
                                },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center,
                            )
                        }
                        Text(
                            text = if (state.installing) "${state.installProgress}%" else if (state.installSuccess) "100%" else state.statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(999.dp)),
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = !state.installSuccess && !state.stagedUpdateFound,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Button(
                    onClick = onInstall,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = installEnabled,
                ) {
                    if (state.installing) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Text("Устанавливаем… ${state.installProgress}%")
                        }
                    } else {
                        Text(if (state.updateAvailable) "Обновить модуль" else "Установить")
                    }
                }
            }

            AnimatedVisibility(
                visible = state.installSuccess || state.stagedUpdateFound,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Button(
                    onClick = onReboot,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.rebooting,
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.RestartAlt, contentDescription = null)
                        Text(if (state.rebooting) "Подготавливаем перезагрузку…" else "Перезагрузить устройство")
                    }
                }
            }

            AnimatedVisibility(
                visible = hasLogs,
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.clickable { showLogs = !showLogs },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Rounded.Article, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                            Text(
                                text = if (showLogs) "Скрыть логи" else "Посмотреть логи",
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = showLogs,
                        enter = fadeIn() + expandVertically(),
                        exit = fadeOut() + shrinkVertically(),
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            SelectionContainer {
                                Text(
                                    text = state.installLog.ifBlank { state.installError.orEmpty() },
                                    modifier = Modifier.padding(16.dp),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InfoValue(title: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}
