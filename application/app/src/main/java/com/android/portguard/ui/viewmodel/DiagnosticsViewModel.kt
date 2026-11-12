package com.android.portguard.ui.viewmodel

import android.app.Application
import android.os.Build
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.BuildConfig
import com.android.portguard.R
import com.android.portguard.core.PortGuardPaths
import com.android.portguard.core.RootShell
import com.android.portguard.data.ModuleInstallerRepository
import com.android.portguard.data.ModuleStatusRepository
import com.android.portguard.data.SettingsFileRepository
import com.android.portguard.data.parseConfig
import com.android.portguard.ui.state.DiagnosticsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

class DiagnosticsViewModel(app: Application) : AndroidViewModel(app) {
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val moduleStatusRepository = ModuleStatusRepository(rootShell, settingsRepository)
    private val moduleInstallerRepository = ModuleInstallerRepository(app.applicationContext, rootShell)

    private val _uiState = MutableStateFlow(
        DiagnosticsUiState(
            appVersionName = BuildConfig.VERSION_NAME,
            appVersionCode = BuildConfig.VERSION_CODE,
            androidVersionText = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})",
            modulePath = PortGuardPaths.MODULE_DIR,
            settingsPath = PortGuardPaths.SETTINGS_DIR,
            statePath = PortGuardPaths.STATE_DIR,
        )
    )
    val uiState: StateFlow<DiagnosticsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(loading = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val env = moduleStatusRepository.inspect(readConfigPreview = false)
            if (!env.rootGranted) {
                _uiState.value = _uiState.value.copy(
                    loading = false,
                    rootGranted = false,
                    moduleInstalled = false,
                    settingsFound = false,
                    stateDirFound = false,
                    configFound = false,
                    statusText = str(R.string.diagnostics_root_missing),
                    errorMessage = env.lastError ?: str(R.string.root_status_denied),
                    installerLabel = str(R.string.installer_label_unknown),
                    assetPresent = false,
                    moduleVersionName = "",
                    moduleVersionCode = "",
                    configPreview = "",
                    statusPreview = "",
                    summaryPreview = "",
                    incidentsPreview = "",
                )
                return@launch
            }

            val installer = moduleInstallerRepository.detectInstaller()
            val configRaw = rootShell.readText(PortGuardPaths.CONFIG_JSON).orEmpty().trim()
            val stateDir = if (configRaw.isBlank()) {
                PortGuardPaths.STATE_DIR
            } else {
                runCatching { parseConfig(configRaw).stateDir }.getOrDefault(PortGuardPaths.STATE_DIR)
            }
            val normalizedStateDir = stateDir.trim().trimEnd('/').ifBlank { PortGuardPaths.STATE_DIR }
            val stateDirFound = rootShell.fileExists(normalizedStateDir)
            val statusPath = "$normalizedStateDir/status.json"
            val summaryPath = "$normalizedStateDir/summary.json"
            val incidentsPath = "$normalizedStateDir/incidents.jsonl"
            val statusFileFound = rootShell.fileExists(statusPath)
            val summaryFileFound = rootShell.fileExists(summaryPath)
            val incidentsFileFound = rootShell.fileExists(incidentsPath)
            val statusRaw = rootShell.readText(statusPath).orEmpty().trim()
            val summaryRaw = rootShell.readText(summaryPath).orEmpty().trim()
            val incidentsRaw = rootShell.readTextTail(incidentsPath, 20).orEmpty().trim()
            val modulePropRaw = rootShell.readText(PortGuardPaths.MODULE_PROP).orEmpty()
            val moduleVersionName = parsePropValue(modulePropRaw, "version")
            val moduleVersionCode = parsePropValue(modulePropRaw, "versionCode")

            _uiState.value = _uiState.value.copy(
                loading = false,
                rootGranted = true,
                moduleInstalled = env.moduleDirectoryExists,
                settingsFound = env.settingsDirectoryExists,
                stateDirFound = stateDirFound,
                statePath = normalizedStateDir,
                configFound = configRaw.isNotBlank(),
                statusFileFound = statusFileFound,
                summaryFileFound = summaryFileFound,
                incidentsFileFound = incidentsFileFound,
                configJsonValid = isValidJson(configRaw),
                statusJsonValid = isValidJson(statusRaw),
                summaryJsonValid = isValidJson(summaryRaw),
                installerLabel = installer.type.label,
                assetPresent = installer.assetPresent,
                moduleVersionName = moduleVersionName,
                moduleVersionCode = moduleVersionCode,
                statusText = buildStatusText(
                    moduleInstalled = env.moduleDirectoryExists,
                    settingsFound = env.settingsDirectoryExists,
                    stateDirFound = stateDirFound,
                    configFound = configRaw.isNotBlank(),
                    statusFound = statusFileFound,
                    summaryFound = summaryFileFound,
                    incidentsFound = incidentsFileFound,
                ),
                configPreview = configRaw.take(2500),
                statusPreview = statusRaw.take(2500),
                summaryPreview = summaryRaw.take(2500),
                incidentsPreview = incidentsRaw.lines().takeLast(20).joinToString("\n").take(2500),
                infoMessage = str(R.string.diagnostics_refreshed),
                errorMessage = null,
            )
        }
    }

    private fun buildStatusText(
        moduleInstalled: Boolean,
        settingsFound: Boolean,
        stateDirFound: Boolean,
        configFound: Boolean,
        statusFound: Boolean,
        summaryFound: Boolean,
        incidentsFound: Boolean,
    ): String {
        val parts = mutableListOf<String>()
        parts += if (moduleInstalled) str(R.string.module_status_found) else str(R.string.module_status_not_found)
        parts += if (settingsFound) str(R.string.settings_status_found) else str(R.string.settings_status_not_found)
        parts += if (stateDirFound) str(R.string.diagnostics_state_dir_found) else str(R.string.diagnostics_state_dir_missing)
        parts += if (configFound) str(R.string.diagnostics_config_found) else str(R.string.diagnostics_config_missing)
        parts += if (statusFound) str(R.string.diagnostics_status_found) else str(R.string.diagnostics_status_missing)
        parts += if (summaryFound) str(R.string.diagnostics_summary_found) else str(R.string.diagnostics_summary_missing)
        parts += if (incidentsFound) str(R.string.diagnostics_incidents_found) else str(R.string.diagnostics_incidents_missing)
        return parts.joinToString(" • ")
    }

    private fun parsePropValue(raw: String, key: String): String {
        return raw.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.trim()
            .orEmpty()
    }

    private fun isValidJson(raw: String): Boolean {
        if (raw.isBlank()) return false
        return runCatching { JSONObject(raw) }.isSuccess
    }

    private fun str(@StringRes resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)
}
