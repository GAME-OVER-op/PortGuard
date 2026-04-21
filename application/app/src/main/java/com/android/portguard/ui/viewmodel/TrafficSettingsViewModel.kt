package com.android.portguard.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.core.RootShell
import com.android.portguard.data.ModuleStatusRepository
import com.android.portguard.data.SettingsFileRepository
import com.android.portguard.ui.state.TrafficSettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class TrafficSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val moduleStatusRepository = ModuleStatusRepository(rootShell, settingsRepository)

    private val _uiState = MutableStateFlow(TrafficSettingsUiState())
    val uiState: StateFlow<TrafficSettingsUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        _uiState.value = _uiState.value.copy(loading = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val env = moduleStatusRepository.inspect(readConfigPreview = false)
            val config = settingsRepository.readConfig()
            val canEdit = env.rootGranted && env.moduleDirectoryExists && env.settingsDirectoryExists
            _uiState.value = if (config != null) {
                TrafficSettingsUiState(
                    loading = false,
                    canEdit = canEdit,
                    statusText = buildStatus(env),
                    tcp4Enabled = config.tcp4Enabled,
                    udp4Enabled = config.udp4Enabled,
                    tcp6Enabled = config.tcp6Enabled,
                    udp6Enabled = config.udp6Enabled,
                    userAppsOnly = config.userAppsOnly,
                    networkRefreshSecs = config.networkRefreshSecs.toString(),
                    activeSelfTestEnabled = config.activeSelfTestEnabled,
                    selfTestTimeoutMs = config.selfTestTimeoutMs.toString(),
                )
            } else {
                TrafficSettingsUiState(
                    loading = false,
                    canEdit = canEdit,
                    statusText = buildStatus(env),
                    errorMessage = "Не удалось загрузить настройки трафика.",
                )
            }
        }
    }

    fun setTcp4(value: Boolean) = patch { copy(tcp4Enabled = value, dirty = true, infoMessage = null, errorMessage = null) }
    fun setUdp4(value: Boolean) = patch { copy(udp4Enabled = value, dirty = true, infoMessage = null, errorMessage = null) }
    fun setTcp6(value: Boolean) = patch { copy(tcp6Enabled = value, dirty = true, infoMessage = null, errorMessage = null) }
    fun setUdp6(value: Boolean) = patch { copy(udp6Enabled = value, dirty = true, infoMessage = null, errorMessage = null) }
    fun setUserAppsOnly(value: Boolean) = patch { copy(userAppsOnly = value, dirty = true, infoMessage = null, errorMessage = null) }
    fun setNetworkRefreshSecs(value: String) = patch { copy(networkRefreshSecs = value.filter(Char::isDigit), dirty = true, infoMessage = null, errorMessage = null) }
    fun setActiveSelfTest(value: Boolean) = patch { copy(activeSelfTestEnabled = value, dirty = true, infoMessage = null, errorMessage = null) }
    fun setSelfTestTimeoutMs(value: String) = patch { copy(selfTestTimeoutMs = value.filter(Char::isDigit), dirty = true, infoMessage = null, errorMessage = null) }

    fun save() {
        val state = _uiState.value
        if (!state.canEdit || state.saving) return
        val networkRefresh = state.networkRefreshSecs.toIntOrNull()
        val selfTestTimeout = state.selfTestTimeoutMs.toIntOrNull()
        if (networkRefresh == null || selfTestTimeout == null) {
            _uiState.value = state.copy(errorMessage = "Проверь числовые значения перед сохранением.")
            return
        }
        _uiState.value = state.copy(saving = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = settingsRepository.updateTrafficAndRuntimeConfig(
                tcp4Enabled = _uiState.value.tcp4Enabled,
                udp4Enabled = _uiState.value.udp4Enabled,
                tcp6Enabled = _uiState.value.tcp6Enabled,
                udp6Enabled = _uiState.value.udp6Enabled,
                userAppsOnly = _uiState.value.userAppsOnly,
                networkRefreshSecs = networkRefresh,
                activeSelfTestEnabled = _uiState.value.activeSelfTestEnabled,
                selfTestTimeoutMs = selfTestTimeout,
            )
            if (!ok) {
                _uiState.value = _uiState.value.copy(saving = false, errorMessage = "Не удалось сохранить настройки трафика.")
                return@launch
            }
            refreshWithMessage("Настройки этапа 2 сохранены.")
        }
    }

    private suspend fun refreshWithMessage(message: String) {
        val env = moduleStatusRepository.inspect(readConfigPreview = false)
        val config = settingsRepository.readConfig()
        if (config == null) {
            _uiState.value = _uiState.value.copy(saving = false, errorMessage = "Не удалось перечитать настройки.")
            return
        }
        _uiState.value = TrafficSettingsUiState(
            loading = false,
            canEdit = env.rootGranted && env.moduleDirectoryExists && env.settingsDirectoryExists,
            statusText = buildStatus(env),
            tcp4Enabled = config.tcp4Enabled,
            udp4Enabled = config.udp4Enabled,
            tcp6Enabled = config.tcp6Enabled,
            udp6Enabled = config.udp6Enabled,
            userAppsOnly = config.userAppsOnly,
            networkRefreshSecs = config.networkRefreshSecs.toString(),
            activeSelfTestEnabled = config.activeSelfTestEnabled,
            selfTestTimeoutMs = config.selfTestTimeoutMs.toString(),
            infoMessage = message,
            dirty = false,
        )
    }

    private fun patch(block: TrafficSettingsUiState.() -> TrafficSettingsUiState) {
        _uiState.value = _uiState.value.block()
    }

    private fun buildStatus(env: com.android.portguard.data.ModuleEnvironmentStatus): String {
        val parts = mutableListOf<String>()
        parts += if (env.rootGranted) "root есть" else "root недоступен"
        parts += if (env.moduleDirectoryExists) "модуль найден" else "модуль не найден"
        parts += if (env.settingsDirectoryExists) "settings найден" else "settings не найден"
        return parts.joinToString(" • ")
    }
}
