package com.android.portguard.ui.screens

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.android.portguard.ui.state.CompatibilityState
import com.android.portguard.ui.state.InstallerFlowUiState

@Composable
fun RequirementsScreen(
    state: InstallerFlowUiState,
    onRequestRoot: () -> Unit,
) {
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
                .widthIn(max = 560.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                imageVector = Icons.Rounded.AdminPanelSettings,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(82.dp),
            )
            Text(
                text = "Для работы нужен root-доступ",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Root — это расширенный системный доступ. Обычно Android не даёт приложениям менять защищённые системные каталоги и использовать системные механизмы, скрытые от обычного пользователя. PortGuard использует такие возможности, поэтому для установки и дальнейшей работы нужен root.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    StatusRow(
                        ok = state.rootGranted,
                        title = if (state.rootGranted) "Root-доступ получен" else "Root-доступ ещё не подтверждён",
                    )
                    StatusRow(
                        ok = state.compatibilityState == CompatibilityState.SUPPORTED,
                        title = when (state.compatibilityState) {
                            CompatibilityState.UNSUPPORTED_ANDROID -> "Нужен Android 9 или новее"
                            CompatibilityState.UNSUPPORTED_ARCH -> "Нужна архитектура arm64"
                            CompatibilityState.SUPPORTED -> "Устройство совместимо: Android 9+ и arm64"
                            CompatibilityState.UNKNOWN -> "Совместимость будет проверена после root"
                        },
                    )
                    Text(
                        text = state.compatibilityMessage.ifBlank { state.statusMessage },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            Button(
                onClick = onRequestRoot,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.loading,
            ) {
                Crossfade(targetState = state.loading, label = "root_button_state") { loading ->
                    if (loading) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                            )
                            Text("Проверяем root…")
                        }
                    } else {
                        Text(if (state.rootGranted) "Проверить ещё раз" else "Запросить root")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(
    ok: Boolean,
    title: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = if (ok) Icons.Rounded.CheckCircleOutline else Icons.Rounded.ErrorOutline,
            contentDescription = null,
            tint = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        )
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}
