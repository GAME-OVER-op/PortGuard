package com.android.portguard.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.portguard.core.PortGuardPaths
import com.android.portguard.core.RootShell
import com.android.portguard.data.ActiveProtectionMode
import com.android.portguard.data.AppCatalogRepository
import com.android.portguard.data.ModuleInstallerRepository
import com.android.portguard.data.PortGuardConfig
import com.android.portguard.data.PortGuardStateRepository
import com.android.portguard.data.ReactionMode
import com.android.portguard.data.SettingsFileRepository
import com.android.portguard.data.parseTabLogSummary
import com.android.portguard.ui.state.DetectionEntry
import com.android.portguard.ui.state.MainHubTab
import com.android.portguard.ui.state.MainHubUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class MainHubViewModel(app: Application) : AndroidViewModel(app) {
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val stateRepository = PortGuardStateRepository(rootShell)
    private val appCatalogRepository = AppCatalogRepository(app.applicationContext)
    private val installerRepository = ModuleInstallerRepository(app.applicationContext, rootShell)

    private val _uiState = MutableStateFlow(MainHubUiState())
    val uiState: StateFlow<MainHubUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    @Volatile
    private var settingsPrepared = false

    init {
        refresh(full = true)
        startPolling()
    }

    fun selectTab(tab: MainHubTab) {
        _uiState.value = _uiState.value.copy(selectedTab = tab)
    }

    fun toggleBlockedPortsExpanded() {
        _uiState.value = _uiState.value.copy(blockedPortsExpanded = !_uiState.value.blockedPortsExpanded)
    }

    fun openPicker() {
        _uiState.value = _uiState.value.copy(pickerOpen = true)
        if (_uiState.value.allApps.isEmpty()) {
            _uiState.value = _uiState.value.copy(pickerLoading = true)
            viewModelScope.launch(Dispatchers.IO) {
                runCatching { appCatalogRepository.loadInstalledApps() }
                    .onSuccess { apps ->
                        _uiState.value = _uiState.value.copy(allApps = apps, pickerLoading = false)
                    }
                    .onFailure {
                        _uiState.value = _uiState.value.copy(pickerLoading = false)
                    }
            }
        }
    }

    fun closePicker() {
        _uiState.value = _uiState.value.copy(pickerOpen = false, pickerQuery = "")
    }

    fun setPickerQuery(value: String) {
        _uiState.value = _uiState.value.copy(pickerQuery = value)
    }

    fun toggleTrustedPackage(packageName: String) {
        val current = _uiState.value.trustedPackages.toMutableList()
        if (current.contains(packageName)) current.remove(packageName) else current.add(packageName)
        val normalized = current.distinct().sorted()
        _uiState.value = _uiState.value.copy(trustedPackages = normalized)
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.writeList(PortGuardPaths.TRUSTED_PACKAGES, normalized)
            signalSaved("Исключения сохранены")
        }
    }

    fun setActiveProtection(enabled: Boolean) {
        val current = _uiState.value.config
        val updated = current.copy(activeProtection = if (enabled) ActiveProtectionMode.ON else ActiveProtectionMode.OFF)
        updateDashboardConfig(updated)
    }

    fun setLearningMode(enabled: Boolean) {
        val updated = _uiState.value.config.copy(learningMode = enabled)
        updateDashboardConfig(updated)
    }

    fun setProtectLoopbackOnly(enabled: Boolean) {
        val updated = _uiState.value.config.copy(protectLoopbackOnly = enabled)
        updateDashboardConfig(updated)
    }

    fun setReactionMode(mode: ReactionMode) {
        val updated = _uiState.value.config.copy(reactionMode = mode)
        updateDashboardConfig(updated)
    }

    fun setUserAppsOnly(enabled: Boolean) {
        val updated = _uiState.value.config.copy(userAppsOnly = enabled)
        updateTrafficRuntimeConfig(updated)
    }

    fun setProtocol(proto: String, enabled: Boolean) {
        val cfg = _uiState.value.config
        val updated = when (proto) {
            "tcp4" -> cfg.copy(tcp4Enabled = enabled)
            "udp4" -> cfg.copy(udp4Enabled = enabled)
            "tcp6" -> cfg.copy(tcp6Enabled = enabled)
            else -> cfg.copy(udp6Enabled = enabled)
        }
        updateTrafficRuntimeConfig(updated)
    }

    fun setSelfTestEnabled(enabled: Boolean) {
        val updated = _uiState.value.config.copy(activeSelfTestEnabled = enabled)
        updateTrafficRuntimeConfig(updated)
    }

    fun setResolveProcessDetails(enabled: Boolean) {
        val cfg = _uiState.value.config.copy(resolveProcessDetails = enabled)
        updateAdvancedConfig(cfg)
    }

    fun setNetworkRefreshSecs(value: String) {
        val parsed = value.toIntOrNull() ?: return
        val updated = _uiState.value.config.copy(networkRefreshSecs = parsed)
        updateTrafficRuntimeConfig(updated)
    }

    fun setLoopIntervalMs(value: String) {
        val parsed = value.toIntOrNull() ?: return
        val updated = _uiState.value.config.copy(loopIntervalMs = parsed)
        updateAdvancedConfig(updated)
    }

    fun setPackageRefreshSecs(value: String) {
        val parsed = value.toIntOrNull() ?: return
        val updated = _uiState.value.config.copy(packageRefreshSecs = parsed)
        updateAdvancedConfig(updated)
    }

    fun setCounterRefreshLoops(value: String) {
        val parsed = value.toIntOrNull() ?: return
        val updated = _uiState.value.config.copy(counterRefreshLoops = parsed)
        updateAdvancedConfig(updated)
    }

    fun setSuspiciousUniquePorts(value: String) {
        val parsed = value.toIntOrNull() ?: return
        updateAdvancedConfig(_uiState.value.config.copy(suspiciousUniquePorts = parsed))
    }

    fun setSuspiciousAttempts(value: String) {
        val parsed = value.toIntOrNull() ?: return
        updateAdvancedConfig(_uiState.value.config.copy(suspiciousAttempts = parsed))
    }

    fun setSuspiciousRuleHits(value: String) {
        val parsed = value.toIntOrNull() ?: return
        updateAdvancedConfig(_uiState.value.config.copy(suspiciousRuleHits = parsed))
    }

    private fun updateDashboardConfig(updated: PortGuardConfig) {
        _uiState.value = _uiState.value.copy(config = updated)
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.updateDashboardConfig(
                activeProtection = updated.activeProtection,
                learningMode = updated.learningMode,
                reactionMode = updated.reactionMode,
                protectLoopbackOnly = updated.protectLoopbackOnly,
            )
            signalSaved("Настройки сохранены")
        }
    }

    private fun updateTrafficRuntimeConfig(updated: PortGuardConfig) {
        _uiState.value = _uiState.value.copy(config = updated)
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.updateTrafficAndRuntimeConfig(
                tcp4Enabled = updated.tcp4Enabled,
                udp4Enabled = updated.udp4Enabled,
                tcp6Enabled = updated.tcp6Enabled,
                udp6Enabled = updated.udp6Enabled,
                userAppsOnly = updated.userAppsOnly,
                networkRefreshSecs = updated.networkRefreshSecs,
                activeSelfTestEnabled = updated.activeSelfTestEnabled,
                selfTestTimeoutMs = updated.selfTestTimeoutMs,
            )
            signalSaved("Настройки сохранены")
        }
    }

    private fun updateAdvancedConfig(updated: PortGuardConfig) {
        _uiState.value = _uiState.value.copy(config = updated)
        viewModelScope.launch(Dispatchers.IO) {
            settingsRepository.updateAdvancedConfig(
                suspiciousUniquePorts = updated.suspiciousUniquePorts,
                suspiciousAttempts = updated.suspiciousAttempts,
                suspiciousRuleHits = updated.suspiciousRuleHits,
                scanWindowSecs = updated.scanWindowSecs,
                warnCooldownSecs = updated.warnCooldownSecs,
                reactionCooldownSecs = updated.reactionCooldownSecs,
                loopIntervalMs = updated.loopIntervalMs,
                reloadCheckSecs = updated.reloadCheckSecs,
                packageRefreshSecs = updated.packageRefreshSecs,
                counterRefreshLoops = updated.counterRefreshLoops,
                resolveProcessDetails = updated.resolveProcessDetails,
                protectLoopbackOnly = updated.protectLoopbackOnly,
            )
            signalSaved("Расширенные настройки сохранены")
        }
    }

    fun refresh(full: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            if (full) {
                _uiState.value = _uiState.value.copy(loading = true)
            }
            runCatching {
                if (!settingsPrepared) {
                    settingsPrepared = settingsRepository.ensureDefaultConfig()
                }
                val config = settingsRepository.readConfig() ?: PortGuardConfig()
                val trustedPackages = settingsRepository.readTrustedPackages()
                val incidents = stateRepository.readIncidents(limit = 150)
                val tabLogRaw = rootShell.readText(PortGuardPaths.TAB_LOG)
                val tabSummary = parseTabLogSummary(tabLogRaw)
                val runtime = stateRepository.readRuntimeStatus()
                val installer = runCatching { installerRepository.detectInstaller() }.getOrNull()
                _uiState.value.copy(
                    loading = false,
                    config = config,
                    trustedPackages = trustedPackages,
                    portsCount = if (tabSummary.portsCount > 0) tabSummary.portsCount else runtime.protectedPorts,
                    blockedPorts = if (tabSummary.blockedPorts.isNotEmpty()) tabSummary.blockedPorts else runtime.blockedPorts,
                    groupedRules = if (tabSummary.groupedRules > 0) tabSummary.groupedRules else runtime.activeRules,
                    candidateApps = tabSummary.candidateApps,
                    firewallHealth = tabSummary.firewallHealth,
                    firewallStatuses = tabSummary.protoStatuses,
                    selfTestSummary = tabSummary.selfTest,
                    reapplyReason = tabSummary.reapplyReason,
                    localSnapshots = tabSummary.localSnapshots,
                    rootManagerLabel = installer?.type?.label ?: _uiState.value.rootManagerLabel,
                    incidents = incidents,
                    detections = aggregateDetections(incidents),
                )
            }.onSuccess { state ->
                _uiState.value = state
            }.onFailure {
                _uiState.value = _uiState.value.copy(loading = false)
            }
        }
    }

    private fun startPolling() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (true) {
                delay(8_000)
                refresh(full = false)
            }
        }
    }

    private fun aggregateDetections(incidents: List<com.android.portguard.data.PortGuardIncident>): List<DetectionEntry> {
        val grouped = linkedMapOf<String, MutableList<com.android.portguard.data.PortGuardIncident>>()
        val packageByKey = linkedMapOf<String, String?>()
        incidents.forEach { incident ->
            val packages = incident.packages.filter { it.isNotBlank() }
            val keys = packages.ifEmpty {
                listOf(incident.uid.ifBlank { incident.rawLine.take(36) })
            }
            keys.forEach { key ->
                grouped.getOrPut(key) { mutableListOf() }.add(incident)
                if (!packageByKey.containsKey(key)) {
                    packageByKey[key] = packages.firstOrNull { it == key }
                }
            }
        }
        return grouped.entries
            .map { (key, values) ->
                DetectionEntry(
                    key = key,
                    title = key,
                    packageName = packageByKey[key],
                    attempts = values.size,
                    latestMessage = values.firstOrNull()?.message.orEmpty(),
                )
            }
            .sortedByDescending { it.attempts }
    }

    private fun signalSaved(message: String) {
        _uiState.value = _uiState.value.copy(saveMessage = message)
        viewModelScope.launch {
            delay(1600)
            if (_uiState.value.saveMessage == message) {
                _uiState.value = _uiState.value.copy(saveMessage = null)
            }
        }
    }

    override fun onCleared() {
        refreshJob?.cancel()
        super.onCleared()
    }
}
