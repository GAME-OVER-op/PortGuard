package com.android.portguard.ui.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.R
import com.android.portguard.core.RootShell
import com.android.portguard.data.ModuleStatusRepository
import com.android.portguard.data.PortGuardStateRepository
import com.android.portguard.data.SettingsFileRepository
import com.android.portguard.ui.state.IncidentsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class IncidentsViewModel(app: Application) : AndroidViewModel(app) {
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val moduleStatusRepository = ModuleStatusRepository(rootShell, settingsRepository)
    private val stateRepository = PortGuardStateRepository(rootShell)

    private val _uiState = MutableStateFlow(IncidentsUiState())
    val uiState: StateFlow<IncidentsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(loading = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val environment = moduleStatusRepository.inspect(readConfigPreview = false)
            if (!environment.rootGranted) {
                _uiState.value = IncidentsUiState(
                    loading = false,
                    rootGranted = false,
                    statusText = str(R.string.incidents_root_required),
                    errorMessage = environment.lastError ?: str(R.string.root_status_denied),
                )
                return@launch
            }

            val runtime = stateRepository.readRuntimeStatus()
            val incidents = stateRepository.readIncidents()
            _uiState.value = IncidentsUiState(
                loading = false,
                rootGranted = true,
                stateDirectoryFound = rootShell.fileExists(com.android.portguard.core.PortGuardPaths.STATE_DIR),
                incidentsFileFound = runtime.incidentsFileFound,
                incidents = incidents,
                statusText = when {
                    incidents.isNotEmpty() -> str(R.string.incidents_status_loaded, incidents.size)
                    runtime.incidentsFileFound -> str(R.string.incidents_status_empty)
                    else -> str(R.string.incidents_status_missing_file)
                },
            )
        }
    }

    fun clearIncidents() {
        val state = _uiState.value
        if (state.clearing || !state.rootGranted) return
        _uiState.value = state.copy(clearing = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val ok = stateRepository.clearIncidents()
            if (ok) {
                refreshWithMessage(str(R.string.incidents_clear_success))
            } else {
                _uiState.value = _uiState.value.copy(
                    clearing = false,
                    errorMessage = str(R.string.incidents_clear_failed),
                )
            }
        }
    }

    private fun refreshWithMessage(message: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val runtime = stateRepository.readRuntimeStatus()
            val incidents = stateRepository.readIncidents()
            _uiState.value = IncidentsUiState(
                loading = false,
                rootGranted = true,
                stateDirectoryFound = rootShell.fileExists(com.android.portguard.core.PortGuardPaths.STATE_DIR),
                incidentsFileFound = runtime.incidentsFileFound,
                incidents = incidents,
                statusText = if (incidents.isEmpty()) str(R.string.incidents_status_empty) else str(R.string.incidents_status_loaded, incidents.size),
                infoMessage = message,
            )
        }
    }

    private fun str(@StringRes resId: Int, vararg args: Any): String =
        getApplication<Application>().getString(resId, *args)
}
