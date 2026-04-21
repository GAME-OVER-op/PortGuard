package com.android.portguard.ui.state

data class SystemSettingsUiState(
    val loading: Boolean = true,
    val saving: Boolean = false,
    val canEdit: Boolean = false,
    val statusText: String = "",
    val chainName: String = "PORTGUARD",
    val stateDir: String = "/data/adb/modules/PortGuard/settings/state",
    val maxRules: String = "100000",
    val autoDiscoverPackages: Boolean = true,
    val packageUidSourcesText: String = "/data/system/packages.list",
    val summaryPortLimit: String = "24",
    val ignoredOwnerPackagesText: String = "",
    val infoMessage: String? = null,
    val errorMessage: String? = null,
    val dirty: Boolean = false,
)
