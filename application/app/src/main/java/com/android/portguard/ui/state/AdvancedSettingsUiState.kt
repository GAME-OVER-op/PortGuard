package com.android.portguard.ui.state

data class AdvancedSettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val canEdit: Boolean = false,
    val statusText: String = "",
    val suspiciousUniquePorts: String = "2",
    val suspiciousAttempts: String = "2",
    val suspiciousRuleHits: String = "1",
    val scanWindowSecs: String = "10",
    val warnCooldownSecs: String = "15",
    val reactionCooldownSecs: String = "60",
    val loopIntervalMs: String = "3000",
    val reloadCheckSecs: String = "30",
    val packageRefreshSecs: String = "60",
    val counterRefreshLoops: String = "2",
    val resolveProcessDetails: Boolean = false,
    val protectLoopbackOnly: Boolean = false,
    val dirty: Boolean = false,
    val infoMessage: String? = null,
    val errorMessage: String? = null,
)
