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
import com.android.portguard.data.effectiveCounterRefreshSecs
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class MainHubViewModel(app: Application) : AndroidViewModel(app) {
    private val rootShell = RootShell()
    private val settingsRepository = SettingsFileRepository(rootShell)
    private val stateRepository = PortGuardStateRepository(rootShell)
    private val appCatalogRepository = AppCatalogRepository(app.applicationContext)
    private val installerRepository = ModuleInstallerRepository(app.applicationContext, rootShell)

    private val _uiState = MutableStateFlow(MainHubUiState())
    val uiState: StateFlow<MainHubUiState> = _uiState.asStateFlow()

    private var refreshJob: Job? = null
    private val refreshMutex = Mutex()
    private val settingsWriteMutex = Mutex()
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
            settingsWriteMutex.withLock {
                // Use the latest optimistic UI state. Rapid consecutive toggles can
                // enqueue coroutines in a different order than the user's taps.
                val latest = _uiState.value.trustedPackages.distinct().sorted()
                val ok = settingsRepository.writeList(PortGuardPaths.TRUSTED_PACKAGES, latest)
                signalSaved(if (ok) "Исключения сохранены" else "Ошибка сохранения исключений")
            }
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
        val updated = _uiState.value.config.copy(networkRefreshSecs = parsed.coerceAtLeast(10))
        updateTrafficRuntimeConfig(updated)
    }

    fun setLoopIntervalMs(value: String) {
        val parsed = value.toIntOrNull() ?: return
        val updated = _uiState.value.config.copy(loopIntervalMs = parsed)
        updateAdvancedConfig(updated)
    }

    fun setPackageRefreshSecs(value: String) {
        val parsed = value.toIntOrNull() ?: return
        val updated = _uiState.value.config.copy(packageRefreshSecs = parsed.coerceAtLeast(120))
        updateAdvancedConfig(updated)
    }

    fun setCounterRefreshLoops(value: String) {
        val parsed = value.toIntOrNull() ?: return
        val updated = _uiState.value.config.copy(counterRefreshLoops = parsed.coerceAtLeast(5))
        updateAdvancedConfig(updated)
    }

    fun setSuspiciousUniquePorts(value: String) {
        val parsed = value.toIntOrNull() ?: return
        updateAdvancedConfig(_uiState.value.config.copy(suspiciousUniquePorts = parsed))
    }

    fun setSuspiciousAttempts(value: String) {
        val parsed = value.toIntOrNull() ?: return
        updateAdvancedConfig(_uiState.value.config.copy(suspiciousAttempts = parsed.coerceAtLeast(2)))
    }

    fun setSuspiciousRuleHits(value: String) {
        val parsed = value.toIntOrNull() ?: return
        updateAdvancedConfig(_uiState.value.config.copy(suspiciousRuleHits = parsed.coerceAtLeast(2)))
    }

    private fun updateDashboardConfig(updated: PortGuardConfig) {
        val normalized = normalizeDetectionWindow(updated)
        _uiState.value = _uiState.value.copy(config = normalized)
        viewModelScope.launch(Dispatchers.IO) {
            settingsWriteMutex.withLock {
                val latest = normalizeDetectionWindow(_uiState.value.config)
                val ok = settingsRepository.updateDashboardConfig(
                    activeProtection = latest.activeProtection,
                    learningMode = latest.learningMode,
                    reactionMode = latest.reactionMode,
                    protectLoopbackOnly = latest.protectLoopbackOnly,
                )
                signalSaved(if (ok) "Настройки сохранены" else "Ошибка сохранения настроек")
            }
        }
    }

    private fun updateTrafficRuntimeConfig(updated: PortGuardConfig) {
        _uiState.value = _uiState.value.copy(config = updated)
        viewModelScope.launch(Dispatchers.IO) {
            settingsWriteMutex.withLock {
                val latest = _uiState.value.config
                val ok = settingsRepository.updateTrafficAndRuntimeConfig(
                    tcp4Enabled = latest.tcp4Enabled,
                    udp4Enabled = latest.udp4Enabled,
                    tcp6Enabled = latest.tcp6Enabled,
                    udp6Enabled = latest.udp6Enabled,
                    userAppsOnly = latest.userAppsOnly,
                    networkRefreshSecs = latest.networkRefreshSecs,
                    activeSelfTestEnabled = latest.activeSelfTestEnabled,
                    selfTestTimeoutMs = latest.selfTestTimeoutMs,
                )
                signalSaved(if (ok) "Настройки сохранены" else "Ошибка сохранения настроек")
            }
        }
    }

    private fun updateAdvancedConfig(updated: PortGuardConfig) {
        val normalized = normalizeDetectionWindow(updated)
        _uiState.value = _uiState.value.copy(config = normalized)
        viewModelScope.launch(Dispatchers.IO) {
            settingsWriteMutex.withLock {
                val latest = normalizeDetectionWindow(_uiState.value.config)
                val ok = settingsRepository.updateAdvancedConfig(
                    suspiciousUniquePorts = latest.suspiciousUniquePorts,
                    suspiciousAttempts = latest.suspiciousAttempts,
                    suspiciousRuleHits = latest.suspiciousRuleHits,
                    scanWindowSecs = latest.scanWindowSecs,
                    warnCooldownSecs = latest.warnCooldownSecs,
                    reactionCooldownSecs = latest.reactionCooldownSecs,
                    loopIntervalMs = latest.loopIntervalMs,
                    reloadCheckSecs = latest.reloadCheckSecs,
                    packageRefreshSecs = latest.packageRefreshSecs,
                    counterRefreshLoops = latest.counterRefreshLoops,
                    resolveProcessDetails = latest.resolveProcessDetails,
                    protectLoopbackOnly = latest.protectLoopbackOnly,
                )
                signalSaved(if (ok) "Расширенные настройки сохранены" else "Ошибка сохранения расширенных настроек")
            }
        }
    }

    private fun normalizeDetectionWindow(config: PortGuardConfig): PortGuardConfig {
        val minimum = effectiveCounterRefreshSecs(
            loopIntervalMs = config.loopIntervalMs,
            counterRefreshLoops = config.counterRefreshLoops,
            reactionMode = config.reactionMode,
        )
        return if (config.scanWindowSecs >= minimum) config else config.copy(scanWindowSecs = minimum)
    }

    fun refresh(full: Boolean = false) {
        viewModelScope.launch(Dispatchers.IO) {
            refreshMutex.withLock {
                refreshNow(full)
            }
        }
    }

    private suspend fun refreshNow(full: Boolean) {
        if (full) {
            _uiState.value = _uiState.value.copy(loading = true)
        }
        runCatching {
            if (!settingsPrepared) {
                settingsPrepared = settingsRepository.ensureDefaultConfig()
            }

            // Config/trusted lists are comparatively expensive root reads and do
            // not belong in the live-status polling loop. UI setters already keep
            // local state synchronized; reread them on initial/manual full refresh.
            val initial = _uiState.value
            val refreshedConfig = if (full) {
                settingsRepository.readConfig() ?: initial.config
            } else {
                null
            }
            val refreshedTrustedPackages = if (full) {
                settingsRepository.readTrustedPackages()
            } else {
                null
            }
            val effectiveConfig = refreshedConfig ?: _uiState.value.config
            val incidents = stateRepository.readIncidents(limit = 150, stateDir = effectiveConfig.stateDir)
            val tabLogRaw = rootShell.readText(PortGuardPaths.TAB_LOG)
            val tabSummary = parseTabLogSummary(tabLogRaw)
            val runtime = stateRepository.readRuntimeStatus(stateDir = effectiveConfig.stateDir)
            val installer = if (full) {
                runCatching { installerRepository.detectInstaller() }.getOrNull()
            } else {
                null
            }
            val current = _uiState.value
            current.copy(
                loading = false,
                config = refreshedConfig ?: current.config,
                trustedPackages = refreshedTrustedPackages ?: current.trustedPackages,
                portsCount = if (tabSummary.portsCount > 0) tabSummary.portsCount else runtime.protectedPorts,
                blockedPorts = if (tabSummary.blockedPorts.isNotEmpty()) tabSummary.blockedPorts else runtime.blockedPorts,
                groupedRules = if (tabSummary.groupedRules > 0) tabSummary.groupedRules else runtime.activeRules,
                candidateApps = tabSummary.candidateApps,
                firewallHealth = if (!runtime.firewallApplyComplete) {
                    com.android.portguard.data.FirewallHealth.ERROR
                } else {
                    tabSummary.firewallHealth
                },
                firewallStatuses = tabSummary.protoStatuses,
                selfTestSummary = tabSummary.selfTest,
                reapplyReason = tabSummary.reapplyReason,
                localSnapshots = tabSummary.localSnapshots,
                rootManagerLabel = installer?.type?.label ?: current.rootManagerLabel,
                incidents = incidents,
                detections = aggregateDetections(incidents),
            )
        }.onSuccess { state ->
            _uiState.value = state
        }.onFailure {
            _uiState.value = _uiState.value.copy(loading = false)
        }
    }

    private fun startPolling() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(15_000)
                refreshMutex.withLock {
                    refreshNow(full = false)
                }
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
