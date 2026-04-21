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
import com.android.portguard.data.ModuleStatusRepository
import com.android.portguard.data.SettingsFileRepository
import com.android.portguard.ui.state.RootAccessState
import com.android.portguard.ui.state.RootModuleUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class RootModuleViewModel(app: Application) : AndroidViewModel(app) {
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val moduleStatusRepository = ModuleStatusRepository(rootShell, settingsRepository)
    private val installerRepository = ModuleInstallerRepository(app.applicationContext, rootShell)

    private val _uiState = MutableStateFlow(
        RootModuleUiState(
            statusText = str(R.string.root_status_initial_hint),
        )
    )
    val uiState: StateFlow<RootModuleUiState> = _uiState.asStateFlow()

    fun refresh() {
        _uiState.value = _uiState.value.copy(
            checking = true,
            rootState = RootAccessState.CHECKING,
            statusText = str(R.string.root_status_checking_message),
            installError = null,
        )

        viewModelScope.launch(Dispatchers.IO) {
            val environment = moduleStatusRepository.inspect(readConfigPreview = true)
            val installerInfo = if (environment.rootGranted) installerRepository.detectInstaller() else InstallerInfo(
                type = com.android.portguard.data.ModuleInstallerType.MANUAL,
                assetPresent = false,
            )
            _uiState.value = buildUiState(environment, installerInfo)
        }
    }

    fun installModule() {
        if (_uiState.value.installing) return
        _uiState.value = _uiState.value.copy(
            installing = true,
            installSuccess = false,
            installError = null,
            installLog = str(R.string.installer_log_start),
        )

        viewModelScope.launch(Dispatchers.IO) {
            val result = installerRepository.installUsingDetectedInstaller()
            val refreshed = moduleStatusRepository.inspect(readConfigPreview = true)
            val installerInfo = if (refreshed.rootGranted) installerRepository.detectInstaller() else InstallerInfo(
                type = com.android.portguard.data.ModuleInstallerType.MANUAL,
                assetPresent = false,
            )
            val base = buildUiState(refreshed, installerInfo)
            _uiState.value = base.copy(
                installing = false,
                installSuccess = result.success,
                installError = if (result.success) null else result.log,
                installLog = result.log,
                stagedUpdateFound = base.stagedUpdateFound || result.requiresReboot,
            )
        }
    }

    fun exportModuleZip() {
        if (_uiState.value.installing) return
        _uiState.value = _uiState.value.copy(
            installing = true,
            installSuccess = false,
            installError = null,
            installLog = str(R.string.installer_export_start),
        )

        viewModelScope.launch(Dispatchers.IO) {
            val result = installerRepository.exportModuleZipToSdcard()
            _uiState.value = _uiState.value.copy(
                installing = false,
                installSuccess = result.success,
                installError = if (result.success) null else result.log,
                installLog = result.log,
                exportedZipPath = result.exportedPath,
            )
        }
    }

    fun rebootDevice() {
        if (_uiState.value.rebooting) return
        _uiState.value = _uiState.value.copy(
            rebooting = true,
            installError = null,
            installLog = str(R.string.installer_reboot_start),
        )
        viewModelScope.launch(Dispatchers.IO) {
            val result = installerRepository.rebootDevice()
            _uiState.value = _uiState.value.copy(
                rebooting = false,
                installSuccess = result.success,
                installError = if (result.success) null else result.log,
                installLog = result.log,
            )
        }
    }

    private fun buildUiState(
        result: ModuleEnvironmentStatus,
        installerInfo: InstallerInfo,
    ): RootModuleUiState {
        return if (!result.rootGranted) {
            _uiState.value.copy(
                checking = false,
                rootState = RootAccessState.DENIED,
                moduleInstalled = false,
                settingsDirectoryFound = false,
                configFound = false,
                stagedUpdateFound = false,
                assetPresent = false,
                configPreview = "",
                installerLabel = str(R.string.installer_label_unavailable),
                statusText = result.lastError ?: str(R.string.root_status_denied),
            )
        } else {
            _uiState.value.copy(
                checking = false,
                rootState = RootAccessState.GRANTED,
                moduleInstalled = result.moduleDirectoryExists,
                settingsDirectoryFound = result.settingsDirectoryExists,
                configFound = result.configExists,
                stagedUpdateFound = result.stagedUpdateExists,
                assetPresent = installerInfo.assetPresent,
                configPreview = result.configPreview,
                installerLabel = installerInfo.type.label,
                statusText = buildStatusText(result, installerInfo),
            )
        }
    }

    private fun buildStatusText(result: ModuleEnvironmentStatus, installerInfo: InstallerInfo): String {
        val parts = mutableListOf<String>()
        parts += str(R.string.status_part_root_ok)
        parts += if (result.moduleDirectoryExists) str(R.string.status_part_module_found) else str(R.string.status_part_module_missing)
        parts += if (result.settingsDirectoryExists) str(R.string.status_part_settings_found) else str(R.string.status_part_settings_missing)
        parts += if (result.configExists) str(R.string.status_part_config_found) else str(R.string.status_part_config_missing)
        if (result.stagedUpdateExists) {
            parts += str(R.string.status_part_reboot_needed)
        }
        parts += str(R.string.status_part_installer, installerInfo.type.label)
        parts += if (installerInfo.assetPresent) str(R.string.status_part_asset_ready) else str(R.string.status_part_asset_missing)
        return parts.joinToString(separator = " • ")
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)
}
