package com.android.portguard.ui.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.R
import com.android.portguard.core.RootShell
import com.android.portguard.data.ActiveProtectionMode
import com.android.portguard.data.ModuleEnvironmentStatus
import com.android.portguard.data.ModuleStatusRepository
import com.android.portguard.data.PortGuardConfig
import com.android.portguard.data.PortGuardStateRepository
import com.android.portguard.data.ReactionMode
import com.android.portguard.data.SettingsFileRepository
import com.android.portguard.ui.state.DashboardUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class DashboardViewModel(app: Application) : AndroidViewModel(app) {
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val moduleStatusRepository = ModuleStatusRepository(rootShell, settingsRepository)
    private val stateRepository = PortGuardStateRepository(rootShell)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(
            loading = true,
            infoMessage = null,
            errorMessage = null,
        )
        viewModelScope.launch(Dispatchers.IO) {
            val environment = moduleStatusRepository.inspect(readConfigPreview = true)
            if (!environment.rootGranted) {
                _uiState.value = DashboardUiState(
                    loading = false,
                    rootGranted = false,
                    statusText = str(R.string.dashboard_status_root_missing),
                    errorMessage = environment.lastError ?: str(R.string.root_status_denied),
                )
                return@launch
            }

            val config = settingsRepository.readConfig()
            val runtime = stateRepository.readRuntimeStatus()
            val incidents = stateRepository.readIncidents(limit = 1)
            _uiState.value = buildUiState(
                environment = environment,
                config = config,
                runtime = runtime,
                latestMessage = null,
                latestError = null,
                latestIncident = incidents.firstOrNull(),
            )
        }
    }

    fun createDefaultConfig() {
        _uiState.value = _uiState.value.copy(saving = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = settingsRepository.ensureDefaultConfig()
            if (ok) {
                refreshWithMessage(str(R.string.dashboard_config_created))
            } else {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    errorMessage = str(R.string.dashboard_config_create_failed),
                )
            }
        }
    }

    fun setActiveProtection(enabled: Boolean) {
        updateDraft(
            activeProtection = if (enabled) ActiveProtectionMode.ON else ActiveProtectionMode.OFF,
        )
    }

    fun setLearningMode(enabled: Boolean) = updateDraft(learningMode = enabled)

    fun setReactionMode(mode: ReactionMode) = updateDraft(reactionMode = mode)

    fun setProtectLoopbackOnly(enabled: Boolean) = updateDraft(protectLoopbackOnly = enabled)

    fun saveChanges() {
        val state = _uiState.value
        if (!state.canOpenEditor || state.saving) return
        _uiState.value = state.copy(saving = true, infoMessage = null, errorMessage = null)

        viewModelScope.launch(Dispatchers.IO) {
            val ok = settingsRepository.updateDashboardConfig(
                activeProtection = _uiState.value.activeProtection,
                learningMode = _uiState.value.learningMode,
                reactionMode = _uiState.value.reactionMode,
                protectLoopbackOnly = _uiState.value.protectLoopbackOnly,
            )
            if (ok) {
                refreshWithMessage(str(R.string.dashboard_save_success))
            } else {
                _uiState.value = _uiState.value.copy(
                    saving = false,
                    errorMessage = str(R.string.dashboard_save_failed),
                )
            }
        }
    }

    private fun updateDraft(
        activeProtection: ActiveProtectionMode = _uiState.value.activeProtection,
        learningMode: Boolean = _uiState.value.learningMode,
        reactionMode: ReactionMode = _uiState.value.reactionMode,
        protectLoopbackOnly: Boolean = _uiState.value.protectLoopbackOnly,
    ) {
        val state = _uiState.value
        _uiState.value = state.copy(
            activeProtection = activeProtection,
            learningMode = learningMode,
            reactionMode = reactionMode,
            protectLoopbackOnly = protectLoopbackOnly,
            dirty = true,
            infoMessage = null,
            errorMessage = null,
        )
    }

    private fun refreshWithMessage(message: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val environment = moduleStatusRepository.inspect(readConfigPreview = true)
            val config = settingsRepository.readConfig()
            val runtime = stateRepository.readRuntimeStatus()
            val incidents = stateRepository.readIncidents(limit = 1)
            _uiState.value = buildUiState(
                environment = environment,
                config = config,
                runtime = runtime,
                latestMessage = message,
                latestError = null,
                latestIncident = incidents.firstOrNull(),
            )
        }
    }

    private fun buildUiState(
        environment: ModuleEnvironmentStatus,
        config: PortGuardConfig?,
        runtime: com.android.portguard.data.PortGuardRuntimeStatus,
        latestMessage: String?,
        latestError: String?,
        latestIncident: com.android.portguard.data.PortGuardIncident?,
    ): DashboardUiState {
        val canOpen = environment.moduleDirectoryExists && environment.settingsDirectoryExists
        return DashboardUiState(
            loading = false,
            saving = false,
            rootGranted = true,
            moduleInstalled = environment.moduleDirectoryExists,
            settingsDirectoryFound = environment.settingsDirectoryExists,
            configFound = environment.configExists,
            canOpenEditor = canOpen,
            activeProtection = config?.activeProtection ?: ActiveProtectionMode.ON,
            learningMode = config?.learningMode ?: false,
            reactionMode = config?.reactionMode ?: ReactionMode.OFF,
            protectLoopbackOnly = config?.protectLoopbackOnly ?: false,
            configPreview = config?.rawConfig ?: environment.configPreview,
            statusText = buildStatusText(environment = environment, configLoaded = config != null, runtime = runtime),
            infoMessage = latestMessage,
            errorMessage = latestError,
            runtimeStateAvailable = runtime.stateFilesPresent,
            runtimeStatusText = buildRuntimeStatusText(runtime),
            protectedPorts = runtime.protectedPorts,
            activeRules = runtime.activeRules,
            backend = runtime.backend,
            blockedPorts = runtime.blockedPorts,
            lastUpdated = runtime.lastUpdated,
            summaryPreview = runtime.summaryRaw,
            latestIncident = latestIncident,
        )
    }

    private fun buildStatusText(
        environment: ModuleEnvironmentStatus,
        configLoaded: Boolean,
        runtime: com.android.portguard.data.PortGuardRuntimeStatus,
    ): String {
        val parts = mutableListOf<String>()
        parts += str(R.string.status_part_root_ok)
        parts += if (environment.moduleDirectoryExists) str(R.string.status_part_module_found) else str(R.string.status_part_module_missing)
        parts += if (environment.settingsDirectoryExists) str(R.string.status_part_settings_found) else str(R.string.status_part_settings_missing)
        parts += if (environment.configExists) str(R.string.status_part_config_found) else str(R.string.status_part_config_missing)
        if (environment.stagedUpdateExists) parts += str(R.string.status_part_reboot_needed)
        if (configLoaded) parts += str(R.string.dashboard_status_config_loaded) else parts += str(R.string.dashboard_status_config_not_loaded)
        if (runtime.stateFilesPresent) parts += str(R.string.dashboard_state_files_ready)
        return parts.joinToString(separator = " • ")
    }

    private fun buildRuntimeStatusText(runtime: com.android.portguard.data.PortGuardRuntimeStatus): String {
        if (!runtime.stateFilesPresent) return str(R.string.dashboard_runtime_missing)
        val parts = mutableListOf<String>()
        parts += if (runtime.activeProtection.equals("on", true)) str(R.string.dashboard_runtime_protection_on) else if (runtime.activeProtection.equals("off", true)) str(R.string.dashboard_runtime_protection_off) else str(R.string.dashboard_runtime_protection_unknown)
        if (runtime.standby) parts += str(R.string.dashboard_runtime_standby)
        if (runtime.protectedPorts > 0) parts += str(R.string.dashboard_runtime_ports, runtime.protectedPorts)
        if (runtime.activeRules > 0) parts += str(R.string.dashboard_runtime_rules, runtime.activeRules)
        if (runtime.backend.isNotBlank()) parts += str(R.string.dashboard_runtime_backend, runtime.backend)
        if (runtime.lastUpdated.isNotBlank()) parts += str(R.string.dashboard_runtime_updated, runtime.lastUpdated)
        if (runtime.message.isNotBlank()) parts += runtime.message
        return parts.joinToString(separator = " • ")
    }

    private fun str(@StringRes resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)
}
