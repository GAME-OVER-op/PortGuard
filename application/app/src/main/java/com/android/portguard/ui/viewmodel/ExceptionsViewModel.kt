package com.android.portguard.ui.viewmodel

import android.app.Application
import androidx.annotation.StringRes
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.R
import com.android.portguard.core.PortGuardPaths
import com.android.portguard.core.RootShell
import com.android.portguard.data.AppCatalogRepository
import com.android.portguard.data.SettingsFileRepository
import com.android.portguard.ui.state.ExceptionsTab
import com.android.portguard.ui.state.ExceptionsUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ExceptionsViewModel(app: Application) : AndroidViewModel(app) {
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val appCatalogRepository = AppCatalogRepository(app.applicationContext)

    private val _uiState = MutableStateFlow(ExceptionsUiState())
    val uiState: StateFlow<ExceptionsUiState> = _uiState.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _uiState.value = _uiState.value.copy(loading = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            val apps = appCatalogRepository.loadInstalledApps()
            val trustedPackages = settingsRepository.readList(PortGuardPaths.TRUSTED_PACKAGES)
            val killExceptions = settingsRepository.readList(PortGuardPaths.KILL_EXCEPTIONS)
            val scanIgnorePackages = settingsRepository.readList(PortGuardPaths.SCAN_IGNORE_PACKAGES)
            val trustedUids = settingsRepository.readList(PortGuardPaths.TRUSTED_UIDS)
            _uiState.value = _uiState.value.copy(
                loading = false,
                saving = false,
                installedApps = apps,
                trustedPackages = trustedPackages,
                killExceptions = killExceptions,
                scanIgnorePackages = scanIgnorePackages,
                trustedUids = trustedUids,
            )
        }
    }

    fun selectTab(tab: ExceptionsTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab, infoMessage = null, errorMessage = null)
    }

    fun updateSearchQuery(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
    }

    fun updateUidDraft(value: String) {
        val sanitized = value.filter { it.isDigit() }
        _uiState.value = _uiState.value.copy(uidDraft = sanitized, infoMessage = null, errorMessage = null)
    }

    fun togglePackage(packageName: String, enabled: Boolean) {
        val state = _uiState.value
        val current = state.selectedPackageList.toMutableList()
        if (enabled) {
            if (!current.contains(packageName)) current += packageName
        } else {
            current.remove(packageName)
        }
        savePackageList(state.selectedTab, current)
    }

    fun removeUid(uid: String) {
        val updated = _uiState.value.trustedUids.filterNot { it == uid }
        saveTrustedUids(updated)
    }

    fun addUid() {
        val value = _uiState.value.uidDraft.trim()
        if (value.isBlank()) {
            _uiState.value = _uiState.value.copy(errorMessage = str(R.string.exceptions_uid_empty))
            return
        }
        val updated = (_uiState.value.trustedUids + value).distinct().sortedBy { it.toLongOrNull() ?: Long.MAX_VALUE }
        saveTrustedUids(updated, clearDraft = true)
    }

    private fun savePackageList(tab: ExceptionsTab, values: List<String>) {
        _uiState.value = _uiState.value.copy(saving = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.ensureSettingsLayout()
            val path = when (tab) {
                ExceptionsTab.TRUSTED_PACKAGES -> PortGuardPaths.TRUSTED_PACKAGES
                ExceptionsTab.KILL_EXCEPTIONS -> PortGuardPaths.KILL_EXCEPTIONS
                ExceptionsTab.SCAN_IGNORE -> PortGuardPaths.SCAN_IGNORE_PACKAGES
                ExceptionsTab.TRUSTED_UIDS -> PortGuardPaths.TRUSTED_UIDS
            }
            val ok = settingsRepository.writeList(path, values)
            if (!ok) {
                _uiState.value = _uiState.value.copy(saving = false, errorMessage = str(R.string.exceptions_save_failed))
                return@launch
            }
            val updatedState = when (tab) {
                ExceptionsTab.TRUSTED_PACKAGES -> _uiState.value.copy(trustedPackages = values)
                ExceptionsTab.KILL_EXCEPTIONS -> _uiState.value.copy(killExceptions = values)
                ExceptionsTab.SCAN_IGNORE -> _uiState.value.copy(scanIgnorePackages = values)
                ExceptionsTab.TRUSTED_UIDS -> _uiState.value.copy(trustedUids = values)
            }
            _uiState.value = updatedState.copy(
                saving = false,
                infoMessage = str(R.string.exceptions_save_success),
                errorMessage = null,
            )
        }
    }

    private fun saveTrustedUids(values: List<String>, clearDraft: Boolean = false) {
        _uiState.value = _uiState.value.copy(saving = true, infoMessage = null, errorMessage = null)
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.ensureSettingsLayout()
            val ok = settingsRepository.writeList(PortGuardPaths.TRUSTED_UIDS, values)
            if (!ok) {
                _uiState.value = _uiState.value.copy(saving = false, errorMessage = str(R.string.exceptions_save_failed))
                return@launch
            }
            _uiState.value = _uiState.value.copy(
                saving = false,
                trustedUids = values,
                uidDraft = if (clearDraft) "" else _uiState.value.uidDraft,
                infoMessage = str(R.string.exceptions_uid_saved),
                errorMessage = null,
            )
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)
}
