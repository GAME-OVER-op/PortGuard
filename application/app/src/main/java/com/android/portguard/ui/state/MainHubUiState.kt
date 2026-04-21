package com.android.portguard.ui.state

import com.android.portguard.data.ActiveProtectionMode
import com.android.portguard.data.FirewallHealth
import com.android.portguard.data.FirewallProtoStatus
import com.android.portguard.data.InstalledAppEntry
import com.android.portguard.data.PortGuardConfig
import com.android.portguard.data.PortGuardIncident
import com.android.portguard.data.ReactionMode

enum class MainHubTab {
    MAIN,
    SETTINGS,
    ADVANCED,
}

data class DetectionEntry(
    val key: String,
    val title: String,
    val packageName: String? = null,
    val attempts: Int,
    val latestMessage: String = "",
)

data class MainHubUiState(
    val loading: Boolean = true,
    val selectedTab: MainHubTab = MainHubTab.MAIN,
    val config: PortGuardConfig = PortGuardConfig(),
    val trustedPackages: List<String> = emptyList(),
    val allApps: List<InstalledAppEntry> = emptyList(),
    val pickerOpen: Boolean = false,
    val pickerLoading: Boolean = false,
    val pickerQuery: String = "",
    val rootManagerLabel: String = "Не определён",
    val portsCount: Int = 0,
    val blockedPorts: List<Int> = emptyList(),
    val blockedPortsExpanded: Boolean = false,
    val candidateApps: Int = 0,
    val groupedRules: Int = 0,
    val firewallHealth: FirewallHealth = FirewallHealth.WAITING,
    val firewallStatuses: List<FirewallProtoStatus> = emptyList(),
    val selfTestSummary: String = "",
    val reapplyReason: String = "",
    val localSnapshots: String = "",
    val detections: List<DetectionEntry> = emptyList(),
    val incidents: List<PortGuardIncident> = emptyList(),
    val saveMessage: String? = null,
)

val MainHubUiState.protectionEnabled: Boolean
    get() = config.activeProtection == ActiveProtectionMode.ON

val MainHubUiState.filteredAppsForPicker: List<InstalledAppEntry>
    get() {
        val q = pickerQuery.trim().lowercase()
        if (q.isBlank()) return allApps
        return allApps.filter {
            it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
        }
    }

fun MainHubUiState.withConfig(config: PortGuardConfig): MainHubUiState = copy(config = config)

fun MainHubUiState.withReactionMode(mode: ReactionMode): MainHubUiState = copy(config = config.copy(reactionMode = mode))
