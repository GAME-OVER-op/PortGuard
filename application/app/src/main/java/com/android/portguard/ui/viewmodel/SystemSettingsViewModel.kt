package com.android.portguard.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.core.RootShell
import com.android.portguard.data.ModuleStatusRepository
import com.android.portguard.data.SettingsFileRepository
import com.android.portguard.ui.state.SystemSettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SystemSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val moduleStatusRepository = ModuleStatusRepository(rootShell, settingsRepository)

    private val _uiState = MutableStateFlow(SystemSettingsUiState())
    val uiState: StateFlow<SystemSettingsUiState> = _uiState.asStateFlow()

    init { refresh() }

    fun refresh() {
        _uiState.value = _uiState.value.copy(loading = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val env = moduleStatusRepository.inspect(readConfigPreview = false)
            val config = settingsRepository.readConfig()
            val canEdit = env.rootGranted && env.moduleDirectoryExists && env.settingsDirectoryExists
            _uiState.value = if (config != null) {
                SystemSettingsUiState(
                    loading = false,
                    canEdit = canEdit,
                    statusText = buildStatus(env),
                    chainName = config.chainName,
                    stateDir = config.stateDir,
                    maxRules = config.maxRules.toString(),
                    autoDiscoverPackages = config.autoDiscoverPackages,
                    packageUidSourcesText = config.packageUidSources.joinToString("\n"),
                    summaryPortLimit = config.summaryPortLimit.toString(),
                    ignoredOwnerPackagesText = config.ignoredOwnerPackages.joinToString("\n"),
                )
            } else {
                SystemSettingsUiState(
                    loading = false,
                    canEdit = canEdit,
                    statusText = buildStatus(env),
                    errorMessage = "Не удалось загрузить системные параметры.",
                )
            }
        }
    }

    fun setChainName(value: String) = patch { copy(chainName = value, dirty = true, infoMessage = null, errorMessage = null) }
    fun setStateDir(value: String) = patch { copy(stateDir = value, dirty = true, infoMessage = null, errorMessage = null) }
    fun setMaxRules(value: String) = patch { copy(maxRules = value.filter(Char::isDigit), dirty = true, infoMessage = null, errorMessage = null) }
    fun setAutoDiscoverPackages(value: Boolean) = patch { copy(autoDiscoverPackages = value, dirty = true, infoMessage = null, errorMessage = null) }
    fun setPackageUidSourcesText(value: String) = patch { copy(packageUidSourcesText = value, dirty = true, infoMessage = null, errorMessage = null) }
    fun setSummaryPortLimit(value: String) = patch { copy(summaryPortLimit = value.filter(Char::isDigit), dirty = true, infoMessage = null, errorMessage = null) }
    fun setIgnoredOwnerPackagesText(value: String) = patch { copy(ignoredOwnerPackagesText = value, dirty = true, infoMessage = null, errorMessage = null) }

    fun save() {
        val state = _uiState.value
        if (!state.canEdit || state.saving) return
        val maxRules = state.maxRules.toIntOrNull()
        val summaryPortLimit = state.summaryPortLimit.toIntOrNull()
        if (maxRules == null || summaryPortLimit == null) {
            _uiState.value = state.copy(errorMessage = "Проверь числовые параметры перед сохранением.")
            return
        }
        val packageSources = state.packageUidSourcesText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val ignoredOwners = state.ignoredOwnerPackagesText.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        _uiState.value = state.copy(saving = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = settingsRepository.updateSystemConfig(
                chainName = _uiState.value.chainName,
                stateDir = _uiState.value.stateDir,
                maxRules = maxRules,
                autoDiscoverPackages = _uiState.value.autoDiscoverPackages,
                packageUidSources = packageSources,
                summaryPortLimit = summaryPortLimit,
                ignoredOwnerPackages = ignoredOwners,
            )
            if (!ok) {
                _uiState.value = _uiState.value.copy(saving = false, errorMessage = "Не удалось сохранить системные параметры.")
                return@launch
            }
            refreshWithMessage("Настройки этапа 5 сохранены.")
        }
    }

    private suspend fun refreshWithMessage(message: String) {
        val env = moduleStatusRepository.inspect(readConfigPreview = false)
        val config = settingsRepository.readConfig()
        if (config == null) {
            _uiState.value = _uiState.value.copy(saving = false, errorMessage = "Не удалось перечитать настройки.")
            return
        }
        _uiState.value = SystemSettingsUiState(
            loading = false,
            canEdit = env.rootGranted && env.moduleDirectoryExists && env.settingsDirectoryExists,
            statusText = buildStatus(env),
            chainName = config.chainName,
            stateDir = config.stateDir,
            maxRules = config.maxRules.toString(),
            autoDiscoverPackages = config.autoDiscoverPackages,
            packageUidSourcesText = config.packageUidSources.joinToString("\n"),
            summaryPortLimit = config.summaryPortLimit.toString(),
            ignoredOwnerPackagesText = config.ignoredOwnerPackages.joinToString("\n"),
            infoMessage = message,
            dirty = false,
        )
    }

    private fun patch(block: SystemSettingsUiState.() -> SystemSettingsUiState) {
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
