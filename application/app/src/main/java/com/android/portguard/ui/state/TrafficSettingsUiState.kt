package com.android.portguard.ui.state

data class TrafficSettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val canEdit: Boolean = false,
    val statusText: String = "",
    val tcp4Enabled: Boolean = true,
    val udp4Enabled: Boolean = true,
    val tcp6Enabled: Boolean = true,
    val udp6Enabled: Boolean = true,
    val userAppsOnly: Boolean = true,
    val networkRefreshSecs: String = "10",
    val activeSelfTestEnabled: Boolean = true,
    val selfTestTimeoutMs: String = "1500",
    val infoMessage: String? = null,
    val errorMessage: String? = null,
    val dirty: Boolean = false,
)
