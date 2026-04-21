package com.android.portguard.ui.state

import com.android.portguard.data.ActiveProtectionMode
import com.android.portguard.data.PortGuardIncident
import com.android.portguard.data.ReactionMode

data class DashboardUiState(
    val loading: Boolean = false,
    val saving: Boolean = false,
    val rootGranted: Boolean = false,
    val moduleInstalled: Boolean = false,
    val settingsDirectoryFound: Boolean = false,
    val configFound: Boolean = false,
    val statusText: String = "",
    val activeProtection: ActiveProtectionMode = ActiveProtectionMode.ON,
    val learningMode: Boolean = false,
    val reactionMode: ReactionMode = ReactionMode.OFF,
    val protectLoopbackOnly: Boolean = false,
    val configPreview: String = "",
    val dirty: Boolean = false,
    val canOpenEditor: Boolean = false,
    val infoMessage: String? = null,
    val errorMessage: String? = null,
    val runtimeStateAvailable: Boolean = false,
    val runtimeStatusText: String = "",
    val protectedPorts: Int = 0,
    val activeRules: Int = 0,
    val backend: String = "",
    val blockedPorts: List<Int> = emptyList(),
    val lastUpdated: String = "",
    val summaryPreview: String = "",
    val latestIncident: PortGuardIncident? = null,
)
