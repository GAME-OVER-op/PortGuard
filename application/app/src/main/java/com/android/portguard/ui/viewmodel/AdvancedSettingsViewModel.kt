package com.android.portguard.ui.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.R
import com.android.portguard.core.RootShell
import com.android.portguard.data.ModuleStatusRepository
import com.android.portguard.data.SettingsFileRepository
import com.android.portguard.ui.state.AdvancedSettingsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AdvancedSettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val moduleStatusRepository = ModuleStatusRepository(rootShell, settingsRepository)

    private val _uiState = MutableStateFlow(AdvancedSettingsUiState())
    val uiState: StateFlow<AdvancedSettingsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(loading = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val environment = moduleStatusRepository.inspect(readConfigPreview = false)
            val config = settingsRepository.readConfig()
            val canEdit = environment.rootGranted && environment.moduleDirectoryExists && environment.settingsDirectoryExists
            _uiState.value = if (config != null) {
                AdvancedSettingsUiState(
                    loading = false,
                    saving = false,
                    canEdit = canEdit,
                    statusText = buildStatusText(environment),
                    suspiciousUniquePorts = config.suspiciousUniquePorts.toString(),
                    suspiciousAttempts = config.suspiciousAttempts.toString(),
                    suspiciousRuleHits = config.suspiciousRuleHits.toString(),
                    scanWindowSecs = config.scanWindowSecs.toString(),
                    warnCooldownSecs = config.warnCooldownSecs.toString(),
                    reactionCooldownSecs = config.reactionCooldownSecs.toString(),
                    loopIntervalMs = config.loopIntervalMs.toString(),
                    reloadCheckSecs = config.reloadCheckSecs.toString(),
                    packageRefreshSecs = config.packageRefreshSecs.toString(),
                    counterRefreshLoops = config.counterRefreshLoops.toString(),
                    resolveProcessDetails = config.resolveProcessDetails,
                    protectLoopbackOnly = config.protectLoopbackOnly,
                )
            } else {
                AdvancedSettingsUiState(
                    loading = false,
                    saving = false,
                    canEdit = canEdit,
                    statusText = buildStatusText(environment),
                    errorMessage = str(R.string.advanced_settings_load_failed),
                )
            }
        }
    }

    fun updateNumber(field: AdvancedNumberField, value: String) {
        val sanitized = value.filter { it.isDigit() }
        val state = _uiState.value
        _uiState.value = when (field) {
            AdvancedNumberField.SUSPICIOUS_UNIQUE_PORTS -> state.copy(suspiciousUniquePorts = sanitized, dirty = true, infoMessage = null, errorMessage = null)
            AdvancedNumberField.SUSPICIOUS_ATTEMPTS -> state.copy(suspiciousAttempts = sanitized, dirty = true, infoMessage = null, errorMessage = null)
            AdvancedNumberField.SUSPICIOUS_RULE_HITS -> state.copy(suspiciousRuleHits = sanitized, dirty = true, infoMessage = null, errorMessage = null)
            AdvancedNumberField.SCAN_WINDOW_SECS -> state.copy(scanWindowSecs = sanitized, dirty = true, infoMessage = null, errorMessage = null)
            AdvancedNumberField.WARN_COOLDOWN_SECS -> state.copy(warnCooldownSecs = sanitized, dirty = true, infoMessage = null, errorMessage = null)
            AdvancedNumberField.REACTION_COOLDOWN_SECS -> state.copy(reactionCooldownSecs = sanitized, dirty = true, infoMessage = null, errorMessage = null)
            AdvancedNumberField.LOOP_INTERVAL_MS -> state.copy(loopIntervalMs = sanitized, dirty = true, infoMessage = null, errorMessage = null)
            AdvancedNumberField.RELOAD_CHECK_SECS -> state.copy(reloadCheckSecs = sanitized, dirty = true, infoMessage = null, errorMessage = null)
            AdvancedNumberField.PACKAGE_REFRESH_SECS -> state.copy(packageRefreshSecs = sanitized, dirty = true, infoMessage = null, errorMessage = null)
            AdvancedNumberField.COUNTER_REFRESH_LOOPS -> state.copy(counterRefreshLoops = sanitized, dirty = true, infoMessage = null, errorMessage = null)
        }
    }

    fun setResolveProcessDetails(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(resolveProcessDetails = enabled, dirty = true, infoMessage = null, errorMessage = null)
    }

    fun setProtectLoopbackOnly(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(protectLoopbackOnly = enabled, dirty = true, infoMessage = null, errorMessage = null)
    }

    fun saveChanges() {
        val state = _uiState.value
        if (!state.canEdit || state.saving) return

        val uniquePorts = state.suspiciousUniquePorts.toIntOrNull()
        val attempts = state.suspiciousAttempts.toIntOrNull()
        val ruleHits = state.suspiciousRuleHits.toIntOrNull()
        val scanWindow = state.scanWindowSecs.toIntOrNull()
        val warnCooldown = state.warnCooldownSecs.toIntOrNull()
        val reactionCooldown = state.reactionCooldownSecs.toIntOrNull()
        val loopInterval = state.loopIntervalMs.toIntOrNull()
        val reloadCheck = state.reloadCheckSecs.toIntOrNull()
        val packageRefresh = state.packageRefreshSecs.toIntOrNull()
        val counterRefresh = state.counterRefreshLoops.toIntOrNull()

        if (listOf(uniquePorts, attempts, ruleHits, scanWindow, warnCooldown, reactionCooldown, loopInterval, reloadCheck, packageRefresh, counterRefresh).any { it == null }) {
            _uiState.value = state.copy(errorMessage = str(R.string.advanced_settings_invalid_number))
            return
        }

        _uiState.value = state.copy(saving = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = settingsRepository.updateAdvancedConfig(
                suspiciousUniquePorts = uniquePorts!!,
                suspiciousAttempts = attempts!!,
                suspiciousRuleHits = ruleHits!!,
                scanWindowSecs = scanWindow!!,
                warnCooldownSecs = warnCooldown!!,
                reactionCooldownSecs = reactionCooldown!!,
                loopIntervalMs = loopInterval!!,
                reloadCheckSecs = reloadCheck!!,
                packageRefreshSecs = packageRefresh!!,
                counterRefreshLoops = counterRefresh!!,
                resolveProcessDetails = _uiState.value.resolveProcessDetails,
                protectLoopbackOnly = _uiState.value.protectLoopbackOnly,
            )
            if (ok) {
                refreshWithMessage(str(R.string.advanced_settings_save_success))
            } else {
                _uiState.value = _uiState.value.copy(saving = false, errorMessage = str(R.string.advanced_settings_save_failed))
            }
        }
    }

    private suspend fun refreshWithMessage(message: String) {
        val environment = moduleStatusRepository.inspect(readConfigPreview = false)
        val config = settingsRepository.readConfig()
        if (config == null) {
            _uiState.value = _uiState.value.copy(saving = false, errorMessage = str(R.string.advanced_settings_load_failed))
            return
        }
        _uiState.value = AdvancedSettingsUiState(
            loading = false,
            saving = false,
            canEdit = environment.rootGranted && environment.moduleDirectoryExists && environment.settingsDirectoryExists,
            statusText = buildStatusText(environment),
            suspiciousUniquePorts = config.suspiciousUniquePorts.toString(),
            suspiciousAttempts = config.suspiciousAttempts.toString(),
            suspiciousRuleHits = config.suspiciousRuleHits.toString(),
            scanWindowSecs = config.scanWindowSecs.toString(),
            warnCooldownSecs = config.warnCooldownSecs.toString(),
            reactionCooldownSecs = config.reactionCooldownSecs.toString(),
            loopIntervalMs = config.loopIntervalMs.toString(),
            reloadCheckSecs = config.reloadCheckSecs.toString(),
            packageRefreshSecs = config.packageRefreshSecs.toString(),
            counterRefreshLoops = config.counterRefreshLoops.toString(),
            resolveProcessDetails = config.resolveProcessDetails,
            protectLoopbackOnly = config.protectLoopbackOnly,
            dirty = false,
            infoMessage = message,
            errorMessage = null,
        )
    }

    private fun buildStatusText(environment: com.android.portguard.data.ModuleEnvironmentStatus): String {
        val parts = mutableListOf<String>()
        parts += if (environment.rootGranted) str(R.string.status_part_root_ok) else str(R.string.root_status_denied)
        parts += if (environment.moduleDirectoryExists) str(R.string.status_part_module_found) else str(R.string.status_part_module_missing)
        parts += if (environment.settingsDirectoryExists) str(R.string.status_part_settings_found) else str(R.string.status_part_settings_missing)
        return parts.joinToString(separator = " • ")
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)
}

enum class AdvancedNumberField {
    SUSPICIOUS_UNIQUE_PORTS,
    SUSPICIOUS_ATTEMPTS,
    SUSPICIOUS_RULE_HITS,
    SCAN_WINDOW_SECS,
    WARN_COOLDOWN_SECS,
    REACTION_COOLDOWN_SECS,
    LOOP_INTERVAL_MS,
    RELOAD_CHECK_SECS,
    PACKAGE_REFRESH_SECS,
    COUNTER_REFRESH_LOOPS,
}
