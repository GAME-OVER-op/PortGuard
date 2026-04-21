package com.android.portguard.ui.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.R
import com.android.portguard.core.RootShell
import com.android.portguard.data.InstallerInfo
import com.android.portguard.data.ModuleEnvironmentStatus
import com.android.portguard.data.ModuleInstallerRepository
import com.android.portguard.data.ModuleInstallerType
import com.android.portguard.data.ModuleStatusRepository
import com.android.portguard.data.PortGuardPreset
import com.android.portguard.data.SettingsActionResult
import com.android.portguard.data.SettingsFileRepository
import com.android.portguard.ui.state.MaintenanceUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MaintenanceViewModel(app: Application) : AndroidViewModel(app) {
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val moduleStatusRepository = ModuleStatusRepository(rootShell, settingsRepository)
    private val installerRepository = ModuleInstallerRepository(app.applicationContext, rootShell)

    private val _uiState = MutableStateFlow(MaintenanceUiState(statusText = str(R.string.maintenance_status_initial)))
    val uiState: StateFlow<MaintenanceUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(loading = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val environment = moduleStatusRepository.inspect(readConfigPreview = false)
            val installerInfo = if (environment.rootGranted) installerRepository.detectInstaller() else InstallerInfo(ModuleInstallerType.MANUAL, false)
            _uiState.value = buildUiState(environment, installerInfo).copy(actionLog = _uiState.value.actionLog)
        }
    }

    fun applyPreset(preset: PortGuardPreset) = runSettingsAction { settingsRepository.applyPreset(preset) }
    fun exportBackup() = runSettingsAction { settingsRepository.exportSettingsBundle() }
    fun importBackup() = runSettingsAction { settingsRepository.importSettingsBundle() }
    fun resetRecommended() = runSettingsAction { settingsRepository.resetToRecommended() }

    fun reinstallModule() {
        if (_uiState.value.working) return
        _uiState.value = _uiState.value.copy(working = true, infoMessage = null, errorMessage = null, actionLog = str(R.string.maintenance_action_installing))
        viewModelScope.launch(Dispatchers.IO) {
            val result = installerRepository.installUsingDetectedInstaller()
            val environment = moduleStatusRepository.inspect(readConfigPreview = false)
            val installerInfo = if (environment.rootGranted) installerRepository.detectInstaller() else InstallerInfo(ModuleInstallerType.MANUAL, false)
            _uiState.value = buildUiState(environment, installerInfo).copy(
                working = false,
                infoMessage = if (result.success) str(R.string.maintenance_install_success) else null,
                errorMessage = if (result.success) null else result.log,
                actionLog = result.log,
            )
        }
    }

    fun exportModuleZip() {
        if (_uiState.value.working) return
        _uiState.value = _uiState.value.copy(working = true, infoMessage = null, errorMessage = null, actionLog = str(R.string.installer_export_start))
        viewModelScope.launch(Dispatchers.IO) {
            val result = installerRepository.exportModuleZipToSdcard()
            _uiState.value = _uiState.value.copy(
                working = false,
                infoMessage = if (result.success) str(R.string.maintenance_module_zip_exported) else null,
                errorMessage = if (result.success) null else result.log,
                actionLog = result.log,
            )
        }
    }

    fun rebootDevice() {
        if (_uiState.value.working) return
        _uiState.value = _uiState.value.copy(working = true, infoMessage = null, errorMessage = null, actionLog = str(R.string.installer_reboot_start))
        viewModelScope.launch(Dispatchers.IO) {
            val result = installerRepository.rebootDevice()
            _uiState.value = _uiState.value.copy(
                working = false,
                infoMessage = if (result.success) str(R.string.maintenance_reboot_sent) else null,
                errorMessage = if (result.success) null else result.log,
                actionLog = result.log,
            )
        }
    }

    private fun runSettingsAction(action: suspend () -> SettingsActionResult) {
        if (_uiState.value.working) return
        _uiState.value = _uiState.value.copy(working = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val result = action()
            val environment = moduleStatusRepository.inspect(readConfigPreview = false)
            val installerInfo = if (environment.rootGranted) installerRepository.detectInstaller() else InstallerInfo(ModuleInstallerType.MANUAL, false)
            _uiState.value = buildUiState(environment, installerInfo).copy(
                working = false,
                infoMessage = if (result.success) result.message else null,
                errorMessage = if (result.success) null else result.message,
                actionLog = result.message,
                backupPath = result.path.orEmpty(),
            )
        }
    }

    private fun buildUiState(environment: ModuleEnvironmentStatus, installerInfo: InstallerInfo): MaintenanceUiState {
        val summary = if (!environment.rootGranted) {
            environment.lastError ?: str(R.string.root_status_denied)
        } else buildString {
            append(if (environment.moduleDirectoryExists) str(R.string.module_status_found) else str(R.string.module_status_not_found))
            append(" • ")
            append(if (environment.configExists) str(R.string.status_part_config_found) else str(R.string.status_part_config_missing))
            append(" • ")
            append(str(R.string.status_part_installer, installerInfo.type.label))
        }
        return MaintenanceUiState(
            loading = false,
            working = false,
            rootGranted = environment.rootGranted,
            moduleInstalled = environment.moduleDirectoryExists,
            configFound = environment.configExists,
            installerLabel = installerInfo.type.label,
            assetPresent = installerInfo.assetPresent,
            statusText = summary,
            backupPath = _uiState.value.backupPath,
        )
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)
}
